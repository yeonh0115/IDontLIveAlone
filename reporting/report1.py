"""PC daily security report runner. No MySQL connection and no paid startup test."""
from __future__ import annotations

import argparse
from datetime import timedelta
import os
import sys
import time

if __package__:
    from .report_runtime import Config, ConfigurationError, ReportApi, ReportError, Reporter, now_kst, next_scheduled_run, parse_report_date
    from .report_pairing import connection_mode, device_state_dir, prepare_device, require_same_owner, resolve_config
else:
    from report_runtime import Config, ConfigurationError, ReportApi, ReportError, Reporter, now_kst, next_scheduled_run, parse_report_date
    from report_pairing import connection_mode, device_state_dir, prepare_device, require_same_owner, resolve_config


RESULT_MESSAGES = {
    "SENT": "생성 결과를 서버에 저장했습니다.",
    "ALREADY_SENT": "이미 저장한 날짜입니다. OpenAI를 다시 호출하지 않았습니다.",
    "EMPTY_LOGS": "로그 조회는 성공했으나 기록이 0건입니다. AI 생성과 보고서 저장을 건너뜁니다.",
    "NO_PENDING_REPORT": "재전송할 생성 결과가 없습니다.",
    "WAITING_FOR_LOGS": "로그 조회·생성 대기 상태입니다. --daemon 또는 --once --date로 다시 처리할 수 있습니다.",
}


def retry_outbox(reporter: Reporter, generate_queued: bool = False):
    succeeded = True
    for target, result in reporter.retry_pending(generate_queued=generate_queued):
        if result not in RESULT_MESSAGES:
            succeeded = False
        if result != "ALREADY_SENT":
            print(f"[재전송 {target}] {RESULT_MESSAGES.get(result, result)}")
    return succeeded


def run_daemon(reporter: Reporter):
    scheduled = next_scheduled_run(now_kst(), reporter.config.daily_time)
    next_retry = now_kst()
    print(f"[대기] 매일 {reporter.config.daily_time} Asia/Seoul에 생성합니다. 시작 즉시 AI를 호출하지 않습니다.")
    print(f"[다음 실행] {scheduled.isoformat()}")
    while True:
        now = now_kst()
        if now >= next_retry:
            retry_outbox(reporter, generate_queued=True)
            next_retry = now + timedelta(minutes=5)
        if now >= scheduled:
            target = scheduled.date()
            try:
                result = reporter.run(target)
                print(f"[일간 작업 {target}] {RESULT_MESSAGES[result]}")
            except ReportError as error:
                print(f"[일간 작업 {target}] {error}")
            scheduled = next_scheduled_run(now_kst(), reporter.config.daily_time)
        time.sleep(30)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="한국 날짜 기준 보안 리포트를 HTTPS 443으로 생성·저장합니다.")
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--once", action="store_true", help="지정 날짜를 한 번 처리합니다. 새 결과 생성에는 OpenAI 비용이 발생할 수 있습니다.")
    mode.add_argument("--retry-outbox", action="store_true", help="생성한 결과만 재전송합니다. OpenAI는 호출하지 않습니다.")
    mode.add_argument("--check-config", action="store_true", help="필수 설정만 확인합니다. 네트워크나 OpenAI를 호출하지 않습니다.")
    mode.add_argument("--daemon", action="store_true", help="매일 한국 시간에 생성하고 5분마다 outbox를 재전송합니다(기본 모드).")
    mode.add_argument("--pair", action="store_true", help="PC 연결코드만 발급합니다. 앱에서 입력해 연결하세요. OpenAI는 호출하지 않습니다.")
    parser.add_argument("--date", help="--once에 사용할 한국 날짜 YYYY-MM-DD; 기본값은 한국의 오늘")
    args = parser.parse_args(argv)
    try:
        if args.date and not args.once:
            raise ConfigurationError("--date는 --once와 함께 사용해주세요.")
        mode_name = connection_mode(os.environ)
        if args.check_config:
            base, outbox, model, at = Config.settings_from_env(os.environ)
            if mode_name == "legacy":
                config = Config.from_env()
                print(f"[연결 방식] 기존 환경 변수, 사용자={config.user_no}")
            else:
                print("[연결 방식] 앱 연결코드. 사용자 번호·토큰은 자동 설정됩니다(서버 연결 상태는 아직 조회하지 않음).")
                print(f"[기기 비밀 저장소] {device_state_dir(os.environ)}")
            print(f"[설정 확인] 서버={base}, 모델={model}, 매일={at} KST")
            print(f"[outbox] {outbox}")
            print("[OpenAI 키] " + ("설정됨" if os.environ.get("OPENAI_API_KEY", "").strip() else "미설정 (새 생성에 필요)"))
            return 0
        if args.pair:
            if mode_name == "legacy":
                raise ConfigurationError("앱 연결 방식을 사용하려면 기존 REPORT_USER_NO와 REPORT_API_TOKEN을 모두 비워주세요.")
            try:
                identity = prepare_device(os.environ).enroll()
            except ReportError:
                raise
            except Exception:
                raise ConfigurationError("연결코드 발급에 실패했습니다. 비밀 파일을 보존하고 서버 연결 후 다시 시도해주세요.") from None
            if identity["paired"]:
                print("[기기 연결] 이미 계정에 연결된 PC입니다. 기존 연결 정보를 유지합니다.")
            else:
                print(f"[PC 연결코드] {identity['pairingCode']} — 앱의 마이페이지 → 기기 연결에서 입력해주세요. 코드는 10분 동안 유효합니다.")
            return 0
        config, device = resolve_config()
        guard = None if device is None else lambda: require_same_owner(device, config.user_no)
        reporter = Reporter(config, api=ReportApi(config, credential_guard=guard))
        if args.retry_outbox:
            return 0 if retry_outbox(reporter) else 1
        if args.once:
            target = parse_report_date(args.date) if args.date else now_kst().date()
            if target > now_kst().date():
                raise ConfigurationError("아직 오지 않은 한국 날짜의 보고서는 생성하지 않습니다.")
            print(f"[{target}] {RESULT_MESSAGES[reporter.run(target)]}")
            return 0
        run_daemon(reporter)
    except KeyboardInterrupt:
        print("[종료] 생성된 결과는 outbox에 보관됩니다.")
        return 0
    except ReportError as error:
        print(f"[오류] {error}", file=sys.stderr)
        return 1
    except Exception:
        print("[오류] 작업을 완료하지 못했습니다. outbox와 환경 설정을 확인해주세요. 원시 오류는 출력하지 않습니다.", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
