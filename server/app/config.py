from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path


SERVER_ROOT = Path(__file__).resolve().parents[1]


def _load_env_file() -> None:
    env_path = SERVER_ROOT / ".env"
    if not env_path.exists():
        return
    for raw_line in env_path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        os.environ.setdefault(key.strip(), value.strip().strip('"').strip("'"))


def _int_env(name: str, default: int) -> int:
    value = os.getenv(name)
    if value is None or value == "":
        return default
    try:
        return int(value)
    except ValueError:
        return default


def _split_csv(value: str) -> list[str]:
    return [item.strip() for item in value.split(",") if item.strip()]


def _normalize_origins(values: list[str]) -> list[str]:
    normalized: list[str] = []
    seen: set[str] = set()
    for value in values:
        origin = value.strip().rstrip("/")
        if origin and origin not in seen:
            normalized.append(origin)
            seen.add(origin)
    return normalized


def _public_origins_from_env() -> list[str]:
    configured_origins = _split_csv(os.getenv("PUBLIC_ORIGINS", ""))
    legacy_origin = os.getenv("PUBLIC_ORIGIN", "")
    if legacy_origin:
        configured_origins.append(legacy_origin)
    if not configured_origins:
        configured_origins.append("https://files.dcout.site")
    return _normalize_origins(configured_origins)


@dataclass(frozen=True)
class Settings:
    app_host: str
    app_port: int
    public_origin: str
    public_origins: list[str]
    database_url: str
    room_ttl_minutes: int
    active_transfer_idle_timeout_seconds: int
    chunk_size_bytes: int
    ip_hash_salt: str
    admin_username: str
    admin_password: str
    log_retention_days: int
    rate_limit_join_per_minute: int
    rate_limit_room_create_per_minute: int
    webrtc_stun_urls: list[str]
    webrtc_turn_urls: list[str]
    webrtc_turn_username: str
    webrtc_turn_credential: str
    android_app_sha256_cert_fingerprints: list[str]

    @classmethod
    def from_env(cls) -> "Settings":
        _load_env_file()
        public_origins = _public_origins_from_env()
        return cls(
            app_host=os.getenv("APP_HOST", "127.0.0.1"),
            app_port=_int_env("APP_PORT", 8010),
            public_origin=public_origins[0],
            public_origins=public_origins,
            database_url=os.getenv("DATABASE_URL", "sqlite:///./data/app.db"),
            room_ttl_minutes=_int_env("ROOM_TTL_MINUTES", 30),
            active_transfer_idle_timeout_seconds=_int_env("ACTIVE_TRANSFER_IDLE_TIMEOUT_SECONDS", 180),
            chunk_size_bytes=_int_env("CHUNK_SIZE_BYTES", 65536),
            ip_hash_salt=os.getenv("IP_HASH_SALT", "replace-with-random-secret"),
            admin_username=os.getenv("ADMIN_USERNAME", "admin"),
            admin_password=os.getenv("ADMIN_PASSWORD", "replace-with-strong-password"),
            log_retention_days=_int_env("LOG_RETENTION_DAYS", 30),
            rate_limit_join_per_minute=_int_env("RATE_LIMIT_JOIN_PER_MINUTE", 10),
            rate_limit_room_create_per_minute=_int_env("RATE_LIMIT_ROOM_CREATE_PER_MINUTE", 20),
            webrtc_stun_urls=_split_csv(os.getenv("WEBRTC_STUN_URLS", "stun:stun.l.google.com:19302")),
            webrtc_turn_urls=_split_csv(os.getenv("WEBRTC_TURN_URLS", "")),
            webrtc_turn_username=os.getenv("WEBRTC_TURN_USERNAME", ""),
            webrtc_turn_credential=os.getenv("WEBRTC_TURN_CREDENTIAL", ""),
            android_app_sha256_cert_fingerprints=_split_csv(os.getenv("ANDROID_APP_SHA256_CERT_FINGERPRINTS", "")),
        )

    @property
    def ice_servers(self) -> list[dict[str, object]]:
        servers: list[dict[str, object]] = []
        if self.webrtc_stun_urls:
            servers.append({"urls": self.webrtc_stun_urls})
        if self.webrtc_turn_urls:
            turn_server: dict[str, object] = {"urls": self.webrtc_turn_urls}
            if self.webrtc_turn_username:
                turn_server["username"] = self.webrtc_turn_username
            if self.webrtc_turn_credential:
                turn_server["credential"] = self.webrtc_turn_credential
            servers.append(turn_server)
        return servers

    @property
    def allowed_origins(self) -> list[str]:
        origins = set(self.public_origins)
        origins.add(f"http://{self.app_host}:{self.app_port}")
        origins.add(f"http://localhost:{self.app_port}")
        origins.add(f"http://127.0.0.1:{self.app_port}")
        return sorted(origin for origin in origins if origin)


def get_settings() -> Settings:
    return Settings.from_env()
