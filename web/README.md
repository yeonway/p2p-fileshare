# Web Client

1차 MVP의 Web client는 별도 번들러 없이 FastAPI가 제공하는 template/static 파일로 구현되어 있습니다.

실제 파일:

- `server/app/templates/index.html`
- `server/app/templates/send.html`
- `server/app/templates/receive.html`
- `server/app/static/js/common.js`
- `server/app/static/js/webrtc_protocol.js`
- `server/app/static/js/send.js`
- `server/app/static/js/receive.js`
- `server/app/static/css/style.css`

## Protocol

Web client는 `docs/protocol.md`를 따릅니다.

- REST API로 metadata만 서버에 전송합니다.
- WebSocket은 JSON signaling/status만 사용합니다.
- 파일 bytes는 `RTCDataChannel` `file` channel로만 전송합니다.
- Sender는 `File.slice()`로 chunk를 읽습니다.
- Receiver는 가능한 경우 File System Access API로 chunk를 즉시 저장합니다.

## 제한사항

- File System Access API 미지원 브라우저는 Blob fallback을 사용합니다.
- Blob fallback은 완료 전까지 메모리에 chunk를 보관하므로 대용량 파일에 적합하지 않습니다.
- hash 검증과 chunk 재전송은 TODO입니다.
