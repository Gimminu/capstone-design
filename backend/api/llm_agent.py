"""
ML 탐지 결과 설명용 OpenAI LLM Agent

- 탐지는 기존 ML 파이프라인(classifier + span)이 담당
- LLM은 판정하지 않고, ML이 왜 그렇게 탐지했는지 설명만 담당
"""
from __future__ import annotations

import json
import os


SYSTEM_PROMPT = (
    "너는 유해표현 탐지 결과를 설명하는 Explainer Agent다. "
    "중요: 너는 새로 분류하거나 판정을 뒤집지 않는다. "
    "반드시 입력으로 제공된 ML 탐지 결과만 근거로 설명한다. "
    "즉, is_offensive/is_profane/is_toxic/is_hate, scores, evidence_spans 외의 추측은 하지 마라. "
    "출력 형식은 반드시 아래를 따른다.\n"
    "1. ML 판단\n"
    "2. 탐지 근거\n"
    "3. 해석"
)


EXPLANATION_EXAMPLES = [
    {
        "input": {
            "text": "메갈년들 다 꺼져라",
            "is_offensive": True,
            "is_profane": True,
            "is_toxic": True,
            "is_hate": True,
            "scores": {"profanity": 0.97, "toxicity": 0.99, "hate": 0.98},
            "evidence_spans": ["메갈년들", "꺼져라"],
        },
        "output": (
            "1. ML 판단\n"
            "ML 파이프라인은 이 문장을 유해표현으로 탐지했다. 특히 비속어, 공격성, 혐오표현 신호가 모두 높게 나타났다.\n"
            "2. 탐지 근거\n"
            "점수 기준으로 profanity, toxicity, hate가 모두 높고, evidence span으로 '메갈년들', '꺼져라'가 추출되었다.\n"
            "3. 해석\n"
            "따라서 모델은 특정 집단을 비하하는 표현과 직접적인 공격 표현이 함께 포함된 문장으로 해석했다."
        ),
    },
    {
        "input": {
            "text": "오늘 발표 준비하느라 고생 많았어",
            "is_offensive": False,
            "is_profane": False,
            "is_toxic": False,
            "is_hate": False,
            "scores": {"profanity": 0.03, "toxicity": 0.04, "hate": 0.01},
            "evidence_spans": [],
        },
        "output": (
            "1. ML 판단\n"
            "ML 파이프라인은 이 문장을 유해표현으로 탐지하지 않았다.\n"
            "2. 탐지 근거\n"
            "비속어, 공격성, 혐오표현 점수가 모두 낮고, evidence span도 추출되지 않았다.\n"
            "3. 해석\n"
            "따라서 모델은 이 문장을 일반적인 격려 표현으로 해석했다."
        ),
    },
]


def _analysis_payload(text: str, analysis: dict) -> dict:
    spans = [s.get("text", "") for s in analysis.get("evidence_spans", []) if s.get("text")]
    return {
        "text": text,
        "is_offensive": analysis.get("is_offensive", False),
        "is_profane": analysis.get("is_profane", False),
        "is_toxic": analysis.get("is_toxic", False),
        "is_hate": analysis.get("is_hate", False),
        "scores": analysis.get("scores", {}),
        "evidence_spans": spans,
    }


def _few_shot_block() -> str:
    chunks = ["[Explainer Few-shot]"]
    for idx, example in enumerate(EXPLANATION_EXAMPLES, start=1):
        chunks.append(
            f"예시 {idx} 입력:\n{json.dumps(example['input'], ensure_ascii=False, indent=2)}\n"
            f"예시 {idx} 출력:\n{example['output']}"
        )
    return "\n\n".join(chunks)


def _fallback_response(text: str, analysis: dict, reason: str) -> dict:
    labels = []
    if analysis.get("is_profane"):
        labels.append("비속어")
    if analysis.get("is_toxic"):
        labels.append("공격성")
    if analysis.get("is_hate"):
        labels.append("혐오표현")

    spans = [s.get("text", "") for s in analysis.get("evidence_spans", []) if s.get("text")]
    span_desc = ", ".join(spans) if spans else "추출된 span 없음"

    if not analysis.get("is_offensive"):
        response = (
            "1. ML 판단\n"
            "ML 파이프라인은 이 문장을 유해표현으로 탐지하지 않았다.\n"
            "2. 탐지 근거\n"
            f"점수가 전반적으로 낮고 evidence span이 없다. ({span_desc})\n"
            "3. 해석\n"
            "현재 모델 기준으로는 공격성, 비속어, 혐오표현 신호가 약한 문장으로 본다."
        )
    else:
        response = (
            "1. ML 판단\n"
            f"ML 파이프라인은 이 문장을 {', '.join(labels)} 관련 유해표현으로 탐지했다.\n"
            "2. 탐지 근거\n"
            f"분류 점수와 추출된 evidence span을 근거로 판단했다. ({span_desc})\n"
            "3. 해석\n"
            "즉, 모델은 문장 안에 유해 신호가 있다고 보고 해당 표현을 근거로 삼았다."
        )

    return {
        "mode": "fallback",
        "model": None,
        "reason": reason,
        "response": response,
        "sub_agents": None,
    }


class ToxicityLLMAgent:
    def __init__(self):
        self.enabled = False
        self.reason = ""
        self.model_name = os.environ.get("OPENAI_MODEL", "gpt-4o-mini")
        self.llm = None
        self._try_build_agent()

    def _try_build_agent(self):
        try:
            from langchain_core.messages import HumanMessage, SystemMessage
            from langchain_openai import ChatOpenAI
        except Exception as exc:
            self.reason = f"langchain-openai import 실패: {exc}"
            return

        if not os.environ.get("OPENAI_API_KEY"):
            self.reason = "OPENAI_API_KEY가 설정되지 않음"
            return

        self.SystemMessage = SystemMessage
        self.HumanMessage = HumanMessage
        self.llm = ChatOpenAI(model=self.model_name, temperature=0)
        self.enabled = True
        self.reason = ""

    def summarize(self, text: str, analysis: dict) -> dict:
        if not self.enabled or self.llm is None:
            return _fallback_response(text, analysis, self.reason or "LLM 비활성화")

        payload = _analysis_payload(text, analysis)
        prompt = (
            _few_shot_block()
            + "\n\n[실제 입력]\n"
            + json.dumps(payload, ensure_ascii=False, indent=2)
        )

        try:
            messages = [
                self.SystemMessage(content=SYSTEM_PROMPT),
                self.HumanMessage(content=prompt),
            ]
            response = self.llm.invoke(messages).content
            return {
                "mode": "explainer",
                "model": self.model_name,
                "reason": None,
                "response": response,
                "sub_agents": None,
            }
        except Exception as exc:
            return _fallback_response(text, analysis, f"LLM 호출 실패: {exc}")
