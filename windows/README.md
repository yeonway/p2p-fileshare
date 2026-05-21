# Windows Tauri WebView MVP

Tauri 2 + Vite + TypeScript 기반 Windows MVP입니다. 이번 단계는 WebView File API 기반 구현이며, Rust native streaming bridge는 Phase 2 TODO로 남깁니다.

## 구현 상태

- REST: `/api/config`, `/api/rooms`, `/api/rooms/join`
- WebSocket: signaling/status JSON only
- WebRTC: `control` JSON DataChannel + `file` binary DataChannel
- sender: WebView `File.slice().arrayBuffer()` chunk read
- receiver: `showSaveFilePicker()` + `FileSystemWritableFileStream.write()` chunk write
- 완료 조건:
  - sender는 `receiver-complete` 전까지 완료 처리하지 않음
  - receiver는 `receivedBytes == manifest.file_size`이고 writer close 완료 후 `receiver-complete` 전송
  - `sender-finished`는 수신 완료 신호로 처리하지 않음

## 실행

Vite WebView MVP 확인:

```powershell
cd windows
npm install
npm run dev
```

브라우저 또는 Tauri WebView에서 `http://127.0.0.1:1420`을 엽니다.

정적 빌드:

```powershell
npm run build
```

Tauri 데스크톱 패키징:

```powershell
npm run tauri:build
```

현재 작업 환경에서는 Rust/Cargo가 PATH에 없어 `npm run tauri:build`가 `program not found`로 실패했습니다. Tauri 패키징에는 Rust toolchain과 Windows WebView2 빌드 환경이 필요합니다.

## 서버 URL

설정 화면에서 변경할 수 있습니다.

- 기본값: `https://files.dcout.site`
- 보조 예시: `https://files.sexyminup.site`
- 개발용 예외: `http://127.0.0.1:<port>`, `http://localhost:<port>`

## 제한사항 / Phase 2 TODO

- Rust native streaming bridge는 아직 없습니다.
- 수신 저장은 WebView가 File System Access API `showSaveFilePicker()`를 지원해야 합니다. 지원하지 않으면 전체 파일 메모리 fallback을 하지 않고 명확한 오류를 표시합니다.
- native file picker/save picker와 Rust side chunk read/write bridge는 Phase 2에서 구현합니다.
- hash 검증과 `request-missing` chunk 재전송은 TODO입니다.
