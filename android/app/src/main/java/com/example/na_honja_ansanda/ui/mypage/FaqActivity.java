package com.example.na_honja_ansanda.ui.mypage;

import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.example.na_honja_ansanda.R;

public class FaqActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_faq);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());

        String[][] faqData = {
                {"Q1. 비밀번호 변경 시 즉시 도어락에 반영되나요?", "네, 그렇습니다. 본 서비스는 실시간 Firebase/MQTT 웹소켓 인프라로 연동되어 있어, 앱에서 변경사항을 저장하는 즉시 물리 도어락 기기로 보안 토큰이 전송되어 실시간 반영됩니다."},
                {"Q2. 안면 인식 등록이 자꾸 실패해요.", "안면 인식은 각도와 광량에 민감합니다. 그림자가 많이 지는 곳이나 역광을 피해 주시고, 스마트폰을 눈높이와 수평으로 맞춘 상태에서 렌즈를 정면으로 응시해 재시도해 주세요."},
                {"Q3. 로그아웃하면 알림을 못 받나요?", "아닙니다. 로그아웃 상태이거나 앱 프로세스를 백그라운드에서 완전히 종료하더라도, 기기 고유 토큰 기반의 FCM(Firebase Cloud Messaging) 푸시 시스템을 활용하므로 긴급 방범 경보는 24시간 정상 발송됩니다."},
                {"Q4. 보안 등급 '위험'은 어떤 기준인가요?", "최근 1시간 이내에 현관문 도어락 비밀번호 입력 오류가 연속 5회 이상 발생했거나, 외부인 카메라 센서 서성임 차단 경고가 누적되었을 때 시스템 알고리즘이 연산하여 즉시 '위험' 등급으로 격상시킵니다."},
                {"Q5. 기기 변경 시 기존 데이터는 유지되나요?", "네, 유지됩니다. 모든 방범 기록과 회원 정보는 스마트폰 로컬 저장소가 아닌 안전한 클라우드 데이터베이스 인프라에 계정 ID 매칭 방식으로 저장되므로, 기기를 바꾸셔도 동일 계정으로 로그인하면 그대로 연동됩니다."}
        };

        RecyclerView recyclerView = findViewById(R.id.rv_faq);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(new AccordionAdapter(faqData, "#EC4899")); // 포인트 핑크
    }
}