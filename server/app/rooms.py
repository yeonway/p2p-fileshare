from __future__ import annotations

import random
import uuid
from datetime import timedelta
from typing import Any

from fastapi import APIRouter, HTTPException, Request, status

from . import db
from .config import get_settings
from .models import CLIENT_TYPES, ConfigResponse, RoomCreateRequest, RoomCreateResponse, RoomJoinRequest, RoomJoinResponse
from .rate_limit import check_rate_limit
from .security import client_ip_from_request, hash_value, sanitize_filename, utc_iso, utc_now, parse_utc


router = APIRouter()


def generate_room_code() -> str:
    return f"{random.SystemRandom().randint(0, 999999):06d}"


def _validate_client_type(client_type: str) -> None:
    if client_type not in CLIENT_TYPES:
        raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail="Invalid client_type")


def _validate_file_size(file_size: int) -> None:
    if not isinstance(file_size, int) or file_size < 0:
        raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail="Invalid file_size")


def _active_code_exists(code_hash: str) -> bool:
    now = utc_iso()
    row = db.fetch_one(
        """
        SELECT 1 FROM transfers
        WHERE room_code_hash = ?
          AND status IN ('waiting', 'connected', 'transferring')
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


def record_security_event(
    event_type: str,
    ip_hash: str | None = None,
    user_agent: str | None = None,
    room_id: str | None = None,
    detail: str | None = None,
) -> None:
    db.execute(
        """
        INSERT INTO security_events (event_type, ip_hash, user_agent, room_id, created_at, detail)
        VALUES (?, ?, ?, ?, ?, ?)
        """,
        (event_type, ip_hash, user_agent, room_id, utc_iso(), detail),
    )


@router.get("/api/config", response_model=ConfigResponse)
def get_client_config() -> ConfigResponse:
    settings = get_settings()
    return ConfigResponse(
        iceServers=settings.ice_servers,
        roomTtlMinutes=settings.room_ttl_minutes,
        activeTransferIdleTimeoutSeconds=settings.active_transfer_idle_timeout_seconds,
        chunkSizeBytes=settings.chunk_size_bytes,
    )


@router.post("/api/rooms", response_model=RoomCreateResponse)
def create_room(payload: RoomCreateRequest, request: Request) -> RoomCreateResponse:
    settings = get_settings()
    _validate_client_type(payload.client_type)
    _validate_file_size(payload.file_size)
    ip = client_ip_from_request(request)
    ip_hash = hash_value(ip)
    if not check_rate_limit("room-create", ip_hash, settings.rate_limit_room_create_per_minute):
        record_security_event("rate_limit_room_create", ip_hash, request.headers.get("user-agent"), detail="limit exceeded")
        raise HTTPException(status_code=status.HTTP_429_TOO_MANY_REQUESTS, detail="Too many room create requests")

    code, code_hash = _new_unique_code()
    room_id = str(uuid.uuid4())
    now = utc_now()
    expires_at = now + timedelta(minutes=settings.room_ttl_minutes)
    db.execute(
        """
        INSERT INTO transfers (
            room_id, room_code_hash, sender_ip_hash, sender_client_type, file_name,
            file_size, mime_type, status, created_at, expires_at, user_agent_sender,
            direct_p2p, turn_used, bytes_reported
        )
        VALUES (?, ?, ?, ?, ?, ?, ?, 'waiting', ?, ?, ?, NULL, NULL, 0)
        """,
        (
            room_id,
            code_hash,
            ip_hash,
            payload.client_type,
            sanitize_filename(payload.file_name),
            payload.file_size,
            (payload.mime_type or "application/octet-stream")[:255],
            utc_iso(now),
            utc_iso(expires_at),
            request.headers.get("user-agent", "")[:500],
        ),
    )
    return RoomCreateResponse(room_id=room_id, code=code, expires_at=utc_iso(expires_at))


@router.post("/api/rooms/join", response_model=RoomJoinResponse)
def join_room(payload: RoomJoinRequest, request: Request) -> RoomJoinResponse:
    settings = get_settings()
    _validate_client_type(payload.client_type)
    if not payload.code.isdigit() or len(payload.code) != 6:
        raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail="Code must be 6 digits")
    ip = client_ip_from_request(request)
    ip_hash = hash_value(ip)
    if not check_rate_limit("room-join", ip_hash, settings.rate_limit_join_per_minute):
        record_security_event("rate_limit_room_join", ip_hash, request.headers.get("user-agent"), detail="limit exceeded")
        raise HTTPException(status_code=status.HTTP_429_TOO_MANY_REQUESTS, detail="Too many join requests")

    code_hash = hash_value(payload.code)
    row = db.fetch_one(
        """
        SELECT * FROM transfers
        WHERE room_code_hash = ?
          AND status IN ('waiting', 'connected')
        ORDER BY created_at DESC
        LIMIT 1
        """,
        (code_hash,),
    )
    if row is None:
        record_security_event("room_join_not_found", ip_hash, request.headers.get("user-agent"), detail="invalid or unavailable code")
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Room not found")

    now = utc_now()
    if parse_utc(row["expires_at"]) <= now:
        db.execute("UPDATE transfers SET status = 'expired' WHERE room_id = ? AND status = 'waiting'", (row["room_id"],))
        record_security_event("room_join_expired", ip_hash, request.headers.get("user-agent"), row["room_id"], "expired code")
        raise HTTPException(status_code=status.HTTP_410_GONE, detail="Room expired")

    db.execute(
        """
        UPDATE transfers
        SET receiver_ip_hash = ?, receiver_client_type = ?, user_agent_receiver = ?
        WHERE room_id = ?
        """,
        (ip_hash, payload.client_type, request.headers.get("user-agent", "")[:500], row["room_id"]),
    )
    return RoomJoinResponse(
        room_id=row["room_id"],
        file_name=row["file_name"],
        file_size=row["file_size"],
        mime_type=row["mime_type"],
        expires_at=row["expires_at"],
    )


def get_room(room_id: str) -> Any | None:
    return db.fetch_one("SELECT * FROM transfers WHERE room_id = ?", (room_id,))


def mark_connected(room_id: str) -> None:
    row = get_room(room_id)
    if row and row["status"] == "waiting":
        db.execute(
            "UPDATE transfers SET status = 'connected', connected_at = COALESCE(connected_at, ?) WHERE room_id = ?",
            (utc_iso(), room_id),
        )


def mark_transfer_started(room_id: str) -> None:
    db.execute(
        """
        UPDATE transfers
        SET status = 'transferring',
            started_at = COALESCE(started_at, ?)
        WHERE room_id = ? AND status IN ('waiting', 'connected', 'transferring')
        """,
        (utc_iso(), room_id),
    )


def mark_transfer_progress(room_id: str, bytes_reported: int | None) -> None:
    if bytes_reported is None:
        return
    db.execute(
        "UPDATE transfers SET bytes_reported = MAX(COALESCE(bytes_reported, 0), ?) WHERE room_id = ?",
        (max(0, int(bytes_reported)), room_id),
    )


def mark_transfer_completed(room_id: str, bytes_reported: int | None, direct_p2p: bool | None, turn_used: bool | None) -> None:
    row = get_room(room_id)
    duration = None
    if row and row["started_at"]:
        duration = int((utc_now() - parse_utc(row["started_at"])).total_seconds())
    db.execute(
        """
        UPDATE transfers
        SET status = 'completed',
            completed_at = COALESCE(completed_at, ?),
            bytes_reported = COALESCE(?, bytes_reported),
            direct_p2p = COALESCE(?, direct_p2p),
            turn_used = COALESCE(?, turn_used),
            transfer_duration_seconds = COALESCE(?, transfer_duration_seconds)
        WHERE room_id = ?
        """,
        (utc_iso(), bytes_reported, direct_p2p, turn_used, duration, room_id),
    )


def mark_transfer_failed(room_id: str, reason: str) -> None:
    db.execute(
        """
        UPDATE transfers
        SET status = 'failed',
            failed_at = COALESCE(failed_at, ?),
            fail_reason = ?
        WHERE room_id = ? AND status != 'completed'
        """,
        (utc_iso(), reason[:500], room_id),
    )


def update_connection_info(room_id: str, direct_p2p: bool | None, turn_used: bool | None) -> None:
    db.execute(
        """
        UPDATE transfers
        SET direct_p2p = COALESCE(?, direct_p2p),
            turn_used = COALESCE(?, turn_used)
        WHERE room_id = ?
        """,
        (direct_p2p, turn_used, room_id),
    )


def expire_waiting_rooms() -> int:
    now = utc_iso()
    with db.connect() as conn:
        cur = conn.execute(
            """
            UPDATE transfers
            SET status = 'expired'
            WHERE status = 'waiting' AND expires_at <= ?
            """,
            (now,),
        )
        conn.commit()
        return cur.rowcount


def latest_transfers(limit: int = 100) -> list[Any]:
    return db.fetch_all(
        """
        SELECT * FROM transfers
        ORDER BY created_at DESC
        LIMIT ?
        """,
        (limit,),
    )
