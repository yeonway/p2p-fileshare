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

- Kotlin
- Jetpack Compose
- WebRTC Native
- Storage Access Framework
- Sender는 `InputStream` chunk read.
- Receiver는 `OutputStream` chunk write.
- 전체 파일 메모리 로딩 금지.
- Web과 같은 REST/WebSocket/DataChannel 메시지 사용.
- `sender-finished`는 송신 완료 신호일 뿐이며 수신 완료로 처리하지 않음.
- Receiver는 `receivedBytes == file_size`이고 writer close 완료 후 `receiver-complete` 전송.

## Windows

- Tauri 2 + Vite WebView MVP.
- Web과 같은 signaling/transfer protocol 사용.
- Sender는 WebView `File.slice().arrayBuffer()` chunk read.
- Receiver는 File System Access API streaming writer 사용.
- Rust native file picker/save picker와 chunk streaming bridge는 Phase 2 TODO.
- 전체 파일 메모리 로딩 금지. WebView가 streaming save를 지원하지 않으면 fallback 없이 오류 처리.

## 상호 전송 목표

- Web sender → Android receiver
- Android sender → Web receiver
- Windows sender → Android receiver
- Android sender → Windows receiver
- Web sender → Windows receiver
- Windows sender → Web receiver
- Android sender → Android receiver
- Windows sender → Windows receiver
