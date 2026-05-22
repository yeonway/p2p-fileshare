# Raspberry Pi Deployment

기준 경로:

```text
/home/user/server/p2p-fileshare
```

## 1. 설치

```bash
sudo mkdir -p /home/user/server
cd /home/user/server
git clone <repo-url> p2p-fileshare
cd p2p-fileshare/server
python3 -m venv .venv
. .venv/bin/activate
pip install -r requirements.txt
cp ../.env.example .env
mkdir -p data/stored_files
```

`.env`에서 반드시 바꿉니다.

```text
PUBLIC_ORIGINS=https://files.dcout.site,https://files.sexyminup.site
IP_HASH_SALT=<random-secret>
ADMIN_USERNAME=admin
ADMIN_PASSWORD=<strong-password>
STORED_FILES_DIR=./data/stored_files
STORED_CHUNK_SIZE_BYTES=4194304
STORED_MAX_TOTAL_SIZE_BYTES=32212254720
STORED_IDLE_TIMEOUT_SECONDS=600
STORED_READY_TTL_MINUTES=30
```

여러 외부 도메인을 허용해야 하면 `PUBLIC_ORIGINS`에 쉼표로 구분해 입력합니다. 각 값은 공백을 제거한 뒤 CORS와 WebSocket Origin check에 함께 사용됩니다.

## 2. 로컬 실행 확인

```bash
cd /home/user/server/p2p-fileshare/server
. .venv/bin/activate
uvicorn app.main:app --host 127.0.0.1 --port 8010
```

다른 터미널:

```bash
curl http://127.0.0.1:8010/health
```

예상 응답:

```json
{"status":"ok"}
```

## 3. systemd 등록

```bash
sudo cp /home/user/server/p2p-fileshare/systemd/p2p-fileshare.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now p2p-fileshare
sudo systemctl status p2p-fileshare
```

로그:

```bash
journalctl -u p2p-fileshare -f
```

## 4. Caddy 설정

`/etc/caddy/Caddyfile`:

```caddy
files.dcout.site {
    reverse_proxy 127.0.0.1:8010
}
```

적용:

```bash
sudo systemctl reload caddy
```

## 5. DNS

`files.dcout.site`의 A/AAAA 레코드를 Raspberry Pi가 연결된 공인 IP로 설정합니다. 공유기 뒤라면 80/443 포트 포워딩이 필요합니다.

## 6. 포트 정책

- `80`: Caddy HTTP, 공개
- `443`: Caddy HTTPS, 공개
- `8010`: FastAPI, `127.0.0.1` 전용, 외부 공개 금지
- `3478`: hidden P2P TURN을 사용할 때만 공개
- TURN relay port range: hidden P2P TURN을 사용할 때만 제한 공개
- `22`: 외부 공개 비추천
- `25565`: Minecraft와 충돌하지 않게 유지

## 7. Stored Mode 외부 테스트

1. `https://files.dcout.site/health` 확인
2. `/send`에서 단일 파일 전송
3. 다른 기기 또는 다른 네트워크에서 `/receive` 접속
4. 6자리 code 입력 후 다운로드
5. 완료 후 `server/data/stored_files`에서 해당 transfer directory가 삭제되는지 확인
6. 여러 파일과 폴더 선택 후 `.tar` 수신과 path 보존 확인
7. 업로드/다운로드 중 네트워크를 끊고 10분 안에 재시작 버튼으로 이어받기 확인

## 8. 만료 테스트

- 업로드 중 10분간 재시작이 없으면 파일과 DB row가 삭제되어야 합니다.
- 다운로드 중 10분간 재시작이 없으면 파일과 DB row가 삭제되어야 합니다.
- 업로드 완료 후 30분 안에 receiver가 join하지 않으면 파일과 DB row가 삭제되어야 합니다.

## 9. 운영 주의

- v1은 plaintext 임시 저장입니다. 신뢰하는 사용자만 쓰는 private Pi 전제로 운영하세요.
- 30GB 전송 여러 개가 동시에 올라오면 SSD 여유 공간이 병목입니다.
- 정기적으로 `server/data`와 SQLite 파일 크기를 확인하세요.
