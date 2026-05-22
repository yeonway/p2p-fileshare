# Windows Build

Windows MVP는 이번 범위에서 기존 WebRTC/P2P 모드를 유지합니다. 기본 Web/Android stored mode와 직접 호환되지 않으며, Windows stored mode 전환은 후속 작업입니다.

## Requirements

- Node.js 22 또는 호환 버전
- npm
- Tauri desktop packaging은 Rust/Cargo 필요
- Windows WebView2 runtime

## WebView MVP

```powershell
cd windows
npm install
npm run dev
```

Vite dev server:

```text
http://127.0.0.1:1420
```

정적 빌드:

```powershell
npm run build
```

## Tauri Packaging

```powershell
npm run tauri:build
```

Tauri 패키징은 Rust toolchain과 Cargo가 필요합니다.

## Manual Test

1. `npm run dev` 또는 Tauri dev로 Windows 앱을 실행합니다.
2. Web hidden P2P `/p2p/send` -> Windows receiver를 테스트합니다.
3. Windows sender -> Web hidden P2P `/p2p/receive`를 테스트합니다.
4. 수신 파일 크기가 원본과 같은지 확인합니다.

## Known Limits

- Windows는 아직 stored transfer API를 기본으로 사용하지 않습니다.
- 수신은 native save bridge가 있지만 송신은 WebView `File.slice()` 기반입니다.
- Tauri 패키징은 Rust/Cargo 환경이 필요합니다.
