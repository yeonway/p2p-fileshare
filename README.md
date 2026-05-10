# Raspberry Pi P2P File Share

SendAnywhere처럼 6자리 코드로 파일을 주고받는 P2P 파일 전송 MVP입니다. Raspberry Pi 서버는 방 생성, signaling, 기록 저장만 담당하고 파일 본문은 저장하거나 중계하지 않습니다.

## 구조

- `server/`: FastAPI, WebSocket signaling, SQLite 기록 저장, Web MVP 제공
- `web/`: Web 클라이언트 설명 문서
- `android/`: Android 후속 구현용 scaffold/README
- `windows/`: Windows 후속 구현용 scaffold/README
- `docs/`: 공통 protocol, Raspberry Pi 배포, 보안, coturn 문서

파일 전송은 WebRTC `RTCDataChannel`로만 수행합니다. 서버에는 파일 업로드 API가 없고, WebSocket으로 파일 chunk를 보내는 것도 금지되어 있습니다.

## 1차 MVP 범위

- 서버 방 생성/join/config/admin/health API
- WebSocket JSON signaling/status relay
- WebSocket binary message reject + `security_events` 기록
- Web sender/receiver MVP
- File System Access API 기반 chunk 즉시 저장
- Blob fallback 및 대용량 경고
- 6자리 코드, 30분 신규 접속 TTL
- active transfer idle timeout 기본 180초
- SQLite metadata/status/security event 저장
- Raspberry Pi systemd/Caddy 배포 파일

Android와 Windows는 이번 단계에서 완성 앱이 아니라 protocol-compatible scaffold와 README만 포함합니다.

## 로컬 실행

```bash
cd server
python -m venv .venv
. .venv/bin/activate
pip install -r requirements.txt
cp ../.env.example .env
uvicorn app.main:app --host 127.0.0.1 --port 8010
```

Windows PowerShell:

```powershell
cd server
python -m venv .venv
.\.venv\Scripts\Activate.ps1
pip install -r requirements.txt
Copy-Item ..\.env.example .env
uvicorn app.main:app --host 127.0.0.1 --port 8010
```

브라우저에서 `http://127.0.0.1:8010`을 열고 `파일 보내기`와 `파일 받기`를 각각 다른 탭에서 테스트합니다.

## 테스트

```bash
cd server
pytest
```

테스트는 room 생성, 만료 join 차단, IP/code hash, upload endpoint 부재, admin auth, filename sanitize, status transition, rate limit, WebSocket binary/금지 메시지 reject를 확인합니다.

## Raspberry Pi 배포 요약

```bash
sudo mkdir -p /home/user/server
cd /home/user/server
git clone <repo-url> p2p-fileshare
cd p2p-fileshare/server
python3 -m venv .venv
. .venv/bin/activate
pip install -r requirements.txt
cp ../.env.example .env
```

`.env`에서 `IP_HASH_SALT`, `ADMIN_PASSWORD`, `PUBLIC_ORIGIN`을 실제 값으로 바꾼 뒤:

```bash
uvicorn app.main:app --host 127.0.0.1 --port 8010
```

systemd:

```bash
sudo cp ../systemd/p2p-fileshare.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now p2p-fileshare
```

Caddy:

```bash
sudo cp ../Caddyfile.example /etc/caddy/Caddyfile
sudo systemctl reload caddy
```

자세한 절차는 `docs/deployment_raspberry_pi.md`를 참고합니다.

## Direct P2P와 TURN fallback

기본값은 STUN을 사용한 direct P2P입니다. NAT 환경에 따라 direct 연결이 실패할 수 있으며, 이 경우 coturn을 선택적으로 추가할 수 있습니다. TURN을 사용하면 트래픽은 TURN 서버를 경유할 수 있지만 파일은 저장되지 않습니다.

## 대용량 파일과 화질

파일은 `File.slice()`와 DataChannel binary chunk로 전송되며 동영상/이미지/오디오를 decode, encode, 압축, 변환하지 않습니다. 수신 측은 가능한 경우 File System Access API로 chunk를 즉시 디스크에 씁니다. 따라서 원본 bytes가 그대로 전달되고 화질 저하가 발생하지 않습니다.

## 미완성/제한사항

- Android/Windows는 scaffold + README만 제공됩니다.
- hash 검증은 TODO입니다. MVP는 `received_bytes == file_size`를 필수 검증으로 사용합니다.
- chunk 재전송/request-missing은 protocol 확장 구조만 문서화했습니다.
- Blob fallback 브라우저는 큰 파일에서 메모리 사용량이 커질 수 있습니다.
- in-memory rate limit은 단일 프로세스 기준입니다.
