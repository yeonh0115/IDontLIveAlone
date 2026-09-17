# Pi B 센서 이벤트를 HTTPS로 전송

`DB_security.py`, `event_runtime.py`, 공통 `device_client/device_pairing.py`를 같은 폴더에 배치합니다. 저장소에서는 공통 helper 폴더를 그대로 사용할 수 있습니다. 이 수정본은 MySQL 드라이버·DB 주소·DB 계정·비밀번호를 사용하지 않습니다. Pi B는 Render의 HTTPS 443 API에 센서 이벤트를 보내고, 서버가 Aiven DB 기록과 일별 집계를 처리합니다. 실행에 필요한 외부 Python 패키지는 기존 UART용 `pyserial`뿐이며 네트워크·영속 큐는 표준 라이브러리를 사용합니다.

## 새 설치: IP와 사용자 번호 없이 연결코드 사용

이번 배포는 사용자 요청에 따라 서버와 Pi의 **EVENT_PHOTOS_ENABLED=false**를 사용합니다. 센서 로그는 계속 저장하고 paired ACK의 `cameraCaptureQueued:false`, `captureStatus:NOT_REQUESTED`를 정상 처리합니다. 수동 호환 모드에서도 이 플래그를 false로 설정하면 LAN 사진 요청을 생략합니다. 아래 사진 예약·재시도 설명은 사진 기능을 다시 켠 경우에 해당합니다.

`DEVICE_PAIRING=1`로 실행하면 SENSOR 장치가 자동 등록합니다. 기존 USER_NO/DEVICE_API_TOKEN 설정이 둘 다 없을 때도 이 방식이 기본입니다. 공통 helper는 최초 요청 전에 무작위 장치 토큰을 `device_state/credentials.json`에 Linux mode `0600`으로 저장합니다. 앱에 8자리 연결코드를 입력하고 서버가 paired 상태와 실제 계정 번호를 돌려준 다음에 UART 수신을 시작합니다. `DEVICE_STATE_DIR`로 영속 저장소, `DEVICE_NAME`으로 앱에 보일 이름을 정할 수 있습니다. CAMERA의 credential을 SENSOR에 복사하지 마세요.

모니터가 없으면 PC에서 공통 helper를 `--state-dir 비공개폴더 --role SENSOR --name 이름`으로 실행해 코드를 미리 발급하고 같은 credential을 Pi에 설치할 수 있습니다. 코드만 사용자에게 보여주고 토큰 원문은 출력하거나 Git에 넣지 않습니다. Pi 재시작과 응답 유실은 같은 장치 토큰을 재사용합니다.

연결코드 모드에서는 Pi A의 IP나 `CAMERA_TRIGGER_URL`이 필요 없습니다. 센서 이벤트 저장 시 서버가 같은 계정 CAMERA의 촬영을 예약하고 `cameraCaptureQueued`, `captureTaskId`, `captureStatus`를 ACK에 포함합니다. Pi B는 이 예약 ACK를 검증해 `photoStatus:server_managed`로 완료하며 LAN 카메라를 호출하지 않습니다. 이는 실제 촬영 성공이라는 뜻이 아닙니다. Pi A가 서버에서 작업을 가져와 사진을 올리면 서버가 성공을 확정합니다. 발생 후 120초가 지난 사건은 새 사진을 찍지 않습니다. 정확한 사건 순간의 영상 보장은 아닙니다.

## 그대로 유지한 장치 동작

- UART `/dev/serial0`, 115200 baud, 읽기 timeout 1초. STM32 연결의 GPIO 14(TX)/15(RX)를 사용합니다.
- 기존 문자열 판별 순서와 센서 값/설명을 유지합니다.
- 같은 단계 또는 낮은 단계는 3초 동안 억제하고 높은 단계는 즉시 수용합니다. 10초간 새 수용 이벤트가 없으면 우선도 기준을 초기화합니다.
- 원본의 사용자 번호 2 하드코딩은 제거했습니다. 새 설치에서는 등록된 장치의 서버 상태가 실제 계정 번호를 제공합니다.

| UART 문자열 | logType | val1 | severity |
| --- | --- | --- | --- |
| `SENSOR_MOVE` | SENSOR | 6.0 | medium |
| `SECURITY_LOW` | SECURITY | 0.5 | low |
| `SECURITY_MIDDLE` | SECURITY | 1.0 | high |
| `SECURITY_HIGH` | SECURITY | 2.0 | high |

GPIO 출력·음성·다른 Pi B 프로그램은 추가하거나 변경하지 않습니다.

## 설정

| 변수 | 값 |
| --- | --- |
| `RENDER_SERVER_URL` | 기본 `https://idontlivealone.onrender.com`; HTTPS 필수 |
| `DEVICE_PAIRING` | 새 설치는 1; USER_NO/DEVICE_API_TOKEN이 없으면 기본 1. 0은 기존 수동 설정 호환 모드 |
| `EVENT_PHOTOS_ENABLED` | 이번 설치 false; 서버도 동일하게 false로 배포. 센서 이벤트는 계속 등록 |
| `DEVICE_STATE_DIR`, `DEVICE_NAME` | 기본 `device_state/`, `Sensor`; 토큰을 보존할 폴더와 앱 표시 이름 |
| `USER_NO` | 수동 모드에서만 필수. 실제 앱 계정의 양의 정수 사용자 번호. 연결코드 모드는 서버 상태의 값 사용 |
| `DEVICE_API_TOKEN` | 수동 모드에서만 필수. 서버에 설정한 32자 이상 장치 Bearer 토큰. 연결코드 모드는 개별 영속 장치 토큰 사용 |
| `CAMERA_TRIGGER_URL` | 수동 호환 모드에서만 사용. Pi A의 `http://호스트이름또는주소:5002/trigger_event`. 새 연결코드 모드는 사용하지 않음 |
| `PI_B_DATA_DIR` | 기본 스크립트 폴더의 `event_state/`. 상대경로는 스크립트 폴더 기준. 재시작 후에도 유지되고 쓰기 가능한 저장소 사용 |
| `PHOTO_MAX_DELAY_SECONDS` | 수동 모드 기본 120초, 0~3600. 0은 수동 사진 비활성화. 연결코드 모드의 서버 예약은 고정 120초 정책 사용 |
| `UART_PORT`, `UART_BAUD_RATE` | 기본 `/dev/serial0`, `115200` |
| `REQUESTS_CA_BUNDLE` / `CURL_CA_BUNDLE` | 필요한 경우 신뢰할 CA 파일. 인증서 검증을 끄지 않음 |

Pi의 시계와 시간 동기화가 정상이어야 합니다. 이벤트에는 UTC offset이 포함된 발생시각을 저장하며 서버는 그 시각을 한국시간의 날짜로 묶습니다. 기존 수동 모드의 LAN 호출을 유지할 때만 실제 Pi A 호스트 주소를 확인합니다.

기존 `DB_security.py` 프로세스를 중지한 뒤 새 서버 API와 Pi A의 `expected_user_no` 지원을 먼저 적용하고, 같은 사용자 설정을 가진 새 Pi B를 실행합니다. 예전 Pi A는 owner 검증 응답을 제공하지 않으므로 새 Pi B와 함께 갱신해야 합니다.

```sh
export DEVICE_PAIRING=1
python3 DB_security.py
```

## 수동 호환 모드의 사진 요청과 공통 이벤트 재시도

1. UART 이벤트를 수용하면 UUID `eventId`와 발생시각을 한 번 만들고 로컬 outbox에 먼저 저장합니다.
2. `POST /api/device/events`에 `Authorization: Bearer ...`와 `eventId`, `userNo`, `occurredAt`, `logType`, `subType`, `val1`, `val2`, `severity`, `description`을 전송합니다.
3. 서버의 HTTP 200·`success:true`·동일 사건 ID와 사용자·유효한 `logId`를 확인한 뒤 ACK 상태를 디스크에 기록합니다.
4. 그때만 Pi A에 `GET /trigger_event?log_id=동일eventId&expected_user_no=USER_NO`를 보냅니다. Bearer 토큰은 LAN 카메라로 보내지 않습니다.
5. Pi A의 동일 사건 ID·사용자·사진 성공 응답까지 확인합니다. Pi A는 사용자 불일치를 403으로 거부하며 계정을 쿼리로 바꾸지 않습니다.

서버 장애·응답 유실·Pi B 재시작 시 이벤트 payload를 바꾸지 않고 재전송합니다. 서버는 같은 사건을 중복 집계하지 않습니다. 이벤트 등록 후 사진 요청만 실패하면 재시작 후에도 사진 단계부터 재시도합니다. 한 사진 실패가 다음 이벤트의 등록을 막지 않습니다.

이벤트 등록은 사진 유효시간과 관계없이 재시도합니다. 사진은 기본 120초 안에만 요청하고 기한이 지나면 `photoStatus:expired`로 완료 기록을 남깁니다. 과거 사건의 현장 사진인 것처럼 나중 장면을 수집하지 않기 위한 정책이며, 정확한 사건 발생 순간의 촬영을 보장하는 기능은 아닙니다.

`event_state/pending`은 미처리 데이터, `completed`는 등록·사진 결과, `quarantine`은 형식/사건충돌 오류를 보존합니다. HTTP 400/409/422는 원본을 격리하며, 401/403은 토큰/사용자 설정 오류로 보존·재시도합니다. 서버 5xx·네트워크 장애도 보존합니다. 사진 사용자 불일치 403은 로그와 pending 기록의 `lastError`에 남고 성공으로 처리하지 않습니다. 사진 기한이 지나면 그 오류도 completed 기록에 보존됩니다. 저장 폴더는 임의로 삭제하거나 Git에 추가하지 마세요.

HTTP 리다이렉트는 따라가지 않습니다. 로그는 인증 헤더·비밀값·예외 URL 원문·서버 응답본문을 출력하지 않습니다. DB 연결 문제가 있어도 클라이언트의 DNS 우회나 고정 DB IP로 연결하지 않습니다.

## 모의 검증

```sh
python3 -m unittest discover -s tests -v
```

UART나 실제 네트워크 없이 기존 문자열/우선도, 서버 ACK 전 사진 금지, 응답 유실 후 동일 payload 재전송, 사진 단계 재시작, 사진 만료, 토큰 오류·사건충돌, 사용자 불일치, Bearer 토큰의 LAN 전송 방지를 검증합니다. 실제 STM32 수신·실제 Pi A 연결·운영 계정 권한은 장치에서 별도로 확인해야 합니다.
