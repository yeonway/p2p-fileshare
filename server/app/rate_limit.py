from __future__ import annotations

import threading
import time
from dataclasses import dataclass


@dataclass
class Bucket:
    window_started_at: float
    count: int


_buckets: dict[str, Bucket] = {}
_lock = threading.Lock()


def check_rate_limit(name: str, key: str, limit: int, window_seconds: int = 60) -> bool:
    if limit <= 0:
        return True
    now = time.monotonic()
    bucket_key = f"{name}:{key}"
    with _lock:
        bucket = _buckets.get(bucket_key)
        if bucket is None or now - bucket.window_started_at >= window_seconds:
            _buckets[bucket_key] = Bucket(window_started_at=now, count=1)
            return True
        if bucket.count >= limit:
            return False
        bucket.count += 1
        return True


def clear_rate_limits() -> None:
    with _lock:
        _buckets.clear()
