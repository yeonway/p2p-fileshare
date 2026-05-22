# Web Client

Web client는 별도 번들 없이 FastAPI가 제공하는 template/static 파일로 구현되어 있습니다. 기본 `/send`, `/receive`는 Raspberry Pi HTTP 임시 저장 모드입니다.

실제 파일:

- `server/app/templates/index.html`
- `server/app/templates/send.html`
- `server/app/templates/receive.html`
- `server/app/templates/p2p_send.html`
- `server/app/templates/p2p_receive.html`
- `server/app/static/js/stored_send.js`
- `server/app/static/js/stored_receive.js`
- `server/app/static/js/send.js`
- `server/app/static/js/receive.js`
- `server/app/static/css/style.css`

## 기본 Stored Mode

- 단일 파일 선택
- 여러 파일 선택
- 폴더 선택: `webkitdirectory`로 relative path 보존
- 전체 합계 30GB 초과 시 차단
- 업로드 실패 시 재시작 버튼으로 이미 업로드된 chunk 건너뜀
- 전송 끝나면 자동 초기화 설정 제공, 기본값 켬
- 단일 파일 수신은 원본 파일명으로 다운로드
- 여러 파일/폴더 수신은 uncompressed `.tar`로 다운로드

## PWA

- PWA manifest와 service worker를 제공합니다.
- Wake Lock API가 가능하면 전송 중 화면 잠금을 지연합니다.
- 브라우저 창이나 탭이 완전히 닫히면 Web JavaScript 실행은 중단됩니다. stored mode에서는 이미 서버에 업로드된 chunk는 재시작으로 이어갈 수 있습니다.

## QR / 링크

- 송신 화면은 Android intent URL QR을 표시합니다.
- 앱 설치 기기는 Android 앱이 열립니다.
- 앱 미설치 기기는 `https://files.dcout.site/receive?code=...` 웹 receive로 fallback합니다.
- QR SVG는 `/api/qr/svg` POST로 생성합니다.

## Hidden P2P Mode

기존 WebRTC/P2P UI는 숨겨진 고급 모드로 유지합니다.

- `/p2p/send`
- `/p2p/receive`

Windows MVP와 P2P 호환 테스트가 필요할 때만 사용합니다.
