import pytest
from pydantic import ValidationError

from lookbuddy.config import Settings


def test_production_rejects_development_defaults() -> None:
    with pytest.raises(ValidationError):
        Settings(env="production")


def test_production_accepts_managed_database_and_explicit_origin() -> None:
    settings = Settings(
        env="production",
        database_url="postgresql+psycopg://lookbuddy:secret@database.example/lookbuddy",
        jwt_secret="a" * 32,
        cors_origins=["https://app.example.com"],
    )

    assert settings.env == "production"


@pytest.mark.parametrize("url", [
    "sqlite:///./lookbuddy.db", "sqlite:////tmp/lookbuddy.db", "sqlite:///:memory:",
    "sqlite:////data/../tmp/lookbuddy.db", "sqlite:////data",
    "sqlite:////data/lookbuddy.db?mode=memory", "postgresql://db/lookbuddy",
])
def test_competition_rejects_nonpersistent_paths(url):
    with pytest.raises(ValidationError):
        Settings(env="competition", database_url=url, jwt_secret="x" * 40,
                 cors_origins=["https://lookbuddy.example.com"], _env_file=None)


def test_competition_accepts_data_volume(monkeypatch):
    monkeypatch.setenv("LOOKBUDDY_CORS_ORIGINS", "https://lookbuddy.example.com,https://web.example.com")
    value = Settings(env="competition", database_url="sqlite:////data/lookbuddy.db",
                     jwt_secret="x" * 40, _env_file=None)
    assert len(value.cors_origins) == 2


@pytest.mark.parametrize("origin", ["*", "http://example.com", "https://localhost", "https://example.com/path"])
def test_competition_enforces_deployment_security(origin):
    with pytest.raises(ValidationError):
        Settings(env="competition", database_url="sqlite:////data/lookbuddy.db",
                 jwt_secret="x" * 40, cors_origins=[origin], _env_file=None)


def test_competition_rejects_default_signing_configuration():
    with pytest.raises(ValidationError):
        Settings(env="competition", database_url="sqlite:////data/lookbuddy.db",
                 jwt_secret="short", cors_origins=["https://example.com"], _env_file=None)


def test_competition_accepts_postgres_with_private_numeric_schema():
    settings = Settings(env="competition", database_url="postgresql+psycopg://database.example/supabase_db",
                        database_schema="19357380370", jwt_secret="x" * 40,
                        cors_origins=["https://example.com"], _env_file=None)
    assert settings.database_schema == "19357380370"


@pytest.mark.parametrize("schema", ["public", "pg_catalog", 'bad"schema', "a,b", "a b"])
def test_database_schema_rejects_unsafe_or_shared_names(schema):
    with pytest.raises(ValidationError):
        Settings(database_schema=schema, _env_file=None)
