# Android App Module

`site.sexyminup.p2pfileshare` Android app module입니다.

주요 코드 위치:

- `data/`: REST API, 설정 저장
- `signaling/`: OkHttp WebSocket signaling
- `webrtc/`: Native WebRTC `PeerConnection` / `DataChannel`
- `transfer/`: protocol message, SAF file metadata, formatting
- `ui/`: Compose UI와 전송 상태 ViewModel

빌드:

```powershell
cd android
.\gradlew.bat :app:assembleDebug
```

APK:

```text
android/app/build/outputs/apk/debug/app-debug.apk
```
