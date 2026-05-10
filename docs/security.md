# Security Notes

## 서버 저장 금지

서버는 다음 데이터를 저장하거나 로그에 남기지 않습니다.

- 파일 본문
- chunk
- base64 파일 데이터
- Blob
- ArrayBuffer
- multipart 업로드 본문

서버는 metadata, 상태, hash 처리된 IP/code, 보안 이벤트만 SQLite에 저장합니다.

## WebSocket 정책

- JSON object만 허용합니다.
- binary frame 수신 시 close code `1003`으로 종료하고 `security_events`에 기록합니다.
- `file-chunk`, `file-data`, `base64-file`, `binary-upload` type은 close code `1008`로 종료하고 기록합니다.
- signaling/status payload는 peer에게 relay하지만 파일 본문은 relay하지 않습니다.

## IP와 코드

- IP 원문 저장 금지.
- IP는 `IP_HASH_SALT` 기반 SHA-256 hash로 저장.
- room code 원문 저장 지양.
- room code는 hash로 저장하고 응답 시 생성 직후 sender에게만 반환.

## Rate Limit

- room create 기본값: IP hash당 분당 20회.
- room join 기본값: IP hash당 분당 10회.
- 현재 구현은 단일 프로세스 in-memory rate limit입니다.

## Admin

- `/admin/logs`는 Basic Auth로 보호됩니다.
- 파일 다운로드 또는 파일 본문 확인 기능은 없습니다.
- 표시 항목은 metadata/status 중심입니다.

## Headers

서버는 기본 보안 헤더를 설정합니다.

- `Content-Security-Policy`
- `X-Content-Type-Options`
- `Referrer-Policy`
- `X-Frame-Options`

## 운영 주의

- `IP_HASH_SALT`와 `ADMIN_PASSWORD`는 기본값을 쓰지 마세요.
- `8010`은 외부에 공개하지 마세요.
- HTTPS를 사용하세요. 브라우저 WebRTC와 저장 API는 보안 컨텍스트에서 안정적으로 동작합니다.
