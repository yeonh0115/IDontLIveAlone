package com.example.na_honja_ansanda.ui.security;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.example.na_honja_ansanda.R;

public class SecurityHubFragment extends Fragment {

    private LinearLayout cardDoorlock, cardFacescan;
    private ImageView ivDoorlock, ivFacescan;
    private TextView tvDoorlock, tvFacescan;

    private TextView tvLastPasswordChange;
    private ProgressBar pbSecurityLevel;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_security_hub, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        initViews(view);

        if (savedInstanceState == null) {
            replaceSubFragment(new SafetyFragment());
            updateTabUI(true);
        }
    }

    private void initViews(View view) {
        cardDoorlock = view.findViewById(R.id.card_doorlock);
        cardFacescan = view.findViewById(R.id.card_facescan);
        ivDoorlock = view.findViewById(R.id.iv_doorlock);
        ivFacescan = view.findViewById(R.id.iv_facescan);
        tvDoorlock = view.findViewById(R.id.tv_doorlock);
        tvFacescan = view.findViewById(R.id.tv_facescan);

        tvLastPasswordChange = view.findViewById(R.id.tv_last_password_change);
        pbSecurityLevel = view.findViewById(R.id.pb_security_level);

        cardDoorlock.setOnClickListener(v -> {
            replaceSubFragment(new SafetyFragment());
            updateTabUI(true);
        });

        cardFacescan.setOnClickListener(v -> {
            replaceSubFragment(new FaceScanFragment());
            updateTabUI(false);
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        if (getContext() != null) {
            SharedPreferences pref = getContext().getSharedPreferences("security_prefs", Context.MODE_PRIVATE);
            String savedDate = pref.getString("last_change_date", "32일 전");
            int savedProgress = pref.getInt("security_progress", 85);

            if (tvLastPasswordChange != null) {
                tvLastPasswordChange.setText("최근 비밀번호 변경: " + savedDate);
            }
            if (pbSecurityLevel != null) {
                pbSecurityLevel.setProgress(savedProgress);
            }
        }
    }

    public void updateSecurityStatus(String textValue, int progressValue) {
        if (tvLastPasswordChange != null) {
            tvLastPasswordChange.setText("최근 비밀번호 변경: " + textValue);
        }
        if (pbSecurityLevel != null) {
            pbSecurityLevel.setProgress(progressValue);
        }

        if (getContext() != null) {
            SharedPreferences pref = getContext().getSharedPreferences("security_prefs", Context.MODE_PRIVATE);
            pref.edit()
                    .putString("last_change_date", textValue)
                    .putInt("security_progress", progressValue)
                    .apply();
        }
    }

    private void replaceSubFragment(Fragment fragment) {
        getChildFragmentManager().beginTransaction()
                .setCustomAnimations(android.R.anim.fade_in, android.R.anim.fade_out)
                .replace(R.id.security_sub_container, fragment)
                .commit();
    }

    private void updateTabUI(boolean isDoorlockSelected) {
        int activeColor = Color.parseColor("#3182F6");   // 토스 오리지널 블루 시그니처
        int inactiveColor = Color.parseColor("#8B95A1"); // 토스 소프트 차콜 그레이

        if (getView() != null) {
            androidx.cardview.widget.CardView containerDoorlock = getView().findViewById(R.id.card_container_doorlock);
            if (containerDoorlock != null) {
                // 부드러운 플랫 감성을 위해 Elevation 조작 배제하고 배경 투명도 배율만으로 탭 선택 유무 분리
                containerDoorlock.setCardBackgroundColor(isDoorlockSelected ? Color.parseColor("#FFFFFF") : Color.parseColor("#F2F4F6"));
            }

            androidx.cardview.widget.CardView containerFacescan = getView().findViewById(R.id.card_container_facescan);
            if (containerFacescan != null) {
                containerFacescan.setCardBackgroundColor(!isDoorlockSelected ? Color.parseColor("#FFFFFF") : Color.parseColor("#F2F4F6"));
            }
        }

        if (tvDoorlock != null) tvDoorlock.setTextColor(isDoorlockSelected ? activeColor : inactiveColor);
        if (ivDoorlock != null) ivDoorlock.setColorFilter(isDoorlockSelected ? activeColor : inactiveColor);

        if (tvFacescan != null) tvFacescan.setTextColor(!isDoorlockSelected ? activeColor : inactiveColor);
        if (ivFacescan != null) ivFacescan.setColorFilter(!isDoorlockSelected ? activeColor : inactiveColor);
    }
}