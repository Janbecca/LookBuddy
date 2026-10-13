"""Additive schema revisions: no existing table is altered or dropped.

Future column changes must be explicit versioned migrations, not create_all.
"""
from sqlalchemy import Engine, select
from sqlalchemy.orm import Session

from .database import Base
from .models import SchemaRevision


REVISION = "20261008_p0_learning_foundation"
REVISIONS = (REVISION, "20261008_p2_scheduler", "20261008_p3_expression")


def migrate(bind: Engine) -> None:
    Base.metadata.create_all(bind=bind)
    with Session(bind) as db:
        for revision in REVISIONS:
            if db.get(SchemaRevision, revision) is None:
                db.add(SchemaRevision(version=revision))
        db.commit()
