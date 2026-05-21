from __future__ import annotations

from pydantic import BaseModel


CLIENT_TYPES = {"web", "android", "windows"}
TRANSFER_STATUSES = {"waiting", "connected", "transferring", "completed", "failed", "expired"}

ALLOWED_WS_MESSAGE_TYPES = {
    "sender-ready",
    "receiver-ready",
    "offer",
    "answer",
    "ice-candidate",
    "transfer-started",
    "transfer-progress",
    "transfer-completed",
    "transfer-failed",
    "heartbeat",
    "connection-info",
}

FORBIDDEN_WS_MESSAGE_TYPES = {
    "file-chunk",
    "file-data",
    "base64-file",
    "binary-upload",
}


class RoomCreateRequest(BaseModel):
    file_name: str
    file_size: int
    mime_type: str = ""
    client_type: str


class RoomCreateResponse(BaseModel):
    room_id: str
    code: str
    expires_at: str


class RoomJoinRequest(BaseModel):
    code: str
    client_type: str


class RoomJoinResponse(BaseModel):
    room_id: str
    file_name: str
    file_size: int
    mime_type: str
    expires_at: str


class ConfigResponse(BaseModel):
    iceServers: list[dict[str, object]]
    roomTtlMinutes: int
    activeTransferIdleTimeoutSeconds: int
    chunkSizeBytes: int


class QrSvgRequest(BaseModel):
    value: str
