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

- Tauri 앱의 수신 저장은 native save picker와 Rust `BufWriter<File>` chunk write를 사용합니다.
- 송신 쪽은 아직 WebView `File.slice()` 기반입니다.
- 브라우저 fallback은 File System Access API를 우선 사용하고, 미지원 시 Blob download fallback을 사용합니다.
- Blob fallback은 완료 전까지 chunk를 메모리에 보관하므로 대용량 파일에는 적합하지 않습니다.
