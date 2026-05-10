# Android Scaffold

이번 단계에서는 Android 앱을 완성 구현하지 않습니다. 후속 구현을 위한 protocol-compatible scaffold 문서만 제공합니다.

## 목표 기술

- Kotlin
- Jetpack Compose
- WebRTC Native
- Storage Access Framework

## 모듈 구조 제안

```text
android/app/
  README.md
  src/main/java/.../protocol/
    RestClient.kt
    SignalingClient.kt
    TransferMessages.kt
  src/main/java/.../webrtc/
    PeerConnectionController.kt
    DataChannelTransfer.kt
  src/main/java/.../storage/
    SafFileReader.kt
    SafFileWriter.kt
  src/main/java/.../ui/
    SendScreen.kt
    ReceiveScreen.kt
    TransferState.kt
```

## 필수 구현 규칙

- `docs/protocol.md`와 같은 REST/WebSocket/DataChannel 메시지를 사용합니다.
- 파일 본문은 WebRTC Native DataChannel로만 전송합니다.
- Sender는 SAF Uri를 `InputStream`으로 열고 chunk 단위로 읽습니다.
- Receiver는 SAF 저장 Uri를 `OutputStream`으로 열고 chunk 단위로 즉시 씁니다.
- 전체 파일을 메모리에 올리지 않습니다.
- 압축/변환/재인코딩을 하지 않습니다.
- 완료 검증은 `received_bytes == file_size`를 필수로 합니다.

## APK

이번 1차 MVP 범위는 Android 완성 앱이 아니라 scaffold/README입니다. 실제 Android 프로젝트가 추가되는 후속 단계에서는 구현 후 APK를 빌드해야 합니다.
