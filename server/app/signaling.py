from __future__ import annotations

import json
from typing import Any

from fastapi import APIRouter, WebSocket, WebSocketDisconnect, status

from .activity import forget_room, touch_room
from .config import get_settings
from .models import ALLOWED_WS_MESSAGE_TYPES, FORBIDDEN_WS_MESSAGE_TYPES
from .rooms import (
    get_room,
    mark_connected,
    mark_transfer_completed,
    mark_transfer_failed,
    mark_transfer_progress,
    mark_transfer_started,
    record_security_event,
    update_connection_info,
)
from .security import client_ip_from_websocket, hash_value, is_allowed_origin, parse_utc, utc_now


router = APIRouter()


class ConnectionManager:
    def __init__(self) -> None:
        self._rooms: dict[str, dict[str, WebSocket]] = {}

    async def connect(self, room_id: str, role: str, websocket: WebSocket) -> None:
        await websocket.accept()
        self._rooms.setdefault(room_id, {})[role] = websocket
        if {"sender", "receiver"}.issubset(set(self._rooms.get(room_id, {}))):
            mark_connected(room_id)
        touch_room(room_id)

    def disconnect(self, room_id: str, role: str) -> None:
        room = self._rooms.get(room_id)
        if not room:
            return
        if room.get(role):
            room.pop(role, None)
        if not room:
            self._rooms.pop(room_id, None)
            forget_room(room_id)

    async def relay(self, room_id: str, source_role: str, message: dict[str, Any]) -> None:
        target_role = "receiver" if source_role == "sender" else "sender"
        peer = self._rooms.get(room_id, {}).get(target_role)
        if peer:
            await peer.send_json(message)


manager = ConnectionManager()


def _extract_reported_bytes(payload: dict[str, Any]) -> int | None:
    for key in ("bytes_reported", "bytes_received", "bytes_sent", "total_bytes"):
        value = payload.get(key)
        if isinstance(value, int):
            return max(0, value)
    return None


def _bool_or_none(value: Any) -> bool | None:
    if isinstance(value, bool):
        return value
    return None


def _handle_status_message(room_id: str, payload: dict[str, Any]) -> None:
    message_type = payload.get("type")
    touch_room(room_id)
    if message_type == "transfer-started":
        mark_transfer_started(room_id)
    elif message_type == "transfer-progress":
        mark_transfer_progress(room_id, _extract_reported_bytes(payload))
    elif message_type == "transfer-completed":
        mark_transfer_completed(
            room_id,
            _extract_reported_bytes(payload),
            _bool_or_none(payload.get("direct_p2p")),
            _bool_or_none(payload.get("turn_used")),
        )
    elif message_type == "transfer-failed":
        reason = str(payload.get("reason") or payload.get("message") or "transfer failed")
        error_code = payload.get("error_code")
        if isinstance(error_code, str) and error_code:
            reason = f"[{error_code[:64]}] {reason}"
        mark_transfer_failed(room_id, reason)
    elif message_type == "connection-info":
        update_connection_info(
            room_id,
            _bool_or_none(payload.get("direct_p2p")),
            _bool_or_none(payload.get("turn_used")),
        )


async def _reject_with_security_event(
    websocket: WebSocket,
    event_type: str,
    room_id: str | None,
    detail: str,
    code: int = status.WS_1008_POLICY_VIOLATION,
) -> None:
    ip_hash = hash_value(client_ip_from_websocket(websocket))
    record_security_event(event_type, ip_hash, websocket.headers.get("user-agent"), room_id, detail)
    await websocket.close(code=code)


@router.websocket("/ws/{room_id}/{role}")
async def websocket_signaling(websocket: WebSocket, room_id: str, role: str) -> None:
    settings = get_settings()
    if role not in {"sender", "receiver"}:
        await _reject_with_security_event(websocket, "websocket_invalid_role", room_id, "invalid role")
        return
    if not is_allowed_origin(websocket.headers.get("origin"), settings):
        await _reject_with_security_event(websocket, "websocket_invalid_origin", room_id, "invalid origin")
        return

    row = get_room(room_id)
    if row is None:
        await _reject_with_security_event(websocket, "websocket_room_not_found", room_id, "room not found")
        return
    if row["status"] in {"completed", "failed", "expired"}:
        await _reject_with_security_event(websocket, "websocket_room_closed", room_id, "room closed")
        return
    if parse_utc(row["expires_at"]) <= utc_now() and row["status"] != "transferring":
        await _reject_with_security_event(websocket, "websocket_room_expired", room_id, "expired room")
        return

    await manager.connect(room_id, role, websocket)
    try:
        while True:
            received = await websocket.receive()
            if received.get("type") == "websocket.disconnect":
                break
            if received.get("bytes") is not None:
                await _reject_with_security_event(
                    websocket,
                    "websocket_binary_rejected",
                    room_id,
                    "binary websocket message rejected",
                    code=status.WS_1003_UNSUPPORTED_DATA,
                )
                break
            text = received.get("text")
            if text is None:
                continue
            try:
                payload = json.loads(text)
            except json.JSONDecodeError:
                await _reject_with_security_event(websocket, "websocket_invalid_json", room_id, "invalid json")
                break
            if not isinstance(payload, dict):
                await _reject_with_security_event(websocket, "websocket_invalid_payload", room_id, "payload is not object")
                break
            message_type = payload.get("type")
            if message_type in FORBIDDEN_WS_MESSAGE_TYPES:
                await _reject_with_security_event(
                    websocket,
                    "websocket_forbidden_message",
                    room_id,
                    f"forbidden message type: {message_type}",
                )
                break
            if message_type not in ALLOWED_WS_MESSAGE_TYPES:
                await _reject_with_security_event(
                    websocket,
                    "websocket_unknown_message",
                    room_id,
                    f"unknown message type: {message_type}",
                )
                break
            _handle_status_message(room_id, payload)
            await manager.relay(room_id, role, payload)
    except WebSocketDisconnect:
        pass
    finally:
        manager.disconnect(room_id, role)
