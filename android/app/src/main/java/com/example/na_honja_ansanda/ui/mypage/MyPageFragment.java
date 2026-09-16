package com.example.na_honja_ansanda.ui.mypage;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import com.example.na_honja_ansanda.R;

// 싱글톤 세션 및 유저 모델 정상 참조
import com.example.na_honja_ansanda.data.session.SessionManager;
import com.example.na_honja_ansanda.data.model.User;

public class MyPageFragment extends Fragment {

    private ImageView ivProfileAvatar;
    private TextView tvUsername, tvEmail, tvCacheSize;

    private SessionManager sessionManager;
    private SharedPreferences sharedPreferences;

    private static final String PREF_NAME = "user_prefs";
    private static final String KEY_AVATAR = "selected_avatar_res";

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.fragment_my_page, container, false);

        // UI 뷰 초기화
        ivProfileAvatar = root.findViewById(R.id.iv_profile_avatar);
        tvUsername = root.findViewById(R.id.tv_profile_name);
        tvEmail = root.findViewById(R.id.tv_profile_email);
        tvCacheSize = root.findViewById(R.id.tv_cache_size);
        TextView tvAppVersion = root.findViewById(R.id.tv_app_version);

        if (getActivity() != null) {
            // 싱글톤 객체 정상 호출
            sessionManager = SessionManager.getInstance(getActivity());
            sharedPreferences = getActivity().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);

            // 앱 버전 정보 세팅
            try {
                String versionName = getActivity().getPackageManager()
                        .getPackageInfo(getActivity().getPackageName(), 0).versionName;
                tvAppVersion.setText("v" + versionName + " (최신 버전)");
            } catch (Exception e) {
                tvAppVersion.setText("v1.0.0 (최신 버전)");
            }
        }

        // 🔄 1. DB의 users 테이블 기반 세션에서 데이터 가져와 세팅하기
        displayUserInfo();

        // 🧹 기능 1: 캐시 비우기
        root.findViewById(R.id.layout_menu_clear_cache).setOnClickListener(v -> {
            if (getContext() != null) {
                try {
                    java.io.File dir = getContext().getCacheDir();
                    if (dir != null && dir.isDirectory()) {
                        for (java.io.File child : dir.listFiles()) {
                            child.delete();
                        }
                    }
                    tvCacheSize.setText("0.0 KB");
                    Toast.makeText(getContext(), "임시 캐시 파일이 정상적으로 정리되었습니다.", Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    Toast.makeText(getContext(), "캐시 정리 중 오류가 발생했습니다.", Toast.LENGTH_SHORT).show();
                }
            }
        });

        // 💬 기능 2: 개발자에게 의견 보내기
        root.findViewById(R.id.layout_menu_feedback).setOnClickListener(v -> {
            Intent emailIntent = new Intent(Intent.ACTION_SENDTO);
            emailIntent.setData(Uri.parse("mailto:"));
            emailIntent.putExtra(Intent.EXTRA_EMAIL, new String[]{"kko20_s24035@gclass.ice.go.kr"});
            emailIntent.putExtra(Intent.EXTRA_SUBJECT, "[나혼자안산다] 서비스 이용 피드백");
            emailIntent.putExtra(Intent.EXTRA_TEXT, "여기에 소중한 의견을 작성해 주세요.\n\nApp Version: v1.0.0");

            try {
                startActivity(Intent.createChooser(emailIntent, "이메일 앱 선택"));
            } catch (android.content.ActivityNotFoundException ex) {
                Toast.makeText(getContext(), "이메일을 보낼 수 있는 앱이 없습니다.", Toast.LENGTH_SHORT).show();
            }
        });

        // 📄 기능 3: 오픈소스 라이선스 고지
        root.findViewById(R.id.layout_menu_license).setOnClickListener(v -> {
            new AlertDialog.Builder(getContext())
                    .setTitle("오픈소스 라이선스 고지")
                    .setMessage("• Android Jetpack Component\n - Apache License 2.0\n\n• Material Design Components\n - Apache License 2.0")
                    .setPositiveButton("확인", null)
                    .show();
        });
        View menuNotice = root.findViewById(R.id.layout_menu_notice);
        if (menuNotice != null) {
            menuNotice.setOnClickListener(v -> {
                Intent intent = new Intent(getActivity(), NoticeActivity.class);
                startActivity(intent);
            });
        }
        View menuFaq = root.findViewById(R.id.layout_menu_faq);
        if (menuFaq != null) {
            menuFaq.setOnClickListener(v -> {
                Intent intent = new Intent(getActivity(), FaqActivity.class);
                startActivity(intent);
            });
        }

        // 긴급전화 인텐트
        root.findViewById(R.id.btn_call_hotline).setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_DIAL);
            intent.setData(Uri.parse("tel:112"));
            startActivity(intent);
        });

        // 프로필 아바타 변경 트리거
        root.findViewById(R.id.layout_avatar_trigger).setOnClickListener(v -> showAvatarSelectionDialog());
        root.findViewById(R.id.btn_edit_profile).setOnClickListener(v -> showAvatarSelectionDialog());

        // 로그아웃 처리
        root.findViewById(R.id.btn_logout).setOnClickListener(v -> {
            if (sessionManager != null) {
                int currentAvatar = sharedPreferences.getInt(KEY_AVATAR, R.drawable.avatar_police);
                sessionManager.clearSession();
                sharedPreferences.edit().putInt(KEY_AVATAR, currentAvatar).apply();
            }
            Toast.makeText(getActivity(), "안전하게 로그아웃 되었습니다.", Toast.LENGTH_SHORT).show();


            if (getActivity() != null) {
                try {
                    Class<?> loginActivityClass = Class.forName("com.example.na_honja_ansanda.ui.auth.LoginActivity");
                    Intent intent = new Intent(getActivity(), loginActivityClass);


                    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    startActivity(intent);
                    getActivity().finish();
                } catch (ClassNotFoundException e) {
                    Toast.makeText(getActivity(), "로그인 화면을 찾을 수 없습니다.", Toast.LENGTH_SHORT).show();
                }
            }
        });

        return root;
    }

    // 👤 유저 데이터 화면 매핑
    private void displayUserInfo() {
        if (sessionManager != null && sessionManager.getLoginUser() != null) {
            User user = sessionManager.getLoginUser();

            // 유저 정보 매핑
            tvUsername.setText(user.getUsername() + " 님");
            tvEmail.setText(user.getEmail() != null && !user.getEmail().isEmpty() ? user.getEmail() : "이메일 정보 없음");

            // 아바타 이미지 세팅
            if (sharedPreferences != null) {
                int savedAvatar = sharedPreferences.getInt(KEY_AVATAR, R.drawable.avatar_police);
                ivProfileAvatar.setImageResource(savedAvatar);
            }
        }
    }

    private void changeAndSaveAvatar(int drawableId) {
        ivProfileAvatar.setImageResource(drawableId);
        if (sharedPreferences != null) {
            sharedPreferences.edit().putInt(KEY_AVATAR, drawableId).apply();
        }
    }

    private void showAvatarSelectionDialog() {
        if (getActivity() == null) return;
        AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
        LayoutInflater inflater = requireActivity().getLayoutInflater();
        View dialogView = inflater.inflate(R.layout.dialog_avatar_select, null);
        builder.setView(dialogView);

        AlertDialog dialog = builder.create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }

        dialogView.findViewById(R.id.iv_select_police).setOnClickListener(v -> { changeAndSaveAvatar(R.drawable.avatar_police); dialog.dismiss(); });
        dialogView.findViewById(R.id.iv_select_sheriff).setOnClickListener(v -> { changeAndSaveAvatar(R.drawable.avatar_sheriff); dialog.dismiss(); });
        dialogView.findViewById(R.id.iv_select_home).setOnClickListener(v -> { changeAndSaveAvatar(R.drawable.avatar_home); dialog.dismiss(); });
        dialogView.findViewById(R.id.iv_select_ghost).setOnClickListener(v -> { changeAndSaveAvatar(R.drawable.avatar_ghost); dialog.dismiss(); });

        dialog.show();
    }
}