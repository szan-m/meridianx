"""x402 计费中间件 v2 —— 基于 OKX Onchain OS Payment SDK 的真实校验与结算。

工作方式（x402 标准）：
  1) 调用方未附支付凭证 -> 返回 402 语义 + 报价单（x402 accepts 形状，金额/资产/网络/收款地址）
  2) 调用方带凭证重试 -> verify_payment() 经 OKX Facilitator 校验签名 + 链上结算 -> 放行执行工具
接入文档：web3.okx.com/onchainos/dev-docs/payments/overview
  卖方(MCP 服务)走 "SDK 集成"；采用 x402ResourceServer 直调 Facilitator（等价 Go 版 mcp.PaymentWrapper 模式），
  以兼容 FastMCP 单端点下的 per-tool 定价（HTTP 中间件层看不到工具粒度）。
  scheme：aggr_deferred = pay-as-you-go（session key 签名 + OKX 链上批量结算）；exact = 每次一笔 EIP-3009（回退）。
环境变量：
  X402_DEV_MODE=1      开发模式跳过计费（默认 1；上线置 0）
  PAY_TO_ADDRESS       收款地址（X Layer 上的本产品独立钱包 —— 三个产品必须各用各的）
  OKX_API_KEY          OKX API Key（上线必填）
  OKX_SECRET_KEY       OKX Secret Key（上线必填）
  OKX_PASSPHRASE       OKX Passphrase（上线必填）
  OKX_BASE_URL         Facilitator 地址，默认 https://www.web3.okx.com
  X402_SCHEME          aggr_deferred（默认，pay-as-you-go）或 exact
"""
import os, json, base64, time, hashlib, logging

log = logging.getLogger("x402.payment")

DEV_MODE = os.getenv("X402_DEV_MODE", "1") == "1"
PAY_TO = os.getenv("PAY_TO_ADDRESS", "0xREPLACE_ME")
NETWORK = "eip155:196"
ASSET = "USDT"
SCHEME = os.getenv("X402_SCHEME", "aggr_deferred")
REPLAY_TTL = 3600  # 防重放记录保留时长（秒）

PRICES_USDT: dict[str, float] = {}   # 由 server.py 启动时注册

# ---------- OKX Facilitator 懒装配 ----------
# OKXFacilitatorClientSync 构造时即校验三元组，缺则 raise；故 DEV_MODE 或无凭据时不能在 import 期构造，
# 用懒初始化：首次真正需要（生产模式出 402 报价单 / 校验收到的凭证）时才建并 initialize()。
_server = None

def _get_server():
    global _server
    if _server is not None:
        return _server
    from x402.http import OKXAuthConfig, OKXFacilitatorClientSync, OKXFacilitatorConfig
    from x402.server import x402ResourceServerSync
    from x402.mechanisms.evm.exact.server import ExactEvmScheme
    from x402.mechanisms.evm.deferred.server import AggrDeferredEvmScheme

    facilitator = OKXFacilitatorClientSync(OKXFacilitatorConfig(
        auth=OKXAuthConfig(
            api_key=os.getenv("OKX_API_KEY", ""),
            secret_key=os.getenv("OKX_SECRET_KEY", ""),
            passphrase=os.getenv("OKX_PASSPHRASE", ""),
        ),
        base_url=os.getenv("OKX_BASE_URL", "https://www.web3.okx.com"),
        sync_settle=True,
    ))
    srv = x402ResourceServerSync(facilitator)
    srv.register(NETWORK, AggrDeferredEvmScheme())   # pay-as-you-go（默认）
    srv.register(NETWORK, ExactEvmScheme())          # 回退
    srv.initialize()  # 拉取 facilitator 支持的 kinds（同步网络调用）
    _server = srv
    return srv

def _build_requirements(tool: str):
    """按工具价目构造 x402 PaymentRequirements（list）。"""
    from x402.schemas import ResourceConfig
    return _get_server().build_payment_requirements(ResourceConfig(
        scheme=SCHEME, network=NETWORK, pay_to=PAY_TO,
        price=f"${PRICES_USDT[tool]}",
    ))


class PaymentRequired(Exception):
    """携带 x402 报价单的异常；FastMCP 会把 message 返回给调用方。"""
    def __init__(self, tool: str):
        reqs = _build_requirements(tool)
        quote = {
            "x402": "payment_required",
            "tool": tool,
            "accepts": [r.model_dump(by_alias=True, exclude_none=True) for r in reqs],
            "note": "sign a PaymentPayload against accepts[0] and retry with _meta.x402 = base64(JSON)",
        }
        super().__init__(json.dumps(quote))


# ---------- 防重放缓存 ----------
_seen_nonces: dict[str, float] = {}

def _replay_key(payer: str | None, payload_model) -> str:
    """(payer, payload 指纹) 唯一标识一次授权，跨 scheme 通用。"""
    from x402.schemas import PaymentPayload, PaymentPayloadV1
    body = payload_model.payload if isinstance(payload_model, (PaymentPayload, PaymentPayloadV1)) else {}
    fp = hashlib.sha256(json.dumps(body, sort_keys=True, default=str).encode()).hexdigest()
    return f"{payer or '?'}:{fp}"

def _prune_replay() -> None:
    now = time.time()
    for k, ts in list(_seen_nonces.items()):
        if now - ts > REPLAY_TTL:
            _seen_nonces.pop(k, None)


def verify_payment(tool: str, payment_proof: str) -> bool:
    """经 OKX Facilitator 校验买方签名并链上结算。

    payment_proof: 买方钱包签好的 PaymentPayload，约定为 base64(JSON) 或裸 JSON 字符串
                   （由 MCP 调用方放进 _meta.x402，FastMCP 映射到工具的 _payment 参数）。
    生产模式一律 fail-closed：任何解析/校验/结算失败均返回 False（宁可拒不漏）。
    """
    if not payment_proof:
        log.info("verify_payment tool=%s empty proof -> False", tool)
        return False

    # 1) 解码 + 解析买方凭证（SDK 缺失 / 解析失败一律 fail-closed）
    try:
        from x402.schemas.helpers import parse_payment_payload
        stripped = payment_proof.lstrip()
        raw = base64.b64decode(payment_proof) if not stripped.startswith("{") else payment_proof.encode()
        payload = parse_payment_payload(raw)  # 自动识别 v1/v2
        log.debug("verify_payment tool=%s parsed payload scheme=%s network=%s",
                  tool, getattr(payload, "get_scheme", lambda: "?")(), getattr(payload, "get_network", lambda: "?")())
    except Exception as e:
        log.warning("verify_payment tool=%s parse failed -> False: %r", tool, e)
        log.debug("parse traceback", exc_info=True)
        return False

    # 2) 构造本工具的 PaymentRequirements
    try:
        requirements = _build_requirements(tool)[0]
        log.info("verify_payment tool=%s requirements scheme=%s amount=%s payTo=%s",
                 tool, requirements.scheme, requirements.amount, requirements.pay_to)
    except Exception as e:
        log.warning("verify_payment tool=%s build requirements failed -> False: %r", tool, e)
        log.debug("build requirements traceback", exc_info=True)
        return False

    srv = _get_server()

    # 3) 校验签名与金额/收款地址
    try:
        v = srv.verify_payment(payload, requirements)
        log.info("verify_payment tool=%s OKX /verify is_valid=%s invalid_reason=%s invalid_message=%s payer=%s",
                 tool, getattr(v, "is_valid", None), getattr(v, "invalid_reason", None),
                 getattr(v, "invalid_message", None), getattr(v, "payer", None))
    except Exception as e:
        log.warning("verify_payment tool=%s OKX /verify raised -> False: %r", tool, e)
        log.debug("verify traceback", exc_info=True)
        return False
    if not getattr(v, "is_valid", False):
        log.warning("verify_payment tool=%s is_valid=False -> False (reason=%s)", tool, getattr(v, "invalid_reason", None))
        return False

    # 4) 防重放：(payer, payload 指纹) 去重
    _prune_replay()
    key = _replay_key(getattr(v, "payer", None), payload)
    if key in _seen_nonces:
        log.warning("verify_payment tool=%s replay hit key=%s -> False", tool, key)
        return False

    # 5) 链上结算（sync_settle=True；aggr_deferred 走批量通道，exact 走单笔 EIP-3009）
    try:
        s = srv.settle_payment(payload, requirements)
        log.info("verify_payment tool=%s OKX /settle success=%s status=%s tx=%s error_reason=%s error_message=%s",
                 tool, getattr(s, "success", None), getattr(s, "status", None),
                 getattr(s, "transaction", None), getattr(s, "error_reason", None), getattr(s, "error_message", None))
    except Exception as e:
        log.warning("verify_payment tool=%s OKX /settle raised -> False: %r", tool, e)
        log.debug("settle traceback", exc_info=True)
        return False
    # success=True 即 OKX 已受理；status 可能是 success/pending/timeout。
    # timeout 表示 OKX 已把交易广播上链但同步等回执超时——此时 transaction 字段会有真实 tx hash，
    # 链上转账已在进行，应判通过（宁可按 pending 放行，再由对账核对到账）。
    s_status = getattr(s, "status", None)
    s_tx = getattr(s, "transaction", None)
    if not getattr(s, "success", False):
        log.warning("verify_payment tool=%s settle success=False -> False (status=%s reason=%s)",
                    tool, s_status, getattr(s, "error_reason", None))
        return False
    if s_status not in ("success", "pending") and not (s_status == "timeout" and s_tx):
        log.warning("verify_payment tool=%s settle unexpected status -> False (status=%s reason=%s tx=%s)",
                    tool, s_status, getattr(s, "error_reason", None), s_tx)
        return False

    _seen_nonces[key] = time.time()
    log.info("verify_payment tool=%s ACCEPT key=%s tx=%s", tool, key, getattr(s, "transaction", None))
    return True


def require_payment(tool: str, payment_proof: str | None) -> None:
    if DEV_MODE:
        log.info("require_payment tool=%s DEV_MODE bypass", tool)
        return
    if not payment_proof:
        log.info("require_payment tool=%s no proof -> 402", tool)
        raise PaymentRequired(tool)
    if not verify_payment(tool, payment_proof):
        log.warning("require_payment tool=%s verify failed -> PermissionError", tool)
        raise PermissionError("payment proof invalid or amount mismatch")
