package com.example.na_honja_ansanda.ui.mypage;

import android.graphics.Color;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.example.na_honja_ansanda.R;

public class NoticeActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_notice);

        // 뒤로가기 버튼 활성화
        findViewById(R.id.btn_back).setOnClickListener(v -> finish());

        String[][] noticeData = {
                {"[안내] 나 혼자 안산다 서비스 점검 안내 (07/15)", "안정적인 치안 데이터 동기화를 위해 7월 15일 새벽 2시부터 4시까지 서버 점검이 진행됩니다. 점검 중에도 도어락 제어 및 긴급 신고 기능은 로컬 세션을 통해 정상 작동하오니 안심하시기 바랍니다."},
                {"[위험] 최근 안산 인근 서성임 감지 건수 증가, 문단속 유의", "최근 상록구 및 단원구 일대 원룸촌 부근에서 야간 시간대 외부인 서성임 발생 경보가 평소 대비 25% 증가했습니다. 귀가 시 반드시 공동현관 문이 완전히 닫혔는지 확인하시고, 창문 잠금장치를 다시 한번 점검해 주세요."},
                {"[기능] 스마트 도어락 비밀번호 주기적 변경 캠페인", "비밀번호 오염 및 유출을 방지하기 위해 3개월 주기로 비밀번호를 변경하는 것을 권장합니다. 마이페이지 -> 보안 설정 메뉴에서 간편하게 원격 변경이 가능합니다."},
                {"[방범] 장기 외출 시 '외출 모드' 활성화 방법 안내", "3일 이상 집을 비우실 경우 홈 화면의 '외출 모드'를 켜주세요. 센서가 평소보다 예민하게 작동하며, 미세한 움직임이나 현관문 충격 감지 시 즉시 사용자에게 사진과 함께 긴급 푸시를 발송합니다."},
                {"[공지] 개인정보 처리방침 개정 안내", "위치 정보 기반 자치구별 치안 등급 고도화를 위해 개인정보 처리방침이 일부 개정되었습니다. 상세 내용은 공식 홈페이지의 약관 게시판을 참고해 주시기 바랍니다."},
                {"[팁] 안면 인식 등록 성공률을 높이는 사진 촬영 꿀팁", "안면 인식 등록 시 정면에서 플래시 없이 자연광 아래에서 촬영하는 것이 가장 좋습니다. 모자, 안경, 마스크를 벗은 상태의 원본 데이터가 등록되어야 야간 인식 정확도가 99%까지 향상됩니다."},
                {"[경고] 도어락 강제 개방 시도 시 즉시 경찰 신고 연동 안내", "외부에서 강제 비틀기, 충격 등 비정상적인 개방 시도가 감지되면 앱에서 경고음이 울림과 동시에 지정된 112 안심 센터로 현장 위치 및 가구 정보가 즉시 핫라인 전송됩니다."},
                {"[업데이트] 앱 버전 1.2.4 보안 패치 완료 안내", "일부 기기에서 발생하던 백그라운드 튕김 현상이 수정되었습니다. 실시간 탐지 기능의 연속성을 위해 반드시 구글 플레이스토어에서 최신 버전으로 업데이트를 완료해 주세요."},
                {"[방범] 1인 가구 대상 범죄 예방 수칙 가이드", "1. 택배는 무인 택배함이나 '문 앞' 배송 이용하기\n2. 배달 앱 주문 시 닉네임을 성별이 드러나지 않는 중성적 이름으로 설정하기\n3. 현관문 앞 복도에 불필요한 물건 적재하지 않기"},
                {"[안내] 패스워드 5회 오류 시 5분간 진입 제한 기능 도입", "비밀번호 무작위 대입 공격(Brute Force)을 차단하기 위해, 인증 연속 5회 실패 시 해당 계정 및 도어락 매칭 세션이 5분간 물리적으로 잠금 상태로 전환됩니다."}
        };

        RecyclerView recyclerView = findViewById(R.id.rv_notice);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(new AccordionAdapter(noticeData, "#3B82F6")); // 포인트 블루
    }
}