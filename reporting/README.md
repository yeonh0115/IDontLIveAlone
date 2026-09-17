# PC 일간 리포트 — HTTPS 443

이 PC 프로그램은 MySQL에 직접 접속하지 않습니다. 학교 Wi-Fi에서는 Render의 HTTPS 443 API로 한국 날짜의 로그를 읽고, OpenAI로 요약한 본문을 Render에 전달합니다. Aiven 연결과 사진·이벤트 집계 갱신은 서버가 담당합니다.

현재 배포는 `EVENT_PHOTOS_ENABLED=false`로 이벤트 사진을 촬영·보관하지 않습니다. PC 리포트는 로그와 AI 본문만 처리하므로 사진이 없어도 텍스트·이벤트 수 집계가 계속 동작합니다. 실시간 영상과 얼굴 등록은 별도 기능입니다.

## 준비

- Python 3.10 이상. 저장소 안에서는 바로 실행합니다. 다른 폴더에 설치할 때는 `report1.py`, `report_runtime.py`, `report_pairing.py`, 공통 `../device_client/device_pairing.py`를 같은 폴더에 두세요.
- 저장소 루트에서 `python -m pip install -r reporting/requirements.txt`로 OpenAI SDK를 설치하세요.
- 사용자 번호와 기기 토큰은 **앱 연결코드로 자동 설정**합니다. `REPORT_USER_NO`, `REPORT_API_TOKEN`은 둘 다 비운 상태에서 실행하세요. `.env.example`은 항목 안내용이며 **프로그램이 `.env` 파일을 자동으로 읽지는 않습니다.**

| 환경 변수 | 설정 |
| --- | --- |
| `DEVICE_NAME` | 앱에서 확인할 PC 이름. 기본 `PC AI 리포트` |
| `DEVICE_STATE_DIR` | 기본 이 폴더의 `device_state/`. 자동 발급한 기기 비밀값 보관 경로 |
| `REPORT_USER_NO` | 기존 환경 변수 방식만 사용. 서버의 실제 사용자 번호. 앱 연결 방식에서는 비움 |
| `REPORT_API_TOKEN` | 기존 방식만 사용. Render에 설정한 32자 이상 비밀값. 사용자 번호와 함께 설정하거나 둘 다 비움 |
| `OPENAI_API_KEY` | 새 AI 보고서를 생성할 때 필요. 기존 결과 재전송에는 불필요 |
| `REPORT_API_URL` | 기본 `https://idontlivealone.onrender.com`. HTTPS 443만 허용 |
| `OPENAI_MODEL` | 기본 `gpt-4o`, 기존 리포트 모델 유지 |
| `REPORT_DAILY_TIME` | 기본 `23:50`. PC 표준 시간대와 무관하게 한국 시간으로 실행 |
| `REPORT_OUTBOX_DIR` | 기본 이 폴더의 `outbox/`. 사용자별 결과를 보관할 쓰기 가능한 로컬 경로 |

OpenAI 키·기존 방식의 토큰은 환경 변수로 전달하세요. 소스 코드, Git, 콘솔 출력에 붙여 넣지 마세요. 기본 `outbox/`, `device_state/`, `state/`, `quarantine/`, `.env`는 Git에서 제외됩니다. 저장 경로를 바꿀 때에도 Git에 포함되지 않는 개인 로컬 폴더를 사용하세요. outbox에는 개인 보안 리포트 본문이, `device_state/credentials.json`에는 PC 기기 비밀값이 들어갑니다. 이 비밀 파일을 공개하거나 무작정 삭제하지 마세요.

## 실행

저장소 루트 기준입니다.

```powershell
# 설정 검증만: 네트워크와 OpenAI 호출 없음
python reporting/report1.py --check-config

# 무료 연결코드 발급: 앱 로그인 → 마이페이지 → 기기 연결 → 코드 입력
python reporting/report1.py --pair

# 기본 모드: 매일 23:50 KST 생성, 5분마다 미완료 작업 재시도
python reporting/report1.py --daemon

# 한국의 오늘을 한 번 처리. 새 보고서 생성에는 OpenAI 비용이 발생할 수 있음
python reporting/report1.py --once

# 지정 한국 날짜를 한 번 처리
python reporting/report1.py --once --date 2026-09-16

# 이미 생성한 결과만 재전송. OpenAI 호출 없음
python reporting/report1.py --retry-outbox
```

자동 모드는 시작 즉시 새로운 유료 테스트를 하지 않습니다. 이전에 예약했지만 로그 조회에 실패한 작업은 원래 한국 날짜를 보존해 5분마다 재시도하며 재시작 후에도 복구됩니다. 아직 OpenAI를 호출하지 않은 `QUEUED` 작업은 연결 복구 후 처음 생성할 수 있습니다. 프로그램과 PC가 꺼져 있어 아예 예약되지 않은 날짜는 `--once --date YYYY-MM-DD`로 처리하세요. 기본 23:50 실행은 그 시점까지의 당일 기록을 요약하므로 이후 늦게 발생한 기록까지 포함한 완결된 자정 보고서는 아닙니다.

`--pair`는 PC의 영속 비밀값을 로컬에 먼저 보관한 뒤 10분 유효한 8자리 코드만 표시하고 종료합니다. 앱에서 **PC AI 리포트** 종류와 이름을 확인해 연결하세요. 만료되면 같은 명령을 다시 실행하며 기존 비밀값은 유지합니다. 한 계정에는 REPORT 기기를 한 대 연결할 수 있습니다. 교체 시 앱에서 이전 기기를 연결 해제하세요.

기본 실행은 연결이 없으면 코드와 안내를 표시하고 앱 연결을 기다립니다. 연결 후 서버가 실제 계정을 알려주므로 IP나 사용자 번호를 입력하지 않습니다. 매 로그 조회·저장 전에 연결 상태와 계정을 다시 확인하고, 연결 해제·계정 변경·조회 실패 시 기존 outbox를 보존하며 생성을 진행하지 않습니다. `--check-config`는 로컬 설정만 검증하며 서버 등록이나 기기 비밀값 생성을 하지 않습니다.

## API 계약

두 요청 모두 자동 발급한 기기 Bearer(또는 기존 `REPORT_API_TOKEN`)를 사용합니다. 앱 로그인 세션 토큰을 PC에 복사하지 않습니다. 리디렉션은 허용하지 않으며 TLS 인증서 검증을 유지합니다.

1. `GET /api/logs?userNo=<실제번호>&date=YYYY-MM-DD`
   - HTTP 200의 통합 로그 배열을 받습니다. 사용자 번호와 한국 날짜를 검증합니다.
   - `createdAt`에 오프셋이 없으면 서버 계약에 따라 KST로 해석합니다. 오프셋이 있으면 KST로 변환합니다.
   - HTTP 오류·깨진 응답은 조회 실패입니다. 성공한 빈 배열은 기록 없음이며, AI 생성과 POST 모두 건너뜁니다. 기록 없음이 안전을 의미하지는 않습니다.
2. OpenAI `gpt-4o`로 보안/센서/환경 흐름을 요약합니다.
   - 시스템 지시와 관측 JSON을 분리합니다. 로그 안의 명령·링크·역할 변경은 관측 데이터로 취급합니다.
   - SDK의 자동 재호출을 끄고, 응답이 불명확하면 자동 생성 반복을 막습니다.
3. `POST /api/reports/generated`
   - 본문은 `requestId`, `userNo`, `reportDate`, `reportText` **네 필드만** 보냅니다.
   - 사진, `totalEvents`, `highRiskEvents`, 실제 발생 시각은 클라이언트에서 덮어쓰지 않습니다.
   - HTTP 200의 `success`, `reportId`, `userNo`, `reportDate`를 검증한 뒤 전송 완료로 기록합니다.
   - 같은 요청 ID와 같은 내용의 재전송은 서버가 중복 요청으로 처리합니다.

401·403은 앱의 PC 기기 연결 상태 또는 기존 토큰·사용자 설정을 확인하세요. 503은 서버의 보고서 기능 설정을 확인하세요. 409는 같은 요청 ID에 다른 내용이 있는 상태이므로 outbox를 확인해야 합니다. 서버·SDK의 원시 오류나 비밀값은 출력하지 않습니다.

## 중복 비용 방지와 outbox

사용자 번호와 한국 날짜별로 하나의 JSON 상태 파일을 저장합니다. 키와 토큰은 파일에 저장하지 않습니다.

| 상태 | 의미와 처리 |
| --- | --- |
| `QUEUED` | 로그 조회 전에 저장한 날짜별 작업. 일시 조회 실패 시 자동 모드가 5분마다 같은 날짜를 재시도. `--retry-outbox`에서는 생성하지 않음 |
| `EMPTY` | 조회 성공·기록 0건. 자동 생성하지 않으며 필요하면 `--once --date`로 재조회 가능 |
| `GENERATING` | OpenAI를 호출하기 **전** 저장한 표식. 호출 결과가 불명확하거나 프로세스가 중단되면 자동 재호출하지 않음 |
| `READY` | 생성된 본문을 안전하게 저장함. 서버 연결·업로드 실패 시 동일 본문/요청 ID로 재전송하며 OpenAI는 호출하지 않음 |
| `SENT` | 서버가 저장을 확인함. 같은 날짜 재실행은 재생성하지 않음 |

서버가 저장했지만 응답이 끊겨도 `READY`가 유지되어 동일 요청 ID로 확인할 수 있습니다. 저장 완료 파일은 지우지 않아야 같은 날짜에 반복 과금되는 것을 막을 수 있습니다. OS 파일 잠금으로 같은 날짜의 동시 실행을 막으며, 프로세스 종료 시 잠금은 자동으로 풀립니다. 남아 있는 `.lock` 파일 자체는 진행 중인 작업을 뜻하지 않습니다.

`GENERATING`이 남으면 공급자 사용 내역과 로컬 상태를 먼저 확인하세요. 이 프로그램은 결과가 불명확한 유료 요청을 자동으로 반복하지 않습니다. 생성 결과가 `.tmp` 파일에 남아 있으면 보존하세요. 사람이 상태를 확인하기 전까지 JSON을 지우거나 초기화하지 마세요. 정상 `READY`/`SENT` 파일도 본문·요청 ID를 바꾸지 마세요. 파일 손상·서버 주소 변경·다른 사용자 상태는 자동으로 덮어쓰지 않습니다.

## 검증

```powershell
python -m unittest discover -s reporting/tests -v
```

표준 라이브러리 `unittest`와 모의 API로 조회 실패/빈 로그, KST 경계, 사용자 검증, 업로드 재시도, 생성 중 응답 유실, 디스크 저장 실패, 동시 실행, 손상 파일, 프롬프트 데이터 경계를 확인합니다. 실제 OpenAI API와 실제 서버 쓰기를 호출하지 않습니다.

OpenAI 인터페이스 참고: [Chat Completions Python API](https://developers.openai.com/api/reference/python/resources/chat/subresources/completions/methods/create), [GPT-4o 모델](https://developers.openai.com/api/docs/models/gpt-4o).
