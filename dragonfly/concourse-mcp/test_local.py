"""10 秒本地自检（DEV 模式绕过计费）：python test_local.py

包含两段：
  A) 三工具冒烟（policy_check / trip_quote / spend_report，DEV 模式，绕过计费）
  B) payment 模块表面 + fail-closed 冒烟（不真打 OKX）：
     - require_payment 在 DEV_MODE 下旁路
     - verify_payment 对空/垃圾凭证一律 False（fail-closed，不漏计费）
     - 若已装 okxweb3-app-x402 且 OKX 三元组齐全，额外尝试 _get_server() 装配并打印报价单形状
"""
import json, os
try:
    import sys
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:
    pass
os.environ.setdefault("X402_DEV_MODE", "1")
import server
import payment


def call(t):
    return getattr(t, 'fn', t)

# ---- A) 工具冒烟 ----
print("-- policy_check --")
print(json.dumps(call(server.policy_check)(41, 4.05, "Business", 298, 41), indent=2)[:600])
print("-- trip_quote --")
print(json.dumps(call(server.trip_quote)("DXB", 3, [{"rank":65,"origin":"HKG"},{"rank":41,"origin":"SIN"},{"rank":41,"origin":"LIS"}]), indent=2)[:900])
print("-- spend_report --")
recs = json.dumps([
  {"person":"Sofia","trip":"Dubai BD","category":"flights","amount_usdt":1850,"receipt_tx":"0xaa"},
  {"person":"Sofia","trip":"Dubai BD","category":"hotels","amount_usdt":894,"receipt_tx":"0xbb"},
  {"person":"Kenji","trip":"Lisbon offsite","category":"flights","amount_usdt":620,"receipt_tx":""},
])
print(json.dumps(call(server.spend_report)(recs, "June 2026", 148000), indent=2)[:900])

# ---- B) payment 冒烟 ----
print("\n--- payment smoke ---")
assert callable(payment.require_payment) and callable(payment.verify_payment)
assert hasattr(payment, "PaymentRequired")

# DEV_MODE 旁路
payment.require_payment("policy_check", None)

# fail-closed：空凭证 / 垃圾凭证都必须 False，且不抛
assert payment.verify_payment("policy_check", "") is False
assert payment.verify_payment("policy_check", "garbage-not-a-payload") is False
print("fail-closed OK (empty/garbage proof -> False)")

# 可选：真装配（需 SDK + OKX 三元组）
creds = all(os.getenv(k) for k in ("OKX_API_KEY", "OKX_SECRET_KEY", "OKX_PASSPHRASE"))
if creds:
    try:
        payment.PRICES_USDT.setdefault("policy_check", 0.5)
        srv = payment._get_server()
        reqs = payment._build_requirements("policy_check")
        print("SDK assembled; accepts[0] =",
              json.dumps(reqs[0].model_dump(by_alias=True, exclude_none=True), ensure_ascii=False))
    except Exception as e:
        print("SDK assembly skipped:", e)
else:
    print("SDK assembly skipped (OKX creds not set) — fill .env to exercise live verify/settle")
