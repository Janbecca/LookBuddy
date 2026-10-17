import os

os.environ["LOOKBUDDY_DATABASE_URL"] = "sqlite:///./lookbuddy-test.db"
os.environ["LOOKBUDDY_JWT_SECRET"] = "test-secret-that-is-not-used-outside-tests"
os.environ["LOOKBUDDY_SCHEDULER_ENABLED"] = "false"
os.environ["LOOKBUDDY_LLM_API_KEY"] = ""
os.environ["LOOKBUDDY_ROUTER_API_KEY"] = ""
os.environ["LOOKBUDDY_GREETING_API_KEY"] = ""

import pytest
from fastapi.testclient import TestClient

from lookbuddy.database import Base, engine
from lookbuddy.main import app


@pytest.fixture(autouse=True)
def clean_database():
    Base.metadata.drop_all(bind=engine)
    Base.metadata.create_all(bind=engine)
    yield
    Base.metadata.drop_all(bind=engine)


@pytest.fixture
def client():
    with TestClient(app) as test_client:
        yield test_client


@pytest.fixture
def auth(client):
    response = client.post(
        "/v1/auth/register",
        json={"email": "demo@example.com", "password": "correct-horse", "displayName": "Demo"},
    )
    assert response.status_code == 201, response.text
    payload = response.json()
    return {"Authorization": f"Bearer {payload['accessToken']}"}

