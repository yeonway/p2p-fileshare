from __future__ import annotations

from datetime import timedelta

from app import db, rooms
from app.security import hash_value, utc_iso, utc_now


def create_room(client, file_name: str = "video.mp4", file_size: int = 1234):
    return client.post(
        "/api/rooms",
        json={
            "file_name": file_name,
            "file_size": file_size,
            "mime_type": "video/mp4",
            "client_type": "web",
        },
    )


def test_room_create_returns_six_digit_code(client):
    response = create_room(client)
    assert response.status_code == 200
    body = response.json()
    assert len(body["code"]) == 6
    assert body["code"].isdigit()
    row = db.fetch_one("SELECT * FROM transfers WHERE room_id = ?", (body["room_id"],))
    assert row["file_name"] == "video.mp4"
    assert row["status"] == "waiting"


def test_active_code_duplicate_is_avoided(client, monkeypatch):
    first = create_room(client).json()
    fallback = "999999" if first["code"] != "999999" else "000000"
    sequence = iter([first["code"], fallback])
    monkeypatch.setattr(rooms, "generate_room_code", lambda: next(sequence))
    second = create_room(client).json()
    assert second["code"] == fallback


def test_expired_room_join_is_blocked(client):
    created = create_room(client).json()
    db.execute(
        "UPDATE transfers SET expires_at = ? WHERE room_id = ?",
        (utc_iso(utc_now() - timedelta(minutes=1)), created["room_id"]),
    )
    response = client.post("/api/rooms/join", json={"code": created["code"], "client_type": "web"})
    assert response.status_code == 410
    row = db.fetch_one("SELECT status FROM transfers WHERE room_id = ?", (created["room_id"],))
    assert row["status"] == "expired"


def test_room_join_returns_metadata_only(client):
    created = create_room(client, "clip.mov", 987654).json()
    response = client.post("/api/rooms/join", json={"code": created["code"], "client_type": "web"})
    assert response.status_code == 200
    body = response.json()
    assert body["room_id"] == created["room_id"]
    assert body["file_name"] == "clip.mov"
    assert body["file_size"] == 987654
    assert "code" not in body


def test_room_status_change_helpers(client):
    created = create_room(client).json()
    rooms.mark_connected(created["room_id"])
    row = db.fetch_one("SELECT status, connected_at FROM transfers WHERE room_id = ?", (created["room_id"],))
    assert row["status"] == "connected"
    assert row["connected_at"]
    rooms.mark_transfer_started(created["room_id"])
    row = db.fetch_one("SELECT status, started_at FROM transfers WHERE room_id = ?", (created["room_id"],))
    assert row["status"] == "transferring"
    assert row["started_at"]
    rooms.mark_transfer_completed(created["room_id"], 1234, True, False)
    row = db.fetch_one("SELECT status, bytes_reported, direct_p2p, turn_used FROM transfers WHERE room_id = ?", (created["room_id"],))
    assert row["status"] == "completed"
    assert row["bytes_reported"] == 1234
    assert row["direct_p2p"] == 1
    assert row["turn_used"] == 0


def test_rate_limit_room_create(client, monkeypatch):
    monkeypatch.setenv("RATE_LIMIT_ROOM_CREATE_PER_MINUTE", "1")
    first = create_room(client)
    second = create_room(client)
    assert first.status_code == 200
    assert second.status_code == 429


def test_code_hash_is_stored_instead_of_plain_code(client):
    created = create_room(client).json()
    row = db.fetch_one("SELECT room_code_hash FROM transfers WHERE room_id = ?", (created["room_id"],))
    assert row["room_code_hash"] != created["code"]
    assert row["room_code_hash"] == hash_value(created["code"])


def test_default_config_chunk_size_is_64kb(client):
    response = client.get("/api/config")
    assert response.status_code == 200
    assert response.json()["chunkSizeBytes"] == 65536


def test_pwa_assets_are_served(client):
    manifest = client.get("/manifest.webmanifest")
    assert manifest.status_code == 200
    assert manifest.headers["content-type"].startswith("application/manifest+json")
    assert manifest.json()["display"] == "standalone"

    service_worker = client.get("/sw.js")
    assert service_worker.status_code == 200
    assert service_worker.headers["service-worker-allowed"] == "/"


def test_qr_svg_endpoint_generates_svg_without_get_query(client):
    response = client.post("/api/qr/svg", json={"value": "sendhoney://receive?code=123456"})
    assert response.status_code == 200
    assert response.headers["content-type"].startswith("image/svg+xml")
    assert response.headers["cache-control"] == "no-store"
    assert response.text.startswith("<svg")


def test_qr_svg_endpoint_rejects_empty_or_large_values(client):
    assert client.post("/api/qr/svg", json={"value": ""}).status_code == 422
    assert client.post("/api/qr/svg", json={"value": "x" * 2049}).status_code == 422


def test_android_asset_links_uses_configured_fingerprints(client, monkeypatch):
    fingerprint = "AA:BB:CC"
    monkeypatch.setenv("ANDROID_APP_SHA256_CERT_FINGERPRINTS", fingerprint)
    response = client.get("/.well-known/assetlinks.json")
    assert response.status_code == 200
    body = response.json()
    assert body[0]["target"]["package_name"] == "site.sexyminup.p2pfileshare"
    assert body[0]["target"]["sha256_cert_fingerprints"] == [fingerprint]
