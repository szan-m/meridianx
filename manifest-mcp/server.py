"""Manifest — Event Intel for the Crypto Circuit (A2MCP ASP) · server v2
Luma link in -> who-to-meet battle plan out.

v2 变化（相对 v1 演示版）：
  · _sandbox_fetch 变成真实抓取：仅允许 lu.ma / luma.com 公开页，10s 超时，24h 内存缓存
  · 接入 LLM 分析管道（OpenAI 兼容接口，环境变量配置）；无 Key 时回退到 heuristic 模式并如实标注
  · 隐私纪律写进系统提示词：输出永远角色化描述，不输出真实人名
  · side_event_map 默认关闭（ENABLE_SIDE_EVENT_MAP=1 开启）—— 数据源就绪前不上架该工具，上架≠吹牛

环境变量：
  MANIFEST_LLM_API_BASE   OpenAI 兼容 /v1（如 https://api.openai.com/v1 或自建网关）
  MANIFEST_LLM_API_KEY    密钥；缺省则 heuristic 模式
  MANIFEST_LLM_MODEL      默认 gpt-4o-mini 级别即可
  ENABLE_SIDE_EVENT_MAP   默认 0
计费接入（详见 payment.py）：
  每个工具的 _payment 形参承接买方支付凭证；调用方把签好的 x402 PaymentPayload 以
  base64(JSON) 放进 MCP tools/call 的 _meta.x402，FastMCP 映射到该形参。
  无凭证 -> PaymentRequired(402 报价单)；凭证无效 -> PermissionError。
"""
import os, re, json, time, hashlib, logging
from dotenv import load_dotenv
load_dotenv()  # 本地读 .env；Docker 由 -e 注入，load_dotenv 不覆盖已存在 env，安全
logging.basicConfig(level=os.getenv("LOG_LEVEL", "INFO"))
import httpx
from bs4 import BeautifulSoup
from fastmcp import FastMCP
from payment import require_payment, PRICES_USDT

PRICES_USDT.update({"analyze_event": 0.5, "worth_going": 0.1, "side_event_map": 0.3})

LLM_BASE = os.getenv("MANIFEST_LLM_API_BASE", "")
LLM_KEY = os.getenv("MANIFEST_LLM_API_KEY", "")
LLM_MODEL = os.getenv("MANIFEST_LLM_MODEL", "gpt-4o-mini")

mcp = FastMCP(
    "Manifest — Event Intel for the Crypto Circuit",
    instructions="Paste a Luma event URL and your goals; get a ranked who-to-meet battle plan. Public data only; people are described by role, never by name.",
)

# ---------- sandbox fetcher ----------
_ALLOWED = re.compile(r"^https://(www\.)?(lu\.ma|luma\.com)/[A-Za-z0-9_\-./?=&]+$")
_cache: dict[str, tuple[float, dict]] = {}
_TTL = 24 * 3600

def _sandbox_fetch(luma_url: str) -> dict:
    """只读公开活动页；域名白名单 + 超时 + 24h 缓存。"""
    if not _ALLOWED.match(luma_url.strip()):
        raise ValueError("only public lu.ma / luma.com event URLs are accepted")
    key = hashlib.sha256(luma_url.encode()).hexdigest()
    hit = _cache.get(key)
    if hit and time.time() - hit[0] < _TTL:
        return hit[1]
    r = httpx.get(luma_url, timeout=10, follow_redirects=True,
                  headers={"User-Agent": "ManifestBot/1.0 (+public event pages only)"})
    r.raise_for_status()
    soup = BeautifulSoup(r.text, "html.parser")
    data = {
        "url": luma_url,
        "title": (soup.title.string or "").strip() if soup.title else "",
        "description": "",
        "jsonld": [],
        "visible_text": soup.get_text(" ", strip=True)[:6000],
    }
    md = soup.find("meta", attrs={"name": "description"}) or soup.find("meta", attrs={"property": "og:description"})
    if md: data["description"] = md.get("content", "")[:1000]
    for tag in soup.find_all("script", type="application/ld+json"):
        try: data["jsonld"].append(json.loads(tag.string or "{}"))
        except Exception: pass
    _cache[key] = (time.time(), data)
    return data

# ---------- LLM pipeline ----------
_SYSTEM = (
    "You are Manifest, an event-intelligence analyst for the crypto industry.\n"
    "HARD RULES:\n"
    "1) Use ONLY the provided public event page content. Do not invent attendees.\n"
    "2) NEVER output a real person's name. Describe every target by ROLE only "
    "(e.g. 'Partner — infra-focused fund'). If the page names speakers, convert them to role descriptions.\n"
    "3) Be decisive and specific; every recommendation carries a WHY tied to the user's stated goals.\n"
    "4) Output strict JSON matching the schema you are given. No prose outside JSON."
)

def _llm_json(user_prompt: str, schema_hint: str) -> dict | None:
    if not (LLM_BASE and LLM_KEY):
        return None
    try:
        r = httpx.post(f"{LLM_BASE}/chat/completions",
            headers={"Authorization": f"Bearer {LLM_KEY}"},
            json={"model": LLM_MODEL, "temperature": 0.3,
                  "response_format": {"type": "json_object"},
                  "messages": [{"role": "system", "content": _SYSTEM},
                               {"role": "user", "content": user_prompt + "\n\nJSON schema:\n" + schema_hint}]},
            timeout=45)
        r.raise_for_status()
        return json.loads(r.json()["choices"][0]["message"]["content"])
    except Exception:
        return None

def _heuristic_report(ev: dict, goals: list[str]) -> dict:
    """无 LLM 时的诚实降级：基于页面解析给结构化建议，并明确标注模式。"""
    return {
        "event": ev["title"] or ev["url"],
        "analysis_mode": "heuristic (LLM not configured)",
        "targets": [
            {"rank": 1, "who": "Event host / organizing team", "why": f"Controls the room; state your goal ({goals[0] if goals else 'partnerships'}) and ask who you should meet.", "warm_path": "Reply to the event page contact.", "goal_tag": "ACCESS"},
            {"rank": 2, "who": "Listed speakers (see event page)", "why": "Speakers self-select as open to conversations; approach at Q&A.", "warm_path": "Reference their talk topic.", "goal_tag": "GOAL-ADJACENT"},
        ],
        "day_of_brief": "Arrive early, leave the main floor for target conversations, decline everything not tied to your stated goals.",
        "data_policy": "public event data only; roles not names",
    }

# ---------- tools ----------
@mcp.tool()
def analyze_event(luma_url: str, goals: list[str], project_one_liner: str,
                  _payment: str | None = None) -> dict:
    """Full who-to-meet battle plan for one event. Input a public Luma URL, your goals
    (e.g. ['raising Series A','CEX listing']) and a one-line project description. 0.5 USDT."""
    require_payment("analyze_event", _payment)
    ev = _sandbox_fetch(luma_url)
    prompt = (f"EVENT PAGE CONTENT:\ntitle: {ev['title']}\ndescription: {ev['description']}\n"
              f"jsonld: {json.dumps(ev['jsonld'])[:3000]}\nvisible_text: {ev['visible_text'][:4000]}\n\n"
              f"USER GOALS: {goals}\nUSER PROJECT: {project_one_liner}\n"
              "Produce the who-to-meet battle plan.")
    schema = ('{"event":str,"targets":[{"rank":int,"who":str(role only),"why":str,'
              '"warm_path":str,"goal_tag":str}],"side_events":[{"name":str,"score":float,'
              '"verdict":"GO|SKIP|CONFLICT","reason":str}],"day_of_brief":str}')
    out = _llm_json(prompt, schema) or _heuristic_report(ev, goals)
    out.setdefault("data_policy", "public event data only; roles not names")
    out["source"] = {"url": ev["url"], "fetched": "sandboxed, cached <=24h"}
    return out

@mcp.tool()
def worth_going(luma_url: str, goals: list[str], _payment: str | None = None) -> dict:
    """Is this event worth your time? 0-10 score + three-line verdict. 0.1 USDT."""
    require_payment("worth_going", _payment)
    ev = _sandbox_fetch(luma_url)
    prompt = (f"EVENT: {ev['title']}\n{ev['description']}\ntext: {ev['visible_text'][:2500]}\n"
              f"GOALS: {goals}\nScore 0-10 whether attending is worth it for these goals.")
    schema = '{"event":str,"score":float,"verdict":"GO|ONLY IF NEARBY|SKIP","three_lines":[str,str,str]}'
    out = _llm_json(prompt, schema)
    if not out:
        out = {"event": ev["title"] or ev["url"], "score": 5.0, "verdict": "ONLY IF NEARBY",
               "analysis_mode": "heuristic (LLM not configured)",
               "three_lines": ["Event page parsed; configure LLM for full scoring.",
                                "Check speaker list overlap with your goals.",
                                "Small rooms beat big floors."]}
    return out

if os.getenv("ENABLE_SIDE_EVENT_MAP", "0") == "1":
    @mcp.tool()
    def side_event_map(main_event: str, city: str, date_range: str,
                       _payment: str | None = None) -> dict:
        """Ranked side-event list around a main conference. 0.3 USDT.
        NOTE: requires a side-event data source; keep disabled until wired."""
        require_payment("side_event_map", _payment)
        # TODO(wire): 接自建 side-event 数据集（运营组每周维护的活动清单）
        return {"main_event": main_event, "city": city, "window": date_range,
                "ranked": [], "data_coverage": "not wired yet"}

if __name__ == "__main__":
    mcp.run(transport="http", host="0.0.0.0", port=8000)
