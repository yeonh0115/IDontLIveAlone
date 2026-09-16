# 네트워크 수정 전 소스 체크포인트

2026-09-16에 수정 범위를 Pi B의 `DB_security.py`와 PC의 `report1.py`까지 넓히기로 승인받아 원본을 보관했습니다.

GitHub 공개 저장소에는 DB 접속 설정 전체(`DB_HOST`, `DB_PORT`, `DB_USERNAME`, `DB_PASSWORD`, `DB_NAME`), OpenAI API 키(`OPENAI_API_KEY`), Pi A 주소(`CAMERA_HOST`)를 환경변수 참조로 치환한 소스를 저장합니다. 원본에 있던 비밀값 3개가 공개 소스에 남아 있지 않음을 프로그램으로 비교 확인했습니다. 주석과 서식은 Python AST로 다시 출력했으며 그 외 원래 로직을 유지합니다. 사용자 번호, DB 직접 접속 등 당시 동작상의 문제도 이 체크포인트에 남아 있습니다.

바이트 단위 원본 두 파일은 로컬 `project-maintenance/private-checkpoints/network-original-2026-09-16/`에 별도 보관했습니다. 원본 SHA-256과 치환 건수는 `network-source-backup-2026-09-16.json`에 기록했습니다. 비밀값을 GitHub에 게시하지 않았으며 원본을 복원하려면 로컬 사본을 사용합니다.

이 체크포인트는 수정 전 스크립트 보관용이며 배포 권장 버전이 아닙니다.
