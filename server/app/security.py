from __future__ import annotations

import hashlib
import re
import secrets
from datetime import datetime, timezone
from html import escape
from typing import Any

from fastapi import HTTPException, Request, WebSocket, status
from fastapi.security import HTTPBasicCredentials

from .config import Settings, get_settings


CONTROL_CHARS = re.compile(r"[\x00-\x1f\x7f]")
SAFE_FILENAME_CHARS = re.compile(r"[^A-Za-z0-9가-힣._ ()\[\]\-]+")


def utc_now() -> datetime:
    return datetime.now(timezone.utc)


def utc_iso(dt: datetime | None = None) -> str:
    value = dt or utc_now()
    return value.replace(microsecond=0).isoformat()


def parse_utc(value: str) -> datetime:
    return datetime.fromisoformat(value)


def hash_value(value: str, salt: str | None = None) -> str:
    settings = get_settings()
    chosen_salt = salt if salt is not None else settings.ip_hash_salt
    return hashlib.sha256(f"{chosen_salt}:{value}".encode("utf-8")).hexdigest()


def client_ip_from_request(request: Request) -> str:
    forwarded = request.headers.get("x-forwarded-for")
    if forwarded:
        return forwarded.split(",", 1)[0].strip()
    return request.client.host if request.client else "unknown"


def client_ip_from_websocket(websocket: WebSocket) -> str:
    forwarded = websocket.headers.get("x-forwarded-for")
    if forwarded:
        return forwarded.split(",", 1)[0].strip()
    return websocket.client.host if websocket.client else "unknown"


def sanitize_filename(file_name: str) -> str:
    name = CONTROL_CHARS.sub("", file_name or "")
    name = name.replace("\\", "/").split("/")[-1].strip()
    name = SAFE_FILENAME_CHARS.sub("_", name)
    name = name.strip(". ")
    if not name:
        return "unnamed"
    return name[:255]


def escape_display(value: Any) -> str:
    return escape(str(value or ""))


def is_allowed_origin(origin: str | None, settings: Settings | None = None) -> bool:
    if not origin:
        # Native clients commonly omit Origin. Browser requests include it and are checked.
        return True
    active_settings = settings or get_settings()
    return origin.rstrip("/") in set(active_settings.allowed_origins)


def verify_admin_credentials(credentials: HTTPBasicCredentials) -> None:
    settings = get_settings()
    username_ok = secrets.compare_digest(credentials.username, settings.admin_username)
    password_ok = secrets.compare_digest(credentials.password, settings.admin_password)
    if not (username_ok and password_ok):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Authentication required",
            headers={"WWW-Authenticate": "Basic"},
        )
