# Android MVP

Kotlin + Jetpack Compose + Native WebRTC 기반 Android MVP입니다. 서버는 기존 FastAPI signaling 서버를 그대로 사용하며, 파일 본문은 서버나 WebSocket을 거치지 않고 WebRTC `file` DataChannel binary chunk로만 전송합니다.

## 구현 상태

- REST: `/api/config`, `/api/rooms`, `/api/rooms/join`
- WebSocket: signaling/status JSON only
- DataChannel: `control` JSON channel + `file` binary channel
- 파일 선택: Storage Access Framework `ACTION_OPEN_DOCUMENT` / `ACTION_OPEN_DOCUMENT_MULTIPLE`
- 파일 저장: Storage Access Framework `ACTION_CREATE_DOCUMENT`
- sender: `InputStream` chunk read + `DataChannel.bufferedAmount()` backpressure
- receiver: `OutputStream` chunk write + size validation
- 다중 파일 전송: 선택한 파일들을 압축하지 않은 `.tar` 묶음으로 스트리밍
- 완료 조건:
  - sender는 `receiver-complete` 전까지 완료 처리하지 않음
  - receiver는 `receivedBytes == manifest.file_size`이고 writer close 완료 후 `receiver-complete` 전송
  - `sender-finished`는 수신 완료 신호로 처리하지 않음
- Foreground Service:
  - 전송 준비/진행/검증 단계에서 알림을 표시하고 진행률을 갱신
  - 완료/실패/초기화 시 알림 종료
- 딥링크:
  - `sendhoney://receive?code=123456`
  - `https://files.dcout.site/receive?code=123456`
  - QR 또는 링크로 열면 받기 화면에 코드가 자동 입력됨
- 백그라운드 허용:
  - Android 13+ 알림 권한 요청
  - 배터리 최적화 예외 요청
- UI: 한국어, 보내기/받기/설정, 6자리 코드, 진행률, 속도, ETA, 연결 상태, 실패 사유

## 빌드

Android SDK가 설치되어 있고 `ANDROID_HOME`이 SDK 경로를 가리켜야 합니다. 이 작업 환경에서는 다음 경로를 사용했습니다.

```powershell
$env:ANDROID_HOME="$env:LOCALAPPDATA\Android\Sdk"
cd android
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

APK 경로:

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

## 서버 URL

설정 화면에서 변경할 수 있습니다.

- 기본값: `https://files.dcout.site`
- 보조 예시: `https://files.sexyminup.site`
- 개발용 예외: `http://127.0.0.1:<port>`, `http://localhost:<port>`

## 제한사항 / TODO

- 사용자가 앱을 강제 종료하거나 시스템이 프로세스를 종료하면 진행 중인 WebRTC 전송은 복구되지 않습니다.
- hash 검증은 TODO입니다. 현재 MVP 검증은 `receivedBytes == file_size`입니다.
- `request-missing` chunk 재전송은 protocol slot만 유지하고 구현하지 않았습니다.
- direct/TURN 표시는 WebRTC stats 기반 best-effort입니다.
