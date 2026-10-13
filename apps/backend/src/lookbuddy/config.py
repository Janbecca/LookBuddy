from functools import lru_cache

from pydantic import field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=(".env", "../../.env"),
        env_prefix="LOOKBUDDY_",
        extra="ignore",
    )

    env: str = "development"
    database_url: str = "sqlite:///./lookbuddy.db"
    jwt_secret: str = "development-only-change-me-at-least-32-bytes"
    access_token_minutes: int = 30
    refresh_token_days: int = 30
    cors_origins: list[str] = ["http://localhost:5173", "http://127.0.0.1:5173"]
    router_base_url: str | None = None
    router_api_key: str | None = None
    router_model: str | None = None
    scheduler_enabled: bool = True

    @field_validator("cors_origins", mode="before")
    @classmethod
    def split_origins(cls, value: object) -> object:
        if isinstance(value, str):
            return [item.strip() for item in value.split(",") if item.strip()]
        return value


@lru_cache
def get_settings() -> Settings:
    return Settings()

