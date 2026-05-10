# Client Compatibility

모든 클라이언트는 `docs/protocol.md`를 기준으로 구현합니다.

## Web

- REST API로 room 생성/join/config 조회.
- WebSocket으로 signaling/status JSON만 전송.
- WebRTC `control` / `file` DataChannel 사용.
- Sender는 `File.slice()`로 chunk 읽기.
- Receiver는 File System Access API 지원 시 `createWritable()`로 즉시 write.
- 미지원 브라우저는 Blob fallback 사용.

## Android

후속 구현 기준:

- Kotlin
- Jetpack Compose
- WebRTC Native
- Storage Access Framework
- Sender는 `InputStream` chunk read.
- Receiver는 `OutputStream` chunk write.
- 전체 파일 메모리 로딩 금지.
- Web과 같은 REST/WebSocket/DataChannel 메시지 사용.

## Windows

후속 구현 기준:

- Tauri 권장.
- Web과 같은 signaling/transfer protocol 사용.
- native file picker/save picker.
- 파일 read/write는 chunk streaming bridge 사용.
- 전체 파일 메모리 로딩 금지.

## 상호 전송 목표

- Web sender → Android receiver
- Android sender → Web receiver
- Windows sender → Android receiver
- Android sender → Windows receiver
- Web sender → Windows receiver
