"""Regression checks for the local entry point, without changing upstream rules."""
import importlib.util
import os
from pathlib import Path


def launcher():
    path = Path(__file__).resolve().parents[1] / "app.py"
    spec = importlib.util.spec_from_file_location("lookbuddy_local_launcher", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def clear_model_env(monkeypatch, module):
    monkeypatch.setattr(os, "environ", os.environ.copy())
    for legacy, canonical in module.LEGACY_ENV.items():
        monkeypatch.delenv(legacy, raising=False)
        monkeypatch.delenv(canonical, raising=False)


def test_existing_glm_config_maps_to_upstream(monkeypatch, tmp_path):
    module = launcher()
    clear_model_env(monkeypatch, module)
    env = tmp_path / ".env"
    env.write_text("GLM_BASE_URL=https://example.invalid/v1\nGLM_MODEL=local-model\nGLM_API_KEY=test-only-placeholder\n", encoding="utf-8")
    module.load_environment(env)
    from lookbuddy.config import Settings
    settings = Settings(_env_file=None)
    assert settings.greeting_base_url == "https://example.invalid/v1"
    assert settings.greeting_model == "local-model"
    assert settings.greeting_api_key == "test-only-placeholder"


def test_process_and_canonical_config_take_precedence(monkeypatch, tmp_path):
    module = launcher()
    clear_model_env(monkeypatch, module)
    monkeypatch.setenv("LOOKBUDDY_GREETING_MODEL", "canonical-process-model")
    monkeypatch.setenv("GLM_BASE_URL", "https://process.invalid/v1")
    env = tmp_path / ".env"
    env.write_text("GLM_BASE_URL=https://file.invalid/v1\nGLM_MODEL=legacy-file-model\n", encoding="utf-8")
    module.load_environment(env)
    from lookbuddy.config import Settings
    settings = Settings(_env_file=None)
    assert settings.greeting_model == "canonical-process-model"
    assert settings.greeting_base_url == "https://process.invalid/v1"


def test_no_env_ignores_file_but_maps_process_legacy_names(monkeypatch, tmp_path):
    module = launcher()
    clear_model_env(monkeypatch, module)
    monkeypatch.setenv("GLM_MODEL", "process-only-model")
    env = tmp_path / ".env"
    env.write_text("GLM_BASE_URL=https://must-not-load.invalid/v1\n", encoding="utf-8")
    module.load_environment(env, read_dotenv=False)
    from lookbuddy.config import Settings
    settings = Settings(_env_file=None)
    assert settings.greeting_model == "process-only-model"
    assert settings.greeting_base_url is None
