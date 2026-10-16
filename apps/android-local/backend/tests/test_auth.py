def test_register_login_refresh_and_me(client):
    registered = client.post(
        "/v1/auth/register",
        json={"email": "User@Example.com", "password": "correct-horse", "displayName": "小明"},
    )
    assert registered.status_code == 201
    tokens = registered.json()
    assert tokens["user"]["email"] == "user@example.com"

    me = client.get("/v1/me", headers={"Authorization": f"Bearer {tokens['accessToken']}"})
    assert me.status_code == 200
    assert me.json()["displayName"] == "小明"

    refreshed = client.post("/v1/auth/refresh", json={"refreshToken": tokens["refreshToken"]})
    assert refreshed.status_code == 200
    assert refreshed.json()["refreshToken"] != tokens["refreshToken"]

    reused = client.post("/v1/auth/refresh", json={"refreshToken": tokens["refreshToken"]})
    assert reused.status_code == 401


def test_duplicate_email_is_rejected(client):
    body = {"email": "same@example.com", "password": "correct-horse", "displayName": "One"}
    assert client.post("/v1/auth/register", json=body).status_code == 201
    assert client.post("/v1/auth/register", json=body).status_code == 409

