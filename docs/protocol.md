# Common Protocol

기본 전송 모드는 Raspberry Pi 서버에 파일을 plaintext로 임시 저장한 뒤 HTTP로 내려받는 stored transfer입니다. 기존 WebRTC/P2P 프로토콜은 삭제하지 않고 `/p2p/send`, `/p2p/receive` 고급 모드와 Windows MVP 호환용으로만 유지합니다.

## 기본 규칙

- 기본 `/`, `/send`, `/receive`는 stored transfer UI를 사용합니다.
- Web과 Android는 stored transfer API를 사용합니다.
- Windows MVP는 이번 범위에서 기존 P2P를 유지합니다.
- 전체 전송 합계는 최대 30GB입니다.
- 파일 개수 제한은 앱 레벨에서 두지 않습니다.
- 다중 파일과 폴더 수신은 속도 우선으로 uncompressed `.tar` stream을 사용합니다.
- 서버 저장은 지인용 private Pi SSD를 전제로 한 plaintext 임시 저장입니다.
- DB에는 code/token/IP 원문을 저장하지 않고 hash만 저장합니다.
- hash 검증은 아직 TODO이며, 현재 완료 검증은 size/chunk 상태 기준입니다.

## Stored REST API

### `POST /api/stored/transfers`

송신자가 단일 파일, 다중 파일, 폴더 manifest를 등록합니다. 서버는 6자리 code와 upload token을 발급합니다.

Request:

```json
{
  "client_type": "web",
  "entries": [
    {
      "relative_path": "photos/a.jpg",
      "file_size": 123456,
      "mime_type": "image/jpeg"
    }
  ]
}
```

Response:

```json
{
  "transfer_id": "uuid",
  "code": "123456",
  "upload_token": "token",
  "expires_at": "2026-05-23T12:00:00+00:00",
  "chunk_size_bytes": 4194304,
  "max_total_size_bytes": 32212254720,
  "total_size": 123456,
  "is_bundle": true,
  "archive_name": "send_honey_where_files.tar",
  "entries": [
    {
      "entry_id": "000001",
      "relative_path": "photos/a.jpg",
      "file_name": "a.jpg",
      "file_size": 123456,
      "mime_type": "image/jpeg",
      "chunk_count": 1,
      "uploaded_chunks": [],
      "bytes_uploaded": 0,
      "completed": false
    }
  ]
}
```

서버는 `relative_path`에서 absolute path, `..`, 빈 segment, 위험한 파일명을 제거하고 중복 path에는 suffix를 붙입니다.

### `PUT /api/stored/transfers/{transfer_id}/entries/{entry_id}/chunks/{chunk_index}`

raw binary chunk를 업로드합니다.

Headers:

```text
X-Upload-Token: token
Content-Type: application/octet-stream
```

Response:

```json
{
  "entry_id": "000001",
  "chunk_index": 0,
  "bytes_uploaded": 123456,
  "completed": true
}
```

같은 chunk를 다시 올리면 같은 위치에 덮어써서 재시작/재개가 가능합니다.

### `POST /api/stored/transfers/{transfer_id}/complete`

모든 entry의 chunk 상태와 저장된 파일 크기를 검증하고 transfer를 `ready` 상태로 바꿉니다.

Headers:

```text
X-Upload-Token: token
```

누락 chunk가 있으면 `409`와 missing 목록을 반환합니다.

### `POST /api/stored/transfers/join`

수신자가 6자리 code로 metadata를 조회하고 download token을 발급받습니다.

Request:

```json
{
  "code": "123456",
  "client_type": "web"
}
```

Response:

```json
{
  "transfer_id": "uuid",
  "download_token": "token",
  "expires_at": "2026-05-23T12:30:00+00:00",
  "total_size": 123456,
  "download_size": 124928,
  "is_bundle": true,
  "archive_name": "send_honey_where_files.tar",
  "entries": []
}
```

### `GET /api/stored/transfers/{transfer_id}/status`

송신자는 `X-Upload-Token`, 수신자는 `X-Download-Token`으로 상태를 조회합니다. 재시작 버튼은 이 응답의 `uploaded_chunks`, `bytes_uploaded`, `completed`를 사용해 이미 올라간 chunk를 건너뜁니다.

### `GET /api/stored/transfers/{transfer_id}/download`

Headers:

```text
X-Download-Token: token
Range: bytes=1048576-
```

- 단일 파일은 원본 파일명과 MIME으로 stream합니다.
- 단일 파일은 `Range` 재시도를 지원합니다.
- 다중 파일/폴더는 uncompressed POSIX tar로 stream합니다.
- tar는 압축하지 않고 폴더 구조만 보존합니다.
- 정상 stream 완료 후 서버는 파일과 DB row를 즉시 삭제합니다.

## 저장 모델

- 파일 bytes: `server/data/stored_files/{transfer_id}/entries/{entry_id}.blob`
- DB: transfer status, code/token/IP hash, sanitized path, 크기, MIME, chunk 상태, timestamp
- 저장하지 않는 값: code 원문, token 원문, IP 원문

## 삭제 정책

- 업로드/다운로드가 끊긴 뒤 10분간 재연결/재시작이 없으면 삭제합니다.
- 업로드 완료 후 수신 대기 TTL은 30분입니다.
- 첫 정상 다운로드 stream 완료 후 즉시 삭제합니다.
- cleanup은 서버 request lifecycle과 background cleanup에서 실행됩니다.

## QR And App Links

송신 QR payload는 Android intent URL입니다. 앱 설치 기기는 Android 앱을 열고, 미설치 기기는 HTTPS receive 페이지로 fallback합니다.

```text
intent://receive?code=123456#Intent;scheme=sendhoney;package=site.sexyminup.p2pfileshare;S.browser_fallback_url=https%3A%2F%2Ffiles.dcout.site%2Freceive%3Fcode%3D123456;end
```

Plain receive URL:

```text
https://files.dcout.site/receive?code=123456
```

Android 앱은 `sendhoney://receive?code=123456`와 HTTPS `/receive?code=123456` 링크를 stored receive 화면으로 처리합니다.

## Hidden P2P Mode

기존 WebRTC/P2P는 `/p2p/send`, `/p2p/receive`에서 유지합니다. 이 모드에서는 다음 규칙을 계속 지킵니다.

- 서버는 signaling, room code, metadata/status만 처리합니다.
- 파일 bytes는 WebRTC `RTCDataChannel` `file` channel로만 전송합니다.
- WebSocket으로 파일 본문을 중계하지 않습니다.
- 서버에 파일/chunk/base64/Blob/ArrayBuffer를 저장하지 않습니다.
- Windows MVP는 이 P2P 프로토콜을 계속 사용합니다.

P2P WebSocket에서 허용되는 message type:

- `sender-ready`
- `receiver-ready`
- `offer`
- `answer`
- `ice-candidate`
- `transfer-started`
- `transfer-progress`
- `transfer-completed`
- `transfer-failed`
- `heartbeat`
- `connection-info`

금지 message type:

- `file-chunk`
- `file-data`
- `base64-file`
- `binary-upload`

## Error Codes

- `E_SIZE_MISMATCH`: manifest, chunk, 저장 크기, 수신 크기 불일치
- `E_STORAGE_WRITE_FAILED`: chunk 저장 또는 수신 파일 쓰기 실패
- `E_STORAGE_OPEN_FAILED`: 수신 저장 위치 열기 실패
- `E_TRANSFER_TIMEOUT`: idle timeout
- `E_TOKEN_INVALID`: upload/download token 불일치
- `E_TRANSFER_EXPIRED`: TTL 만료
- `E_UNKNOWN`: 분류되지 않은 실패

## Hash TODO

현재 v1은 chunk 상태와 크기로 완료를 검증합니다. 이후 hash 검증을 추가할 때도 30GB 파일을 메모리에 전부 올리지 않고 streaming/incremental hash로 처리해야 합니다.
