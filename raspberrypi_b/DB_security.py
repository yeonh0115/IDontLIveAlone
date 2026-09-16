# Credential-free checkpoint; exact original is preserved locally.
import os
import serial
import mysql.connector
import time
import requests
import threading
from datetime import datetime
DB_CONFIG = {'host': os.environ['DB_HOST'], 'port': int(os.environ['DB_PORT']), 'user': os.environ['DB_USERNAME'], 'password': os.environ['DB_PASSWORD'], 'database': os.environ['DB_NAME'], 'connect_timeout': 10}
RPI_A_IP = os.environ['CAMERA_HOST']
RPI_A_TRIGGER_URL = f'http://{RPI_A_IP}:5002/trigger_event'
UART_PORT = '/dev/serial0'
BAUD_RATE = 115200
SEVERITY_LEVELS = {'SENSOR_MOVE': 1, 'SECURITY_LOW': 2, 'SECURITY_MIDDLE': 3, 'SECURITY_HIGH': 4}
last_processed_level = 0
last_processed_time = 0.0
SAME_EVENT_COOLDOWN = 3.0

def update_daily_log(conn, user_no, new_image_url, event_desc, severity):
    """
    daily_reports 테이블에 오늘 날짜 데이터가 있으면 기존 photo_url 뒤에 콤마(,)로 추가(UPDATE),
    total_events 및 high_risk_events 카운트를 누적 증가시킵니다.
    """
    cursor = conn.cursor(dictionary=True)
    today_str = datetime.now().strftime('%Y-%m-%d')
    is_high_risk = 1 if severity == 'high' else 0
    try:
        select_query = '\n            SELECT id, photo_url FROM daily_reports \n            WHERE user_no = %s AND report_date = %s\n        '
        cursor.execute(select_query, (user_no, today_str))
        row = cursor.fetchone()
        if row:
            existing_urls = row['photo_url'] or ''
            if existing_urls:
                updated_urls = f'{existing_urls},{new_image_url}'
            else:
                updated_urls = new_image_url
            update_query = "\n                UPDATE daily_reports \n                SET photo_url = %s,\n                    report_text = CONCAT(IFNULL(report_text, ''), ' / ', %s),\n                    total_events = total_events + 1,\n                    high_risk_events = high_risk_events + %s\n                WHERE id = %s\n            "
            cursor.execute(update_query, (updated_urls, event_desc, is_high_risk, row['id']))
            print(f"[{time.strftime('%H:%M:%S')}] 🔄 [daily_reports] 기존 로그에 사진 및 카운트 추가 완료!")
        else:
            insert_query = '\n                INSERT INTO daily_reports (user_no, report_date, photo_url, report_text, total_events, high_risk_events, created_at)\n                VALUES (%s, %s, %s, %s, 1, %s, NOW())\n            '
            cursor.execute(insert_query, (user_no, today_str, new_image_url, event_desc, is_high_risk))
            print(f"[{time.strftime('%H:%M:%S')}] 🆕 [daily_reports] 오늘 자 신규 로그 생성 완료!")
    except Exception as e:
        print(f"[{time.strftime('%H:%M:%S')}] 🚨 [daily_reports 처리 오류] {e}")
    finally:
        cursor.close()

def process_security_event_async(event_key, log_values):
    """
    [통합 스레드]
    1. 라파 A 캡처 요청 -> 서버 업로드된 이미지 URL 수신
    2. integrated_logs 테이블에 로그 INSERT
    3. daily_reports 테이블에 사진 URL 누적 및 이벤트 카운트 증가
    """
    user_no, log_type, sub_type, val1, val2, severity, description = log_values
    captured_image_url = None
    try:
        response = requests.get(RPI_A_TRIGGER_URL, timeout=5)
        if response.status_code == 200:
            res_data = response.json()
            captured_image_url = res_data.get('image_url') or res_data.get('url')
            print(f"[{time.strftime('%H:%M:%S')}] 📸 [캡처 완료] URL: {captured_image_url}")
        else:
            print(f"[{time.strftime('%H:%M:%S')}] ⚠️ [카메라 응답 실패] 코드: {response.status_code}")
    except Exception as e:
        print(f"[{time.strftime('%H:%M:%S')}] 🚨 [카메라 통신 에러] {e}")
    max_retries = 3
    for attempt in range(1, max_retries + 1):
        conn = None
        try:
            conn = mysql.connector.connect(**DB_CONFIG)
            cursor = conn.cursor()
            query_integrated = '\n                INSERT INTO integrated_logs (user_no, log_type, sub_type, val1, val2, severity, description)\n                VALUES (%s, %s, %s, %s, %s, %s, %s)\n            '
            cursor.execute(query_integrated, log_values)
            conn.commit()
            cursor.close()
            print(f"[{time.strftime('%H:%M:%S')}] 💾 [integrated_logs] 저장 완료!")
            if captured_image_url:
                update_daily_log(conn, user_no, captured_image_url, description, severity)
                conn.commit()
            return
        except mysql.connector.Error as err:
            print(f"[{time.strftime('%H:%M:%S')}] ⚠️ [DB 연결 시도 {attempt}/{max_retries} 실패] {err}")
            if attempt < max_retries:
                time.sleep(2)
        except Exception as e:
            print(f"[{time.strftime('%H:%M:%S')}] 🚨 [기타 DB 오류] {e}")
            break
        finally:
            if conn and conn.is_connected():
                conn.close()
try:
    ser = serial.Serial(UART_PORT, baudrate=BAUD_RATE, timeout=1)
    ser.reset_input_buffer()
    print(f'[시작] GPIO 14(TX), 15(RX)를 통해 STM32 다중 신호 감시 중... ({BAUD_RATE} bps)')
except Exception as e:
    print(f'[포트 오류] {UART_PORT}를 열 수 없습니다: {e}')
    exit()
try:
    while True:
        if ser.in_waiting > 0:
            line = ser.readline().decode('utf-8', errors='ignore').strip()
            if not line:
                continue
            event_key = None
            log_values = None
            if 'SENSOR_MOVE' in line:
                event_key = 'SENSOR_MOVE'
                print("\n[이벤트] PIR 센서 신호 수신 ('SENSOR_MOVE')")
                log_values = (2, 'SENSOR', '움직임 센서', 6.0, 0.0, 'medium', '현관 밖 지속적인 움직임 감지')
            elif 'SECURITY_HIGH' in line:
                event_key = 'SECURITY_HIGH'
                print("\n[치명적 위험] 진동 센서 신호 수신 ('SECURITY_HIGH')")
                log_values = (2, 'SECURITY', '현관 도어락 보디가드', 2.0, 0.0, 'high', '지속적이고 강력한 충격 발생!')
            elif 'SECURITY_MIDDLE' in line:
                event_key = 'SECURITY_MIDDLE'
                print("\n[위험] 진동 센서 신호 수신 ('SECURITY_MIDDLE')")
                log_values = (2, 'SECURITY', '현관 도어락 보디가드', 1.0, 0.0, 'high', '비정상적인 외부 충격 감지')
            elif 'SECURITY_LOW' in line:
                event_key = 'SECURITY_LOW'
                print("\n[경보] 진동 센서 신호 수신 ('SECURITY_LOW')")
                log_values = (2, 'SECURITY', '현관 도어락 보디가드', 0.5, 0.0, 'low', '현관문 근처 가벼운 진동 감지')
            if log_values and event_key:
                current_time = time.time()
                current_level = SEVERITY_LEVELS.get(event_key, 0)
                is_higher_severity = current_level > last_processed_level
                is_cooldown_passed = current_time - last_processed_time >= SAME_EVENT_COOLDOWN
                if is_higher_severity or is_cooldown_passed:
                    last_processed_level = current_level
                    last_processed_time = current_time
                    threading.Thread(target=process_security_event_async, args=(event_key, log_values), daemon=True).start()
                else:
                    print(f"[{time.strftime('%H:%M:%S')}] 🛡️ 동일/하위 위험 중복 신호 ({event_key}). 스킵.")
        if time.time() - last_processed_time > 10.0:
            last_processed_level = 0
        time.sleep(0.01)
except KeyboardInterrupt:
    print('\n[종료] 프로그램이 종료되었습니다.')
