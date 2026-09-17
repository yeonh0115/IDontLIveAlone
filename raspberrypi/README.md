# 현관 라즈베리파이 1대

`fin_camera.py`는 같은 카메라 영상을 로컬 MJPEG와 휴대폰에 직접 연결하는 WebRTC로 제공합니다. Render는 SDP 연결 정보만 중개하며 실시간 영상 바이트를 받지 않습니다. `fin_face.py`는 로컬 영상을 인식하고 설정한 GPIO line을 3초 동안 HIGH로 만들며, Render에서 얼굴 학습 작업을 가져옵니다. 일반 기본값은 line 23이나, 2026-09-16 확인한 실제 Pi A SD의 설정은 **line 24, 인식 threshold 80, 연속 성공 2회**입니다. 해당 장치 설치에는 이 세 값을 명시해 기존 동작을 보존합니다. 얼굴인식 성공을 위험 이벤트로 저장하는 동작은 추가하지 않았습니다.

`fin_camera.py`, `fin_face.py`, `pi_runtime.py`, `webrtc_transport.py`와 `device_client/device_pairing.py`를 같은 폴더에 배치합니다. 저장소에서 직접 실행할 때는 공통 helper 폴더를 그대로 사용합니다. 카메라는 기존 `rpicam-vid`와 별도의 `rtc_env`를 사용합니다. `requirements-webrtc.txt`는 aiortc 1.15.0·PyAV 17.1.0·Flask·requests와 전체 의존성 25개를 고정합니다. 얼굴 서비스의 기존 `ai_env`, OpenCV contrib·NumPy·gpiod v2·모델·GPIO 설정은 변경하지 않습니다. 카메라 환경에는 OpenCV·NumPy·websocket-client가 필요하지 않습니다. GPIO 초기화 실패는 로그에 표시되며 얼굴인식만 실행됩니다.

이미 자동실행 서비스가 설치된 Pi는 [영상 업데이트 안내](../docs/VIDEO_UPDATE.md)의 `deployment/update_camera.py`를 사용합니다. 검증한 코드와 별도 카메라 환경을 준비하고 카메라 서비스의 실행 환경만 전환합니다. 초기 SD용 `install_camera.py`는 기존 `ai_env`를 보존하므로 WebRTC 패키지를 설치하지 않습니다. 초기 설치 후 로컬 기능은 사용할 수 있지만, 원격 WebRTC를 사용하려면 이 업데이트 절차도 완료해야 합니다. 기존 얼굴 환경에 requirements를 직접 설치하지 마세요.

## 연결코드로 등록하기

이번 설치는 사용자 요청에 따라 **EVENT_PHOTOS_ENABLED=false**입니다. 사건 사진을 저장하거나 업로드하지 않으며 촬영 예약 worker와 기존 `/trigger_event` 사진 요청도 비활성화합니다. 실시간 영상·얼굴인식·기존 얼굴 모델은 유지합니다. 아래 촬영 큐 설명은 사진 기능을 명시적으로 다시 켠 경우의 동작입니다.

새 설치는 `DEVICE_PAIRING=1`로 실행합니다. `USER_NO`를 지정하지 않으면 연결코드 방식이 기본입니다. Pi IP와 사용자 번호를 입력할 필요 없이 CAMERA 장치가 HTTPS로 등록하고, Android 앱에서 8자리 연결코드를 입력하면 서버 상태 조회가 실제 계정과 장치 ID를 돌려줍니다. 기존 `USER_NO` 값도 `DEVICE_PAIRING=1`에서는 서버가 확인한 값으로 대체합니다. 카메라와 얼굴인식 프로세스는 같은 `DEVICE_STATE_DIR`를 사용해야 합니다.

`DEVICE_STATE_DIR`는 기본 스크립트 폴더의 `device_state/`입니다. 최초 통신 전에 무작위 토큰을 Linux mode `0600`의 `credentials.json`에 저장하며 재시작·응답 유실에도 같은 토큰으로 등록합니다. 토큰을 지우거나 다른 Pi로 복사하지 마세요. `DEVICE_NAME`은 앱에 보이는 장치 이름입니다. 등록을 기다리는 동안 코드를 로그와 `pairing-code.json`에 표시합니다. 모니터가 없으면 PC에서 공통 helper의 `--state-dir ... --role CAMERA` 명령으로 미리 등록해 코드만 확인한 뒤 같은 private credential을 설치합니다. [SD 설치 안내](../deployment/SD_BOOTSTRAP.md)

paired 모드는 얼굴 작업과 HTTPS WebRTC signaling에 같은 장치 Bearer 토큰을 사용합니다. 현재 카메라 시작 경로는 WebRTC worker만 실행하며 기존 클라우드 JPEG 송신·사진 촬영 큐 worker는 실행하지 않습니다. 보존한 legacy 사진 helper의 동작 설명은 아래를 참고하세요. 실시간 영상 연결 실패를 사진 저장/업로드나 Render JPEG 중계로 전환하지 않습니다.

## 환경설정

두 프로세스에 같은 환경변수를 설정합니다. 자동실행 서비스라면 해당 서비스의 환경변수에도 적용해야 합니다.

| 변수 | 기본값 / 용도 |
| --- | --- |
| `RENDER_SERVER_URL` | `https://idontlivealone.onrender.com` |
| `STREAM_URL` | `http://127.0.0.1:5002/video_feed` — 같은 Pi의 카메라. 별도 호스트에서 실행하면 실제 주소로 변경 |
| `DEVICE_PAIRING` | USER_NO가 없으면 기본 1. 새 설치는 1로 명시; 0은 기존 수동설정 호환 모드 |
| `EVENT_PHOTOS_ENABLED` | 이번 설치 `false`. 사건 사진/촬영 큐/legacy 사진 업로드 비활성화; 실시간 영상은 유지 |
| `DEVICE_STATE_DIR`, `DEVICE_NAME` | 기본 `device_state/`, `Camera`; 두 프로세스에서 같은 저장소 사용 |
| `DEVICE_ID` | 수동 호환 모드 기본 `door-camera-a`; 연결코드 모드에서는 서버 값 사용 |
| `SOLENOID_PIN`, `FACE_THRESHOLD`, `FACE_REQUIRED_SUCCESSES` | 일반 기본 23/85/4. 실제 확인한 Pi A SD는 24/80/2로 설치 |
| `USER_NO` | 수동 호환 모드에서만 필요한 양의 정수. 연결코드 모드에서는 서버가 확인한 계정 사용 |
| `PI_DATA_DIR` | 스크립트가 있는 폴더. 상대경로도 스크립트 폴더 기준. `trainer/`, `facedata/`, `task_state/` 저장 위치 |
| `REQUESTS_CA_BUNDLE` | 선택사항. 사용자 지정 CA 인증서 파일이 필요한 환경의 HTTPS 요청에 적용 |
| `CAMERA_WIDTH`, `CAMERA_HEIGHT` | 기본 `640`, `480`. 짝수만 허용; 너비 160~1280, 높이 120~720 |
| `CAMERA_JPEG_QUALITY` | 기본 `70`, MJPEG 품질 1~100 |
| `CAMERA_CAPTURE_FPS` | 기본 `15`, 카메라 촬영 속도 1~30 fps |
| `WEBRTC_FPS` | 기본 `12`, 직접 영상 전송 1~20fps이며 촬영 FPS 이하여야 함. 촬영 FPS가 12보다 작으면 해당 정수 FPS가 기본 |
| `CAMERA_MAX_FRAME_AGE` | 기본 `0.5`초, WebRTC가 사용할 JPEG 최대 나이. 0.05~2초; 로컬 영상의 5초 제한과 별도 |

TLS 인증서 검증은 기본 활성화되어 있습니다. 신뢰할 수 있는 인증서와 정상 시스템 시간을 유지해야 합니다.

기존 수동 설정 방식의 예시입니다. 새 설치에서는 위의 연결코드 모드를 사용합니다.

```sh
export DEVICE_ID=door-camera-a
export DEVICE_PAIRING=0
export USER_NO=실제_사용자_번호
export PI_DATA_DIR=/home/사용자/기존_얼굴데이터_폴더
python3 fin_camera.py
# 같은 환경변수를 가진 별도 터미널/서비스에서:
python3 fin_face.py
```

## 기존 데이터와 재시작

기존 `trainer/trainer.yml`, `trainer/user_map.txt`, `facedata/`가 있는 폴더를 `PI_DATA_DIR`로 지정하면 기존 등록 정보를 사용합니다. 이전에 작업 디렉터리 기준으로 저장했다면 그 디렉터리를 명시적으로 지정하세요. 시작 시 데이터를 임의로 다른 폴더에서 찾아 이동하지 않습니다.

새 작업을 처음 학습하기 전에 기존 모델을 `trainer/legacy_base.yml`로 복사하고 `task_model_migration.json`을 기록합니다. 이후 모델은 이 고정된 기존 모델과 `facedata/<정수ID>/task_<작업해시>/`의 작업별 데이터로 다시 구성합니다. 기존 모델이 없었던 환경은 기존 얼굴 파일과 작업별 파일 전체를 사용합니다. 따라서 같은 작업을 다시 받아도 학습 샘플이 중복 누적되지 않습니다. 기존 얼굴 파일도 덮어쓰지 않습니다. 이 기준 모델·마이그레이션 기록·얼굴 데이터·완료 기록은 함께 보존하세요.

처리 중인 새 샘플은 숨김 staging 폴더에서 현재 작업에만 사용합니다. 모델과 샘플 폴더를 함께 적용할 때 복구 journal과 이전 모델 backup을 남기며, 적용 중 실패·재시작은 이전 상태로 되돌립니다. 실패하거나 임대를 잃은 작업의 샘플이 다음 사용자 학습에 섞이지 않습니다. 미완료 복구 파일은 임의로 삭제하지 마세요.

`task_state/outbox/`는 서버가 성공 ACK를 보낼 때까지 결과를 보관합니다. 재시작하면 미전송 결과를 먼저 처리합니다. `receipts/`는 완료된 학습을 다시 실행하지 않도록 보관하며, 오래된 lease의 결과는 `quarantine/`에 남고 로그로 알립니다. 결과 전송이 끝나기 전에는 다음 작업을 가져오지 않습니다.

서버가 결과를 HTTP 400/404/410/422로 영구 거절하면 원본 결과 JSON을 `quarantine/`에 보존하고 작업 ID·HTTP 코드를 로그에 남긴 뒤 다음 작업을 처리합니다. HTTP 409도 오래된 lease로 격리합니다. HTTP 403은 장치/권한 설정 문제이므로 격리하지 않고 결과를 유지하며 재시도합니다. 이때는 `DEVICE_ID`와 서버 허용 장치를 고칠 때까지 새 작업 폴링을 보류합니다. 네트워크 오류·429·5xx도 결과를 보존하고 재시도합니다.

작업 계약은 `GET /api/get-task?device_id=...&user_no=...`, `POST /api/result`, `POST /api/face/tasks/{task_id}/lease`입니다. 120초 임대를 30초 간격으로 갱신합니다. 결과의 HTTP 200과 JSON `status=success`를 모두 확인해야 ACK로 처리합니다. HTTP 409는 이미 대체된 lease입니다. 이미지의 상대 URL은 Render 주소와 결합합니다.

로컬 모델 적용 전에도 claim/갱신 요청 **시작** 시각부터 120초를 단조 시계로 검사합니다. 따라서 네트워크 단절로 409를 받지 못했더라도 유효기간을 넘긴 작업은 모델에 적용하지 않습니다. 저장된 outbox 재전송은 모델을 다시 학습하지 않습니다.

## 이벤트 사진 및 영상

기존 다른 Pi의 `GET http://카메라Pi:5002/trigger_event` 호출을 유지합니다. 기존 이벤트 ID가 있다면 `?log_id=기존_ID`를 붙이면 서버까지 그대로 전달됩니다. 생략하면 UUID를 만듭니다. `log_id`는 영문·숫자·밑줄·하이픈 1~100자입니다. 계정은 프로세스의 `USER_NO`만 사용하며 URL 쿼리로 바꿀 수 없습니다. `USER_NO`가 없으면 명확한 설정 오류를 반환합니다.

성공 응답은 기존 `status`, `image_url`과 추가 `log_id`를 포함합니다. 서버에는 multipart `file`, `userNo`, `log_id`, `date`를 보냅니다. 이 엔드포인트를 얼굴인식 성공에 자동 연결하지 않았습니다.

새 Pi B는 `expected_user_no`도 전달합니다. 이 값이 Pi A의 `USER_NO`와 다르면 업로드 전에 403으로 거부하며, 일치하면 성공 응답에 `user_no`를 포함합니다. 기존 호출처럼 이 검증값을 생략할 수 있지만, 쿼리로 실제 업로드 계정을 바꾸지는 못합니다.

`/video_feed`, `/snapshot`, `/` 주소와 포트 5002는 유지합니다. 카메라 출력은 Linux의 `select`와 nonblocking read로 읽습니다. 프로세스가 종료되거나 JPEG 시작·끝 경계가 갖춰진 프레임이 10초 동안 없으면 이전 프레임을 지우고, 자식 프로세스를 종료·회수한 뒤 2초 후 다시 시작합니다. 부분 출력·JPEG 경계 없는 출력·크기 제한을 넘는 프레임은 감시 시간을 연장하지 않으며, 정상 프레임은 별도 대기 없이 게시합니다. 이미지 디코딩 검사는 기존 WebRTC·얼굴인식 처리에서 수행합니다. 종료 요청 후 2초에도 자식이 남아 있으면 강제 종료하고 최대 2초 더 회수하며, 회수에 실패하면 카메라를 중복 실행하지 않고 오류를 기록합니다. 5초 이상 갱신되지 않은 영상은 현재 프레임으로 반환하지 않습니다. 원격 직접 연결이 종료되면 앱의 새 offer로 다시 협상합니다.

기본 영상은 **640×480, JPEG 품질 70, 촬영 15fps, WebRTC 최대 12fps**입니다. 촬영 스레드는 stdout을 계속 읽으며 최신 JPEG 한 장만 보관합니다. WebRTC track은 이 프레임을 PyAV로 디코딩하고 aiortc에 전달합니다. 디코딩·HTTPS 요청은 asyncio 이벤트 루프 밖에서 처리하며 새 카메라 장치를 중복해서 열지 않습니다. 프레임 저장 대기열은 없고, 512 KiB를 넘거나 오래된 JPEG는 사용하지 않습니다. aiortc의 인코딩은 별도 처리되며 실제 Pi4 CPU 부하·화질·모바일망 지연은 장치 시험이 필요합니다.

`GET /api/rtc/camera/next`를 2초 간격으로 조회하며 동일 offer 협상은 중복 실행하지 않습니다. 새 offer는 이전 peer를 닫습니다. full SDP answer만 서버에 보내며 trickle ICE·TURN·클라우드 JPEG fallback은 사용하지 않습니다. Google STUN `stun:stun.l.google.com:19302`를 사용합니다. 학교망에서 과거 연결에 성공했더라도 현재 NAT/방화벽 경로의 직접 연결 성공은 실제 Pi와 휴대폰으로 확인해야 합니다. [전체 계약](../docs/WEBRTC_CONTRACT.md)

활성 세션 상태를 10초 간격으로 확인하며 CLOSED/EXPIRED·권한 철회·서버 재시작으로 세션을 잃으면 peer를 닫습니다. 상태를 확인할 수 없는 동안은 별도 감시 태스크가 마지막 확인부터 최대 45초와 서버 `expiresAt` 중 빠른 시점을 적용합니다. 긴 HTTP 요청이 이 감시를 막지 않습니다. PENDING 협상은 최대 90초, READY는 최대 30분으로 제한합니다. 요청 실패 후 동일 answer 재시도는 같은 SDP를 사용합니다. 로컬 촬영과 얼굴 인식은 인터넷 연결이나 WebRTC 패키지 유무와 독립적으로 계속 실행합니다.

`GET http://127.0.0.1:5002/video_status`는 `transport=webrtc`, `runtimeAvailable`, `signalingReady`, `state`, `error`, `fps`, `framesSent`, `turnConfigured=false`를 반환합니다. runtimeAvailable은 패키지 import 성공, signalingReady 및 `[webrtc] signaling ready` 로그는 신호 API에 도달했음을 뜻합니다. 실제 휴대폰 영상 연결 완료는 `state=connected`와 휴대폰의 영상 수신으로 별도 확인합니다. 세션 UUID·토큰·SDP·ICE 사설 주소는 상태나 로그에 출력하지 않습니다. 패키지가 없으면 `unavailable`과 예외 종류만 기록하며 로컬 기능은 유지합니다.

얼굴 검출 입력은 영상 해상도와 관계없이 종횡비를 유지한 최대 **160×120**으로 제한합니다. 기본 640×480 영상도 기존 320×240 영상과 같은 검출 입력 크기를 사용하며 학습 이미지 처리·모델·인식 threshold·GPIO 설정은 바꾸지 않습니다. 720p 설정은 별도 성능 확인 후 선택하세요.

## 하드웨어 없는 검증

### DNS/VPN 관련 실제 Pi 진단

Pi A를 켠 뒤 기존 `ai_env`와 실행 서비스에 적용할 환경변수로 다음을 실행할 수 있습니다.

```sh
python3 network_diagnostics.py
```

표준 Python만 사용하며 DNS/VPN/proxy/인증서 설정을 바꾸지 않습니다. 환경변수는 값 없이 설정 여부만, 오류는 원문 대신 종류/번호만 출력합니다. 계정·비밀번호·토큰·사진·서버 응답본문을 출력하지 않습니다. `/api/get-task`처럼 작업을 소비하는 API나 업로드를 호출하지 않습니다.

`camera_snapshot_head`는 로컬 카메라의 `/snapshot`에 HEAD 요청을 보내며, 200은 최신 프레임 준비, 503은 카메라 프레임 미준비를 뜻합니다. `cloud_system_dns`는 현재 시스템 DNS를 사용합니다. 그다음 IPv4/IPv6별 TCP/TLS를 확인하고, 현재 proxy 설정을 사용하는 HTTP HEAD 결과를 별도로 출력합니다. TLS 검증은 유지합니다. `/`의 404도 HTTP 도달에는 성공한 것이며 DB 정상 여부를 의미하지 않습니다. 프록시의 WebSocket 업그레이드 허용 여부는 이 검사만으로 확정하지 않습니다.

이 검사는 표준 `urllib`의 proxy 지원을 사용하므로 `ALL_PROXY`/SOCKS와 실제 `requests`·`websocket-client`의 동작이 다를 수 있습니다. 출력된 환경변수 설정 여부와 실제 서비스 환경을 함께 확인해야 합니다.

로컬 카메라만 실패하면 DNS/VPN보다 카메라 프로세스·포트·`STREAM_URL`을 먼저 확인합니다. DNS만 실패하고 proxy를 통한 HTTP는 성공하면 프록시가 DNS를 대신 처리하는 환경일 수 있습니다. TCP 실패는 경로/방화벽, TLS 실패는 시간/인증서/중간 프록시, HTTP 응답을 받은 뒤의 서버 오류는 앱/DB 계층을 나눠 확인해야 합니다. 이 결과 없이 DNS 교체나 VPN 사용이 필요하다고 단정하지 않습니다.

```sh
python3 -m unittest discover -s tests -v
```

일반 검증은 표준 Python과 대체 객체를 사용하며 카메라·GPIO·운영 API를 호출하지 않습니다. WebRTC 패키지가 없는 환경에서는 실제 peer 테스트 하나만 건너뜁니다. camera 전용 환경에서 같은 명령을 실행하면 합성 JPEG를 실제 aiortc peer 두 개 사이에 전달하여 640×480 수신을 검증합니다. 해당 테스트는 STUN 서버·Render를 호출하지 않고 호스트 후보만 사용합니다. 중복 offer, 새 세션 교체, answer 응답 유실, 권한 상실, 만료 및 블로킹 HTTP 중에도 peer가 종료되는지 검증합니다. requirements의 전체 25개 패키지는 CPython3.13 ARM64용 binary-only 다운로드까지 확인했습니다. 실제 Pi의 촬영 속도·얼굴 인식 정확도·GPIO·서비스 자동시작·학교 Wi-Fi→휴대폰 LTE 연결은 별도 장치 검증이 필요합니다.
