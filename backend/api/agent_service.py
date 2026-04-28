"""
LLM Agent 서비스 레이어
"""
import time

from llm_agent import ToxicityLLMAgent
from pipeline import ProfanityPipeline


class AgentService:
    def __init__(self, pipeline: ProfanityPipeline):
        self.pipeline = pipeline
        self.agent = ToxicityLLMAgent()

    def analyze_with_agent(self, text: str) -> dict:
        llm_started = None
        analysis = self.pipeline.analyze(text)
        llm_started = time.perf_counter()
        agent_result = self.agent.summarize(text, analysis)
        llm_ms = (time.perf_counter() - llm_started) * 1000
        return {
            "analysis": analysis,
            "agent": agent_result,
            "llm_timing_ms": round(llm_ms, 3),
        }
