# Web Client

1차 MVP의 Web client는 별도 번들러 없이 FastAPI가 제공하는 template/static 파일로 구현되어 있습니다.

실제 파일:

- `server/app/templates/index.html`
- `server/app/templates/send.html`
- `server/app/templates/receive.html`
- `server/app/static/manifest.webmanifest`
- `server/app/static/sw.js`
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

## 설치형 웹앱

- Web client는 PWA manifest와 service worker를 제공해 브라우저에서 설치할 수 있습니다.
- 전송 중에는 Wake Lock API가 가능한 환경에서 화면 잠금을 지연하고, 창 닫기/새로고침 전에 경고합니다.
- 브라우저 정책상 창이 완전히 닫히면 WebRTC 전송을 계속할 수 없습니다.

## QR / 링크

- Sender는 방 생성 후 Android 앱을 여는 QR과 일반 `/receive?code=...` 웹 링크를 표시합니다.
- Receiver는 `/receive?code=123456` 형태의 URL로 열리면 코드 입력칸을 자동으로 채우고 파일 정보를 조회합니다.
- QR SVG는 `/api/qr/svg` POST로 즉시 생성하며, 파일 본문이나 chunk는 전송하지 않습니다.

## 제한사항

- File System Access API 미지원 브라우저나 `createWritable()`이 차단되는 모바일 컨텍스트는 Blob fallback을 사용합니다.
- Blob fallback은 완료 전까지 메모리에 chunk를 보관하므로 대용량 파일에 적합하지 않습니다.
- hash 검증과 chunk 재전송은 TODO입니다.
