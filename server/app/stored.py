from __future__ import annotations

import json
import math
import secrets
import shutil
import uuid
from datetime import timedelta
from pathlib import Path, PurePosixPath
from typing import Any, Iterator

from fastapi import APIRouter, Header, HTTPException, Request, Response, status
from fastapi.responses import StreamingResponse
from pydantic import BaseModel, Field

from . import db
from .config import SERVER_ROOT, Settings, get_settings
from .models import CLIENT_TYPES
from .rate_limit import check_rate_limit
from .rooms import generate_room_code, record_security_event
from .security import client_ip_from_request, hash_value, sanitize_filename, utc_iso, utc_now, parse_utc


router = APIRouter()

STORED_STATUSES = {"uploading", "ready", "downloading", "completed", "failed", "expired"}
TAR_BLOCK_SIZE = 512
END_OF_ARCHIVE_BYTES = TAR_BLOCK_SIZE * 2


class StoredEntryCreate(BaseModel):
    relative_path: str = Field(..., min_length=1, max_length=1024)
    file_size: int = Field(..., ge=0)
    mime_type: str = "application/octet-stream"


class StoredTransferCreateRequest(BaseModel):
    entries: list[StoredEntryCreate] = Field(..., min_length=1)
    client_type: str


class StoredEntryResponse(BaseModel):
    entry_id: str
    relative_path: str
    file_name: str
    file_size: int
    mime_type: str
    chunk_count: int
    uploaded_chunks: list[int] = Field(default_factory=list)
    bytes_uploaded: int = 0
    completed: bool = False


class StoredTransferCreateResponse(BaseModel):
    transfer_id: str
    code: str
    upload_token: str
    expires_at: str
    chunk_size_bytes: int
    max_total_size_bytes: int
    total_size: int
    is_bundle: bool
    archive_name: str
    entries: list[StoredEntryResponse]


class StoredTransferJoinRequest(BaseModel):
    code: str
    client_type: str


class StoredTransferJoinResponse(BaseModel):
    transfer_id: str
    download_token: str
    expires_at: str
    total_size: int
    download_size: int
    is_bundle: bool
    archive_name: str
    entries: list[StoredEntryResponse]


class StoredTransferStatusResponse(BaseModel):
    transfer_id: str
    status: str
    expires_at: str
    chunk_size_bytes: int
    total_size: int
    download_size: int
    is_bundle: bool
    archive_name: str
    entries: list[StoredEntryResponse]


def stored_root(settings: Settings | None = None) -> Path:
    active_settings = settings or get_settings()
    path = Path(active_settings.stored_files_dir)
    if not path.is_absolute():
        path = SERVER_ROOT / path
    return path


def transfer_dir(transfer_id: str, settings: Settings | None = None) -> Path:
    return stored_root(settings) / transfer_id


def entry_path(transfer_id: str, entry_id: str, settings: Settings | None = None) -> Path:
    return transfer_dir(transfer_id, settings) / "entries" / f"{entry_id}.blob"


def _validate_client_type(client_type: str) -> None:
    if client_type not in CLIENT_TYPES:
        raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail="Invalid client_type")


def _active_code_exists(code_hash: str) -> bool:
    now = utc_iso()
    row = db.fetch_one(
        """
        SELECT 1 FROM stored_transfers
        WHERE room_code_hash = ?
          AND status IN ('uploading', 'ready', 'downloading')
          AND expires_at > ?
        LIMIT 1
        """,
        (code_hash, now),
    )
    return row is not None


def _new_unique_code() -> tuple[str, str]:
    for _ in range(25):
        code = generate_room_code()
        code_hash = hash_value(code)
        if not _active_code_exists(code_hash):
            return code, code_hash
    raise HTTPException(status_code=status.HTTP_503_SERVICE_UNAVAILABLE, detail="Could not allocate room code")


def _new_token() -> str:
    return secrets.token_urlsafe(32)


def _chunk_count(file_size: int, chunk_size: int) -> int:
    if file_size == 0:
        return 0
    return math.ceil(file_size / chunk_size)


def _chunk_length(file_size: int, chunk_size: int, chunk_index: int) -> int:
    count = _chunk_count(file_size, chunk_size)
    if chunk_index < 0 or chunk_index >= count:
        raise HTTPException(status_code=status.HTTP_416_REQUESTED_RANGE_NOT_SATISFIABLE, detail="Invalid chunk index")
    if chunk_index == count - 1:
        return file_size - (chunk_index * chunk_size)
    return chunk_size


def _uploaded_chunks(value: str | None) -> set[int]:
    if not value:
        return set()
    data = json.loads(value)
    if not isinstance(data, list):
        return set()
    return {int(item) for item in data}


def _encode_chunks(chunks: set[int]) -> str:
    return json.dumps(sorted(chunks), separators=(",", ":"))


def _bytes_uploaded(file_size: int, chunk_size: int, chunks: set[int]) -> int:
    return sum(_chunk_length(file_size, chunk_size, chunk) for chunk in chunks)


def _status_row(transfer_id: str) -> Any | None:
    return db.fetch_one("SELECT * FROM stored_transfers WHERE transfer_id = ?", (transfer_id,))


def _entry_row(transfer_id: str, entry_id: str) -> Any | None:
    return db.fetch_one(
        "SELECT * FROM stored_entries WHERE transfer_id = ? AND entry_id = ?",
        (transfer_id, entry_id),
    )


def _entry_rows(transfer_id: str) -> list[Any]:
    return db.fetch_all(
        "SELECT * FROM stored_entries WHERE transfer_id = ? ORDER BY id ASC",
        (transfer_id,),
    )


def _entry_response(row: Any) -> StoredEntryResponse:
    return StoredEntryResponse(
        entry_id=row["entry_id"],
        relative_path=row["relative_path"],
        file_name=row["file_name"],
        file_size=row["file_size"],
        mime_type=row["mime_type"] or "application/octet-stream",
        chunk_count=row["chunk_count"],
        uploaded_chunks=sorted(_uploaded_chunks(row["uploaded_chunks"])),
        bytes_uploaded=row["bytes_uploaded"],
        completed=bool(row["completed"]),
    )


def _download_size(row: Any, entries: list[Any]) -> int:
    if not row["is_bundle"]:
        return row["total_size"]
    return sum(_tar_entry_size(entry["relative_path"], entry["file_size"]) for entry in entries) + END_OF_ARCHIVE_BYTES


def _status_response(row: Any, entries: list[Any]) -> StoredTransferStatusResponse:
    return StoredTransferStatusResponse(
        transfer_id=row["transfer_id"],
        status=row["status"],
        expires_at=row["expires_at"],
        chunk_size_bytes=row["chunk_size"],
        total_size=row["total_size"],
        download_size=_download_size(row, entries),
        is_bundle=bool(row["is_bundle"]),
        archive_name=row["archive_name"],
        entries=[_entry_response(entry) for entry in entries],
    )


def _sanitize_relative_path(raw_value: str, fallback: str) -> str:
    normalized = raw_value.replace("\\", "/").strip()
    parts: list[str] = []
    for raw_part in PurePosixPath(normalized).parts:
        if raw_part in {"", ".", "..", "/"}:
            continue
        safe = sanitize_filename(raw_part)
        if safe and safe not in {".", ".."}:
            parts.append(safe)
    if not parts:
        parts = [fallback]
    return "/".join(parts)[:1024]


def _dedupe_path(path: str, seen: set[str]) -> str:
    if path not in seen:
        seen.add(path)
        return path
    posix = PurePosixPath(path)
    stem = posix.stem or "file"
    suffix = posix.suffix
    parent = "" if str(posix.parent) == "." else f"{posix.parent}/"
    counter = 2
    while True:
        candidate = f"{parent}{stem}_{counter}{suffix}"
        if candidate not in seen:
            seen.add(candidate)
            return candidate
        counter += 1


def _archive_name(entries: list[dict[str, object]]) -> str:
    if len(entries) == 1:
        return str(entries[0]["file_name"])
    return f"send_honey_where_{len(entries)}_files.tar"


def _authorize_upload(row: Any, token: str | None) -> None:
    if not token or hash_value(token) != row["upload_token_hash"]:
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Invalid upload token")


def _authorize_download(row: Any, token: str | None) -> None:
    if not token or not row["download_token_hash"] or hash_value(token) != row["download_token_hash"]:
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Invalid download token")


def _touch_transfer(transfer_id: str) -> None:
    db.execute("UPDATE stored_transfers SET updated_at = ? WHERE transfer_id = ?", (utc_iso(), transfer_id))


def delete_stored_transfer(transfer_id: str) -> None:
    db.execute("DELETE FROM stored_transfers WHERE transfer_id = ?", (transfer_id,))
    shutil.rmtree(transfer_dir(transfer_id), ignore_errors=True)


def cleanup_stored_transfers() -> int:
    settings = get_settings()
    now = utc_now()
    removed = 0
    rows = db.fetch_all(
        """
        SELECT * FROM stored_transfers
        WHERE status IN ('uploading', 'ready', 'downloading', 'failed', 'expired')
        """
    )
    for row in rows:
        updated_at = parse_utc(row["updated_at"])
        expires_at = parse_utc(row["expires_at"])
        idle_expired = row["status"] in {"uploading", "downloading"} and (
            now - updated_at
        ).total_seconds() > settings.stored_idle_timeout_seconds
        ttl_expired = row["status"] in {"ready", "failed", "expired"} and expires_at <= now
        if idle_expired or ttl_expired:
            delete_stored_transfer(row["transfer_id"])
            removed += 1
    return removed


@router.post("/api/stored/transfers", response_model=StoredTransferCreateResponse)
def create_stored_transfer(payload: StoredTransferCreateRequest, request: Request) -> StoredTransferCreateResponse:
    settings = get_settings()
    _validate_client_type(payload.client_type)
    ip_hash = hash_value(client_ip_from_request(request))
    if not check_rate_limit("stored-create", ip_hash, settings.rate_limit_room_create_per_minute):
        record_security_event("rate_limit_stored_create", ip_hash, request.headers.get("user-agent"), detail="limit exceeded")
        raise HTTPException(status_code=status.HTTP_429_TOO_MANY_REQUESTS, detail="Too many stored transfer requests")

    total_size = sum(entry.file_size for entry in payload.entries)
    if total_size > settings.stored_max_total_size_bytes:
        raise HTTPException(status_code=status.HTTP_413_REQUEST_ENTITY_TOO_LARGE, detail="Transfer exceeds 30GB limit")

    seen_paths: set[str] = set()
    normalized_entries: list[dict[str, object]] = []
    for index, entry in enumerate(payload.entries):
        fallback = f"file_{index + 1}"
        relative_path = _dedupe_path(_sanitize_relative_path(entry.relative_path, fallback), seen_paths)
        file_name = PurePosixPath(relative_path).name or fallback
        normalized_entries.append(
            {
                "entry_id": f"{index + 1:06d}",
                "relative_path": relative_path,
                "file_name": file_name,
                "file_size": entry.file_size,
                "mime_type": (entry.mime_type or "application/octet-stream")[:255],
                "chunk_count": _chunk_count(entry.file_size, settings.stored_chunk_size_bytes),
                "uploaded_chunks": "[]",
                "bytes_uploaded": 0,
                "completed": entry.file_size == 0,
            }
        )

    code, code_hash = _new_unique_code()
    upload_token = _new_token()
    transfer_id = str(uuid.uuid4())
    now = utc_now()
    expires_at = now + timedelta(seconds=settings.stored_idle_timeout_seconds)
    archive_name = _archive_name(normalized_entries)
    is_bundle = len(normalized_entries) > 1 or any("/" in str(entry["relative_path"]) for entry in normalized_entries)
    (transfer_dir(transfer_id, settings) / "entries").mkdir(parents=True, exist_ok=True)
    for entry in normalized_entries:
        if entry["file_size"] == 0:
            entry_path(transfer_id, str(entry["entry_id"]), settings).touch()
    db.execute(
        """
        INSERT INTO stored_transfers (
            transfer_id, room_code_hash, upload_token_hash, sender_ip_hash,
            sender_client_type, archive_name, total_size, status, chunk_size,
            is_bundle, created_at, updated_at, expires_at, user_agent_sender
        )
        VALUES (?, ?, ?, ?, ?, ?, ?, 'uploading', ?, ?, ?, ?, ?, ?)
        """,
        (
            transfer_id,
            code_hash,
            hash_value(upload_token),
            ip_hash,
            payload.client_type,
            archive_name,
            total_size,
            settings.stored_chunk_size_bytes,
            is_bundle,
            utc_iso(now),
            utc_iso(now),
            utc_iso(expires_at),
            request.headers.get("user-agent", "")[:500],
        ),
    )
    db.execute_many(
        """
        INSERT INTO stored_entries (
            transfer_id, entry_id, relative_path, file_name, file_size, mime_type,
            chunk_count, uploaded_chunks, bytes_uploaded, completed, created_at, updated_at
        )
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        [
            (
                transfer_id,
                entry["entry_id"],
                entry["relative_path"],
                entry["file_name"],
                entry["file_size"],
                entry["mime_type"],
                entry["chunk_count"],
                entry["uploaded_chunks"],
                entry["bytes_uploaded"],
                entry["completed"],
                utc_iso(now),
                utc_iso(now),
            )
            for entry in normalized_entries
        ],
    )
    entries = _entry_rows(transfer_id)
    return StoredTransferCreateResponse(
        transfer_id=transfer_id,
        code=code,
        upload_token=upload_token,
        expires_at=utc_iso(expires_at),
        chunk_size_bytes=settings.stored_chunk_size_bytes,
        max_total_size_bytes=settings.stored_max_total_size_bytes,
        total_size=total_size,
        is_bundle=is_bundle,
        archive_name=archive_name,
        entries=[_entry_response(entry) for entry in entries],
    )


@router.put("/api/stored/transfers/{transfer_id}/entries/{entry_id}/chunks/{chunk_index}")
async def upload_stored_chunk(
    transfer_id: str,
    entry_id: str,
    chunk_index: int,
    request: Request,
    x_upload_token: str | None = Header(default=None, alias="X-Upload-Token"),
) -> dict[str, object]:
    row = _status_row(transfer_id)
    if row is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Stored transfer not found")
    if row["status"] != "uploading":
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="Stored transfer is not accepting uploads")
    _authorize_upload(row, x_upload_token)
    entry = _entry_row(transfer_id, entry_id)
    if entry is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Stored entry not found")

    expected_length = _chunk_length(entry["file_size"], row["chunk_size"], chunk_index)
    body = await request.body()
    if len(body) != expected_length:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Chunk size does not match manifest")

    path = entry_path(transfer_id, entry_id)
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("r+b" if path.exists() else "w+b") as file:
        file.seek(chunk_index * row["chunk_size"])
        file.write(body)

    chunks = _uploaded_chunks(entry["uploaded_chunks"])
    chunks.add(chunk_index)
    bytes_uploaded = _bytes_uploaded(entry["file_size"], row["chunk_size"], chunks)
    completed = len(chunks) == entry["chunk_count"]
    now = utc_iso()
    db.execute(
        """
        UPDATE stored_entries
        SET uploaded_chunks = ?, bytes_uploaded = ?, completed = ?, updated_at = ?
        WHERE transfer_id = ? AND entry_id = ?
        """,
        (_encode_chunks(chunks), bytes_uploaded, completed, now, transfer_id, entry_id),
    )
    db.execute(
        "UPDATE stored_transfers SET updated_at = ?, expires_at = ? WHERE transfer_id = ?",
        (
            now,
            utc_iso(utc_now() + timedelta(seconds=get_settings().stored_idle_timeout_seconds)),
            transfer_id,
        ),
    )
    return {
        "entry_id": entry_id,
        "chunk_index": chunk_index,
        "bytes_uploaded": bytes_uploaded,
        "completed": completed,
    }


@router.post("/api/stored/transfers/{transfer_id}/complete", response_model=StoredTransferStatusResponse)
def complete_stored_upload(
    transfer_id: str,
    x_upload_token: str | None = Header(default=None, alias="X-Upload-Token"),
) -> StoredTransferStatusResponse:
    row = _status_row(transfer_id)
    if row is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Stored transfer not found")
    _authorize_upload(row, x_upload_token)
    entries = _entry_rows(transfer_id)
    missing: list[dict[str, object]] = []
    for entry in entries:
        chunks = _uploaded_chunks(entry["uploaded_chunks"])
        expected = set(range(entry["chunk_count"]))
        if chunks != expected:
            missing.append({"entry_id": entry["entry_id"], "missing_chunks": sorted(expected - chunks)})
            continue
        path = entry_path(transfer_id, entry["entry_id"])
        actual_size = path.stat().st_size if path.exists() else 0
        if actual_size != entry["file_size"]:
            missing.append({"entry_id": entry["entry_id"], "reason": "file size mismatch"})
    if missing:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail={"missing": missing})
    now = utc_now()
    expires_at = now + timedelta(minutes=get_settings().stored_ready_ttl_minutes)
    db.execute(
        """
        UPDATE stored_transfers
        SET status = 'ready', updated_at = ?, expires_at = ?, upload_completed_at = ?
        WHERE transfer_id = ?
        """,
        (utc_iso(now), utc_iso(expires_at), utc_iso(now), transfer_id),
    )
    updated = _status_row(transfer_id)
    return _status_response(updated, _entry_rows(transfer_id))


@router.post("/api/stored/transfers/join", response_model=StoredTransferJoinResponse)
def join_stored_transfer(payload: StoredTransferJoinRequest, request: Request) -> StoredTransferJoinResponse:
    settings = get_settings()
    _validate_client_type(payload.client_type)
    if not payload.code.isdigit() or len(payload.code) != 6:
        raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail="Code must be 6 digits")
    ip_hash = hash_value(client_ip_from_request(request))
    if not check_rate_limit("stored-join", ip_hash, settings.rate_limit_join_per_minute):
        record_security_event("rate_limit_stored_join", ip_hash, request.headers.get("user-agent"), detail="limit exceeded")
        raise HTTPException(status_code=status.HTTP_429_TOO_MANY_REQUESTS, detail="Too many join requests")

    row = db.fetch_one(
        """
        SELECT * FROM stored_transfers
        WHERE room_code_hash = ?
          AND status IN ('ready', 'downloading')
        ORDER BY created_at DESC
        LIMIT 1
        """,
        (hash_value(payload.code),),
    )
    if row is None:
        record_security_event("stored_join_not_found", ip_hash, request.headers.get("user-agent"), detail="invalid or unavailable code")
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Stored transfer not found")
    if parse_utc(row["expires_at"]) <= utc_now():
        delete_stored_transfer(row["transfer_id"])
        raise HTTPException(status_code=status.HTTP_410_GONE, detail="Stored transfer expired")

    download_token = _new_token()
    now = utc_iso()
    db.execute(
        """
        UPDATE stored_transfers
        SET download_token_hash = ?, receiver_ip_hash = ?, receiver_client_type = ?,
            user_agent_receiver = ?, updated_at = ?
        WHERE transfer_id = ?
        """,
        (
            hash_value(download_token),
            ip_hash,
            payload.client_type,
            request.headers.get("user-agent", "")[:500],
            now,
            row["transfer_id"],
        ),
    )
    updated = _status_row(row["transfer_id"])
    entries = _entry_rows(row["transfer_id"])
    return StoredTransferJoinResponse(
        transfer_id=row["transfer_id"],
        download_token=download_token,
        expires_at=updated["expires_at"],
        total_size=updated["total_size"],
        download_size=_download_size(updated, entries),
        is_bundle=bool(updated["is_bundle"]),
        archive_name=updated["archive_name"],
        entries=[_entry_response(entry) for entry in entries],
    )


@router.get("/api/stored/transfers/{transfer_id}/status", response_model=StoredTransferStatusResponse)
def stored_transfer_status(
    transfer_id: str,
    x_upload_token: str | None = Header(default=None, alias="X-Upload-Token"),
    x_download_token: str | None = Header(default=None, alias="X-Download-Token"),
) -> StoredTransferStatusResponse:
    row = _status_row(transfer_id)
    if row is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Stored transfer not found")
    if x_upload_token:
        _authorize_upload(row, x_upload_token)
    elif x_download_token:
        _authorize_download(row, x_download_token)
    else:
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Missing transfer token")
    _touch_transfer(transfer_id)
    return _status_response(_status_row(transfer_id), _entry_rows(transfer_id))


@router.get("/api/stored/transfers/{transfer_id}/download")
def download_stored_transfer(
    transfer_id: str,
    request: Request,
    x_download_token: str | None = Header(default=None, alias="X-Download-Token"),
) -> Response:
    row = _status_row(transfer_id)
    if row is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Stored transfer not found")
    _authorize_download(row, x_download_token)
    if row["status"] not in {"ready", "downloading"}:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="Stored transfer is not downloadable")
    entries = _entry_rows(transfer_id)
    db.execute(
        """
        UPDATE stored_transfers
        SET status = 'downloading', updated_at = ?, download_started_at = COALESCE(download_started_at, ?),
            expires_at = ?
        WHERE transfer_id = ?
        """,
        (
            utc_iso(),
            utc_iso(),
            utc_iso(utc_now() + timedelta(seconds=get_settings().stored_idle_timeout_seconds)),
            transfer_id,
        ),
    )
    if bool(row["is_bundle"]):
        return _tar_download_response(row, entries)
    return _single_file_download_response(row, entries[0], request.headers.get("range"))


def _single_file_download_response(row: Any, entry: Any, range_header: str | None) -> StreamingResponse:
    path = entry_path(row["transfer_id"], entry["entry_id"])
    file_size = entry["file_size"]
    start = 0
    end = file_size - 1
    status_code = status.HTTP_200_OK
    headers = {
        "Accept-Ranges": "bytes",
        "Content-Disposition": f'attachment; filename="{_ascii_filename(row["archive_name"])}"; filename*=UTF-8\'\'{_url_quote(row["archive_name"])}',
    }
    if range_header:
        start, end = _parse_range(range_header, file_size)
        status_code = status.HTTP_206_PARTIAL_CONTENT
        headers["Content-Range"] = f"bytes {start}-{end}/{file_size}"
    length = max(0, end - start + 1)
    headers["Content-Length"] = str(length)
    headers["Content-Type"] = entry["mime_type"] or "application/octet-stream"

    def body() -> Iterator[bytes]:
        sent = 0
        completed = False
        try:
            with path.open("rb") as file:
                file.seek(start)
                remaining = length
                while remaining > 0:
                    chunk = file.read(min(1024 * 1024, remaining))
                    if not chunk:
                        break
                    remaining -= len(chunk)
                    sent += len(chunk)
                    yield chunk
            completed = sent == length and end == file_size - 1
        finally:
            if completed:
                _mark_download_completed(row["transfer_id"])
            else:
                _touch_transfer(row["transfer_id"])

    return StreamingResponse(body(), status_code=status_code, headers=headers, media_type=headers["Content-Type"])


def _tar_download_response(row: Any, entries: list[Any]) -> StreamingResponse:
    archive_name = row["archive_name"] or "send_honey_where_files.tar"
    total_size = _download_size(row, entries)
    headers = {
        "Content-Type": "application/x-tar",
        "Content-Length": str(total_size),
        "Content-Disposition": f'attachment; filename="{_ascii_filename(archive_name)}"; filename*=UTF-8\'\'{_url_quote(archive_name)}',
    }

    def body() -> Iterator[bytes]:
        sent = 0
        completed = False
        try:
            for entry in entries:
                for chunk in _tar_entry_chunks(row["transfer_id"], entry):
                    sent += len(chunk)
                    yield chunk
            trailer = bytes(END_OF_ARCHIVE_BYTES)
            sent += len(trailer)
            yield trailer
            completed = sent == total_size
        finally:
            if completed:
                _mark_download_completed(row["transfer_id"])
            else:
                _touch_transfer(row["transfer_id"])

    return StreamingResponse(body(), headers=headers, media_type="application/x-tar")


def _mark_download_completed(transfer_id: str) -> None:
    now = utc_iso()
    db.execute(
        """
        UPDATE stored_transfers
        SET status = 'completed', updated_at = ?, download_completed_at = ?
        WHERE transfer_id = ?
        """,
        (now, now, transfer_id),
    )
    delete_stored_transfer(transfer_id)


def _parse_range(header: str, file_size: int) -> tuple[int, int]:
    if not header.startswith("bytes="):
        raise HTTPException(status_code=status.HTTP_416_REQUESTED_RANGE_NOT_SATISFIABLE, detail="Invalid range")
    value = header.removeprefix("bytes=").strip()
    if "," in value or "-" not in value:
        raise HTTPException(status_code=status.HTTP_416_REQUESTED_RANGE_NOT_SATISFIABLE, detail="Invalid range")
    start_text, end_text = value.split("-", 1)
    if start_text == "":
        suffix = int(end_text)
        if suffix <= 0:
            raise HTTPException(status_code=status.HTTP_416_REQUESTED_RANGE_NOT_SATISFIABLE, detail="Invalid range")
        return max(0, file_size - suffix), file_size - 1
    start = int(start_text)
    end = int(end_text) if end_text else file_size - 1
    if start < 0 or end < start or start >= file_size:
        raise HTTPException(status_code=status.HTTP_416_REQUESTED_RANGE_NOT_SATISFIABLE, detail="Invalid range")
    return start, min(end, file_size - 1)


def _tar_entry_size(path: str, file_size: int) -> int:
    pax = _pax_records(path, file_size)
    return TAR_BLOCK_SIZE + _round_tar_block(len(pax)) + TAR_BLOCK_SIZE + _round_tar_block(file_size)


def _tar_entry_chunks(transfer_id: str, entry: Any) -> Iterator[bytes]:
    path = entry["relative_path"]
    size = entry["file_size"]
    pax = _pax_records(path, size)
    yield _tar_header(f"PaxHeaders.{entry['entry_id']}", len(pax), "x")
    yield pax
    yield bytes(_round_tar_block(len(pax)) - len(pax))
    yield _tar_header(path, size, "0")
    with entry_path(transfer_id, entry["entry_id"]).open("rb") as file:
        while True:
            chunk = file.read(1024 * 1024)
            if not chunk:
                break
            yield chunk
    padding = _round_tar_block(size) - size
    if padding:
        yield bytes(padding)


def _tar_header(name: str, size: int, typeflag: str) -> bytes:
    header = bytearray(TAR_BLOCK_SIZE)
    _write_ascii(header, 0, 100, _safe_tar_name(name))
    _write_octal(header, 100, 8, 0o644)
    _write_octal(header, 108, 8, 0)
    _write_octal(header, 116, 8, 0)
    _write_octal(header, 124, 12, min(size, 0x1FFFFFFFF))
    _write_octal(header, 136, 12, int(utc_now().timestamp()))
    for index in range(148, 156):
        header[index] = 32
    header[156] = ord(typeflag)
    _write_ascii(header, 257, 6, "ustar")
    _write_ascii(header, 263, 2, "00")
    checksum = sum(header)
    _write_ascii(header, 148, 6, oct(checksum)[2:].rjust(6, "0"))
    header[154] = 0
    header[155] = 32
    return bytes(header)


def _pax_records(path: str, size: int) -> bytes:
    return (_pax_record("path", path) + _pax_record("size", str(size))).encode("utf-8")


def _pax_record(key: str, value: str) -> str:
    length_digits = 1
    while True:
        length = length_digits + 1 + len(key.encode("utf-8")) + 1 + len(value.encode("utf-8")) + 1
        next_digits = len(str(length))
        if next_digits == length_digits:
            return f"{length} {key}={value}\n"
        length_digits = next_digits


def _safe_tar_name(value: str) -> str:
    safe = "".join(char if 32 <= ord(char) <= 126 and char != "/" else "_" for char in value)
    data = safe.encode("ascii", errors="ignore")
    if len(data) <= 100:
        return safe
    return data[:100].decode("ascii", errors="ignore").rstrip("_") or "file"


def _round_tar_block(value: int) -> int:
    remainder = value % TAR_BLOCK_SIZE
    return value if remainder == 0 else value + (TAR_BLOCK_SIZE - remainder)


def _write_ascii(target: bytearray, offset: int, length: int, value: str) -> None:
    data = value.encode("ascii", errors="ignore")[:length]
    target[offset : offset + len(data)] = data


def _write_octal(target: bytearray, offset: int, length: int, value: int) -> None:
    text = oct(value)[2:][-length + 1 :].rjust(length - 1, "0")
    _write_ascii(target, offset, length - 1, text)
    target[offset + length - 1] = 0


def _url_quote(value: str) -> str:
    from urllib.parse import quote

    return quote(value, safe="")


def _ascii_filename(value: str) -> str:
    safe = "".join(char if 32 <= ord(char) <= 126 and char not in {'"', "\\"} else "_" for char in value)
    return safe or "download"
