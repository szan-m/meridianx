"""10 秒本地自检（DEV 模式绕过计费）：python test_local.py <可选 luma 链接>

包含两段：
  A) analyze_event 工具冒烟（DEV 模式，绕过计费）
  B) payment 模块表面 + fail-closed 冒烟（不真打 OKX）：
     - require_payment 在 DEV_MODE 下旁路
     - verify_payment 对空/垃圾凭证一律 False（fail-closed，不漏计费）
     - 若已装 okxweb3-app-x402 且 OKX 三元组齐全，额外尝试 _get_server() 装配并打印报价单形状
"""
import sys, json, os
os.environ.setdefault("X402_DEV_MODE", "1")
import server
import payment


def call(t):
    return getattr(t, 'fn', t)

url = sys.argv[1] if len(sys.argv) > 1 else "https://lu.ma/token2049"

# ---- A) 工具冒烟 ----
try:
    print(json.dumps(call(server.analyze_event)(url, ["raising Series A"], "L1 infra project"),
                     indent=2, ensure_ascii=False)[:2000])
except Exception as e:
    print("analyze_event error:", e)

# ---- A2) worth_going 冒烟（DEV 模式）----
print("\n--- worth_going smoke ---")
try:
    wg = call(server.worth_going)(url, ["raising Series A"])
    print(json.dumps(wg, indent=2, ensure_ascii=False)[:1200])
    assert "score" in wg and "verdict" in wg, "worth_going 缺字段"
    print("[PASS] worth_going 返回含 score/verdict")
except AttributeError:
    print("[SKIP] worth_going 未注册（正常情况不应发生）")
except Exception as e:
    print("worth_going error:", e)

# ---- A3) side_event_map 冒烟（DEV 模式，需 ENABLE_SIDE_EVENT_MAP=1）----
print("\n--- side_event_map smoke ---")
sem = getattr(server, "side_event_map", None)
if sem is None:
    print("[SKIP] side_event_map 未注册（ENABLE_SIDE_EVENT_MAP != 1）")
else:
    try:
        r = call(sem)("TOKEN2049", "Singapore", "2026-09")
        print(json.dumps(r, indent=2, ensure_ascii=False)[:1200])
        assert r.get("main_event") == "TOKEN2049" and "ranked" in r, "side_event_map 缺字段"
        print("[PASS] side_event_map 返回含 main_event/ranked")
    except Exception as e:
        print("side_event_map error:", e)

# ---- B) payment 冒烟 ----
print("\n--- payment smoke ---")
assert callable(payment.require_payment) and callable(payment.verify_payment)
assert hasattr(payment, "PaymentRequired")

# DEV_MODE 旁路
payment.require_payment("analyze_event", None)

# fail-closed：空凭证 / 垃圾凭证都必须 False，且不抛
assert payment.verify_payment("analyze_event", "") is False
assert payment.verify_payment("analyze_event", "garbage-not-a-payload") is False
print("fail-closed OK (empty/garbage proof -> False)")

# 可选：真装配（需 SDK + OKX 三元组）
creds = all(os.getenv(k) for k in ("OKX_API_KEY", "OKX_SECRET_KEY", "OKX_PASSPHRASE"))
if creds:
    try:
        payment.PRICES_USDT.setdefault("analyze_event", 0.5)
        srv = payment._get_server()
        reqs = payment._build_requirements("analyze_event")
        print("SDK assembled; accepts[0] =",
              json.dumps(reqs[0].model_dump(by_alias=True, exclude_none=True), ensure_ascii=False))
    except Exception as e:
        print("SDK assembly skipped:", e)
else:
    print("SDK assembly skipped (OKX creds not set) — fill .env to exercise live verify/settle")
