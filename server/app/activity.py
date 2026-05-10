from __future__ import annotations

import threading
from datetime import datetime

from .security import utc_now


_activity: dict[str, datetime] = {}
_lock = threading.Lock()


def touch_room(room_id: str) -> None:
    with _lock:
        _activity[room_id] = utc_now()


def get_room_activity(room_id: str) -> datetime | None:
    with _lock:
        return _activity.get(room_id)


def forget_room(room_id: str) -> None:
    with _lock:
        _activity.pop(room_id, None)
