"""
FastAPI 백엔드 — 혐오 표현 탐지 API

엔드포인트:
  POST /analyze              Chrome Extension 단일 텍스트
  POST /analyze_batch        Chrome Extension 배치
  POST /analyze_android      Android App 배치 (boundsInScreen 포함)
  GET  /health               헬스체크
"""

from contextlib import asynccontextmanager
import time
from fastapi import FastAPI
from fastapi.openapi.docs import get_swagger_ui_html, get_swagger_ui_oauth2_redirect_html
from fastapi.responses import HTMLResponse
from pydantic import BaseModel

from agent_service import AgentService
from pipeline import ProfanityPipeline


# ── Pydantic 모델 ──────────────────────────────────────────

class AnalyzeRequest(BaseModel):
    text: str
    sensitivity: int | None = None


class AnalyzeBatchRequest(BaseModel):
    texts: list[str]
    sensitivity: int | None = None


class BoundsInScreen(BaseModel):
    top: int
    bottom: int
    left: int
    right: int


class AndroidComment(BaseModel):
    commentText: str
    boundsInScreen: BoundsInScreen
    author_id: str | None = None


class AndroidRequest(BaseModel):
    timestamp: int
    comments: list[AndroidComment]


class EvidenceSpan(BaseModel):
    text: str
    start: int
    end: int
    score: float


class AnalyzeResponse(BaseModel):
    original: str
    is_offensive: bool
    is_profane: bool
    is_toxic: bool
    is_hate: bool
    scores: dict[str, float]
    evidence_spans: list[EvidenceSpan]
    timing_ms: float | None = None
    model_timing_ms: float | None = None
    llm_timing_ms: float | None = None


class AnalyzeBatchResponse(BaseModel):
    results: list[AnalyzeResponse]


class AgentResponse(BaseModel):
    mode: str
    model: str | None = None
    reason: str | None = None
    response: str
    sub_agents: dict[str, str] | None = None


class AndroidResultItem(BaseModel):
    original: str
    boundsInScreen: BoundsInScreen
    is_offensive: bool
    is_profane: bool
    is_toxic: bool
    is_hate: bool
    scores: dict[str, float]
    evidence_spans: list[EvidenceSpan]


class AndroidResponse(BaseModel):
    timestamp: int
    filtered_count: int
    results: list[AndroidResultItem]


class AgentAnalyzeResponse(BaseModel):
    analysis: AnalyzeResponse
    agent: AgentResponse
    timing_ms: float | None = None
    model_timing_ms: float | None = None
    llm_timing_ms: float | None = None


# ── 파이프라인 싱글톤 ──────────────────────────────────────

pipeline: ProfanityPipeline | None = None
agent_service: AgentService | None = None


@asynccontextmanager
async def lifespan(app: FastAPI):
    global pipeline, agent_service
    pipeline = ProfanityPipeline()
    agent_service = AgentService(pipeline)
    print("파이프라인 초기화 완료")
    yield
    print("서버 종료")


app = FastAPI(
    title="Offensive Language Detection API",
    version="1.0.0",
    lifespan=lifespan,
    docs_url=None,
)


# ── 헬퍼 ───────────────────────────────────────────────────

def _format_result(
    analysis: dict,
    timing_ms: float | None = None,
    model_timing_ms: float | None = None,
    llm_timing_ms: float | None = None,
) -> dict:
    """pipeline.analyze() 결과를 API 응답 형식으로 변환."""
    timing = analysis.get("_timing", {})
    return {
        "original": analysis["text"],
        "is_offensive": analysis["is_offensive"],
        "is_profane": analysis["is_profane"],
        "is_toxic": analysis["is_toxic"],
        "is_hate": analysis["is_hate"],
        "scores": analysis["scores"],
        "evidence_spans": [
            {
                "text": s["text"],
                "start": s.get("start", -1),
                "end": s.get("end", -1),
                "score": s["score"],
            }
            for s in analysis["evidence_spans"]
        ],
        "timing_ms": round(timing_ms, 3) if timing_ms is not None else timing.get("pipeline_ms"),
        "model_timing_ms": round(model_timing_ms, 3) if model_timing_ms is not None else timing.get("model_ms"),
        "llm_timing_ms": round(llm_timing_ms, 3) if llm_timing_ms is not None else None,
    }


_DOCS_RESPONSE_ENHANCER = r"""
<style>
.response-viz {
  margin-top: 14px;
  padding: 14px;
  border: 1px solid #d8dee9;
  border-radius: 10px;
  background: #fff;
  color: #111827;
}
.response-viz h4 {
  margin: 0 0 12px;
  font-size: 15px;
}
.response-viz-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 14px;
}
.response-viz-card {
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: #f8fafc;
  padding: 12px;
}
.response-viz-card table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}
.response-viz-card td {
  padding: 6px 4px;
  border-bottom: 1px solid #e5e7eb;
  vertical-align: top;
}
.response-viz-card tr:last-child td {
  border-bottom: 0;
}
.bar-row {
  margin-bottom: 10px;
}
.bar-head {
  display: flex;
  justify-content: space-between;
  font-size: 12px;
  font-weight: 700;
  margin-bottom: 4px;
}
.bar-track {
  width: 100%;
  height: 12px;
  border-radius: 999px;
  background: #dbeafe;
  overflow: hidden;
}
.bar-fill {
  height: 100%;
  border-radius: 999px;
  background: linear-gradient(90deg, #60a5fa, #2563eb);
}
.span-badges {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.span-badge {
  display: inline-block;
  padding: 6px 10px;
  border-radius: 999px;
  background: #fee2e2;
  color: #991b1b;
  font-size: 12px;
  font-weight: 700;
}
.viz-text {
  white-space: pre-wrap;
  line-height: 1.5;
  font-size: 13px;
}
@media (max-width: 980px) {
  .response-viz-grid {
    grid-template-columns: 1fr;
  }
}
</style>
<script>
(function () {
  const runState = new WeakMap();

  function esc(text) {
    return String(text)
      .replaceAll("&", "&amp;")
      .replaceAll("<", "&lt;")
      .replaceAll(">", "&gt;");
  }

  function pct(v) {
    return ((v || 0) * 100).toFixed(1) + "%";
  }

  function payloadFrom(json) {
    if (json && json.analysis) {
      return {
        analysis: json.analysis,
        agent: json.agent || null,
        timing_ms: json.timing_ms ?? json.analysis.timing_ms ?? null,
        model_timing_ms: json.model_timing_ms ?? json.analysis.model_timing_ms ?? null,
        llm_timing_ms: json.llm_timing_ms ?? json.analysis.llm_timing_ms ?? null,
      };
    }
    if (json && (json.scores || Object.prototype.hasOwnProperty.call(json, "is_offensive"))) {
      return {
        analysis: json,
        agent: null,
        timing_ms: json.timing_ms ?? null,
        model_timing_ms: json.model_timing_ms ?? null,
        llm_timing_ms: json.llm_timing_ms ?? null,
      };
    }
    return null;
  }

  function renderViz(host, json) {
    const parsed = payloadFrom(json);
    if (!parsed) return;

    const old = host.querySelector(".response-viz");
    if (old) old.remove();

    const analysis = parsed.analysis;
    const agent = parsed.agent;
    const spans = analysis.evidence_spans || [];
    const scores = analysis.scores || {};
    const rows = [
      ["원문", analysis.original || "-"],
      ["유해 여부", String(!!analysis.is_offensive)],
      ["비속어", String(!!analysis.is_profane)],
      ["공격성", String(!!analysis.is_toxic)],
      ["혐오표현", String(!!analysis.is_hate)],
      ["전체 시간", parsed.timing_ms != null ? `${Number(parsed.timing_ms).toFixed(1)} ms` : "-"],
      ["모델 시간", parsed.model_timing_ms != null ? `${Number(parsed.model_timing_ms).toFixed(1)} ms` : "-"],
      ["LLM 시간", parsed.llm_timing_ms != null ? `${Number(parsed.llm_timing_ms).toFixed(1)} ms` : "-"],
      ["LLM 모드", agent?.mode || "-"],
      ["LLM 모델", agent?.model || "-"],
    ];

    const panel = document.createElement("div");
    panel.className = "response-viz";
    panel.innerHTML = `
      <h4>시각화 결과</h4>
      <div class="response-viz-grid">
        <section class="response-viz-card">
          <table>
            <tbody>
              ${rows.map(([k, v]) => `<tr><td><strong>${esc(k)}</strong></td><td>${esc(v)}</td></tr>`).join("")}
            </tbody>
          </table>
        </section>
        <section class="response-viz-card">
          ${["profanity", "toxicity", "hate"].map((label) => `
            <div class="bar-row">
              <div class="bar-head"><span>${label}</span><span>${pct(scores[label])}</span></div>
              <div class="bar-track"><div class="bar-fill" style="width:${Math.max(0, Math.min(100, (scores[label] || 0) * 100))}%"></div></div>
            </div>
          `).join("")}
        </section>
        <section class="response-viz-card">
          ${
            spans.length
              ? `<div class="span-badges">${spans.map((s) => `<span class="span-badge">${esc(s.text)} (${s.start}-${s.end})</span>`).join("")}</div>`
              : `<div class="viz-text">검출된 evidence span이 없습니다.</div>`
          }
        </section>
        <section class="response-viz-card">
          <div class="viz-text">${esc(agent?.response || "기본 분석 응답에는 LLM 설명이 없습니다.")}</div>
        </section>
      </div>
    `;
    host.appendChild(panel);
  }

  function findResponseBlock(opblock) {
    return (
      opblock.querySelector(".responses-inner") ||
      opblock.querySelector(".responses-wrapper") ||
      opblock
    );
  }

  function collectJson(opblock) {
    const selectors = [
      ".responses-inner .live-responses-table .highlight-code code",
      ".responses-inner .live-responses-table pre.microlight",
      ".responses-wrapper .live-responses-table .highlight-code code",
      ".responses-wrapper .live-responses-table pre.microlight",
      ".live-responses-table .highlight-code code",
      ".live-responses-table pre.microlight",
    ];

    for (const selector of selectors) {
      const nodes = opblock.querySelectorAll(selector);
      for (let idx = nodes.length - 1; idx >= 0; idx -= 1) {
        const code = nodes[idx];
        const raw = code.textContent || "";
        try {
          return { raw, json: JSON.parse(raw) };
        } catch (e) {
          continue;
        }
      }
    }
    return null;
  }

  async function tryRender(opblock, runId, previousRaw, retries = 30) {
    for (let i = 0; i < retries; i += 1) {
      if (runState.get(opblock) !== runId) return;
      const payload = collectJson(opblock);
      if (payload && payload.raw && payload.raw !== previousRaw) {
        if (runState.get(opblock) !== runId) return;
        renderViz(findResponseBlock(opblock), payload.json);
        return;
      }
      await new Promise((resolve) => setTimeout(resolve, 400));
    }
  }

  document.addEventListener("click", (event) => {
    const button = event.target.closest(".btn.execute");
    if (!button) return;
    const opblock = button.closest(".opblock");
    if (!opblock) return;
    const previousPayload = collectJson(opblock);
    const previousRaw = previousPayload ? previousPayload.raw : "";
    const runId = Date.now() + Math.random();
    runState.set(opblock, runId);
    const old = findResponseBlock(opblock).querySelector(".response-viz");
    if (old) old.remove();
    tryRender(opblock, runId, previousRaw);
  });
})();
</script>
"""


@app.get("/docs", include_in_schema=False)
async def custom_swagger_ui_html():
    response = get_swagger_ui_html(
        openapi_url=app.openapi_url,
        title=f"{app.title} - Swagger UI",
        oauth2_redirect_url=app.swagger_ui_oauth2_redirect_url,
    )
    html = response.body.decode("utf-8").replace("</body>", _DOCS_RESPONSE_ENHANCER + "</body>")
    safe_headers = {
        key: value
        for key, value in dict(response.headers).items()
        if key.lower() != "content-length"
    }
    return HTMLResponse(html, status_code=response.status_code, headers=safe_headers)


@app.get(app.swagger_ui_oauth2_redirect_url, include_in_schema=False)
async def swagger_ui_redirect():
    return get_swagger_ui_oauth2_redirect_html()


# ── 엔드포인트 ─────────────────────────────────────────────

@app.get("/health")
async def health():
    return {"status": "ok"}


@app.post("/analyze", response_model=AnalyzeResponse)
async def analyze(req: AnalyzeRequest):
    """Chrome Extension — 단일 텍스트 분석."""
    started = time.perf_counter()
    result = pipeline.analyze(req.text, sensitivity=req.sensitivity)
    elapsed_ms = (time.perf_counter() - started) * 1000
    print(f"[TIMING] /analyze total={elapsed_ms:.1f}ms text={req.text[:40]!r}")
    return _format_result(
        result,
        timing_ms=elapsed_ms,
        model_timing_ms=result.get("_timing", {}).get("model_ms"),
        llm_timing_ms=0.0,
    )


@app.post("/analyze_batch", response_model=AnalyzeBatchResponse)
async def analyze_batch(req: AnalyzeBatchRequest):
    """Chrome Extension — 배치 텍스트 분석."""
    started = time.perf_counter()
    results = pipeline.analyze_batch(req.texts, sensitivity=req.sensitivity)
    elapsed_ms = (time.perf_counter() - started) * 1000
    print(f"[TIMING] /analyze_batch total={elapsed_ms:.1f}ms count={len(req.texts)}")
    return {"results": [_format_result(r) for r in results]}


@app.post("/analyze_android", response_model=AndroidResponse)
async def analyze_android(req: AndroidRequest):
    """Android App — 배치 분석 (boundsInScreen 보존)."""
    started = time.perf_counter()
    raw = req.model_dump()
    result = pipeline.analyze_android_batch(raw)
    elapsed_ms = (time.perf_counter() - started) * 1000
    print(f"[TIMING] /analyze_android total={elapsed_ms:.1f}ms count={len(req.comments)}")
    return result


@app.post("/agent/analyze", response_model=AgentAnalyzeResponse)
async def analyze_with_agent(req: AnalyzeRequest):
    """LangGraph 기반 LLM Agent 설명."""
    started = time.perf_counter()
    result = agent_service.analyze_with_agent(req.text)
    elapsed_ms = (time.perf_counter() - started) * 1000
    agent_mode = result["agent"].get("mode")
    print(f"[TIMING] /agent/analyze total={elapsed_ms:.1f}ms mode={agent_mode} text={req.text[:40]!r}")
    return {
        "analysis": _format_result(
            result["analysis"],
            timing_ms=elapsed_ms,
            model_timing_ms=result["analysis"].get("_timing", {}).get("model_ms"),
            llm_timing_ms=result.get("llm_timing_ms"),
        ),
        "agent": result["agent"],
        "timing_ms": round(elapsed_ms, 3),
        "model_timing_ms": result["analysis"].get("_timing", {}).get("model_ms"),
        "llm_timing_ms": result.get("llm_timing_ms"),
    }


# ── 실행 ───────────────────────────────────────────────────

if __name__ == "__main__":
    import uvicorn
    uvicorn.run("app:app", host="0.0.0.0", port=8000, reload=True)
