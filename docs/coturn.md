# coturn Optional Fallback

기본 연결은 STUN + direct P2P입니다. TURN/coturn은 direct P2P가 실패하는 네트워크 조합에서 선택적으로 사용합니다.

## 중요한 점

- TURN을 써도 파일은 서버 디스크에 저장되지 않습니다.
- TURN 사용 시 파일 트래픽은 TURN 서버를 경유할 수 있습니다.
- TURN은 relay 서버이며, 이 프로젝트의 FastAPI signaling 서버가 파일을 중계하는 것은 아닙니다.

## 포트

- `3478` UDP/TCP: TURN 기본 포트
- relay port range: 운영자가 제한해서 공개 권장

예:

```text
min-port=49160
max-port=49200
```

## 환경변수

```text
WEBRTC_TURN_URLS=turn:files.dcout.site:3478
WEBRTC_TURN_USERNAME=<username>
WEBRTC_TURN_CREDENTIAL=<password>
```

TURN을 사용하지 않는 기본값:

```text
WEBRTC_TURN_URLS=
WEBRTC_TURN_USERNAME=
WEBRTC_TURN_CREDENTIAL=
```

## UI 표시

클라이언트는 WebRTC `getStats()`로 selected candidate에 relay candidate가 있는지 확인하고 다음 중 하나를 표시합니다.

- `직접 P2P`
- `TURN relay`

서버에는 `connection-info` status message로 `direct_p2p`, `turn_used` metadata만 보고합니다.
