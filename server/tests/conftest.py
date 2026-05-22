from __future__ import annotations

import sys
from pathlib import Path

import pytest
from fastapi.testclient import TestClient


SERVER_ROOT = Path(__file__).resolve().parents[1]
if str(SERVER_ROOT) not in sys.path:
    sys.path.insert(0, str(SERVER_ROOT))

from app.main import app
from app.rate_limit import clear_rate_limits


@pytest.fixture()
def client(tmp_path, monkeypatch):
    db_path = tmp_path / "app.db"
    monkeypatch.setenv("DATABASE_URL", f"sqlite:///{db_path}")
    monkeypatch.setenv("STORED_FILES_DIR", str(tmp_path / "stored_files"))
    monkeypatch.delenv("PUBLIC_ORIGINS", raising=False)
    monkeypatch.delenv("PUBLIC_ORIGIN", raising=False)
    monkeypatch.setenv("IP_HASH_SALT", "test-salt")
    monkeypatch.setenv("ADMIN_USERNAME", "admin")
    monkeypatch.setenv("ADMIN_PASSWORD", "secret")
    monkeypatch.setenv("RATE_LIMIT_JOIN_PER_MINUTE", "10")
    monkeypatch.setenv("RATE_LIMIT_ROOM_CREATE_PER_MINUTE", "20")
    clear_rate_limits()
    with TestClient(app) as test_client:
        yield test_client
