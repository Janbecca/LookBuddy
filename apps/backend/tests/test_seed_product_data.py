"""Real FastAPI/isolated SQLite verification of the local seeding script."""
import importlib.util
import json
import warnings
from pathlib import Path

import httpx
import pytest

spec = importlib.util.spec_from_file_location("seed_product_data", Path(__file__).resolve().parents[3] / "scripts/seed_product_data.py")
seed_script = importlib.util.module_from_spec(spec)
spec.loader.exec_module(seed_script)


def make_api(client, auth):
    api = seed_script.Api("http://127.0.0.1", client=client)
    api.token = auth["Authorization"].removeprefix("Bearer ")
    return api


def test_seed_restore_and_originally_absent_mood(client, auth, tmp_path):
    api = make_api(client, auth)
    before = seed_script.snapshot(api)
    backup = tmp_path / "recovery.json"
    after = seed_script.seed(api, before["userId"], backup)
    assert after["before"] != before["before"]
    assert after["before"]["mood"] == {"mood": "Happy"}
    assert json.loads(backup.read_text(encoding="utf-8"))["state"] == "seeded"
    assert api.token not in backup.read_text(encoding="utf-8")
    restored = seed_script.restore(api, before["userId"], backup)
    assert restored["before"] == before["before"]
    assert seed_script.restore(api, before["userId"], backup)["before"] == before["before"]
    assert backup.exists()


def test_restore_existing_mood_and_schedule(client, auth, tmp_path):
    api = make_api(client, auth)
    info = seed_script.snapshot(api)
    api.request("PUT", f"/v1/moods/{info['day']}", {"mood": "Happy"})
    api.request("PATCH", "/v1/work-schedule", {"start": "22:00", "end": "06:00"})
    before = seed_script.snapshot(api)
    backup = tmp_path / "recovery.json"
    after = seed_script.seed(api, before["userId"], backup)
    assert after["before"]["mood"] == {"mood": "Sad"}
    assert seed_script.restore(api, before["userId"], backup)["before"] == before["before"]


def test_wrong_account_and_existing_backup_cannot_write(client, auth, tmp_path):
    api = make_api(client, auth)
    before = seed_script.snapshot(api)
    backup = tmp_path / "recovery.json"
    with pytest.raises(seed_script.SeedError, match="Account mismatch"):
        seed_script.seed(api, "someone_else", backup)
    assert not backup.exists()
    backup.write_text("original backup", encoding="utf-8")
    with pytest.raises(FileExistsError):
        seed_script.seed(api, before["userId"], backup)
    assert backup.read_text() == "original backup"
    assert seed_script.snapshot(api)["before"] == before["before"]


def test_restore_refuses_later_phone_edits(client, auth, tmp_path):
    api = make_api(client, auth)
    before = seed_script.snapshot(api)
    backup = tmp_path / "recovery.json"
    seed_script.seed(api, before["userId"], backup)
    api.request("PATCH", "/v1/me", {"displayName": "用户后来修改的昵称"})
    with pytest.raises(seed_script.SeedError, match="refusing to overwrite"):
        seed_script.restore(api, before["userId"], backup)
    assert api.read("/v1/profile")["displayName"] == "用户后来修改的昵称"


def test_interrupted_write_is_recoverable(client, auth, tmp_path):
    api = make_api(client, auth)
    before = seed_script.snapshot(api)
    original_request = api.request
    def fail_after_mood_write(method, path, body=None):
        result = original_request(method, path, body)
        if method == "PUT" and path.startswith("/v1/moods/"):
            raise seed_script.SeedError("Response lost after committed write")
        return result
    api.request = fail_after_mood_write
    backup = tmp_path / "recovery.json"
    with pytest.raises(seed_script.SeedError):
        seed_script.seed(api, before["userId"], backup)
    assert json.loads(backup.read_text(encoding="utf-8"))["attempted"] == ["profile", "mood"]
    api.request = original_request
    assert seed_script.restore(api, before["userId"], backup)["before"] == before["before"]


def test_origin_mismatch_refuses_restore(client, auth, tmp_path):
    api = make_api(client, auth)
    before = seed_script.snapshot(api)
    backup = tmp_path / "recovery.json"
    seed_script.seed(api, before["userId"], backup)
    journal = json.loads(backup.read_text(encoding="utf-8"))
    journal["backend"] = "https://another.example"
    backup.write_text(json.dumps(journal), encoding="utf-8")
    with pytest.raises(seed_script.SeedError, match="backend mismatch"):
        seed_script.restore(api, before["userId"], backup)


@pytest.mark.parametrize("value", ["http://public.example", "https://user:password@example.com", "https://example.com/v1", "https://example.com?token=x", "https://example.com:bad", "ftp://localhost"])
def test_unsafe_origins(value):
    with pytest.raises(seed_script.SeedError):
        seed_script.origin(value)


def test_future_date_fails_before_writing(client, auth, tmp_path):
    api = make_api(client, auth)
    before = seed_script.snapshot(api)
    with pytest.raises(seed_script.SeedError, match="Future"):
        seed_script.seed(api, before["userId"], tmp_path / "backup.json", "2999-01-01")
    assert seed_script.snapshot(api)["before"] == before["before"]
    assert not (tmp_path / "backup.json").exists()


def test_cli_requires_account_confirmation_before_network():
    with pytest.raises(SystemExit) as error:
        seed_script.main(["seed", "--backend", "https://example.com"])
    assert error.value.code == 2


def test_redirect_and_error_bodies_never_leak_tokens():
    transport = httpx.MockTransport(lambda request: httpx.Response(302,
        headers={"Location": "https://other.example"}, json={"secret": "DO_NOT_PRINT"}))
    with httpx.Client(transport=transport, follow_redirects=False) as client:
        api = seed_script.Api("https://example.com", client=client)
        with pytest.raises(seed_script.SeedError) as error:
            api.read("/v1/profile")
    assert "DO_NOT_PRINT" not in str(error.value)
    assert "302" in str(error.value)


def test_cli_defaults_to_read_only_and_does_not_echo_token(monkeypatch, capsys):
    class ReadOnlyApi:
        backend = "https://example.com"
        token = ""
        def __init__(self):
            self.client = self
            self.closed = False
        def close(self):
            self.closed = True
        def read(self, path):
            return {
                "/health/ready": {"status": "ready"},
                "/v1/profile": {"id": "usr_test", "displayName": "Test", "timezone": "Asia/Shanghai"},
                "/v1/work-schedule": {"start": "09:00", "end": "18:00"},
            }.get(path)
        def request(self, *args):
            raise AssertionError("Read-only CLI must not write")
    api = ReadOnlyApi()
    monkeypatch.setattr(seed_script, "Api", lambda *args, **kwargs: api)
    monkeypatch.setenv("LOOKBUDDY_TEST_ACCESS_TOKEN", "test-only-no-real-token")
    assert seed_script.main(["--backend", api.backend]) == 0
    assert "test-only-no-real-token" not in capsys.readouterr().out
    assert api.token == "" and api.closed


def test_timeout_does_not_echo_request_credentials():
    def timeout(request):
        raise httpx.ReadTimeout("SENSITIVE_REQUEST_TEXT", request=request)
    with httpx.Client(transport=httpx.MockTransport(timeout)) as client:
        api = seed_script.Api("https://example.com", client=client)
        api.token = "test-only-token"
        with pytest.raises(seed_script.SeedError) as error:
            api.read("/v1/profile")
    assert "SENSITIVE_REQUEST_TEXT" not in str(error.value)
    assert "test-only-token" not in str(error.value)


def test_hidden_input_refuses_echo_fallback(monkeypatch):
    def unavailable(prompt):
        warnings.warn("Cannot hide input", seed_script.getpass.GetPassWarning)
        raise AssertionError("Must stop before reading echoed input")
    monkeypatch.setattr(seed_script.getpass, "getpass", unavailable)
    with pytest.raises(seed_script.SeedError, match="interactive terminal"):
        seed_script.hidden_input("Authorization: ")
