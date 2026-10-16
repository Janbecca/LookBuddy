from collections.abc import Generator
from pathlib import Path
from tempfile import TemporaryFile

from sqlalchemy import create_engine, event
from sqlalchemy.engine import make_url
from sqlalchemy.orm import DeclarativeBase, Session, sessionmaker

from .config import get_settings


class Base(DeclarativeBase):
    pass


def check_storage_access() -> None:
    settings = get_settings()
    if settings.env != "competition":
        return
    database = Path(make_url(settings.database_url).database).resolve()
    root = Path("/data").resolve()
    if not database.is_relative_to(root):
        raise RuntimeError("Database path resolves outside /data")
    try:
        with TemporaryFile(dir=database.parent):
            pass
        if database.exists():
            with database.open("r+b"):
                pass
    except OSError as cause:
        raise RuntimeError("SQLite storage is not writable; configure volume ownership for UID/GID 10001") from cause


def _make_engine(url: str | None = None):
    url = url or get_settings().database_url
    connect_args = {"check_same_thread": False, "timeout": 10} if url.startswith("sqlite") else {}
    created_engine = create_engine(url, connect_args=connect_args, pool_pre_ping=True)
    if url.startswith("sqlite"):
        @event.listens_for(created_engine, "connect")
        def enable_sqlite_foreign_keys(dbapi_connection, _connection_record):
            cursor = dbapi_connection.cursor()
            cursor.execute("PRAGMA foreign_keys=ON")
            cursor.execute("PRAGMA busy_timeout=10000")
            cursor.execute("PRAGMA journal_mode=WAL")
            cursor.close()
    return created_engine


engine = _make_engine()
SessionLocal = sessionmaker(bind=engine, autoflush=False, expire_on_commit=False)


def get_db() -> Generator[Session, None, None]:
    with SessionLocal() as session:
        yield session

