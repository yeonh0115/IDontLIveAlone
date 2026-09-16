import os
import cv2
import numpy as np
import requests
import time
import threading
import urllib3
import hashlib
import gpiod

# SSL 경고 로그 비활성화 및 세션 초기화
urllib3.disable_warnings(urllib3.exceptions.InsecureRequestWarning)
session = requests.Session()
session.verify = False

# ==========================================
# [설정 구역] 환경에 맞춰 확인해 주세요
# ==========================================
RENDER_SERVER_URL = "https://idontlivealone.onrender.com"
STREAM_URL = "http://192.168.137.115:5002/video_feed"              # 로컬 스트리밍 비디오 피드 주소
MODEL_PATH = "trainer/trainer.yml"                                  # 학습된 모델 저장 경로
MAP_FILE_PATH = "trainer/user_map.txt"                               # 문자열 ID <-> 정수 ID 매핑 테이블 경로
FACEDATA_DIR = "facedata/"                                           # 원본 이미지 저장용 경로

THRESHOLD = 85                                                      # 얼굴 인식 허용 임계값 (작을수록 엄격)
REQUIRED_CONSECUTIVE_SUCCESS = 4                                    # 연속 매칭 성공 횟수 기준
SOLENOID_PIN = 23                                                   # GPIO 핀 번호

# ==========================================
# GPIO (gpiod v2) 초기화 구현
# ==========================================
is_raspberry_pi = False
req = None

try:
    chip = gpiod.Chip('/dev/gpiochip0')
    cfg = gpiod.LineSettings(direction=gpiod.line.Direction.OUTPUT)
    req = chip.request_lines(config={SOLENOID_PIN: cfg})
    req.set_values({SOLENOID_PIN: gpiod.line.Value.INACTIVE})
    is_raspberry_pi = True
    print(f"🔋 [gpiod v2] {SOLENOID_PIN}번 핀 제어권 획득 완료 (LOW 초기화)")
except Exception as e:
    print(f"⚠️ 하드웨어 초기화 실패 (시뮬레이션/가상 모드로 가동): {e}")

# 글로벌 인식기 및 멀티스레드 제어용 락 선언
recognizer = cv2.face.LBPHFaceRecognizer_create()
model_lock = threading.Lock()
frame_lock = threading.Lock()

face_cascade = cv2.CascadeClassifier(cv2.data.haarcascades + 'haarcascade_frontalface_default.xml')

latest_frame = None
is_processing = False
running = True


# ==========================================
# ID 매핑 (String <-> Int) 고도화 모듈
# ==========================================
def get_or_create_int_id(string_user_id):
    """ 문자열/UUID ID를 OpenCV LBPH가 사용하는 정수형 ID로 변환 후 보존 """
    if not string_user_id:
        return 0
    hasher = hashlib.sha256(string_user_id.encode('utf-8'))
    int_id = int(hasher.hexdigest(), 16) % 100000000 + 1
    
    os.makedirs(os.path.dirname(MAP_FILE_PATH), exist_ok=True)
    mapping_exists = False
    if os.path.exists(MAP_FILE_PATH):
        try:
            with open(MAP_FILE_PATH, "r", encoding="utf-8") as f:
                for line in f:
                    if f"{string_user_id}=" in line:
                        mapping_exists = True
                        break
        except Exception as e:
            print(f"🚨 매핑 파일 읽기 실패: {e}")
            
    if not mapping_exists:
        try:
            with open(MAP_FILE_PATH, "a", encoding="utf-8") as f:
                f.write(f"{string_user_id}={int_id}\n")
        except Exception as e:
            print(f"🚨 매핑 파일 쓰기 실패: {e}")
            
    return int_id


def get_string_user_id(int_id):
    """ 정수형 ID를 원본 문자열 ID로 변환 """
    pure_id = int(int_id)
    if os.path.exists(MAP_FILE_PATH):
        try:
            with open(MAP_FILE_PATH, "r", encoding="utf-8") as f:
                for line in f:
                    parts = line.strip().split("=")
                    if len(parts) == 2 and parts[1] == str(pure_id):
                        return parts[0]
        except Exception as e:
            print(f"🚨 매핑 데이터 역변환 오류: {e}")
    return "Unknown"


# ==========================================
# 모델 파일 제어 및 예측 로직
# ==========================================
def safe_load_model():
    """ 메모리 크래시 없이 안면인식 모델 동기화 실행 """
    global recognizer
    with model_lock:
        if os.path.exists(MODEL_PATH) and os.path.getsize(MODEL_PATH) > 100:
            try:
                recognizer.read(MODEL_PATH)
                print(f"🔄 [안면 인식 시스템] 최신 AI 모델 동기화 완료: {MODEL_PATH}")
                return True
            except Exception as e:
                print(f"🚨 [모델 로딩 에러] 파일이 손상되었거나 열 수 없습니다: {e}")
                return False
        else:
            print("[WARNING] 기존 학습 모델이 없습니다. 최초 학습(TRAIN) 완료 전까지 인식 작동이 일시 대기 상태로 작동합니다.")
            return False


# 시스템 기동 즉시 초기화 로드
safe_load_model()


# ==========================================
# GPIO 제어 스레드
# ==========================================
def trigger_face_signal(user_name):
    """ 인식 성공 시 3초간 솔레노이드 제어 및 원상 복구 """
    global is_processing, req
    try:
        print(f"🔓 [FPGA 신호 발생] 안면인식 최종 인증 성공: {user_name} -> {SOLENOID_PIN}번 핀 HIGH (3초 유지)")
        if is_raspberry_pi and req:
            req.set_values({SOLENOID_PIN: gpiod.line.Value.ACTIVE})
            
        time.sleep(3)
        
        if is_raspberry_pi and req:
            req.set_values({SOLENOID_PIN: gpiod.line.Value.INACTIVE})
        print("🔒 [FPGA 신호 종료] 3초 경과 -> LOW 원상 복구 완료")
    except Exception as e:
        print(f"🚨 솔레노이드 제어부 스레드 내부 오작동: {e}")
        if is_raspberry_pi and req:
            try:
                req.set_values({SOLENOID_PIN: gpiod.line.Value.INACTIVE})
            except:
                pass
    finally:
        is_processing = False


# ==========================================
# 비디오 프레임 캡처 스레드
# ==========================================
def frame_reader():
    """ 끊김 없는 실시간 프레임 수신 및 자동 재연결 스레드 """
    global latest_frame, running
    cap = cv2.VideoCapture(STREAM_URL)
    failed_count = 0
    
    while running:
        ret, frame = cap.read()
        if not ret:
            failed_count += 1
            if failed_count > 30:
                print("🚨 스트림 연결 단절 발생. 재연결 시도 중...")
                cap.release()
                cap = cv2.VideoCapture(STREAM_URL)
                failed_count = 0
            time.sleep(0.1)
            continue
            
        failed_count = 0
        with frame_lock:
            latest_frame = frame


# ==========================================
# 실시간 안면 인식 메인 스레드
# ==========================================
def real_time_recognition_loop():
    """ 상시 안면 인식 분석 루프 """
    global latest_frame, is_processing, running
    consecutive_count = 0
    last_id = -1
    
    print("🚀 실시간 안면 인식 상시 가동 루프 작동 시작...")
    
    while running:
        frame = None
        with frame_lock:
            if latest_frame is not None:
                frame = latest_frame
                latest_frame = None  # 소모 후 초기화
                
        if frame is None:
            time.sleep(0.005)
            continue
            
        # 연산 성능 보장을 위해 프레임 사이즈 축소
        small_frame = cv2.resize(frame, (0, 0), fx=0.5, fy=0.5)
        gray = cv2.cvtColor(small_frame, cv2.COLOR_BGR2GRAY)
        
        # 가벼운 감지 수행
        faces = face_cascade.detectMultiScale(
            gray, 
            scaleFactor=1.1, 
            minNeighbors=5, 
            minSize=(40, 40)
        )
        
        current_label = -1
        
        # 인식 연산과 웹서버를 통한 모델 학습 덮어쓰기 간 충돌 방지를 위한 Lock 적용
        with model_lock:
            # 학습 모델 파일이 존재하는 경우에만 예측 연산 가동
            if os.path.exists(MODEL_PATH) and os.path.getsize(MODEL_PATH) > 100:
                for (x, y, w, h) in faces:
                    try:
                        face_roi = gray[y:y+h, x:x+w]
                        face_roi = cv2.resize(face_roi, (200, 200))
                        face_roi = cv2.equalizeHist(face_roi)
                        
                        label, confidence = recognizer.predict(face_roi)
                        user_name = get_string_user_id(label)
                        
                        print(f"🔎 감지: {user_name} | 수치: {confidence:.2f} | GPIO 상태: {'HIGH' if is_processing else 'LOW'}")
                        
                        if user_name != "Unknown" and confidence < THRESHOLD:
                            current_label = label
                            break
                    except Exception as pred_err:
                        # 모델 파일 재동기화 도중 예외 회피
                        break
                        
        # 연속 성공 검증 알고리즘
        if current_label != -1 and current_label == last_id:
            consecutive_count += 1
        elif current_label != -1:
            consecutive_count = 1
            last_id = current_label
        else:
            consecutive_count = 0
            last_id = -1
            
        # 최종 통과 트리거 발동
        if consecutive_count >= REQUIRED_CONSECUTIVE_SUCCESS:
            if not is_processing:
                is_processing = True
                user_name = get_string_user_id(last_id)
                # 솔레노이드 구동 스레드 분기 실행
                threading.Thread(target=trigger_face_signal, args=(user_name,), daemon=True).start()
            consecutive_count = 0
            
        time.sleep(0.01)


# ==========================================
# 클라우드 REST API 폴링 제어 스레드
# ==========================================
def process_train(task_data):
    """ Render로부터 유저 정보 및 수집한 이미지를 받아 기존 모델 손상 없이 증분(Incremental) 학습 처리 """
    global recognizer
    with model_lock:
        try:
            string_user_id = task_data.get("user_id")
            images_bytes_list = task_data.get("images", [])
            
            if not string_user_id or not images_bytes_list:
                return {"status": "error", "message": "유효하지 않은 유저 식별자 혹은 유실 데이터셋"}
                
            int_user_id = get_or_create_int_id(string_user_id)
            print(f"[INFO] 🧠 문자열 ID '{string_user_id}' -> 정수형 ID '{int_user_id}' 연계 생성")
            
            user_dir = os.path.join(FACEDATA_DIR, str(int_user_id))
            os.makedirs(user_dir, exist_ok=True)
            
            # 1. 파일 저장 및 전처리
            new_face_samples = []
            new_ids = []
            
            for idx, img_bytes in enumerate(images_bytes_list):
                try:
                    nparr = np.frombuffer(img_bytes, np.uint8)
                    img = cv2.imdecode(nparr, cv2.IMREAD_COLOR)
                    if img is not None:
                        gray_temp = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
                        faces = face_cascade.detectMultiScale(gray_temp, 1.3, 5)
                        
                        # 전처리 얼굴 영역 크롭 및 정형화
                        if len(faces) > 0:
                            (x, y, w, h) = sorted(faces, key=lambda f: f[2]*f[3], reverse=True)[0]
                            face_img = gray_temp[y:y+h, x:x+w]
                        else:
                            face_img = gray_temp
                        
                        face_img = cv2.resize(face_img, (200, 200))
                        face_img = cv2.equalizeHist(face_img)
                        
                        # 물리 파일 저장 (백업용)
                        cv2.imwrite(os.path.join(user_dir, f"{idx}.jpg"), face_img)
                        
                        # 학습 배열 적재
                        new_face_samples.append(face_img)
                        new_ids.append(int_user_id)
                except Exception as inner_e:
                    print(f"[DATA INTEGRITY WARNING] 로컬 이미지 가공 실패: {inner_e}")
            
            if len(new_face_samples) == 0:
                return {"status": "error", "message": "학습 가능한 신규 얼굴 데이터가 검출되지 않았습니다."}
                
            # 2. 기존 학습 모델 로드 후 증분 학습(update) 진행
            new_recognizer = cv2.face.LBPHFaceRecognizer_create()
            
            # 기존에 이미 학습 완료된 파일이 디스크에 존재하는지 판별
            if os.path.exists(MODEL_PATH) and os.path.getsize(MODEL_PATH) > 100:
                try:
                    print("[INFO] 기존 모델을 불러와 점진적 업데이트(Update)를 시작합니다.")
                    new_recognizer.read(MODEL_PATH)
                    new_recognizer.update(new_face_samples, np.array(new_ids))
                except Exception as update_err:
                    print(f"⚠️ 기존 모델 업데이트 실패, 새롭게 전체 데이터 빌드업 시도: {update_err}")
                    # 업데이트 실패 시 차선책으로 디렉토리 완전 재스캔 학습
                    return rebuild_model_from_scratch()
            else:
                # 모델 파일이 없는 경우, 최초 학습(train)을 진행
                print("[INFO] 기존 모델이 없으므로 최초 학습(Train)을 시작합니다.")
                new_recognizer.train(new_face_samples, np.array(new_ids))
            
            # 3. 신규 가중치 파일 덤프 및 인스턴스 핫 스왑
            os.makedirs(os.path.dirname(MODEL_PATH), exist_ok=True)
            new_recognizer.write(MODEL_PATH)
            
            recognizer = new_recognizer
            print(f"[SUCCESS] 🎉 점진적 누적 학습 성공! 추가된 샘플 수: {len(new_face_samples)}")
            return {"status": "success", "message": f"유저 {string_user_id} 추가 및 실시간 모델 누적 업데이트 완료"}
            
        except Exception as e:
            return {"status": "error", "message": f"점진적 업데이트 처리 도중 오류: {str(e)}"}


def rebuild_model_from_scratch():
    """ 예외 상황 발생 시 facedata 폴더 전체를 재스캔하여 완전 재학습하는 세이프티 메서드 """
    global recognizer
    face_samples = []
    ids = []
    
    if not os.path.exists(FACEDATA_DIR):
        return {"status": "error", "message": "학습 데이터 백업 폴더가 존재하지 않습니다."}

    for u_id in os.listdir(FACEDATA_DIR):
        u_dir = os.path.join(FACEDATA_DIR, u_id)
        if not os.path.isdir(u_dir) or not u_id.isdigit():
            continue
            
        for img_name in os.listdir(u_dir):
            img_path = os.path.join(u_dir, img_name)
            try:
                gray_img = cv2.imread(img_path, cv2.IMREAD_GRAYSCALE)
                if gray_img is not None:
                    # 크기 정형화 보장
                    gray_img = cv2.resize(gray_img, (200, 200))
                    gray_img = cv2.equalizeHist(gray_img)
                    face_samples.append(gray_img)
                    ids.append(int(u_id))
            except Exception as io_err:
                print(f"[DATA INTEGRITY WARNING] {img_path} 스킵: {io_err}")
    
    if len(face_samples) == 0:
        return {"status": "error", "message": "전체 복구 학습을 수행할 데이터 백업본이 없습니다."}
        
    temp_recognizer = cv2.face.LBPHFaceRecognizer_create()
    temp_recognizer.train(face_samples, np.array(ids))
    temp_recognizer.write(MODEL_PATH)
    
    recognizer = temp_recognizer
    print(f"[SUCCESS] 🛠️ 전체 데이터셋 재구성 학습 완료. 총 복구 샘플: {len(face_samples)}")
    return {"status": "success", "message": f"전체 데이터셋 기준 전면 갱신 및 완전 복구 완료"}


def watch_render_server():
    """ 웹 대시보드 명령 상시 수집 및 동기화 무한 스레드 """
    print("[START] 📡 클라우드 작업 제어 및 동기화 모듈 대기 개시...")
    global running
    
    while running:
        try:
            response = session.get(f"{RENDER_SERVER_URL}/api/get-task", timeout=5)
            if response.status_code == 200:
                try:
                    task = response.json()
                except ValueError:
                    time.sleep(1)
                    continue

                task_id = task.get("task_id")
                task_type = task.get("type")
                
                print(f"\n[TASK] 📥 명령 도달 -> ID: {task_id} | 유형: {task_type}")
                result_payload = {"task_id": str(task_id), "type": str(task_type), "result": {}}
                
                # 예측 명령 수집 분기
                if task_type == "PREDICT":
                    # 상시 백그라운드 프레임 한 장 획득하여 단발적 API 반환용 예측 수행
                    with frame_lock:
                        frame_to_predict = latest_frame
                    
                    if frame_to_predict is not None:
                        gray = cv2.cvtColor(frame_to_predict, cv2.COLOR_BGR2GRAY)
                        faces = face_cascade.detectMultiScale(gray, 1.3, 5)
                        if len(faces) == 0:
                            result_payload["result"] = {"status": "fail", "message": "얼굴 영역을 감지하지 못했습니다."}
                        else:
                            (x, y, w, h) = sorted(faces, key=lambda f: f[2]*f[3], reverse=True)[0]
                            with model_lock:
                                if os.path.exists(MODEL_PATH):
                                    id_, confidence = recognizer.predict(gray[y:y+h, x:x+w])
                                    user_name = get_string_user_id(id_)
                                    if confidence < THRESHOLD:
                                        result_payload["result"] = {
                                            "status": "success",
                                            "detected_id": user_name,
                                            "confidence": round(float(confidence), 2)
                                        }
                                    else:
                                        result_payload["result"] = {"status": "fail", "message": "미등록자 검출"}
                                else:
                                    result_payload["result"] = {"status": "error", "message": "인식 모델 미비"}
                    else:
                        result_payload["result"] = {"status": "fail", "message": "카메라 프레임 비가용"}
                
                # 실시간 무중단 갱신(학습) 분기
                elif task_type == "TRAIN":
                    user_id = task.get("user_id")
                    urls = task.get("image_urls", [])
                    images_bytes = []
                    
                    for url in urls:
                        try:
                            clean_url = url.replace("https://", "HTTPS://").replace("//", "/").replace("HTTPS:/", "https://")
                            img_res = session.get(clean_url, timeout=7)
                            if img_res.status_code == 200 and img_res.content:
                                images_bytes.append(img_res.content)
                                print(f"[DOWNLOAD SUCCESS] 원본 리소스 획득 완료: {clean_url}")
                        except Exception as dl_err:
                            print(f"⚠️ 이미지 다운로드 실패 ({url}): {dl_err}")
                            continue
                    
                    if len(images_bytes) > 0:
                        train_result = process_train({"user_id": user_id, "images": images_bytes})
                        result_payload["result"] = train_result
                    else:
                        result_payload["result"] = {"status": "error", "message": "리소스 획득 실패"}
                
                # 결과 전송 보고
                try:
                    session.post(f"{RENDER_SERVER_URL}/api/result", json=result_payload, timeout=5)
                except Exception as post_err:
                    print(f"🚨 결과 전송 유실: {post_err}")
                    
            elif response.status_code == 204:
                pass
                
        except Exception as e:
            time.sleep(3)
            continue
            
        time.sleep(1)


# ==========================================
# 통합 시스템 진입점 (Entry Point)
# ==========================================
if __name__ == "__main__":
    os.makedirs(FACEDATA_DIR, exist_ok=True)
    os.makedirs("trainer", exist_ok=True)
    
    # 1. 스트리밍 프레임 수집 스레드 실행
    reader_thread = threading.Thread(target=frame_reader, daemon=True)
    reader_thread.start()
    
    # 2. 클라우드 API 동기화 및 학습 제어 스레드 실행
    polling_thread = threading.Thread(target=watch_render_server, daemon=True)
    polling_thread.start()
    
    # 3. 실시간 안면 인식 및 GPIO 핀 감시 메인 스레드 실행 (메인스레드 유지)
    try:
        real_time_recognition_loop()
    except KeyboardInterrupt:
        print("\n👋 프로그램을 안전하게 종료합니다.")
    finally:
        running = False
        if is_raspberry_pi and req:
            try:
                req.set_values({SOLENOID_PIN: gpiod.line.Value.INACTIVE})
                req.release()
                print("🔌 GPIO 라인 제어권이 안전하게 반환되었습니다.")
            except Exception as release_err:
                print(f"⚠️ GPIO 제어권 해제 중 오류: {release_err}")
        print("🧹 시스템 가용 리소스 정리를 정상적으로 마쳤습니다.")