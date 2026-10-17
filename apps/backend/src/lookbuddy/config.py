from functools import lru_cache
import re
from pathlib import PurePosixPath
from urllib.parse import urlsplit
from typing import Annotated

from pydantic import SecretStr, field_validator, model_validator
from pydantic_settings import BaseSettings, NoDecode, SettingsConfigDict
from sqlalchemy.engine import make_url


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=(".env", "../../.env"),
        env_prefix="LOOKBUDDY_",
        extra="ignore",
    )

    env: str = "development"
    database_url: str = "sqlite:///./lookbuddy.db"
    database_schema: str | None = None
    jwt_secret: str = "development-only-change-me-at-least-32-bytes"
    access_token_minutes: int = 30
    refresh_token_days: int = 30
    cors_origins: Annotated[list[str], NoDecode] = ["http://localhost:5173", "http://127.0.0.1:5173"]
    router_base_url: str | None = None
    router_api_key: str | None = None
    router_model: str | None = None
    scheduler_enabled: bool = True
    web_dist: str | None = None
    demo_login_enabled: bool = False
    greeting_base_url: str | None = None
    greeting_api_key: str | None = None
    greeting_model: str = "glm-4-flash"
    # One private runtime key enables both diary writing and greeting generation.
    llm_base_url: str = "https://api.deepseek.com"
    llm_model: str = "deepseek-flash"
    llm_api_key: SecretStr | None = None

    @model_validator(mode="after")
    def validate_production(self) -> "Settings":
        if self.env not in {"production", "competition"}:
            return self
        if self.jwt_secret == "development-only-change-me-at-least-32-bytes" or len(self.jwt_secret) < 32:
            raise ValueError("production requires LOOKBUDDY_JWT_SECRET with at least 32 characters")
        if self.env == "production" and self.database_url.startswith("sqlite"):
            raise ValueError("production requires a managed database, not SQLite")
        if (self.env == "competition" and make_url(self.database_url).get_backend_name() == "postgresql"
                and not self.database_schema):
            raise ValueError("competition PostgreSQL requires LOOKBUDDY_DATABASE_SCHEMA")
        if self.env == "competition" and make_url(self.database_url).get_backend_name() != "postgresql":
            url = make_url(self.database_url)
            path = PurePosixPath(url.database or "")
            if (url.drivername != "sqlite" or not path.is_absolute()
                    or path.parts[:2] != ("/", "data") or len(path.parts) < 3
                    or ".." in path.parts or "\\" in (url.database or "") or url.query):
                raise ValueError("competition requires a SQLite file below /data without URL query parameters")
        if not self.cors_origins or any(
            urlsplit(origin).scheme != "https" or not urlsplit(origin).hostname
            or urlsplit(origin).hostname in {"localhost", "127.0.0.1", "::1"}
            or urlsplit(origin).username is not None or urlsplit(origin).password is not None
            or urlsplit(origin).path not in {"", "/"} or urlsplit(origin).query or urlsplit(origin).fragment
            for origin in self.cors_origins
        ):
            raise ValueError("deployment requires explicit non-local HTTPS LOOKBUDDY_CORS_ORIGINS")
        return self

    @field_validator("database_schema")
    @classmethod
    def validate_database_schema(cls, value: str | None) -> str | None:
        if value is not None and (not re.fullmatch(r"[A-Za-z0-9_]{1,63}", value)
                                  or value.lower() == "public" or value.lower().startswith("pg_")):
            raise ValueError("database_schema must name a private PostgreSQL schema")
        return value

    @field_validator("cors_origins", mode="before")
    @classmethod
    def split_origins(cls, value: object) -> object:
        if isinstance(value, str):
            if value.lstrip().startswith("["):
                import json
                return json.loads(value)
            return [item.strip() for item in value.split(",") if item.strip()]
        return value


@lru_cache
def get_settings() -> Settings:
    return Settings()

