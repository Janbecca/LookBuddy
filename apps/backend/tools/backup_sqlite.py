"""Create an exclusive, verified SQLite snapshot, including committed WAL data."""
from __future__ import annotations

import argparse
from contextlib import closing
import hashlib
from pathlib import Path
import sqlite3


def backup_database(source: Path, destination: Path) -> str:
    source = source.resolve(strict=True)
    destination = destination.resolve()
    # Exclusive creation prevents overwriting either an existing backup or source.
    with destination.open("xb"):
        pass
    try:
        with closing(sqlite3.connect(source.as_uri() + "?mode=ro", uri=True)) as origin:
            with closing(sqlite3.connect(destination)) as target:
                origin.backup(target, pages=256, sleep=0.05)
                if target.execute("PRAGMA integrity_check").fetchone() != ("ok",):
                    raise RuntimeError("Backup failed SQLite integrity check")
        with destination.open("rb") as stream:
            return hashlib.file_digest(stream, "sha256").hexdigest()
    except BaseException:
        destination.unlink(missing_ok=True)
        raise


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, default=Path("/data/lookbuddy.db"))
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    checksum = backup_database(args.source, args.output)
    print(f"Backup verified: {args.output.name}; SHA256={checksum}")
    print("Download this file off the Launch volume before any deletion or redeployment.")


if __name__ == "__main__":
    main()
