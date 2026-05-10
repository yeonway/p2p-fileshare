from __future__ import annotations

import asyncio

from .activity import get_room_activity
from .config import get_settings
from .db import fetch_all
from .rooms import expire_waiting_rooms, mark_transfer_failed
from .security import parse_utc, utc_now


def cleanup_once() -> None:
    settings = get_settings()
    expire_waiting_rooms()
    active_rows = fetch_all("SELECT room_id, started_at FROM transfers WHERE status = 'transferring'")
    now = utc_now()
    for row in active_rows:
        last_activity = get_room_activity(row["room_id"])
        if last_activity is None and row["started_at"]:
            last_activity = parse_utc(row["started_at"])
        if last_activity is None:
            continue
        idle_seconds = (now - last_activity).total_seconds()
        if idle_seconds > settings.active_transfer_idle_timeout_seconds:
            mark_transfer_failed(row["room_id"], "active transfer idle timeout")


async def cleanup_loop() -> None:
    while True:
        cleanup_once()
        await asyncio.sleep(60)
