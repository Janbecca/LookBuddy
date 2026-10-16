from contextlib import asynccontextmanager
import asyncio

from fastapi import FastAPI
from fastapi import HTTPException
from fastapi.middleware.cors import CORSMiddleware
from fastapi.staticfiles import StaticFiles
from sqlalchemy import text

from .api import router
from .product import router as product_router, compatibility as product_compatibility
from .config import get_settings
from .database import check_storage_access, engine
from .migrations import migrate
from .scheduler import scheduler_loop


@asynccontextmanager
async def lifespan(_app: FastAPI):
    check_storage_access()
    migrate(engine)
    stop = asyncio.Event()
    task = asyncio.create_task(scheduler_loop(stop)) if get_settings().scheduler_enabled else None
    try:
        yield
    finally:
        stop.set()
        if task:
            await task


app = FastAPI(
    title="LookBuddy API",
    version="0.1.0",
    description="Privacy-preserving event, reminder and workplace diary API.",
    lifespan=lifespan,
)
settings = get_settings()
app.add_middleware(
    CORSMiddleware,
    allow_origins=settings.cors_origins,
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)
app.include_router(product_router)
app.include_router(product_compatibility)
app.include_router(router)


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok"}


@app.get("/health/live", include_in_schema=False)
def liveness() -> dict[str, str]:
    return {"status": "ok"}


@app.get("/health/ready", include_in_schema=False)
def readiness() -> dict[str, str]:
    try:
        with engine.connect() as connection:
            connection.execute(text("SELECT 1"))
    except Exception as cause:
        raise HTTPException(status_code=503, detail="database unavailable") from cause
    return {"status": "ready"}


# Registered last so API, health and WebSocket routes retain priority.
if settings.web_dist:
    app.mount("/", StaticFiles(directory=settings.web_dist, html=True), name="web")
