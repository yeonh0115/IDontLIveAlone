# 검증 결과 · 2026-09-16

| 검사 | 결과 |
| --- | --- |
| 서버 `test bootJar` | 17개 테스트 통과, 실행 JAR 생성 |
| 앱 `assembleDebug` | 성공, debug APK 생성 |
| 앱 `testDebugUnitTest` | 7개 테스트 통과 |
| Pi `python -m unittest discover -s tests -v` | 하드웨어·네트워크 없는 모의 테스트 20개 통과 |
| Git `diff --check` | 통과 |
| 앱 `lintDebug` | 기존 Glide 알림 클래스 관련 오류 1개, 경고 179개 남음 |

서버는 Java 21.0.6, Gradle 9.4.1, H2 테스트 DB를 사용했습니다. 운영 Aiven에 연결하지 않았습니다. 작업 서비스 재생성 후 DB에 남은 작업 조회, 동시 작업 선점, 임대 만료와 중복 결과, 실제 HTTP 계약, 사진 사용자 연결·다중 사진·동시 저장·자정 재전송·로그 집계와 영상 프레임 일관성을 검증했습니다.

앱은 Gradle 8.13과 설치된 Android SDK 36으로 빌드했습니다. 얼굴 작업 상태 및 리포트 상태 분기 테스트를 포함합니다. 남은 lint 오류는 `NotificationPermission`이며 `com.bumptech.glide.request.target.NotificationTarget`을 사용처로 지목합니다. 앱 코드에는 이 클래스의 호출이나 알림 발송 구현이 없습니다. 오류를 숨기는 전역 억제나 불필요한 알림 권한을 추가하지 않았습니다.

Pi 테스트는 설정과 TLS 옵션, 카메라 EOF 복구, 프레임 소비 경쟁과 만료, 이벤트 ID/사용자 연결, 결과 재전송, 4xx 영구 오류 격리, 403 보존, 임대의 시간 제한, 기존 모델 보존, 중복 학습 방지, 실패 샘플 분리, 모델·샘플 적용 중단 복구, 커밋 후 정리 실패를 검사합니다. OpenCV와 카메라·GPIO·네트워크는 대체 객체로 검증했습니다.

실제 Pi의 인식률·GPIO 결선·카메라, Android 단말 권한과 화면, Render 배포·영속 디스크·Aiven 스키마 변경은 검증하지 않았습니다. Docker 실행기가 없어 컨테이너 이미지 빌드도 수행하지 않았습니다. 적용 시 필요한 설정과 확인 항목은 [DEPLOYMENT.md](DEPLOYMENT.md)에 있습니다.
