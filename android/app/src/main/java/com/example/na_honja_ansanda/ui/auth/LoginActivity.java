package com.example.na_honja_ansanda.ui.auth;

import android.animation.AnimatorSet;
import android.animation.ArgbEvaluator;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.CycleInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;

import androidx.appcompat.app.AppCompatActivity;
import androidx.constraintlayout.widget.ConstraintLayout;

import com.example.na_honja_ansanda.R;
import com.example.na_honja_ansanda.data.model.User;
import com.example.na_honja_ansanda.data.remote.ApiClient;
import com.example.na_honja_ansanda.data.session.SessionManager;
import com.example.na_honja_ansanda.dto.LoginRequest;
import com.example.na_honja_ansanda.ui.main.MainActivity;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class LoginActivity extends AppCompatActivity {

    private TextInputEditText etId, etPw;
    private ProgressBar progressBar;
    private SessionManager sessionManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);

        try {
            sessionManager = SessionManager.getInstance(this);
        } catch (Exception e) {
            e.printStackTrace();
        }

        initViews();

        // 🎬 초강렬 쿵-쾅! 스핀 하강 및 크래시 지진 진동 시네마틱 가동
        getWindow().getDecorView().post(this::startHardcoreLockDownCinematic);
    }

    private void initViews() {
        etId = findViewById(R.id.idController);
        etPw = findViewById(R.id.pwController);
        progressBar = findViewById(R.id.isLoading);

        // 로그인 버튼 리스너 연결
        View btnLogin = findViewById(R.id.btnLogin);
        if (btnLogin != null) {
            btnLogin.setOnClickListener(v -> attemptLogin());
        }

        // 회원가입 버튼 리스너 연결 (예전 코드 기능 통합)
        View btnSignup = findViewById(R.id.tvSignUp);
        if (btnSignup != null) {
            btnSignup.setOnClickListener(v -> {
                startActivity(new Intent(this, SignUPActivity.class));
            });
        }
    }

    /**
     * 🔒🔥 [4.0초 하드코어 보안 오프닝: 회전 낙하 압착 ➔ 지진 진동 크래시 ➔ 로고 대전환]
     */
    private void startHardcoreLockDownCinematic() {
        final ConstraintLayout root = findViewById(R.id.layout_root);
        final View flash = findViewById(R.id.view_flash);
        final ConstraintLayout lockMaster = findViewById(R.id.layout_lock_master);
        final ImageView imgShackle = findViewById(R.id.img_lock_shackle);
        final LinearLayout layoutTitleText = findViewById(R.id.layout_title_text);
        final LinearLayout loginForm = findViewById(R.id.layout_login_form);

        if (root == null || lockMaster == null || imgShackle == null || layoutTitleText == null || loginForm == null || flash == null) return;

        // 초기 대기 상태: 걸쇠를 화면 위 저 멀리 보내고, 거대하게 키운 뒤 훼방 회전시켜 둠
        imgShackle.setTranslationY(-500f);
        imgShackle.setScaleX(2.5f);
        imgShackle.setScaleY(2.5f);
        imgShackle.setRotation(-180f);

        // ==========================================
        // 🎞️ PHASE 1: 걸쇠가 회전 스핀(빙글빙글)하며 초고속 낙하 압착 (0.0s ~ 0.9s) [900ms]
        // ==========================================
        ObjectAnimator shackleDrop = ObjectAnimator.ofFloat(imgShackle, "translationY", 0f);
        ObjectAnimator shackleSpin = ObjectAnimator.ofFloat(imgShackle, "rotation", -180f, 0f);
        ObjectAnimator shackleScaleX = ObjectAnimator.ofFloat(imgShackle, "scaleX", 2.5f, 1.0f);
        ObjectAnimator shackleScaleY = ObjectAnimator.ofFloat(imgShackle, "scaleY", 2.5f, 1.0f);

        AnimatorSet phaseOneSpinDrop = new AnimatorSet();
        phaseOneSpinDrop.playTogether(shackleDrop, shackleSpin, shackleScaleX, shackleScaleY);
        phaseOneSpinDrop.setInterpolator(new AccelerateInterpolator(2.0f));
        phaseOneSpinDrop.setDuration(900);

        // ==========================================
        // 🎞️ PHASE 2: 💥 철컥! 결합 순간 자물쇠 전체가 사방으로 덜덜덜 흔들리는 초강렬 지진 진동 (0.9s ~ 1.7s) [800ms]
        // ==========================================
        ObjectAnimator shakeX = ObjectAnimator.ofFloat(lockMaster, "translationX", 0f, 35);
        shakeX.setInterpolator(new CycleInterpolator(6f));
        shakeX.setDuration(800);

        ObjectAnimator shakeY = ObjectAnimator.ofFloat(lockMaster, "translationY", 0f, 25);
        shakeY.setInterpolator(new CycleInterpolator(5f));
        shakeY.setDuration(800);

        ObjectAnimator bodyPulseX = ObjectAnimator.ofFloat(lockMaster, "scaleX", 1.0f, 1.2f, 1.0f);
        ObjectAnimator bodyPulseY = ObjectAnimator.ofFloat(lockMaster, "scaleY", 1.0f, 0.8f, 1.0f);
        bodyPulseX.setDuration(250);
        bodyPulseY.setDuration(250);

        AnimatorSet phaseTwoEarthquake = new AnimatorSet();
        phaseTwoEarthquake.playTogether(shakeX, shakeY, bodyPulseX, bodyPulseY);

        // ==========================================
        // 🎞️ PHASE 3: 자물쇠가 전면으로 거대하게 폭발 분쇄되며 소멸, 동시에 브랜드 로고 등장 (1.7s ~ 2.9s) [1200ms]
        // ==========================================
        ObjectAnimator lockFadeOut = ObjectAnimator.ofFloat(lockMaster, "alpha", 1f, 0f);
        ObjectAnimator lockExplodeX = ObjectAnimator.ofFloat(lockMaster, "scaleX", 1.0f, 4.0f);
        ObjectAnimator lockExplodeY = ObjectAnimator.ofFloat(lockMaster, "scaleY", 1.0f, 4.0f);

        ObjectAnimator titleReveal = ObjectAnimator.ofFloat(layoutTitleText, "alpha", 0f, 1f);
        ObjectAnimator titleScaleInX = ObjectAnimator.ofFloat(layoutTitleText, "scaleX", 0.4f, 1.2f, 1.0f);
        ObjectAnimator titleScaleInY = ObjectAnimator.ofFloat(layoutTitleText, "scaleY", 0.4f, 1.2f, 1.0f);

        AnimatorSet phaseThreeTransition = new AnimatorSet();
        phaseThreeTransition.playTogether(lockFadeOut, lockExplodeX, lockExplodeY, titleReveal, titleScaleInX, titleScaleInY);
        phaseThreeTransition.setInterpolator(new OvershootInterpolator(1.1f));
        phaseThreeTransition.setDuration(1200);

        // ==========================================
        // 🎞️ PHASE 4: 스페이스 차원 전환 및 명품 로그인 폼 완성 (2.9s ~ 4.0s) [1100ms]
        // ==========================================
        ObjectAnimator flashIn = ObjectAnimator.ofFloat(flash, "alpha", 1f);
        flashIn.setDuration(60);
        ObjectAnimator flashOut = ObjectAnimator.ofFloat(flash, "alpha", 0f);
        flashOut.setDuration(440);
        AnimatorSet flashTimeline = new AnimatorSet();
        flashTimeline.playSequentially(flashIn, flashOut);

        ValueAnimator spaceTransition = ValueAnimator.ofObject(new ArgbEvaluator(), 0xFF05070C, 0xFFF2F4F6);
        spaceTransition.addUpdateListener(animation -> root.setBackgroundColor((int) animation.getAnimatedValue()));
        spaceTransition.setDuration(400);

        ObjectAnimator titleMoveUp = ObjectAnimator.ofFloat(layoutTitleText, "translationY", -220f);
        ObjectAnimator titleSettleScaleX = ObjectAnimator.ofFloat(layoutTitleText, "scaleX", 0.85f);
        ObjectAnimator titleSettleScaleY = ObjectAnimator.ofFloat(layoutTitleText, "scaleY", 0.85f);

        ObjectAnimator formFade = ObjectAnimator.ofFloat(loginForm, "alpha", 1f);
        ObjectAnimator formRise = ObjectAnimator.ofFloat(loginForm, "translationY", 0f);

        AnimatorSet phaseFourFinalize = new AnimatorSet();
        phaseFourFinalize.playTogether(flashTimeline, spaceTransition, titleMoveUp, titleSettleScaleX, titleSettleScaleY, formFade, formRise);
        phaseFourFinalize.setInterpolator(new DecelerateInterpolator(2.0f));
        phaseFourFinalize.setDuration(1100);

        // 🎬 총 4초간 몰아치는 시네마틱 마스터 디렉터 결합 재생
        final AnimatorSet dynamicDirector = new AnimatorSet();
        dynamicDirector.playSequentially(phaseOneSpinDrop, phaseTwoEarthquake, phaseThreeTransition, phaseFourFinalize);
        dynamicDirector.start();
    }

    /**
     * 🔐 실제 로그인 요청 처리 프로세스
     */
    private void attemptLogin() {
        if (etId == null || etPw == null) return;

        String id = etId.getText() != null ? etId.getText().toString().trim() : "";
        String pw = etPw.getText() != null ? etPw.getText().toString().trim() : "";

        if (id.isEmpty() || pw.isEmpty()) {
            Snackbar.make(etId, "아이디와 비밀번호를 모두 입력해주세요.", Snackbar.LENGTH_SHORT).show();
            return;
        }

        // 로딩 바 활성화
        if (progressBar != null) {
            progressBar.setVisibility(View.VISIBLE);
        }

        // Retrofit API 로그인 요청 시작
        ApiClient.getApiService().login(new LoginRequest(id, pw)).enqueue(new Callback<User>() {
            @Override
            public void onResponse(Call<User> call, Response<User> response) {
                // 응답 처리 완료 후 로딩 바 숨기기
                if (progressBar != null) progressBar.setVisibility(View.GONE);

                if (response.isSuccessful() && response.body() != null) {
                    // 세션 생성 및 저장
                    if (sessionManager == null || !sessionManager.createSession(response.body())) {
                        Snackbar.make(etId, "로그인 정보를 안전하게 저장하지 못했습니다. 다시 시도해주세요.", Snackbar.LENGTH_LONG).show();
                        return;
                    }

                    Snackbar.make(etId, String.format("%s님, 환영합니다!", response.body().getUsername()), Snackbar.LENGTH_SHORT).show();

                    // 메인 화면으로 이동 및 현재 화면 종료
                    startActivity(new Intent(LoginActivity.this, MainActivity.class));
                    finish();
                } else {
                    Snackbar.make(etId, "회원정보가 일치하지 않습니다.", Snackbar.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onFailure(Call<User> call, Throwable t) {
                // 네트워크 오류 발생 시 로딩 바 숨기기
                if (progressBar != null) progressBar.setVisibility(View.GONE);

                Log.e("API_ERROR", t.getMessage() != null ? t.getMessage() : "Unknown error");
                Snackbar.make(etId, "서버 연결에 실패했습니다.", Snackbar.LENGTH_SHORT).show();
            }
        });
    }
}
