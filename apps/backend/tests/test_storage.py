import importlib.util
from pathlib import Path
import sqlite3
from types import SimpleNamespace

import pytest

from lookbuddy import database


def test_sqlite_connection_uses_wal_and_waits(tmp_path):
    engine = database._make_engine(f"sqlite:///{(tmp_path / 'database.db').as_posix()}")
    try:
        with engine.connect() as connection:
            assert connection.exec_driver_sql("PRAGMA journal_mode").scalar() == "wal"
            assert connection.exec_driver_sql("PRAGMA busy_timeout").scalar() == 10000
            assert connection.exec_driver_sql("PRAGMA foreign_keys").scalar() == 1
    finally:
        engine.dispose()


def test_storage_rejects_resolved_path_outside_volume(monkeypatch, tmp_path):
    monkeypatch.setattr(database, "get_settings", lambda: SimpleNamespace(
        env="competition", database_url=f"sqlite:///{(tmp_path / 'outside.db').as_posix()}"))
    with pytest.raises(RuntimeError, match="outside /data"):
        database.check_storage_access()


def test_postgres_numeric_schema_applies_to_every_connection(monkeypatch):
    monkeypatch.setattr(database, "get_settings", lambda: SimpleNamespace(
        env="competition", database_url="postgresql://database.example/supabase_db",
        database_schema="19357380370"))
    captured = {}
    def capture_engine(url, **kwargs):
        captured.update(url=url, **kwargs)
        return object()
    monkeypatch.setattr(database, "create_engine", capture_engine)
    database.check_storage_access()
    database._make_engine()
    assert captured["url"].drivername == "postgresql+psycopg"
    assert captured["url"].database == "supabase_db"
    assert captured["connect_args"] == {"connect_timeout": 10, "options": '-csearch_path="19357380370"'}


def test_backup_includes_live_wal_and_never_overwrites(tmp_path):
    spec = importlib.util.spec_from_file_location("backup_sqlite", Path(__file__).parents[1] / "tools/backup_sqlite.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    source = tmp_path / "live.db"
    target = tmp_path / "backup.db"
    with sqlite3.connect(source) as connection:
        connection.execute("PRAGMA journal_mode=WAL")
        connection.execute("CREATE TABLE entries(value TEXT)")
        connection.execute("INSERT INTO entries VALUES ('committed WAL row')")
        connection.commit()
        assert len(module.backup_database(source, target)) == 64
        with sqlite3.connect(target) as backup:
            assert backup.execute("SELECT value FROM entries").fetchone() == ("committed WAL row",)
        with pytest.raises(FileExistsError):
            module.backup_database(source, target)
        with pytest.raises(FileExistsError):
            module.backup_database(source, source)
