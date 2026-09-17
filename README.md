# 나 혼자 안 산다

안드로이드 앱, Spring Boot 서버, Pi A의 카메라·얼굴 인식, Pi B의 센서 기록, PC의 일간 리포트 소스를 함께 보관합니다.

| 위치 | 역할 |
| --- | --- |
| `src/`, `build.gradle`, `Dockerfile` | Render에 배포하는 Spring Boot 서버 |
| `android/` | Android Studio에서 여는 앱 프로젝트 |
| `raspberrypi/` | Pi A의 `fin_camera.py`, `fin_face.py`, 공용 모듈과 테스트 |
| `raspberrypi_b/` | Pi B의 STM32 UART 센서 기록과 HTTPS 전송 |
| `reporting/` | PC의 일간 AI 리포트 생성과 HTTPS 저장 |
| `device_client/` | 장치 연결 코드·인증정보 관리 공용 모듈 |
| `deployment/` | 기존 SD를 보존하는 Pi 설치 도구 |
| `docs/` | 수정 전 백업 내역과 적용 안내 |

수정 전 원본은 [`backup/pre-fixes-2026-09-16`](https://github.com/yeonh0115/IDontLIveAlone/tree/backup/pre-fixes-2026-09-16)에 있습니다. 기존 서버 기준 커밋은 `d1d71d7`, 앱·Pi 소스와 빌드 설정을 포함한 최종 백업 커밋은 `0430fd3`입니다. 추가로 승인된 Pi B 센서 기록과 PC 리포트 생성기는 DB 직접 연결을 HTTPS 방식으로 전환합니다. Pi B의 다른 키패드·음성 기능과 외부 YOLO 프로그램은 수정하지 않습니다.

## 동작

- CCTV: Pi A ↔ 앱의 WebRTC 직접 연결. Render의 `/api/rtc`는 인증된 연결 메시지만 교환합니다. 새 앱·Pi에는 유료 TURN이나 JPEG 중계 대체 경로가 없습니다.
- 얼굴 등록: 앱 사진 3장 → 서버 파일·`face_tasks` 저장 → Pi 작업 수신·학습 → 결과 저장 → 앱에서 실제 완료 확인.
- 장치 연결: 장치가 표시한 8자리 코드를 앱에 입력 → 로그인 계정에 연결. Pi IP와 사용자 번호 수동 입력을 줄입니다.
- 현장 인증: Pi 로컬 모델 → 등록 얼굴 연속 확인 → 설정한 GPIO를 3초간 HIGH → FPGA. 설치할 카드의 기존 핀·인식 기준을 유지합니다.
- 이벤트 사진: 사용자 요청으로 기본 비활성화합니다. 센서 로그와 리포트 본문은 계속 저장합니다.
- 센서 기록: Pi B의 STM32 UART → 디스크 대기열 → 인증된 `POST /api/device/events` → Aiven 저장. 같은 이벤트 ID의 재전송은 중복 기록하지 않습니다.
- 일간 리포트: PC → 날짜별 인증 로그 조회 → OpenAI 요약 → 인증된 `POST /api/reports/generated`. 사건 수는 서버가 계산합니다.

수정된 앱·Pi·PC 프로그램은 Aiven DB 포트에 직접 연결하지 않습니다. 계정·기기·센서·연결 메시지는 Render HTTPS 443을 사용하고 Render 서버가 Aiven에 연결합니다. WebRTC 영상에는 별도의 STUN/직접 통신 경로가 필요하며 학교망↔LTE 현장 시험으로 확인합니다. DB 관리 도구의 직접 연결에는 별도 네트워크 정책이 적용될 수 있습니다.

현재 영상 업데이트는 [docs/VIDEO_UPDATE.md](docs/VIDEO_UPDATE.md)를 참고하세요. 최초 설치와 계정 설정은 [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md), Pi 실행 방법은 [raspberrypi/README.md](raspberrypi/README.md)에 있습니다.

## 로컬 검증

Java 21을 설정한 뒤 서버에서 `./gradlew test bootJar`를 실행합니다. 테스트는 H2를 사용하며 Aiven에 연결하지 않습니다.

Android SDK 경로를 로컬에 설정하고 `android/`에서 `./gradlew assembleDebug testDebugUnitTest lintDebug`를 실행합니다.

Pi의 하드웨어 없는 테스트는 `python -m unittest discover -s raspberrypi/tests -v`로 실행합니다. 실제 카메라·GPIO·안드로이드 기기를 이용한 검증은 별도입니다.
