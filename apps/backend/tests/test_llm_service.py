import asyncio
import json

import httpx
import pytest
from pydantic import SecretStr

from lookbuddy.config import Settings, get_settings
from lookbuddy.diary_service import DiaryWriter
from lookbuddy.llm_service import complete, model_config


def configure(monkeypatch):
    settings = get_settings()
    monkeypatch.setattr(settings, "llm_api_key", SecretStr("unit-test-only"))
    monkeypatch.setattr(settings, "llm_base_url", "https://api.deepseek.com")
    monkeypatch.setattr(settings, "llm_model", "deepseek-flash")
    return settings


def mock_provider(monkeypatch, handler):
    original = httpx.AsyncClient
    transport = httpx.MockTransport(handler)
    monkeypatch.setattr(httpx, "AsyncClient", lambda **kwargs: original(transport=transport, **kwargs))


def test_shared_model_uses_one_private_setting_for_both_features(monkeypatch):
    settings = configure(monkeypatch)
    monkeypatch.setattr(settings, "greeting_base_url", "https://old.example/v1")
    monkeypatch.setattr(settings, "greeting_api_key", "old-unit-only")
    assert model_config("greeting").model == model_config("diary").model == "deepseek-flash"
    assert model_config("greeting").mode == "shared"
    assert "unit-test-only" not in repr(model_config("diary"))
    assert "unit-test-only" not in repr(settings)


def test_legacy_profile_still_works_when_shared_key_absent(monkeypatch):
    settings = get_settings()
    monkeypatch.setattr(settings, "router_base_url", "https://router.example/v1")
    monkeypatch.setattr(settings, "router_api_key", "unit-only")
    monkeypatch.setattr(settings, "router_model", "existing-model")
    assert model_config("diary").mode == "legacy"
    assert model_config("diary").model == "existing-model"


def test_deepseek_request_is_bounded_non_thinking_and_json_for_greeting(monkeypatch):
    configure(monkeypatch)
    def handler(request):
        assert str(request.url) == "https://api.deepseek.com/chat/completions"
        payload = json.loads(request.content)
        assert payload["model"] == "deepseek-flash"
        assert payload["thinking"] == {"type": "disabled"}
        assert payload["response_format"] == {"type": "json_object"}
        assert payload["max_tokens"] == 256
        assert payload["stream"] is False
        assert request.headers["authorization"] == "Bearer unit-test-only"
        return httpx.Response(200, json={"choices": [{"message": {"content": '{"show":true,"message":"一起慢慢开始。"}'}}]})
    mock_provider(monkeypatch, handler)
    result = asyncio.run(complete(model_config("greeting"), [{"role": "system", "content": "Return JSON"}],
                                  json_output=True, max_tokens=256))
    assert json.loads(result)["show"] is True


def test_greeting_route_reports_real_llm_source(client, auth, monkeypatch):
    configure(monkeypatch)
    mock_provider(monkeypatch, lambda request: httpx.Response(200, json={
        "choices": [{"message": {"content": '{"show":true,"message":"按你的节奏，我陪你。"}'}}]}))
    result = client.post("/v1/buddy/greeting", headers=auth, json={"scene": "morning_unknown"})
    assert result.status_code == 200
    assert result.json()["source"] == "llm"
    assert result.json()["message"] == "按你的节奏，我陪你。"


@pytest.mark.parametrize("body", [{}, {"choices": []}, {"choices": [{"message": {"content": None}}]},
                                  {"choices": [{"message": {"content": "not-json"}}]}])
def test_invalid_provider_response_is_honest_fallback(client, auth, monkeypatch, body):
    configure(monkeypatch)
    mock_provider(monkeypatch, lambda request: httpx.Response(200, json=body))
    result = client.post("/v1/buddy/greeting", headers=auth, json={"scene": "morning_unknown"})
    assert result.status_code == 200
    assert result.json()["source"] == "fallback"


def test_diary_uses_same_model_and_preserves_template_when_provider_fails(monkeypatch):
    configure(monkeypatch)
    candidates = [{"kind": "memory", "title": "记录了一次休息", "facts": {"count": 1}}]
    from datetime import date
    provider_down = False
    def handler(request):
        if provider_down:
            return httpx.Response(503, json={"error": "temporarily unavailable"})
        payload = json.loads(request.content)
        assert payload["thinking"] == {"type": "disabled"}
        assert "response_format" not in payload
        assert "image_url" not in request.content.decode()
        return httpx.Response(200, json={"choices": [{"message": {"content": "今天给自己留了一点休息时间。"}}]})
    mock_provider(monkeypatch, handler)
    assert asyncio.run(DiaryWriter().write(date(2026, 10, 17), candidates)) == ("今天给自己留了一点休息时间。", "llm")
    provider_down = True
    content, source = asyncio.run(DiaryWriter().write(date(2026, 10, 17), candidates))
    assert source == "template_fallback"
    assert "记录了一次休息" in content


def test_configuration_status_requires_login_and_never_returns_a_key(client, auth, monkeypatch):
    configure(monkeypatch)
    assert client.get("/v1/llm/status").status_code == 401
    result = client.get("/v1/llm/status", headers=auth)
    assert result.status_code == 200
    assert result.json()["features"]["greeting"] == {
        "configured": True, "model": "deepseek-flash", "configuration": "shared"}
    assert "unit-test-only" not in result.text
    assert "not proof" in result.json()["note"]
