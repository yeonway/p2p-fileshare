# Common Protocol

This protocol is shared by the Web, Android, and Windows clients.

## Core Rules

- The server never stores file bodies, chunks, base64 file data, Blob data, or ArrayBuffer data.
- The server never relays file bodies over WebSocket.
- File bytes are transferred only through WebRTC `RTCDataChannel`.
- The `control` DataChannel carries ordered JSON messages.
- The `file` DataChannel carries ordered binary chunks.
- Control and file channels are independent; ordering is not guaranteed across the two channels.
- Files are transferred as original binary bytes. No compression, conversion, decoding, or re-encoding is allowed.
- MVP integrity validation is `received_bytes == file_size`.
- Hash validation is a future incremental-hash extension.

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
  "chunkSizeBytes": 65536
}
```

Default transfer mode is STUN + direct P2P. TURN/coturn is an optional fallback.

### `POST /api/rooms`

Sender registers metadata only. Do not send file bodies to this endpoint.

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

Receiver resolves a 6-digit code to metadata.

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

`role` is `sender` or `receiver`.

Allowed WebSocket message types:

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

Forbidden WebSocket message types:

- `file-chunk`
- `file-data`
- `base64-file`
- `binary-upload`

Server behavior:

- Only JSON objects are allowed.
- Binary frames are rejected immediately and recorded in `security_events`.
- Forbidden message types are rejected and recorded in `security_events`.
- The server relays signaling/status messages only.
- The server never logs or stores file body/chunk contents.

## DataChannel

Two channels are used:

- `control`: ordered JSON messages
- `file`: ordered binary chunks

### `manifest`

Sender sends this on the `control` channel before file chunks.

```json
{
  "type": "manifest",
  "transfer_id": "uuid",
  "file_name": "video.mp4",
  "file_size": 123456789,
  "mime_type": "video/mp4",
  "chunk_size": 65536,
  "chunk_count": 1884,
  "hash_algorithm": null
}
```

Receiver confirms metadata and sends an initial `ack` when storage is ready.

### `ack`

Receiver sends ACKs every 1 second, every fixed chunk interval, and at final byte count.

```json
{
  "type": "ack",
  "transfer_id": "uuid",
  "received_bytes": 12345678,
  "last_chunk_index": 123
}
```

Sender progress UI is based on receiver ACKs, not local queued bytes.

### `sender-finished`

Sender sends this only after:

- all `File.slice()` chunks have been sent to the `file` channel, and
- `fileChannel.bufferedAmount` has drained to 0 or a low threshold.

```json
{
  "type": "sender-finished",
  "transfer_id": "uuid",
  "total_bytes": 123456789,
  "last_chunk_index": 1883
}
```

This message means only that the sender finished queueing data. It is not a receiver completion signal. Because `control` and `file` channels do not share ordering, this message can arrive before the final binary chunk.

If receiver has `received_bytes < manifest.file_size` when `sender-finished` arrives, it must wait for remaining file chunks. It fails only after the active-transfer idle timeout passes with no additional received bytes.

### `receiver-complete`

Receiver sends this only after:

- `received_bytes == manifest.file_size`
- all chunk writes have completed
- File System Access API writer has been closed, or Blob fallback has all chunks available

```json
{
  "type": "receiver-complete",
  "transfer_id": "uuid",
  "total_bytes": 123456789,
  "last_chunk_index": 1883
}
```

Sender must not show final completion before `receiver-complete`.

Server `transfer-completed` status is reported by the receiver after size verification succeeds.

### `error`

```json
{
  "type": "error",
  "transfer_id": "uuid",
  "message": "reason"
}
```

## Chunk And Backpressure

- Default chunk size is `65536` bytes.
- Sender never loads the full file into memory.
- Web sender reads chunks with `File.slice()`.
- Receiver writes each chunk immediately when File System Access API is available.
- Sender uses `RTCDataChannel.bufferedAmount` and `bufferedAmountLowThreshold`.
- Web client checks `pc.sctp.maxMessageSize` when available and lowers the chunk size if needed.

## Request Missing TODO

Future missing-chunk retry shape:

```json
{
  "type": "request-missing",
  "transfer_id": "uuid",
  "ranges": [
    { "start_chunk_index": 10, "end_chunk_index": 12 }
  ]
}
```

MVP relies on ordered reliable DataChannel and does not implement full chunk retry/resume.

## Hash TODO

MVP uses size validation. Hash validation must be implemented with streaming/incremental hashing, never by loading the full file into memory.

Future shape:

```json
{
  "hash_algorithm": "sha-256",
  "hash_hex": "..."
}
```

## Manual Regression Test: Early `sender-finished`

Use this for TURN or slow external-network validation.

1. Open `/send` and `/receive`.
2. Transfer a large file over an external network or TURN relay.
3. Confirm receiver does not fail immediately if `sender-finished` arrives while `received_bytes < file_size`.
4. Confirm receiver displays a waiting state until remaining chunks arrive.
5. Confirm completion happens only after `received_bytes == file_size` and writer close completes.
6. Confirm timeout failure includes `expected size`, `received size`, and `missing bytes`.
