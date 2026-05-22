# Native Apps

Android는 기본 stored transfer 모드로 전환했습니다. Windows는 이번 범위에서 기존 P2P MVP를 유지합니다.

## Android

- Kotlin, Jetpack Compose, OkHttp, DataStore, Storage Access Framework
- 기본 서버 URL: `https://files.dcout.site`
- 보내기: SAF `ACTION_OPEN_DOCUMENT` / `ACTION_OPEN_DOCUMENT_MULTIPLE`
- 받기: SAF `ACTION_CREATE_DOCUMENT`
- 업로드: stored manifest 등록 후 raw chunk PUT
- 다운로드: stored join 후 HTTP stream 저장
- 단일 파일은 원본 파일명으로 저장
- 다중 파일/폴더는 uncompressed `.tar`로 저장
- Foreground Service로 업로드/다운로드 진행률 알림 표시
- 실패 시 재시작 버튼 제공
- 완료 후 자동 초기화 설정은 DataStore에 저장
- QR/딥링크: `sendhoney://receive?code=...` 또는 HTTPS receive 링크를 열면 코드 자동 입력

Android 폴더 트리 선택은 v1 필수가 아닙니다. 현재는 여러 파일 선택을 우선 지원하고, 폴더 트리 선택은 후속 작업입니다.

## Windows

- Tauri 2 + Vite + TypeScript WebView MVP
- 현재는 기존 WebRTC/P2P protocol 유지
- 수신 native save bridge는 있으나 송신은 WebView `File.slice()` 기반
- 기본 stored transfer 전환은 후속 작업

## 테스트 매트릭스

- Web stored sender -> Android stored receiver
- Android stored sender -> Web stored receiver
- Android stored sender -> Android stored receiver
- Web stored sender -> Web stored receiver
- Web hidden P2P sender -> Windows P2P receiver
- Windows P2P sender -> Web hidden P2P receiver

확인 항목:

- 30GB 초과 manifest 거부
- 여러 파일과 폴더 relative path 보존
- 단일 파일 원본 stream 저장
- 다중 파일/폴더 tar stream 저장
- 네트워크 중단 후 재시작 버튼 동작
- 첫 정상 다운로드 후 서버 파일 삭제
- Android 앱 링크 code 자동 입력
