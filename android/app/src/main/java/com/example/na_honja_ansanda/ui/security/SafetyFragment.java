package com.example.na_honja_ansanda.ui.security;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.example.na_honja_ansanda.R;
import com.example.na_honja_ansanda.data.model.User;
import com.example.na_honja_ansanda.data.remote.ApiClient;
import com.example.na_honja_ansanda.data.session.SessionManager;
import com.example.na_honja_ansanda.dto.DoorLockRequest;
import com.example.na_honja_ansanda.dto.LoginRequest;

import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class SafetyFragment extends Fragment {

    private EditText etCurrentPw, etNewDoorPw, etConfirmDoorPw;
    private LinearLayout layoutNewPw;
    private Button btnVerify, btnSubmit;
    private SessionManager sessionManager;
    private String verifiedAccountPassword = "";

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_safety, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        sessionManager = SessionManager.getInstance(getContext());
        initViews(view);
    }

    private void initViews(View view) {
        etCurrentPw = view.findViewById(R.id.et_current_pw);
        etNewDoorPw = view.findViewById(R.id.et_new_door_pw);
        etConfirmDoorPw = view.findViewById(R.id.et_confirm_door_pw);
        layoutNewPw = view.findViewById(R.id.layout_new_pw);
        btnVerify = view.findViewById(R.id.btn_verify);
        btnSubmit = view.findViewById(R.id.btn_submit);

        if (btnVerify != null) {
            btnVerify.setOnClickListener(v -> verifyAccountPassword());
        }
        if (btnSubmit != null) {
            btnSubmit.setOnClickListener(v -> submitNewDoorPassword());
        }
    }

    private void verifyAccountPassword() {
        if (etCurrentPw == null || sessionManager == null || sessionManager.getLoginUser() == null) return;

        String inputPw = etCurrentPw.getText().toString();
        if (inputPw.isEmpty()) {
            Toast.makeText(getContext(), "비밀번호를 입력해주세요.", Toast.LENGTH_SHORT).show();
            return;
        }

        String userId = sessionManager.getLoginUser().getUserId();
        ApiClient.getApiService().login(new LoginRequest(userId, inputPw)).enqueue(new Callback<User>() {
            @Override
            public void onResponse(Call<User> call, Response<User> response) {
                if (response.isSuccessful()) {
                    verifiedAccountPassword = inputPw;

                    if (layoutNewPw != null) layoutNewPw.setVisibility(View.VISIBLE);
                    if (btnVerify != null) btnVerify.setEnabled(false);
                    if (etCurrentPw != null) etCurrentPw.setEnabled(false);
                    Toast.makeText(getContext(), "계정 인증 완료. 새 번호를 입력하세요.", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(getContext(), "비밀번호가 일치하지 않습니다.", Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onFailure(Call<User> call, Throwable t) {
                Toast.makeText(getContext(), "서버 오류", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void submitNewDoorPassword() {
        if (etNewDoorPw == null || etConfirmDoorPw == null || sessionManager == null || sessionManager.getLoginUser() == null) return;

        String newPw = etNewDoorPw.getText().toString();
        String confirmPw = etConfirmDoorPw.getText().toString();

        if (newPw.isEmpty() || confirmPw.isEmpty()) {
            Toast.makeText(getContext(), "새로운 도어락 번호를 입력해주세요.", Toast.LENGTH_SHORT).show();
            return;
        }

        if (!newPw.equals(confirmPw)) {
            Toast.makeText(getContext(), "도어락 비밀번호가 일치하지 않습니다.", Toast.LENGTH_SHORT).show();
            return;
        }

        String userId = sessionManager.getLoginUser().getUserId();
        String currentPw = verifiedAccountPassword;

        ApiClient.getApiService().updateDoorLock(new DoorLockRequest(userId, currentPw, newPw))
                .enqueue(new Callback<ResponseBody>() {
                    @Override
                    public void onResponse(Call<ResponseBody> call, Response<ResponseBody> response) {
                        if (response.isSuccessful()) {
                            Toast.makeText(getContext(), "도어락 비밀번호 변경 완료!", Toast.LENGTH_LONG).show();

                            // 💡 부모 프래그먼트에 데이터 영구 전송 연동 (오늘로 텍스트 변경 및 프로그레스 100 만점 채우기)
                            if (getParentFragment() instanceof SecurityHubFragment) {
                                ((SecurityHubFragment) getParentFragment()).updateSecurityStatus("오늘", 100);
                            }

                            resetForm();
                        } else {
                            Toast.makeText(getContext(), "변경 실패: 서버 검증 오류 (" + response.code() + ")", Toast.LENGTH_SHORT).show();
                        }
                    }

                    @Override
                    public void onFailure(Call<ResponseBody> call, Throwable t) {
                        Toast.makeText(getContext(), "서버 통신 오류", Toast.LENGTH_SHORT).show();
                    }
                });
    }

    private void resetForm() {
        if (etCurrentPw != null) etCurrentPw.setText("");
        if (etNewDoorPw != null) etNewDoorPw.setText("");
        if (etConfirmDoorPw != null) etConfirmDoorPw.setText("");

        verifiedAccountPassword = "";

        if (layoutNewPw != null) layoutNewPw.setVisibility(View.GONE);
        if (btnVerify != null) btnVerify.setEnabled(true);
        if (etCurrentPw != null) etCurrentPw.setEnabled(true);
    }
}