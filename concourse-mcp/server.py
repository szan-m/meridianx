"""Concourse — Travel Spend Copilot for Web3 Teams (A2MCP ASP) · server v2

v2 变化（相对 v1 演示版）：
  · spend_report 真实解析 records_json（按人/按行程/按类目聚合、缺收据审计、可选环比）
  · trip_quote 支持多出发地（每个出行人带 rank+origin），价格来自 rates.json 可编辑价表，
    永远带 indicative 标注 —— 接入自家供应链实时报价接口后替换 _lookup_fare/_lookup_hotel
  · policy_check 支持从 POLICY_PATH 加载客户自定义政策 JSON，缺省用 P1–P71 默认模型
  · 全部输入走 pydantic 校验，错误信息人话化（审核员会乱传参数，报错也要体面）

环境变量：POLICY_PATH（可选政策 JSON）· RATES_PATH（可选价表 JSON）· X402_DEV_MODE
"""
import os, json
from dotenv import load_dotenv
load_dotenv()  # 本地读 .env；Docker 由 -e 注入，load_dotenv 不覆盖已存在 env，安全
import logging
logging.basicConfig(level=os.getenv("LOG_LEVEL", "INFO"))
from collections import defaultdict
from pydantic import BaseModel, Field, ValidationError
from fastmcp import FastMCP
from payment import require_payment, PRICES_USDT

PRICES_USDT.update({"policy_check": 0.5, "trip_quote": 1.0, "spend_report": 1.0})

mcp = FastMCP(
    "Concourse — Travel Spend Copilot for Web3 Teams",
    instructions="Rank-based travel policy checks, multi-origin team quotes in USDT, audit-ready spend reports. Spend control only — no investment, yield or custody services.",
)

# ---------- policy engine ----------
_DEFAULT_POLICY = {
    "exec":   {"min_rank": 60, "cabin_rule": "First/Business any length", "hotel_cap": 600, "advance_days": 0, "business_max_hours": None},
    "senior": {"min_rank": 40, "cabin_rule": "Business <=6h flights, else Economy", "hotel_cap": 320, "advance_days": 5, "business_max_hours": 6},
    "staff":  {"min_rank": 1,  "cabin_rule": "Economy", "hotel_cap": 200, "advance_days": 7, "business_max_hours": 0},
}
def _policy() -> dict:
    p = os.getenv("POLICY_PATH")
    if p and os.path.exists(p):
        return json.load(open(p))
    here = os.path.dirname(os.path.abspath(__file__))
    local = os.path.join(here, "policy.json")
    if os.path.exists(local):
        return json.load(open(local))
    return _DEFAULT_POLICY

def _tier(rank: int) -> str:
    return "exec" if rank >= 60 else "senior" if rank >= 40 else "staff"

class PolicyIn(BaseModel):
    traveler_rank: int = Field(ge=1, le=71, description="P-rank 1-71")
    flight_hours: float = Field(gt=0, le=30)
    cabin: str
    hotel_usdt_per_night: float = Field(ge=0)
    days_booked_ahead: int = Field(ge=0)

@mcp.tool()
def policy_check(traveler_rank: int, flight_hours: float, cabin: str,
                 hotel_usdt_per_night: float, days_booked_ahead: int,
                 _payment: str | None = None) -> dict:
    """Is this trip within policy for a P-rank traveler? Returns violations + required approvals. 0.5 USDT."""
    require_payment("policy_check", _payment)
    try:
        q = PolicyIn(traveler_rank=traveler_rank, flight_hours=flight_hours, cabin=cabin,
                     hotel_usdt_per_night=hotel_usdt_per_night, days_booked_ahead=days_booked_ahead)
    except ValidationError as e:
        return {"error": "invalid input", "details": json.loads(e.json())}
    p = _policy()[_tier(q.traveler_rank)]
    violations, approvals = [], []
    c = q.cabin.lower()
    if c.startswith("business") and p["business_max_hours"] is not None:
        if p["business_max_hours"] == 0:
            violations.append(f"Business not allowed at P{q.traveler_rank}"); approvals.append("manager sign-off")
        elif q.flight_hours > p["business_max_hours"]:
            violations.append(f"Business only <= {p['business_max_hours']}h at P{q.traveler_rank}; flight is {q.flight_hours}h"); approvals.append("manager sign-off")
    if c.startswith("first") and _tier(q.traveler_rank) != "exec":
        violations.append(f"First class requires P60+; traveler is P{q.traveler_rank}"); approvals.append("exec sign-off")
    if q.hotel_usdt_per_night > p["hotel_cap"]:
        violations.append(f"Hotel {q.hotel_usdt_per_night} USDT/night exceeds cap {p['hotel_cap']}"); approvals.append("finance sign-off")
    if q.days_booked_ahead < p["advance_days"]:
        violations.append(f"Booked {q.days_booked_ahead}d ahead; policy requires >= {p['advance_days']}d"); approvals.append("short-notice approval")
    return {"in_policy": not violations, "tier": _tier(q.traveler_rank), "policy_applied": p,
            "violations": violations, "approvals_needed": sorted(set(approvals)),
            "settlement": "USDT · on-chain receipt"}

# ---------- rates (indicative until supply-chain API wired) ----------
_DEFAULT_RATES = {
    "flights": {  # origin -> destination -> {tier: fare}
        "HKG": {"DXB": {"exec": 1850, "senior": 1850, "staff": 640}, "SIN": {"exec": 1980, "senior": 1980, "staff": 620}},
        "SIN": {"DXB": {"exec": 1720, "senior": 1720, "staff": 590}},
        "LIS": {"DXB": {"exec": 1540, "senior": 1540, "staff": 520}},
    },
    "hotels": {"DXB": 298, "SIN": 298, "LIS": 210, "default": 260},
}
def _rates() -> dict:
    p = os.getenv("RATES_PATH")
    if p and os.path.exists(p):
        return json.load(open(p))
    here = os.path.dirname(os.path.abspath(__file__))
    local = os.path.join(here, "rates.json")
    if os.path.exists(local):
        return json.load(open(local))
    return _DEFAULT_RATES

def _lookup_fare(origin: str, dest: str, tier: str) -> float | None:
    # TODO(wire): 替换为 MeridianX 供应链实时报价接口调用（现有 API）
    r = _rates()["flights"].get(origin.upper(), {}).get(dest.upper())
    return r.get(tier) if r else None

class Traveler(BaseModel):
    rank: int = Field(ge=1, le=71)
    origin: str = Field(min_length=3, max_length=3, description="IATA city/airport code")

@mcp.tool()
def trip_quote(destination: str, nights: int, travelers: list[dict],
               _payment: str | None = None) -> dict:
    """Team x destination -> multi-origin flight + hotel package quote in USDT with budget impact. 1 USDT.
    travelers: [{"rank": 41, "origin": "HKG"}, ...]"""
    require_payment("trip_quote", _payment)
    try:
        team = [Traveler(**t) for t in travelers]
        assert 1 <= len(team) <= 50 and 1 <= nights <= 30
    except (ValidationError, AssertionError) as e:
        return {"error": "invalid input", "hint": 'travelers=[{"rank":41,"origin":"HKG"}], nights 1-30', "details": str(e)}
    dest = destination.upper()[:3]
    lines, missing = [], []
    for i, t in enumerate(team):
        fare = _lookup_fare(t.origin, dest, _tier(t.rank))
        if fare is None:
            missing.append(f"{t.origin}->{dest}")
        else:
            lines.append({"traveler": i + 1, "origin": t.origin.upper(), "tier": _tier(t.rank), "fare_usdt": fare})
    hotel_rate = _rates()["hotels"].get(dest, _rates()["hotels"]["default"])
    flights = sum(l["fare_usdt"] for l in lines)
    hotels = hotel_rate * nights * len(team)
    return {"destination": dest, "nights": nights, "team_size": len(team),
            "flight_lines": lines, "routes_without_rate": missing,
            "flights_usdt": flights, "hotel_usdt": hotels, "hotel_rate_per_night": hotel_rate,
            "total_usdt": flights + hotels, "indicative": True,
            "note": "Indicative quote from negotiated team-rate table; live lock via booking desk."}

# ---------- spend report (real aggregation) ----------
class Record(BaseModel):
    person: str
    trip: str = "unspecified"
    category: str = Field(description="flights|hotels|ground|other")
    amount_usdt: float = Field(ge=0)
    date: str = ""
    receipt_tx: str = ""

@mcp.tool()
def spend_report(records_json: str, month: str, prev_month_total_usdt: float | None = None,
                 _payment: str | None = None) -> dict:
    """Audit-ready monthly travel-spend report from your records JSON. 1 USDT.
    records_json: JSON array of {person, trip, category, amount_usdt, date, receipt_tx}."""
    require_payment("spend_report", _payment)
    try:
        raw = json.loads(records_json)
        records = [Record(**r) for r in raw]
        assert records, "no records"
    except Exception as e:
        return {"error": "invalid records_json", "hint": '[{"person":"Sofia","trip":"Dubai BD","category":"flights","amount_usdt":1850,"receipt_tx":"0x.."}]', "details": str(e)}
    by = lambda key: sorted(({ "key": k, "spend_usdt": round(v, 2)} for k, v in
        _agg(records, key).items()), key=lambda x: -x["spend_usdt"])
    total = round(sum(r.amount_usdt for r in records), 2)
    no_receipt = [f"{r.person}/{r.trip}/{r.amount_usdt}" for r in records if not r.receipt_tx]
    out = {"month": month, "currency": "USDT", "total": total,
           "by_person": by("person"), "by_trip": by("trip"), "by_category": by("category"),
           "records_count": len(records),
           "audit": {"lines_with_onchain_receipt": len(records) - len(no_receipt),
                      "lines_missing_receipt": no_receipt[:20],
                      "receipt_coverage_pct": round(100 * (len(records) - len(no_receipt)) / len(records), 1)},
           "disclaimer": "spend reporting only — not investment, yield or custody services"}
    if prev_month_total_usdt:
        out["mom_change_pct"] = round(100 * (total - prev_month_total_usdt) / prev_month_total_usdt, 1)
    return out

def _agg(records, key):
    d = defaultdict(float)
    for r in records:
        d[getattr(r, key)] += r.amount_usdt
    return d

if __name__ == "__main__":
    mcp.run(transport="http", host="0.0.0.0", port=int(os.getenv("MCP_PORT", "8001")))
