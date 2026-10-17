"""Shared text-only LLM transport. Never logs credentials, prompts or model responses."""
from dataclasses import dataclass, field
from typing import Literal
from urllib.parse import urlsplit

import httpx

from .config import get_settings


@dataclass(frozen=True)
class ModelConfig:
    base_url: str
    model: str
    api_key: str = field(repr=False)
    mode: str = "shared"


def model_config(purpose: Literal["greeting", "diary"]) -> ModelConfig | None:
    settings = get_settings()
    shared_key = settings.llm_api_key.get_secret_value().strip() if settings.llm_api_key else ""
    if shared_key and settings.llm_base_url and settings.llm_model:
        return ModelConfig(settings.llm_base_url.rstrip("/"), settings.llm_model, shared_key)
    prefix = "greeting" if purpose == "greeting" else "router"
    base, key, model = (getattr(settings, f"{prefix}_{part}") for part in ("base_url", "api_key", "model"))
    if base and key and model:
        return ModelConfig(base.rstrip("/"), model, key, "legacy")
    return None


def model_status() -> dict:
    """Configured does not mean verified. No endpoint or credential is exposed."""
    result = {}
    for purpose in ("greeting", "diary"):
        config = model_config(purpose)
        result[purpose] = {"configured": config is not None,
                           "model": config.model if config else None,
                           "configuration": config.mode if config else "template"}
    return result


async def complete(config: ModelConfig, messages: list[dict[str, str]], *,
                   json_output: bool = False, timeout: float = 20, max_tokens: int = 1024) -> str:
    url = urlsplit(config.base_url)
    if (url.scheme != "https" or not url.hostname or url.username or url.password or url.query or url.fragment
            or any(c.isspace() for c in config.base_url)):
        raise ValueError("Model service must use an HTTPS base URL without credentials, query or fragment")
    payload = {"model": config.model, "messages": messages, "stream": False,
               "temperature": 0.4, "max_tokens": max_tokens}
    if url.hostname == "api.deepseek.com":
        # DeepSeek enables thinking by default; short UI text needs bounded latency.
        payload["thinking"] = {"type": "disabled"}
        if json_output:
            payload["response_format"] = {"type": "json_object"}
    async with httpx.AsyncClient(timeout=timeout, follow_redirects=False) as client:
        response = await client.post(config.base_url + "/chat/completions",
                                     headers={"Authorization": "Bearer " + config.api_key}, json=payload)
        response.raise_for_status()
        body = response.json()
    try:
        content = body["choices"][0]["message"]["content"]
    except (KeyError, TypeError, IndexError) as exc:
        raise ValueError("Model service returned an invalid completion") from exc
    if not isinstance(content, str) or not content.strip():
        raise ValueError("Model service returned empty text")
    return content.strip()
