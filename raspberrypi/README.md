# 현관 라즈베리파이 1대

`fin_camera.py`는 카메라를 촬영해 Render 웹소켓과 로컬 MJPEG로 보냅니다. `fin_face.py`는 로컬 영상을 인식하고 GPIO line 23을 3초 동안 HIGH로 만들며, Render에서 얼굴 학습 작업을 가져옵니다. GPIO 배선은 기존 회로를 기준으로 확인해야 합니다. 얼굴인식 성공을 위험 이벤트로 저장하는 동작은 추가하지 않았습니다.

세 파일 `fin_camera.py`, `fin_face.py`, `pi_runtime.py`를 같은 폴더에 배치합니다. 실행에는 기존 환경의 `rpicam-vid`, OpenCV의 `cv2.face`(contrib), NumPy, requests, Flask, websocket-client, gpiod v2가 필요합니다. GPIO 초기화 실패는 로그에 표시되며 얼굴인식만 실행됩니다.

## 환경설정

두 프로세스에 같은 환경변수를 설정합니다. 자동실행 서비스라면 해당 서비스의 환경변수에도 적용해야 합니다.

| 변수 | 기본값 / 용도 |
| --- | --- |
| `RENDER_SERVER_URL` | `https://idontlivealone.onrender.com` |
| `STREAM_URL` | `http://127.0.0.1:5002/video_feed` — 같은 Pi의 카메라. 별도 호스트에서 실행하면 실제 주소로 변경 |
| `DEVICE_ID` | `door-camera-a` — 서버에 허용된 장치 ID와 일치해야 함 |
| `USER_NO` | 기본값 없음. 이벤트 업로드에 필요한 실제 계정의 양의 정수 사용자 번호. 설정하면 학습 작업 폴링도 해당 번호로 제한 |
| `PI_DATA_DIR` | 스크립트가 있는 폴더. 상대경로도 스크립트 폴더 기준. `trainer/`, `facedata/`, `task_state/` 저장 위치 |
| `REQUESTS_CA_BUNDLE` | 선택사항. 사용자 지정 CA 인증서 파일이 필요한 환경에서 설정. HTTPS와 WSS에 적용 |
| `SSL_CERT_FILE` | 선택사항. WSS의 CA 파일; 설정 시 `REQUESTS_CA_BUNDLE`보다 우선 |

TLS 인증서 검증은 기본 활성화되어 있습니다. 신뢰할 수 있는 인증서와 정상 시스템 시간을 유지해야 합니다.

Pi에서 수동으로 실행하는 예시입니다. `USER_NO`에는 관리 중인 실제 계정 번호를 넣습니다.

```sh
export DEVICE_ID=door-camera-a
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

`/video_feed`, `/snapshot`, `/` 주소와 포트 5002는 유지합니다. 카메라 프로세스가 종료되면 이전 프레임을 지우고 2초 뒤 다시 시작합니다. 5초 이상 갱신되지 않은 영상은 현재 사진으로 반환하지 않습니다. 웹소켓은 연결 종료 후 재접속합니다.

## 하드웨어 없는 검증

```sh
python3 -m unittest discover -s tests -v
```

표준 Python만으로 실행됩니다. 외부 모듈과 네트워크·카메라 호출은 대체 객체를 사용합니다. 설정, TLS 검증 옵션, 카메라 EOF 복구, 이벤트 ID/사용자 번호, 오래된 프레임, 결과 ACK/재시작/409, 모델 저장 직후 중단 후 재실행의 중복 방지를 검증합니다. 실제 Pi의 촬영 속도·얼굴 인식 정확도·GPIO·서비스 자동시작·배포 서버 연결은 별도 장치 검증이 필요합니다.
