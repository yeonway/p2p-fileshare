from __future__ import annotations

import io
import tarfile
from datetime import timedelta

from app import db
from app.security import hash_value, utc_iso, utc_now
from app.stored import cleanup_stored_transfers, stored_root


def create_stored(client, entries=None):
    response = client.post(
        "/api/stored/transfers",
        json={
            "client_type": "web",
            "entries": entries
            or [
                {
                    "relative_path": "../video.mp4",
                    "file_size": 10,
                    "mime_type": "video/mp4",
                }
            ],
        },
    )
    assert response.status_code == 200
    return response.json()


def upload_entry(client, transfer, entry, data: bytes):
    chunk_size = transfer["chunk_size_bytes"]
    token = transfer["upload_token"]
    for chunk_index, offset in enumerate(range(0, len(data), chunk_size)):
        chunk = data[offset : offset + chunk_size]
        response = client.put(
            f"/api/stored/transfers/{transfer['transfer_id']}/entries/{entry['entry_id']}/chunks/{chunk_index}",
            content=chunk,
            headers={"X-Upload-Token": token, "Content-Type": "application/octet-stream"},
        )
        assert response.status_code == 200


def complete_upload(client, transfer):
    response = client.post(
        f"/api/stored/transfers/{transfer['transfer_id']}/complete",
        headers={"X-Upload-Token": transfer["upload_token"]},
    )
    assert response.status_code == 200
    return response.json()


def test_stored_rejects_total_size_over_30gb(client):
    response = client.post(
        "/api/stored/transfers",
        json={
            "client_type": "web",
            "entries": [{"relative_path": "huge.bin", "file_size": 30 * 1024 * 1024 * 1024 + 1}],
        },
    )
    assert response.status_code == 413


def test_stored_accepts_many_entries_without_file_count_limit(client):
    entries = [
        {
            "relative_path": f"folder/file_{index:04d}.txt",
            "file_size": 0,
            "mime_type": "text/plain",
        }
        for index in range(1200)
    ]
    transfer = create_stored(client, entries)

    assert len(transfer["entries"]) == 1200
    assert transfer["total_size"] == 0


def test_stored_manifest_hashes_code_and_token_and_sanitizes_paths(client, monkeypatch):
    monkeypatch.setenv("STORED_CHUNK_SIZE_BYTES", "4")
    transfer = create_stored(
        client,
        [
            {"relative_path": "../../secret/video.mp4", "file_size": 10, "mime_type": "video/mp4"},
            {"relative_path": "/secret/video.mp4", "file_size": 0, "mime_type": "application/octet-stream"},
        ],
    )
    assert len(transfer["code"]) == 6
    assert transfer["total_size"] == 10
    assert transfer["is_bundle"] is True
    assert transfer["entries"][0]["relative_path"] == "secret/video.mp4"
    assert transfer["entries"][1]["relative_path"] == "secret/video_2.mp4"

    row = db.fetch_one("SELECT * FROM stored_transfers WHERE transfer_id = ?", (transfer["transfer_id"],))
    assert row["room_code_hash"] == hash_value(transfer["code"])
    assert row["room_code_hash"] != transfer["code"]
    assert row["upload_token_hash"] == hash_value(transfer["upload_token"])
    assert row["upload_token_hash"] != transfer["upload_token"]


def test_stored_chunk_upload_complete_join_and_single_download_deletes_file(client, monkeypatch):
    monkeypatch.setenv("STORED_CHUNK_SIZE_BYTES", "4")
    data = b"0123456789"
    transfer = create_stored(client)
    entry = transfer["entries"][0]
    upload_entry(client, transfer, entry, data)

    duplicate = client.put(
        f"/api/stored/transfers/{transfer['transfer_id']}/entries/{entry['entry_id']}/chunks/0",
        content=data[:4],
        headers={"X-Upload-Token": transfer["upload_token"], "Content-Type": "application/octet-stream"},
    )
    assert duplicate.status_code == 200
    completed = complete_upload(client, transfer)
    assert completed["status"] == "ready"

    joined = client.post("/api/stored/transfers/join", json={"code": transfer["code"], "client_type": "web"})
    assert joined.status_code == 200
    joined_body = joined.json()
    assert "download_token" in joined_body

    ranged = client.get(
        f"/api/stored/transfers/{transfer['transfer_id']}/download",
        headers={"X-Download-Token": joined_body["download_token"], "Range": "bytes=4-9"},
    )
    assert ranged.status_code == 206
    assert ranged.content == b"456789"
    assert db.fetch_one("SELECT 1 FROM stored_transfers WHERE transfer_id = ?", (transfer["transfer_id"],)) is None
    assert not (stored_root() / transfer["transfer_id"]).exists()


def test_stored_complete_rejects_missing_chunk_and_invalid_token(client, monkeypatch):
    monkeypatch.setenv("STORED_CHUNK_SIZE_BYTES", "4")
    transfer = create_stored(client)
    entry = transfer["entries"][0]
    response = client.put(
        f"/api/stored/transfers/{transfer['transfer_id']}/entries/{entry['entry_id']}/chunks/0",
        content=b"0123",
        headers={"X-Upload-Token": "wrong", "Content-Type": "application/octet-stream"},
    )
    assert response.status_code == 403

    upload_entry(client, transfer, entry, b"0123")
    missing = client.post(
        f"/api/stored/transfers/{transfer['transfer_id']}/complete",
        headers={"X-Upload-Token": transfer["upload_token"]},
    )
    assert missing.status_code == 409


def test_stored_rejects_invalid_download_token(client, monkeypatch):
    monkeypatch.setenv("STORED_CHUNK_SIZE_BYTES", "4")
    transfer = create_stored(client)
    upload_entry(client, transfer, transfer["entries"][0], b"0123456789")
    complete_upload(client, transfer)

    response = client.get(
        f"/api/stored/transfers/{transfer['transfer_id']}/download",
        headers={"X-Download-Token": "wrong"},
    )

    assert response.status_code == 403


def test_stored_single_zero_byte_file_downloads_and_deletes(client):
    transfer = create_stored(client, [{"relative_path": "empty.txt", "file_size": 0, "mime_type": "text/plain"}])
    complete_upload(client, transfer)
    joined = client.post("/api/stored/transfers/join", json={"code": transfer["code"], "client_type": "web"}).json()

    response = client.get(
        f"/api/stored/transfers/{transfer['transfer_id']}/download",
        headers={"X-Download-Token": joined["download_token"]},
    )

    assert response.status_code == 200
    assert response.content == b""
    assert db.fetch_one("SELECT 1 FROM stored_transfers WHERE transfer_id = ?", (transfer["transfer_id"],)) is None


def test_stored_bundle_downloads_uncompressed_tar(client, monkeypatch):
    monkeypatch.setenv("STORED_CHUNK_SIZE_BYTES", "4")
    transfer = create_stored(
        client,
        [
            {"relative_path": "folder/a.txt", "file_size": 3, "mime_type": "text/plain"},
            {"relative_path": "folder/b.txt", "file_size": 5, "mime_type": "text/plain"},
        ],
    )
    upload_entry(client, transfer, transfer["entries"][0], b"abc")
    upload_entry(client, transfer, transfer["entries"][1], b"12345")
    complete_upload(client, transfer)
    joined = client.post("/api/stored/transfers/join", json={"code": transfer["code"], "client_type": "web"}).json()
    response = client.get(
        f"/api/stored/transfers/{transfer['transfer_id']}/download",
        headers={"X-Download-Token": joined["download_token"]},
    )
    assert response.status_code == 200
    with tarfile.open(fileobj=io.BytesIO(response.content), mode="r:") as archive:
        assert archive.extractfile("folder/a.txt").read() == b"abc"
        assert archive.extractfile("folder/b.txt").read() == b"12345"


def test_stored_cleanup_removes_idle_and_ready_expired(client, monkeypatch):
    monkeypatch.setenv("STORED_CHUNK_SIZE_BYTES", "4")
    transfer = create_stored(client)
    old = utc_iso(utc_now() - timedelta(minutes=11))
    db.execute(
        "UPDATE stored_transfers SET updated_at = ?, expires_at = ? WHERE transfer_id = ?",
        (old, utc_iso(utc_now() + timedelta(minutes=10)), transfer["transfer_id"]),
    )
    assert cleanup_stored_transfers() == 1
    assert db.fetch_one("SELECT 1 FROM stored_transfers WHERE transfer_id = ?", (transfer["transfer_id"],)) is None


def test_stored_cleanup_removes_ready_expired(client):
    transfer = create_stored(client, [{"relative_path": "empty.txt", "file_size": 0}])
    complete_upload(client, transfer)
    past = utc_iso(utc_now() - timedelta(minutes=31))
    db.execute(
        "UPDATE stored_transfers SET expires_at = ? WHERE transfer_id = ?",
        (past, transfer["transfer_id"]),
    )

    assert cleanup_stored_transfers() == 1
    assert db.fetch_one("SELECT 1 FROM stored_transfers WHERE transfer_id = ?", (transfer["transfer_id"],)) is None
