from contextlib import asynccontextmanager
import asyncio

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from .api import router
from .config import get_settings
from .database import engine
from .migrations import migrate
from .scheduler import scheduler_loop


@asynccontextmanager
async def lifespan(_app: FastAPI):
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
app.include_router(router)


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok"}
