# Windows Scaffold

이번 단계에서는 Windows 앱을 완성 구현하지 않습니다. 후속 구현을 위한 protocol-compatible scaffold 문서만 제공합니다.

## 목표 기술

- Tauri 권장
- WebRTC
- native file picker/save picker
- chunk streaming read/write bridge

## 모듈 구조 제안

```text
windows/
  src/
    protocol/
      rest_client.ts
      signaling_client.ts
      transfer_messages.ts
    webrtc/
      peer_connection.ts
      datachannel_transfer.ts
    native/
      file_reader.rs
      file_writer.rs
    ui/
      SendView.tsx
      ReceiveView.tsx
```

## 필수 구현 규칙

- `docs/protocol.md`와 같은 REST/WebSocket/DataChannel 메시지를 사용합니다.
- 파일 본문은 WebRTC DataChannel로만 전송합니다.
- 파일은 native side에서 chunk 단위로 읽고 씁니다.
- 전체 파일을 메모리에 올리지 않습니다.
- 압축/변환/재인코딩을 하지 않습니다.
- 완료 검증은 `received_bytes == file_size`를 필수로 합니다.

## 제한사항

Tauri에서 WebRTC와 native streaming bridge를 어떻게 묶을지는 후속 구현에서 확정합니다. 이번 단계는 server/web MVP와 protocol 문서 확정이 범위입니다.
