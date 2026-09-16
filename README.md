# 나 혼자 안 산다

안드로이드 앱, Spring Boot 서버, 라즈베리파이 A의 카메라·얼굴 인식 소스를 함께 보관합니다.

| 위치 | 역할 |
| --- | --- |
| `src/`, `build.gradle`, `Dockerfile` | Render에 배포하는 Spring Boot 서버 |
| `android/` | Android Studio에서 여는 앱 프로젝트 |
| `raspberrypi/` | Pi A의 `fin_camera.py`, `fin_face.py`, 공용 모듈과 테스트 |
| `docs/` | 수정 전 백업 내역과 적용 안내 |

수정 전 원본은 [`backup/pre-fixes-2026-09-16`](https://github.com/yeonh0115/IDontLIveAlone/tree/backup/pre-fixes-2026-09-16)에 있습니다. 기존 서버 기준 커밋은 `d1d71d7`, 앱·Pi 소스와 빌드 설정을 포함한 최종 백업 커밋은 `0430fd3`입니다. Pi B의 키패드·센서·음성 프로그램과 외부 YOLO/리포트 생성기는 이번 작업에 포함하지 않았습니다.

## 동작

- CCTV: Pi A → `/ws/camera` → Render → `/video_feed` → 앱.
- 얼굴 등록: 앱 사진 3장 → 서버 파일·`face_tasks` 저장 → Pi 작업 수신·학습 → 결과 저장 → 앱에서 실제 완료 확인.
- 현장 인증: Pi 로컬 모델 → 등록 얼굴 연속 4회 확인 → GPIO 23을 3초간 HIGH → FPGA. 이 변경은 GPIO 핀과 시간 기준을 유지합니다.
- 이벤트 사진: Pi A의 `/trigger_event?log_id=...` → 서버 `/api/upload` → 해당 사용자의 일별 리포트에 사진 누적.

설정과 적용 순서는 [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md), Pi 실행 방법은 [raspberrypi/README.md](raspberrypi/README.md)를 참고하세요.

## 로컬 검증

Java 21을 설정한 뒤 서버에서 `./gradlew test bootJar`를 실행합니다. 테스트는 H2를 사용하며 Aiven에 연결하지 않습니다.

Android SDK 경로를 로컬에 설정하고 `android/`에서 `./gradlew assembleDebug testDebugUnitTest lintDebug`를 실행합니다.

Pi의 하드웨어 없는 테스트는 `python -m unittest discover -s raspberrypi/tests -v`로 실행합니다. 실제 카메라·GPIO·안드로이드 기기를 이용한 검증은 별도입니다.
