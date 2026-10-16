from collections import defaultdict

from fastapi import WebSocket


class ConnectionManager:
    def __init__(self) -> None:
        self._connections: dict[str, set[WebSocket]] = defaultdict(set)

    async def connect(self, user_id: str, websocket: WebSocket) -> None:
        await websocket.accept()
        self._connections[user_id].add(websocket)

    def disconnect(self, user_id: str, websocket: WebSocket) -> None:
        self._connections[user_id].discard(websocket)

    async def publish(self, user_id: str, message: dict) -> None:
        failed: list[WebSocket] = []
        for websocket in self._connections[user_id]:
            try:
                await websocket.send_json(message)
            except RuntimeError:
                failed.append(websocket)
        for websocket in failed:
            self.disconnect(user_id, websocket)


manager = ConnectionManager()

