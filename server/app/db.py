from __future__ import annotations

import sqlite3
from pathlib import Path
from typing import Iterable, Sequence

from .config import SERVER_ROOT, Settings, get_settings


SCHEMA = """
CREATE TABLE IF NOT EXISTS transfers (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    room_id TEXT UNIQUE NOT NULL,
    room_code_hash TEXT NOT NULL,
    sender_ip_hash TEXT,
    receiver_ip_hash TEXT,
    sender_client_type TEXT,
    receiver_client_type TEXT,
    file_name TEXT,
    file_size INTEGER,
    mime_type TEXT,
    status TEXT,
    created_at TEXT,
    expires_at TEXT,
    connected_at TEXT,
    started_at TEXT,
    completed_at TEXT,
    failed_at TEXT,
    fail_reason TEXT,
    user_agent_sender TEXT,
    user_agent_receiver TEXT,
    direct_p2p BOOLEAN,
    turn_used BOOLEAN,
    bytes_reported INTEGER,
    transfer_duration_seconds INTEGER
);

CREATE INDEX IF NOT EXISTS idx_transfers_code_hash ON transfers(room_code_hash);
CREATE INDEX IF NOT EXISTS idx_transfers_status ON transfers(status);
CREATE INDEX IF NOT EXISTS idx_transfers_expires_at ON transfers(expires_at);

CREATE TABLE IF NOT EXISTS security_events (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    event_type TEXT,
    ip_hash TEXT,
    user_agent TEXT,
    room_id TEXT,
    created_at TEXT,
    detail TEXT
);

CREATE INDEX IF NOT EXISTS idx_security_events_created_at ON security_events(created_at);

CREATE TABLE IF NOT EXISTS stored_transfers (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    transfer_id TEXT UNIQUE NOT NULL,
    room_code_hash TEXT NOT NULL,
    upload_token_hash TEXT NOT NULL,
    download_token_hash TEXT,
    sender_ip_hash TEXT,
    receiver_ip_hash TEXT,
    sender_client_type TEXT,
    receiver_client_type TEXT,
    archive_name TEXT,
    total_size INTEGER NOT NULL,
    status TEXT NOT NULL,
    chunk_size INTEGER NOT NULL,
    is_bundle BOOLEAN NOT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    expires_at TEXT NOT NULL,
    upload_completed_at TEXT,
    download_started_at TEXT,
    download_completed_at TEXT,
    fail_reason TEXT,
    user_agent_sender TEXT,
    user_agent_receiver TEXT
);

CREATE INDEX IF NOT EXISTS idx_stored_transfers_code_hash ON stored_transfers(room_code_hash);
CREATE INDEX IF NOT EXISTS idx_stored_transfers_status ON stored_transfers(status);
CREATE INDEX IF NOT EXISTS idx_stored_transfers_updated_at ON stored_transfers(updated_at);
CREATE INDEX IF NOT EXISTS idx_stored_transfers_expires_at ON stored_transfers(expires_at);

CREATE TABLE IF NOT EXISTS stored_entries (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    transfer_id TEXT NOT NULL,
    entry_id TEXT NOT NULL,
    relative_path TEXT NOT NULL,
    file_name TEXT NOT NULL,
    file_size INTEGER NOT NULL,
    mime_type TEXT,
    chunk_count INTEGER NOT NULL,
    uploaded_chunks TEXT NOT NULL,
    bytes_uploaded INTEGER NOT NULL,
    completed BOOLEAN NOT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    UNIQUE(transfer_id, entry_id),
    FOREIGN KEY (transfer_id) REFERENCES stored_transfers(transfer_id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_stored_entries_transfer_id ON stored_entries(transfer_id);
"""


def sqlite_path_from_url(database_url: str) -> Path:
    if not database_url.startswith("sqlite:///"):
        raise ValueError("Only sqlite:/// DATABASE_URL is supported")
    raw_path = database_url.removeprefix("sqlite:///")
    path = Path(raw_path)
    if not path.is_absolute():
        path = SERVER_ROOT / path
    return path


def connect(settings: Settings | None = None) -> sqlite3.Connection:
    active_settings = settings or get_settings()
    path = sqlite_path_from_url(active_settings.database_url)
    path.parent.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(path)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA journal_mode=WAL")
    conn.execute("PRAGMA foreign_keys=ON")
    return conn


def init_db(settings: Settings | None = None) -> None:
    with connect(settings) as conn:
        conn.executescript(SCHEMA)
        conn.commit()


def fetch_one(query: str, params: Sequence[object] = ()) -> sqlite3.Row | None:
    with connect() as conn:
        return conn.execute(query, params).fetchone()


def fetch_all(query: str, params: Sequence[object] = ()) -> list[sqlite3.Row]:
    with connect() as conn:
        return list(conn.execute(query, params).fetchall())


def execute(query: str, params: Sequence[object] = ()) -> None:
    with connect() as conn:
        conn.execute(query, params)
        conn.commit()


def execute_many(query: str, params: Iterable[Sequence[object]]) -> None:
    with connect() as conn:
        conn.executemany(query, params)
        conn.commit()
