# Native Apps

Android와 Windows 앱은 기존 서버 protocol을 변경하지 않고 Web MVP와 같은 REST/WebSocket/WebRTC DataChannel protocol을 사용합니다.

## 공통 원칙

- 서버는 signaling, room code, metadata/status 기록만 담당합니다.
- 파일 본문, chunk, base64, Blob, ArrayBuffer는 서버에 저장하거나 WebSocket으로 중계하지 않습니다.
- 파일 bytes는 WebRTC `file` DataChannel의 binary chunk로만 전송합니다.
- `control` DataChannel은 JSON control message만 전송합니다.
- 기본 chunk size는 `/api/config.chunkSizeBytes`를 사용합니다.
- 동영상/이미지/오디오는 압축, 변환, 재인코딩하지 않습니다.
- 완료 조건은 `receivedBytes == manifest.file_size`와 writer close 완료입니다.

## Android

- Kotlin, Jetpack Compose, Native WebRTC, OkHttp, DataStore
- SAF `ACTION_OPEN_DOCUMENT` / `ACTION_CREATE_DOCUMENT`
- sender는 `InputStream`에서 chunk read
- receiver는 `OutputStream`에 chunk write
- debug APK: `android/app/build/outputs/apk/debug/app-debug.apk`
- 전송 시작부터 완료/실패/초기화까지 Foreground Service 알림으로 상태와 진행률을 갱신

## Windows

- Tauri 2 + Vite + TypeScript WebView MVP
- sender는 WebView `File.slice().arrayBuffer()` chunk read
- receiver는 File System Access API streaming writer 사용
- Rust native streaming bridge는 Phase 2 TODO
- WebView가 `showSaveFilePicker()`를 지원하지 않으면 전체 파일 메모리 fallback 없이 오류 처리

## 호환성 테스트 매트릭스

- Web sender -> Android receiver
- Android sender -> Web receiver
- Web sender -> Windows receiver
- Windows sender -> Web receiver
- Android sender -> Windows receiver
- Windows sender -> Android receiver
- Android sender -> Android receiver
- Windows sender -> Windows receiver
- 같은 Wi-Fi, LTE/5G 외부망, TURN fallback, 큰 영상 파일

확인 항목:

- 수신 파일 크기가 원본과 동일해야 합니다.
- sender는 `receiver-complete` 전까지 완료 표시하지 않아야 합니다.
- receiver는 `sender-finished`만으로 완료 처리하지 않아야 합니다.
- 서버 admin logs에는 metadata/status만 남고 파일 본문이 없어야 합니다.
