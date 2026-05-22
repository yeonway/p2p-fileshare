# Security Notes

## Stored Mode Storage

기본 모드는 서버에 파일을 plaintext로 임시 저장합니다. 운영 전제는 private Raspberry Pi + SSD + HTTPS + 제한된 사용자입니다.

- 파일 bytes는 `server/data/stored_files/{transfer_id}/entries/{entry_id}.blob` 아래에만 저장합니다.
- multipart/form-data 파일 업로드는 사용하지 않고 raw chunk PUT만 허용합니다.
- 업로드/다운로드 token 없이는 status, upload, download API를 사용할 수 없습니다.
- 첫 정상 다운로드 stream 완료, TTL 만료, idle timeout 때 파일과 DB row를 삭제합니다.
- 관리자 화면은 metadata/status/security event 확인용이며 파일 본문 다운로드 기능을 제공하지 않습니다.

## 저장하지 않는 원문 값

- 6자리 code 원문은 DB에 저장하지 않습니다.
- upload/download token 원문은 DB에 저장하지 않습니다.
- IP 원문은 DB에 저장하지 않습니다.
- 위 값들은 `IP_HASH_SALT` 기반 SHA-256 hash로만 저장합니다.

## Manifest Sanitization

서버는 manifest의 `relative_path`를 그대로 신뢰하지 않습니다.

- absolute path 제거
- `..`, `.`, 빈 path segment 제거
- 파일명 sanitize
- 중복 path suffix 처리
- 최종 파일 bytes는 entry id 기반 `.blob` 파일로 저장

수신 tar stream에 들어가는 path도 sanitize된 relative path만 사용합니다.

## Stored Mode Limits

- 전체 전송 합계 최대 30GB
- 파일 수 앱 레벨 제한 없음
- 기본 chunk size 4MiB
- 업로드/다운로드 idle cleanup 10분
- 업로드 완료 후 수신 대기 TTL 30분

## Hidden P2P Mode

`/p2p/send`, `/p2p/receive` 고급 모드는 기존 보안 규칙을 유지합니다.

- 서버는 signaling/status JSON만 relay합니다.
- WebSocket binary frame은 close code `1003`으로 종료하고 security event에 기록합니다.
- `file-chunk`, `file-data`, `base64-file`, `binary-upload` message type은 close code `1008`로 종료하고 기록합니다.
- P2P 모드에서는 파일 본문, chunk, base64, Blob, ArrayBuffer를 서버에 저장하지 않습니다.

## Rate Limit

- stored transfer create는 기존 room create rate limit과 같은 기본값을 사용합니다.
- stored join은 기존 join rate limit과 같은 기본값을 사용합니다.
- 현재 구현은 단일 프로세스 in-memory rate limit입니다.

## Headers

서버는 기본 보안 헤더를 설정합니다.

- `Content-Security-Policy`
- `X-Content-Type-Options`
- `Referrer-Policy`
- `X-Frame-Options`

## 운영 주의

- `IP_HASH_SALT`와 `ADMIN_PASSWORD`의 기본값을 운영에 쓰지 마세요.
- FastAPI 내부 포트 `8010`은 외부에 직접 공개하지 마세요.
- HTTPS 뒤에서 운영하세요. PWA, 앱 링크, 파일 다운로드가 안정적으로 동작합니다.
- SSD 용량과 filesystem quota를 확인하세요. 30GB 전송 여러 개가 동시에 올라오면 디스크가 먼저 병목이 됩니다.
