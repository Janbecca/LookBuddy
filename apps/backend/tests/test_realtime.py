import pytest
from starlette.websockets import WebSocketDisconnect


def test_header_and_legacy_query_stream_auth(client, auth):
    with client.websocket_connect('/v1/stream', headers=auth) as socket:
        socket.send_text('ping')
    token = auth['Authorization'][7:]
    with client.websocket_connect(f'/v1/stream?token={token}') as socket:
        socket.send_text('ping')


def test_missing_invalid_and_deleted_user_stream_rejected(client, auth):
    for headers in ({}, {'Authorization': 'Bearer invalid'}):
        with pytest.raises(WebSocketDisconnect) as error:
            with client.websocket_connect('/v1/stream', headers=headers):
                pass
        assert error.value.code == 4401
    assert client.delete('/v1/account', headers=auth).status_code == 204
    with pytest.raises(WebSocketDisconnect) as error:
        with client.websocket_connect('/v1/stream', headers=auth):
            pass
    assert error.value.code == 4401
