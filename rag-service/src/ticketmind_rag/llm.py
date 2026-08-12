"""LLM abstraction — a tiny wrapper over ``langchain-anthropic`` plus a fake.

The Protocol keeps the surface small: one async method that takes a system
prompt and a user prompt and returns the text of the assistant reply.
"""

from __future__ import annotations

from typing import Protocol, runtime_checkable

from langchain_anthropic import ChatAnthropic
from langchain_core.messages import HumanMessage, SystemMessage

from .errors import UpstreamError


@runtime_checkable
class ChatModel(Protocol):
    async def complete(self, system: str, user: str) -> str: ...


class AnthropicChat:
    """Production chat model backed by ``langchain-anthropic``."""

    def __init__(self, api_key: str, model: str) -> None:
        # No temperature: Claude Opus 5 (and every Opus 4.7+ model) rejects
        # sampling parameters with a 400. Determinism is steered by the prompt.
        self._client = ChatAnthropic(
            api_key=api_key,
            model=model,
            max_tokens=1024,
        )

    async def complete(self, system: str, user: str) -> str:
        try:
            response = await self._client.ainvoke(
                [SystemMessage(content=system), HumanMessage(content=user)]
            )
        except Exception as exc:
            raise UpstreamError(f"chat provider failed: {exc}") from exc
        content = response.content
        if isinstance(content, list):
            # LangChain can return content blocks for multi-part responses.
            return "".join(
                block.get("text", "") if isinstance(block, dict) else str(block)
                for block in content
            )
        return str(content)


class FakeChat:
    """Test double that returns a caller-supplied canned response."""

    def __init__(self, canned: str) -> None:
        self._canned = canned
        self.calls: list[tuple[str, str]] = []

    async def complete(self, system: str, user: str) -> str:
        self.calls.append((system, user))
        return self._canned
