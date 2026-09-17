"""Resolve the REPORT account through a private device credential or legacy env."""
from pathlib import Path
import os
import sys

if __package__:
    from .report_runtime import ApiError, Config, ConfigurationError
else:
    from report_runtime import ApiError, Config, ConfigurationError


def connection_mode(env):
    user = bool(env.get("REPORT_USER_NO", "").strip())
    token = bool(env.get("REPORT_API_TOKEN", "").strip())
    if user != token:
        raise ConfigurationError("기존 방식은 REPORT_USER_NO와 REPORT_API_TOKEN을 모두 설정해주세요. 앱 연결 방식은 두 값을 모두 비워주세요.")
    return "legacy" if user else "paired"


def device_state_dir(env):
    source = Path(__file__).resolve().parent
    path = Path(env.get("DEVICE_STATE_DIR") or source / "device_state").expanduser()
    return path.resolve() if path.is_absolute() else (source / path).resolve()


def prepare_device(env, client_factory=None):
    base, _, _, _ = Config.settings_from_env(env)
    if client_factory is None:
        # A standalone installation carries this helper next to report1.py.
        shared = Path(__file__).resolve().parents[1] / "device_client"
        if shared.is_dir():
            sys.path.insert(0, str(shared))
        try:
            from device_pairing import DeviceClient
        except ImportError:
            raise ConfigurationError("device_pairing.py 공통 연결 파일이 없습니다. 배포 파일을 함께 복사해주세요.") from None
        client_factory = DeviceClient
    try:
        return client_factory(device_state_dir(env), role="REPORT",
                              name=env.get("DEVICE_NAME") or "PC AI 리포트", server_url=base)
    except Exception:
        raise ConfigurationError("기기 연결 정보를 열 수 없습니다. device_state 백업과 서버 주소를 확인해주세요. 비밀 파일을 삭제하지 마세요.") from None


def resolve_config(env=None, client_factory=None):
    env = os.environ if env is None else env
    if connection_mode(env) == "legacy":
        return Config.from_env(env), None
    client = prepare_device(env, client_factory)
    try:
        identity = client.wait_paired()
    except Exception:
        raise ConfigurationError("앱에서 PC 기기 연결을 완료하지 못했습니다. 연결 상태를 확인해주세요.") from None
    if (not isinstance(identity, dict) or identity.get("role") != "REPORT"
            or identity.get("paired") is not True or type(identity.get("userNo")) is not int
            or identity["userNo"] <= 0):
        raise ConfigurationError("서버에서 연결된 PC 계정을 확인하지 못했습니다.")
    config = Config.from_env({**env, "REPORT_USER_NO": str(identity["userNo"]), "REPORT_API_TOKEN": client.token})
    return config, client


def require_same_owner(client, user_no):
    try:
        identity = client.refresh()
        valid = (isinstance(identity, dict) and identity.get("paired") is True
                 and identity.get("role") == "REPORT" and type(identity.get("userNo")) is int
                 and identity["userNo"] == user_no)
    except Exception:
        valid = False
    if not valid:
        raise ApiError("PC의 계정 연결을 확인하지 못했습니다. 기존 outbox를 보존하고 앱 연결·네트워크를 확인해주세요.")
