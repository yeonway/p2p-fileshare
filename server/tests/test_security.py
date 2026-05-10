from __future__ import annotations

import base64

import pytest
from starlette.websockets import WebSocketDisconnect

from app import db
from app.config import Settings
from app.security import hash_value, is_allowed_origin, sanitize_filename


def create_room(client):
    response = client.post(
        "/api/rooms",
        json={
            "file_name": "../secret/video.mp4",
            "file_size": 4096,
            "mime_type": "video/mp4",
            "client_type": "web",
        },
    )
    assert response.status_code == 200
    return response.json()


def test_ip_hash_is_not_plain_ip(client):
    created = create_room(client)
    row = db.fetch_one("SELECT sender_ip_hash FROM transfers WHERE room_id = ?", (created["room_id"],))
    assert row["sender_ip_hash"] != "testclient"
    assert row["sender_ip_hash"] == hash_value("testclient")


def test_no_file_upload_endpoint_exists(client):
    response = client.post("/api/upload", content=b"not a file endpoint")
    assert response.status_code == 404
    response = client.post("/api/files", content=b"not a file endpoint")
    assert response.status_code == 404


def test_admin_logs_require_auth(client):
    response = client.get("/admin/logs")
    assert response.status_code == 401
    credentials = base64.b64encode(b"admin:secret").decode("ascii")
    ok = client.get("/admin/logs", headers={"Authorization": f"Basic {credentials}"})
    assert ok.status_code == 200


def test_filename_sanitize_blocks_path_traversal():
    assert sanitize_filename("../../etc/passwd") == "passwd"
    assert sanitize_filename("..\\windows\\system.ini") == "system.ini"
    assert sanitize_filename("\x00") == "unnamed"


def test_websocket_binary_message_is_rejected_and_recorded(client):
    created = create_room(client)
    with pytest.raises(WebSocketDisconnect):
        with client.websocket_connect(f"/ws/{created['room_id']}/sender") as websocket:
            websocket.send_bytes(b"raw-file-chunk")
            websocket.receive_text()
    row = db.fetch_one("SELECT * FROM security_events WHERE event_type = 'websocket_binary_rejected'")
    assert row is not None
    assert row["room_id"] == created["room_id"]


def test_websocket_forbidden_message_type_is_rejected(client):
    created = create_room(client)
    with pytest.raises(WebSocketDisconnect):
        with client.websocket_connect(f"/ws/{created['room_id']}/sender") as websocket:
            websocket.send_json({"type": "file-chunk", "chunk": "forbidden"})
            websocket.receive_text()
    row = db.fetch_one("SELECT * FROM security_events WHERE event_type = 'websocket_forbidden_message'")
    assert row is not None
    assert row["detail"] == "forbidden message type: file-chunk"


def test_public_origins_split_strip_and_allow_multiple(monkeypatch):
    monkeypatch.setenv("PUBLIC_ORIGINS", " https://files.sexyminup.site, https://files.dcout.site/ ")
    monkeypatch.delenv("PUBLIC_ORIGIN", raising=False)
    settings = Settings.from_env()
    assert settings.public_origins == ["https://files.sexyminup.site", "https://files.dcout.site"]
    assert "https://files.sexyminup.site" in settings.allowed_origins
    assert "https://files.dcout.site" in settings.allowed_origins
    assert is_allowed_origin("https://files.dcout.site/", settings)


def test_public_origin_backward_compatibility(monkeypatch):
    monkeypatch.delenv("PUBLIC_ORIGINS", raising=False)
    monkeypatch.setenv("PUBLIC_ORIGIN", " https://legacy.example.com/ ")
    settings = Settings.from_env()
    assert settings.public_origins == ["https://legacy.example.com"]
    assert is_allowed_origin("https://legacy.example.com", settings)


def test_websocket_origin_check_uses_public_origins(client, monkeypatch):
    monkeypatch.setenv("PUBLIC_ORIGINS", "https://files.sexyminup.site, https://files.dcout.site")
    created = create_room(client)
    with client.websocket_connect(
        f"/ws/{created['room_id']}/sender",
        headers={"origin": "https://files.dcout.site"},
    ) as websocket:
        websocket.send_json({"type": "heartbeat"})
