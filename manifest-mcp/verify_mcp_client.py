"""MCP 客户端验证：python verify_mcp_client.py

连接本地 manifest-mcp（http://localhost:8000/mcp），验证：
  1) tools/list 含三个工具（analyze_event / worth_going / side_event_map）
  2) 生产模式下 _payment=None 调每个工具 -> 返回 402 报价单（PaymentRequired）
"""
import asyncio, sys, json, os

try:
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:
    pass

from fastmcp import Client

URL = os.getenv("MCP_URL", "http://localhost:8000/mcp")
EXPECTED_TOOLS = {"analyze_event", "worth_going", "side_event_map"}

async def main():
    async with Client(URL) as client:
        # 1) tools/list
        tools = await client.list_tools()
        names = {t.name for t in tools}
        print("tools/list ->", sorted(names))
        missing = EXPECTED_TOOLS - names
        assert not missing, f"缺少工具: {missing}"
        print(f"[PASS] tools/list 含三个工具")

        # 2) 402 流程：每工具无凭证调用
        for tool in EXPECTED_TOOLS:
            args = {
                "analyze_event": {"luma_url": "https://lu.ma/token2049", "goals": ["raising Series A"], "project_one_liner": "L1 infra"},
                "worth_going": {"luma_url": "https://lu.ma/token2049", "goals": ["raising Series A"]},
                "side_event_map": {"main_event": "TOKEN2049", "city": "Singapore", "date_range": "2026-09"},
            }[tool]
            try:
                res = await client.call_tool(tool, args)
                # 若返回而非抛，检查是否 is_error
                if getattr(res, "is_error", False):
                    txt = "".join(c.text for c in getattr(res, "content", []) if hasattr(c, "text"))
                    quote = json.loads(txt)
                    assert quote.get("x402") == "payment_required", f"{tool}: 非 402 报价单"
                    print(f"[PASS] {tool} -> 402 accepts amount={quote['accepts'][0]['amount']}")
                else:
                    print(f"[FAIL] {tool} 未返回 402，结果: {str(res)[:200]}")
                    sys.exit(1)
            except Exception as e:
                # FastMCP 可能把工具异常作为抛出
                msg = str(e)
                if "payment_required" in msg or "402" in msg:
                    print(f"[PASS] {tool} -> 402 (via exception): {msg[:120]}")
                else:
                    print(f"[FAIL] {tool} 异常非 402: {type(e).__name__}: {msg[:200]}")
                    sys.exit(1)

asyncio.run(main())
print("\n=== MCP 客户端验证通过 ===")
