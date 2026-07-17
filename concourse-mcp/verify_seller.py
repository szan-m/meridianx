"""卖方侧验证脚本：python verify_seller.py

不依赖买方钱包，验证三层卖方逻辑：
  1) OKX Facilitator 装配（HMAC 鉴权 + /supported）+ 报价单形状（accepts / 原子单位换算）
  2) 生产模式 402 流程：无凭证 -> 抛 PaymentRequired 且 message 为合规 accepts JSON
  3) fail-closed：垃圾凭证 -> verify_payment 返回 False

读取同目录 .env；不会覆盖已存在的环境变量。
"""
import os, sys, json, traceback

# Windows 控制台默认 GBK，报价 JSON 含 ₮ 等非 ASCII 字符 -> 强制 UTF-8 输出
try:
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:
    pass

# ---- 极简 .env 加载（不引入 python-dotenv 依赖）----
_here = os.path.dirname(os.path.abspath(__file__))
for line in open(os.path.join(_here, ".env"), encoding="utf-8"):
    line = line.strip()
    if not line or line.startswith("#") or "=" not in line:
        continue
    k, v = line.split("=", 1)
    os.environ.setdefault(k.strip(), v.strip())

# 强制生产模式以验证计费路径
os.environ["X402_DEV_MODE"] = "0"

import payment
payment.PRICES_USDT.setdefault("policy_check", 0.5)
payment.PRICES_USDT.setdefault("trip_quote", 1.0)
payment.PRICES_USDT.setdefault("spend_report", 1.0)

# 工具 -> 期望原子单位（USDT 6 位小数）
_EXPECTED_AMOUNT = {"policy_check": "500000", "trip_quote": "1000000", "spend_report": "1000000"}

ok = []
fail = []
def step(name, fn):
    try:
        fn(); ok.append(name); print(f"[PASS] {name}")
    except Exception as e:
        fail.append((name, e)); print(f"[FAIL] {name}: {e}")

# ---- 前置检查 ----
pay_to = os.getenv("PAY_TO_ADDRESS", "")
if pay_to.startswith("0xREPLACE") or not pay_to:
    print(f"[WARN] PAY_TO_ADDRESS 仍是占位符 ({pay_to}) —— 装配/报价可跑通，但 settle 会失败，上线前必须改成真实 X Layer 钱包")

# ---- 1) Facilitator 装配 + 三工具报价单 ----
def t1():
    srv = payment._get_server()                       # 触发 OKX /supported（HMAC 鉴权）
    for tool, expected in _EXPECTED_AMOUNT.items():
        reqs = payment._build_requirements(tool)
        assert reqs, f"{tool}: build_payment_requirements 返回空"
        q = reqs[0].model_dump(by_alias=True, exclude_none=True)
        print(f"    [{tool}] accepts[0] =", json.dumps(q, ensure_ascii=False))
        assert q["scheme"] == os.getenv("X402_SCHEME", "aggr_deferred"), f"{tool}: scheme={q['scheme']}"
        assert q["network"] == "eip155:196", f"{tool}: network={q['network']}"
        assert q["payTo"] == pay_to, f"{tool}: payTo={q['payTo']}"
        assert q["amount"] == expected, f"{tool}: amount={q['amount']}（期望 {expected}）"
    print("    三工具 scheme/network/payTo/amount 校验通过")

# ---- 2) 402 流程（三工具各一次）----
def t2():
    for tool in _EXPECTED_AMOUNT:
        try:
            payment.require_payment(tool, None)
        except payment.PaymentRequired as e:
            quote = json.loads(str(e))
            assert quote["x402"] == "payment_required", f"{tool}: x402 字段错"
            assert "accepts" in quote and quote["accepts"], f"{tool}: accepts 为空"
            print(f"    [{tool}] 402 quote =", json.dumps(quote, ensure_ascii=False)[:200], "...")
            continue
        raise AssertionError(f"{tool}: 无凭证未抛 PaymentRequired")

# ---- 3) fail-closed（三工具各一次）----
def t3():
    for tool in _EXPECTED_AMOUNT:
        assert payment.verify_payment(tool, "") is False, f"{tool}: 空凭证应 False"
        assert payment.verify_payment(tool, "garbage-not-a-payload") is False, f"{tool}: 垃圾凭证应 False"

step("1) Facilitator 装配 + 三工具报价单形状", t1)
step("2) 生产模式 402 流程（三工具）", t2)
step("3) fail-closed（三工具，空/垃圾凭证 -> False）", t3)

print("\n=== 汇总 ===")
print(f"PASS: {len(ok)}  FAIL: {len(fail)}")
if fail:
    for n, e in fail:
        print(f"  [FAIL] {n}: {e}")
        traceback.print_exc()
    sys.exit(1)
print("卖方侧第 1-3 层全部通过。第 4 层（verify+settle 全链路）需买方钱包签名，见 verify_buyer.py 顶部说明。")
