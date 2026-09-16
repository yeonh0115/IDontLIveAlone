# Credential-free checkpoint; exact original is preserved locally.
import os
import os
import time
from datetime import datetime
import mysql.connector
import schedule
from openai import OpenAI
OPENAI_API_KEY = os.environ['OPENAI_API_KEY']
client = OpenAI(api_key=OPENAI_API_KEY)
DB_CONFIG = {'host': os.environ['DB_HOST'], 'port': int(os.environ['DB_PORT']), 'user': os.environ['DB_USERNAME'], 'password': os.environ['DB_PASSWORD'], 'database': os.environ['DB_NAME']}

def generate_daily_report(user_no):
    print(f'[시작] User {user_no}의 일간 리포트 생성 중 (Aiven DB 직접 연결)...')
    now = datetime.now()
    today_str = now.strftime('%Y-%m-%d')
    conn = None
    cursor = None
    try:
        conn = mysql.connector.connect(**DB_CONFIG)
        cursor = conn.cursor(dictionary=True)
        query = '\n            SELECT created_at, log_type, sub_type, val1, val2, severity, description \n            FROM integrated_logs \n            WHERE user_no = %s AND DATE(created_at) = CURDATE()\n            ORDER BY created_at ASC\n        '
        cursor.execute(query, (user_no,))
        logs = cursor.fetchall()
        total_events = len(logs)
        high_risk_events = sum((1 for log in logs if log.get('severity') == 'high'))
        log_text = ''
        for log in logs:
            time_str = log['created_at'].strftime('%H:%M') if log.get('created_at') else '00:00'
            log_type = log.get('log_type')
            sub_type = log.get('sub_type')
            val1 = log.get('val1')
            val2 = log.get('val2')
            severity = log.get('severity')
            description = log.get('description')
            if log_type == 'SECURITY':
                log_text += f'[{time_str}] 보안({severity}): {sub_type} - {description}\n'
            elif log_type == 'ENV':
                log_text += f'[{time_str}] 환경: 온도 {val1}°C, 습도 {val2}%\n'
            elif log_type == 'SENSOR':
                log_text += f'[{time_str}] 센서({sub_type}): 감지값 {val1}\n'
        if not log_text.strip():
            log_text = '오늘 발생한 특이 사항 및 센서 기록이 없습니다.'
        prompt = f"\n당신은 지능형 주거 보안 비서입니다. 현재 시각은 {now.strftime('%Y-%m-%d %H:%M')}입니다.\n아래 제공된 [오늘의 통합 로그]를 바탕으로 사용자에게 하루 요약 리포트를 작성하세요.\n\n[오늘의 통합 로그]\n{log_text}\n\n[작성 가이드라인]\n1. 단순 나열이 아닌, 하루의 흐름을 요약해 줄 것.\n2. 온도/습도 등 환경 데이터의 평균적인 상태를 짧게 언급할 것.\n3. 중요도(high)가 높은 보안 이벤트가 있다면 반드시 최상단에 주의 표시와 함께 강조할 것.\n4. 친절하고 안심감을 주는 어조를 유지할 것.\n"
        openai_response = client.chat.completions.create(model='gpt-4o', messages=[{'role': 'system', 'content': '당신은 유능하고 친절한 홈 보안 전문 AI 비서입니다.'}, {'role': 'user', 'content': prompt}], temperature=0.7)
        report_text = openai_response.choices[0].message.content
        print('\n' + '=' * 40)
        print(f'★ [성공] 생성된 리포트 내용 ★')
        print('=' * 40)
        print(report_text)
        print('=' * 40 + '\n')
        check_query = 'SELECT id FROM daily_reports WHERE user_no = %s AND report_date = CURDATE()'
        cursor.execute(check_query, (user_no,))
        existing_report = cursor.fetchone()
        if existing_report:
            update_query = '\n                UPDATE daily_reports \n                SET report_text = %s, total_events = %s, high_risk_events = %s, created_at = NOW() \n                WHERE id = %s\n            '
            cursor.execute(update_query, (report_text, total_events, high_risk_events, existing_report['id']))
            print(f'[성공] 아벤 DB(daily_reports)의 기존 리포트를 새로운 분석 데이터로 업데이트했습니다.')
        else:
            insert_query = '\n                INSERT INTO daily_reports (user_no, report_date, total_events, high_risk_events, report_text, created_at) \n                VALUES (%s, CURDATE(), %s, %s, %s, NOW())\n            '
            cursor.execute(insert_query, (user_no, total_events, high_risk_events, report_text))
            print(f'[성공] 아벤 DB(daily_reports)에 오늘 자 새로운 리포트 저장 완료!')
        conn.commit()
    except mysql.connector.Error as err:
        print(f'[DB 오류] 아벤 데이터베이스 연결 실패 또는 쿼리 에러: {err}')
    except Exception as e:
        print(f'[시스템 오류] 작업 중 에러 발생: {e}')
    finally:
        if cursor:
            cursor.close()
        if conn and conn.is_connected():
            conn.close()
            print('[알림] 아벤 DB 커넥션이 안전하게 닫혔습니다.')

def run_daily_job():
    print(f'\n=== {datetime.now()} 일일 리포트 자동화 작업 시작 ===')
    target_users = [1]
    for user_id in target_users:
        generate_daily_report(user_id)
schedule.every().day.at('23:50').do(run_daily_job)
print('아벤 직접 연동 스케줄러가 대기 모드로 시작되었습니다.')
if __name__ == '__main__':
    run_daily_job()
    print('\n[알림] 첫 테스트가 종료되었습니다. 매일 밤 23:50 자동 스케줄러가 대기 중입니다.')
    while True:
        schedule.run_pending()
        time.sleep(60)
