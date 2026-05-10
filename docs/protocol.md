# Common Protocol

이 문서는 Web, Android, Windows 클라이언트가 공유해야 하는 1차 protocol입니다.

## 원칙

- 서버는 파일 본문, chunk, base64, Blob, ArrayBuffer를 저장하지 않는다.
- 서버는 WebSocket으로 파일 본문을 중계하지 않는다.
- 파일 본문은 WebRTC `RTCDataChannel`의 `file` channel로만 전송한다.
- `control` channel은 JSON control message만 사용한다.
- `file` channel은 binary chunk만 사용한다.
- 파일은 원본 binary bytes 그대로 전송하며 압축/변환/재인코딩하지 않는다.
- MVP 완료 검증은 `received_bytes == file_size`다.

## REST API

### `GET /api/config`

```json
{
  "iceServers": [
    {
      "urls": ["stun:stun.l.google.com:19302"]
    }
  ],
  "roomTtlMinutes": 30,
  "activeTransferIdleTimeoutSeconds": 180,
  "chunkSizeBytes": 262144
}
```

기본은 STUN + direct P2P입니다. TURN 설정은 선택 fallback입니다.

### `POST /api/rooms`

Sender가 파일 metadata만 등록합니다. 파일 본문은 절대 보내지 않습니다.

Request:

```json
{
  "file_name": "video.mp4",
  "file_size": 123456789,
  "mime_type": "video/mp4",
  "client_type": "web"
}
```

Response:

```json
{
  "room_id": "uuid",
  "code": "123456",
  "expires_at": "2026-05-10T12:30:00+00:00"
}
```

### `POST /api/rooms/join`

Receiver가 6자리 code로 metadata를 조회합니다.

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
  "room_id": "uuid",
  "file_name": "video.mp4",
  "file_size": 123456789,
  "mime_type": "video/mp4",
  "expires_at": "2026-05-10T12:30:00+00:00"
}
```

## WebSocket Signaling

Endpoint:

```text
WS /ws/{room_id}/{role}
```

`role`은 `sender` 또는 `receiver`입니다.

허용 메시지:

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

금지 메시지:

- `file-chunk`
- `file-data`
- `base64-file`
- `binary-upload`

서버 동작:

- JSON object만 허용한다.
- binary frame은 즉시 reject하고 `security_events`에 기록한다.
- 금지 메시지 type은 reject하고 `security_events`에 기록한다.
- 서버는 signaling/status payload를 peer에게 relay하지만 파일 body는 받지 않는다.
- 서버 로그와 DB에는 파일 내용 또는 chunk 내용이 기록되지 않는다.

Example offer:

```json
{
  "type": "offer",
  "sdp": {
    "type": "offer",
    "sdp": "..."
  }
}
```

Example progress:

```json
{
  "type": "transfer-progress",
  "bytes_received": 7340032
}
```

## DataChannel

두 channel을 사용합니다.

- `control`: ordered JSON message
- `file`: ordered binary chunk

### `manifest`

Sender가 `control` channel로 보냅니다.

```json
{
  "type": "manifest",
  "transfer_id": "uuid",
  "file_name": "video.mp4",
  "file_size": 123456789,
  "mime_type": "video/mp4",
  "chunk_size": 262144,
  "chunk_count": 471,
  "hash_algorithm": null
}
```

Receiver는 metadata가 REST join 결과와 일치하는지 확인하고 저장 준비가 끝나면 `ack`를 보냅니다.

### `ack`

```json
{
  "type": "ack",
  "transfer_id": "uuid",
  "received_bytes": 12345678,
  "last_chunk_index": 123
}
```

MVP는 매 chunk ACK가 아니라 1초마다 또는 완료 시 ACK합니다.

### `progress`

```json
{
  "type": "progress",
  "transfer_id": "uuid",
  "bytes_sent": 12345678,
  "bytes_received": 12345678
}
```

현재 Web MVP는 UI와 WebSocket status 보고에 `ack`와 `transfer-progress`를 사용합니다. `progress`는 Android/Windows 호환 확장을 위해 예약되어 있습니다.

### `complete`

```json
{
  "type": "complete",
  "transfer_id": "uuid",
  "total_bytes": 123456789
}
```

Receiver는 모든 write가 끝난 뒤 `total_bytes == manifest.file_size`를 확인합니다. 다르면 실패입니다.

### `error`

```json
{
  "type": "error",
  "transfer_id": "uuid",
  "message": "reason"
}
```

## Chunk와 Backpressure

- 기본 chunk size는 `262144` bytes입니다.
- Sender는 파일 전체를 메모리에 올리지 않고 chunk 단위로 읽습니다.
- Web sender는 `File.slice()`로 chunk를 읽습니다.
- Receiver는 가능한 경우 chunk를 즉시 파일 writer에 씁니다.
- Sender는 `RTCDataChannel.bufferedAmount`와 `bufferedAmountLowThreshold`로 backpressure를 처리합니다.

## Request Missing TODO

향후 누락 chunk 재요청을 위해 아래 구조를 예약합니다. MVP에서는 ordered reliable DataChannel을 사용하므로 완전 재전송 기능은 구현하지 않습니다.

```json
{
  "type": "request-missing",
  "transfer_id": "uuid",
  "ranges": [
    { "start_chunk_index": 10, "end_chunk_index": 12 }
  ]
}
```

후속 구현 시 sender는 chunk index별 재읽기와 재전송을 지원해야 하며, receiver는 중복 chunk write를 방지하기 위한 random access writer 또는 임시 sparse file 전략이 필요합니다.

## Hash TODO

MVP는 size 검증을 필수로 사용합니다. hash 검증은 대용량 파일을 전체 메모리에 올리지 않는 incremental hash 방식으로 확장해야 합니다.

예상 확장:

```json
{
  "hash_algorithm": "sha-256",
  "hash_hex": "..."
}
```

클라이언트는 streaming/incremental hash만 사용해야 합니다.
