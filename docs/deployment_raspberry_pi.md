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
```

`.env`에서 반드시 변경합니다.

```text
PUBLIC_ORIGINS=https://files.dcout.site,https://files.sexyminup.site
IP_HASH_SALT=<random-secret>
ADMIN_USERNAME=admin
ADMIN_PASSWORD=<strong-password>
CHUNK_SIZE_BYTES=1048576
```

여러 외부 도메인을 허용해야 하면 `PUBLIC_ORIGINS`에 쉼표로 구분해 입력합니다. 각 값은 공백을 제거한 뒤 CORS와 WebSocket Origin check에 함께 사용됩니다. 기존 `PUBLIC_ORIGIN=https://files.sexyminup.site` 단일 설정도 backward compatibility로 계속 지원됩니다.

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
- `3478`: coturn 선택 사용 시에만 공개
- TURN relay port range: coturn 선택 사용 시 제한 공개
- `22`: 외부 공개 비추천
- `25565`: Minecraft와 충돌하지 않게 유지

## 7. 외부 접속 테스트

1. `https://files.dcout.site/health` 확인
2. `/send`에서 파일 선택 후 6자리 코드 생성
3. 다른 기기 또는 다른 네트워크에서 `/receive` 접속
4. 코드 입력 후 저장 위치 선택
5. 전송 완료 후 수신 파일 크기 확인
6. `server/data`에 SQLite 외 파일/chunk가 생기지 않았는지 확인
7. `/admin/logs`에서 metadata/status만 기록되는지 확인

## 8. 만료 테스트

- room 생성 후 30분이 지나면 새 receiver join이 차단되어야 합니다.
- 30분 안에 연결되고 전송이 시작된 active transfer는 idle timeout 전까지 유지됩니다.
- 기본 idle timeout은 180초입니다.
