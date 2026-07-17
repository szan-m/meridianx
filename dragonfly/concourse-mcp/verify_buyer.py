"""脚本化买方 E2E：python verify_buyer.py [tool_name]

完整链路（需服务端 X402_SCHEME=exact）：
  1) MCP tools/call 无凭证 -> 捕获 PaymentRequired，解析 accepts[0]（exact 报价单）
  2) 用 BUYER_PRIVATE_KEY 构造 EVM signer -> x402ClientSync 签出 PaymentPayload
  3) base64(JSON(payload)) 作为 _payment 重试 tools/call -> 服务端 verify+settle -> 返回工具结果
  4) 打印每步日志与 OKX 响应，核对 payTo 到账

前置：
  · 服务端以 X402_SCHEME=exact 启动（python server.py，.env 里 X402_SCHEME=exact）
  · 买方钱包在 X Layer（eip155:196）上持有足额 USD₮0（asset=0x779ded...）
    policy_check=0.5 USDT / trip_quote=1.0 USDT / spend_report=1.0 USDT
  · 环境变量 BUYER_PRIVATE_KEY（0x 前缀私钥），可写进 .env
"""
import asyncio, sys, os, json, base64, re, traceback

try:
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:
    pass

# ---- 极简 .env 加载 ----
_here = os.path.dirname(os.path.abspath(__file__))
_env = os.path.join(_here, ".env")
if os.path.exists(_env):
    for line in open(_env, encoding="utf-8"):
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        k, v = line.split("=", 1)
        os.environ.setdefault(k.strip(), v.strip())

from fastmcp import Client

URL = os.getenv("MCP_URL", "http://localhost:8000/mcp")
TOOL = sys.argv[1] if len(sys.argv) > 1 else "policy_check"  # 默认最便宜

TOOL_ARGS = {
    "policy_check": {"traveler_rank": 41, "flight_hours": 4.05, "cabin": "Business",
                     "hotel_usdt_per_night": 298, "days_booked_ahead": 41},
    "trip_quote": {"destination": "DXB", "nights": 3,
                   "travelers": [{"rank": 65, "origin": "HKG"}, {"rank": 41, "origin": "SIN"}]},
    "spend_report": {"records_json": '[{"person":"Sofia","trip":"Dubai BD","category":"flights","amount_usdt":1850,"receipt_tx":"0xaa"}]',
                     "month": "June 2026"},
}


def _extract_quote_json(msg: str) -> dict:
    """从 'Error calling tool ...: {json}' 里抠出 402 报价 JSON。"""
    m = re.search(r"\{.*\}", msg, re.S)
    if not m:
        raise RuntimeError(f"无法从异常消息提取 402 JSON: {msg[:200]}")
    return json.loads(m.group(0))


def _sign_payment(quote: dict, buyer_key: str) -> str:
    """用买方私钥对 accepts[0] 签出 PaymentPayload，返回 base64(JSON)。"""
    from eth_account import Account
    from x402 import x402ClientSync
    from x402.mechanisms.evm.exact import ExactEvmScheme
    from x402.schemas.payments import PaymentRequired, PaymentRequirements

    accepts = quote.get("accepts") or []
    assert accepts, "402 报价单 accepts 为空"
    req = PaymentRequirements.model_validate(accepts[0])
    print(f"  accepts[0] scheme={req.scheme} network={req.network} amount={req.amount} payTo={req.pay_to} asset={req.asset}")
    assert req.scheme == "exact", f"期望 exact scheme，实际 {req.scheme}（请用 X402_SCHEME=exact 重启服务）"

    signer = Account.from_key(buyer_key)
    print(f"  buyer address={signer.address}")

    client = x402ClientSync()
    client.register(req.network, ExactEvmScheme(signer=signer))
    pr = PaymentRequired(accepts=[req])
    payload = client.create_payment_payload(pr)
    payload_dict = payload.model_dump(by_alias=True, exclude_none=True)
    print(f"  signed PaymentPayload x402_version={payload_dict.get('x402_version')} keys={list(payload_dict.keys())}")
    return base64.b64encode(json.dumps(payload_dict).encode()).decode()


async def main():
    buyer_key = os.getenv("BUYER_PRIVATE_KEY", "")
    if not buyer_key:
        print("[FAIL] 未设置 BUYER_PRIVATE_KEY（写入 .env 或环境变量）")
        sys.exit(2)

    args = TOOL_ARGS[TOOL]
    print(f"=== 买方 E2E: tool={TOOL} url={URL} ===")

    async with Client(URL) as client:
        # 1) 无凭证调用 -> 402
        print("\n[1] tools/call 无凭证，期望 402 ...")
        try:
            await client.call_tool(TOOL, args)
            print("[FAIL] 未抛 402，异常")
            sys.exit(1)
        except Exception as e:
            quote = _extract_quote_json(str(e))
            assert quote.get("x402") == "payment_required", f"非 402 报价: {quote}"
            print(f"  402 OK: tool={quote.get('tool')} accepts={len(quote['accepts'])} 条")

        # 2) 签名
        print("\n[2] 买方签名 PaymentPayload ...")
        proof = _sign_payment(quote, buyer_key)
        print(f"  proof(base64) 长度={len(proof)}")

        # 3) 带凭证重试 -> verify+settle -> 工具结果
        print("\n[3] tools/call 带 _payment 重试，期望 verify+settle 成功 ...")
        try:
            res = await client.call_tool(TOOL, {**args, "_payment": proof})
            if getattr(res, "is_error", False):
                txt = "".join(c.text for c in getattr(res, "content", []) if hasattr(c, "text"))
                print(f"[FAIL] 工具返回 is_error: {txt[:500]}")
                sys.exit(1)
            txt = "".join(c.text for c in getattr(res, "content", []) if hasattr(c, "text"))
            print(f"  工具结果: {txt[:800]}")
            print("\n=== 买方 E2E 通过：verify+settle 成功，工具返回结果 ===")
        except Exception as e:
            print(f"[FAIL] 带凭证调用异常: {type(e).__name__}: {str(e)[:500]}")
            traceback.print_exc()
            sys.exit(1)

asyncio.run(main())
