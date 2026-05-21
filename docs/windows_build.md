# Windows Build

## Requirements

- Node.js 22 또는 호환 버전
- npm
- Tauri desktop packaging용 Rust/Cargo
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

현재 검증 환경에서는 Cargo가 없어 `cargo metadata` 실행 단계에서 실패했습니다. Rust toolchain 설치 후 다시 실행해야 합니다.

## Manual Test

1. `npm run dev` 또는 Tauri dev로 Windows 앱을 실행합니다.
2. Windows sender -> Web receiver를 테스트합니다.
3. Web sender -> Windows receiver를 테스트합니다.
4. Windows sender -> Android receiver를 테스트합니다.
5. Android sender -> Windows receiver를 테스트합니다.
6. 수신 파일 크기가 원본과 같은지 확인합니다.

## Known Limits

- 이번 단계는 WebView File API 기반 MVP입니다.
- Rust native streaming bridge는 Phase 2 TODO입니다.
- 수신 저장은 `showSaveFilePicker()` 지원이 필요합니다.
- 지원하지 않는 WebView에서는 전체 파일 메모리 fallback 없이 오류를 표시합니다.
