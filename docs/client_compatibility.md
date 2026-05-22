# Client Compatibility

모든 클라이언트는 `docs/protocol.md`를 기준으로 구현합니다. 단, Windows MVP는 이번 범위에서 기존 P2P 모드를 유지합니다.

## Web

- 기본 `/send`, `/receive`는 stored transfer API를 사용합니다.
- 단일 파일, 여러 파일, `webkitdirectory` 폴더 선택을 지원합니다.
- 폴더 선택 시 browser가 제공하는 relative path를 manifest에 넣고 서버가 sanitize합니다.
- 업로드 실패 시 재시작 버튼으로 `/status`를 조회하고 이미 올라간 chunk는 건너뜁니다.
- 수신 단일 파일은 원본 stream으로 저장합니다.
- 수신 다중 파일/폴더는 uncompressed `.tar`로 저장합니다.
- 단일 파일 다운로드는 Range 기반 재시도를 지원합니다.
- tar 다운로드 재시작은 v1에서 처음부터 다시 받습니다.
- File System Access API가 없으면 Blob fallback을 사용하되 대용량에는 적합하지 않습니다.

## Android

- 기본 보내기/받기는 stored transfer API를 사용합니다.
- SAF로 단일/다중 파일 선택을 지원합니다.
- Android SAF 제약 때문에 v1 폴더 트리 선택은 후속 작업입니다.
- Foreground Service 알림으로 업로드/다운로드 진행률을 표시합니다.
- 실패 시 재시작 버튼을 제공합니다.
- 자동 초기화 설정을 DataStore에 저장합니다.
- `sendhoney://receive?code=...`와 HTTPS `/receive?code=...` 링크를 stored receive 화면으로 처리합니다.

## Windows

- Windows MVP는 현재 기존 P2P 모드를 유지합니다.
- 기본 Web/Android stored mode와 직접 호환되지 않습니다.
- Windows 전환은 후속 작업입니다.
- Windows와 Web을 P2P로 테스트하려면 Web의 숨겨진 `/p2p/send`, `/p2p/receive`를 사용합니다.

## 현재 상호 전송 목표

- Web stored sender -> Web stored receiver
- Web stored sender -> Android stored receiver
- Android stored sender -> Web stored receiver
- Android stored sender -> Android stored receiver
- Windows P2P sender -> Web `/p2p/receive`
- Web `/p2p/send` -> Windows P2P receiver
