package com.example.na_honja_ansanda.ui.security;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.example.na_honja_ansanda.R;
import com.example.na_honja_ansanda.data.model.DeviceInfo;
import com.example.na_honja_ansanda.data.model.DevicePairing;
import com.example.na_honja_ansanda.data.model.User;
import com.example.na_honja_ansanda.data.remote.ApiClient;
import com.example.na_honja_ansanda.data.session.SessionManager;
import com.example.na_honja_ansanda.dto.DeviceClaimRequest;
import com.example.na_honja_ansanda.ui.auth.LoginActivity;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.util.List;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class DeviceLinkActivity extends AppCompatActivity {
    private SessionManager session;
    private String authorization, verifiedCode;
    private EditText codeInput;
    private Button lookupButton, claimButton, refreshButton, loginButton;
    private TextView statusText, previewText;
    private LinearLayout previewLayout, deviceList;
    private Call<DeviceInfo> previewCall, claimCall;
    private Call<List<DeviceInfo>> listCall;
    private Call<Void> unlinkCall;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_device_link);
        session = SessionManager.getInstance(this);
        authorization = session.getAuthorizationHeader();
        codeInput = findViewById(R.id.et_device_code);
        lookupButton = findViewById(R.id.btn_lookup_device);
        claimButton = findViewById(R.id.btn_claim_device);
        refreshButton = findViewById(R.id.btn_refresh_devices);
        loginButton = findViewById(R.id.btn_device_login);
        statusText = findViewById(R.id.tv_device_status);
        previewText = findViewById(R.id.tv_device_preview);
        previewLayout = findViewById(R.id.layout_device_preview);
        deviceList = findViewById(R.id.layout_linked_devices);
        findViewById(R.id.btn_device_back).setOnClickListener(v -> finish());
        lookupButton.setOnClickListener(v -> inspectCode());
        claimButton.setOnClickListener(v -> claim());
        refreshButton.setOnClickListener(v -> loadDevices());
        loginButton.setOnClickListener(v -> {
            session.clearSession();
            Intent intent = new Intent(this, LoginActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            finish();
        });
        codeInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable text) {
                verifiedCode = null;
                previewLayout.setVisibility(View.GONE);
                if (previewCall != null) previewCall.cancel();
                previewCall = null;
                updateControls();
            }
        });
        User user = session.getLoginUser();
        TextView account = findViewById(R.id.tv_device_account);
        account.setText(user == null ? "로그인이 필요합니다." : user.getUsername() + " 님의 계정에 연결합니다.");
        if (authorization == null || user == null) requireLogin();
        else {
            updateControls();
            loadDevices();
        }
    }

    private boolean active() { return !isFinishing() && !isDestroyed(); }

    private void updateControls() {
        boolean loggedIn = authorization != null;
        boolean claiming = claimCall != null;
        codeInput.setEnabled(loggedIn && !claiming);
        lookupButton.setEnabled(loggedIn && !claiming && previewCall == null
                && !DevicePairing.normalizedCode(codeInput.getText().toString()).isEmpty());
        claimButton.setEnabled(loggedIn && !claiming && verifiedCode != null);
        refreshButton.setEnabled(loggedIn && listCall == null);
    }

    private void requireLogin() {
        authorization = null;
        verifiedCode = null;
        previewLayout.setVisibility(View.GONE);
        deviceList.removeAllViews();
        statusText.setText("기기를 계정에 연결하려면 다시 로그인해주세요.");
        loginButton.setVisibility(View.VISIBLE);
        updateControls();
    }

    private String errorMessage(int status) {
        if (status == 401) {
            session.clearSession();
            requireLogin();
            return "로그인 유효기간이 끝났습니다. 다시 로그인해주세요.";
        }
        if (status == 403) return "이 계정에서는 해당 기기를 연결하거나 변경할 수 없습니다.";
        if (status == 404 || status == 410) return "연결코드를 찾을 수 없거나 만료되었습니다. 기기에서 새 코드를 발급해주세요.";
        if (status == 409) return "이미 연결된 기기이거나 같은 종류의 기기가 있습니다. 연결 목록을 확인해주세요.";
        if (status == 429) return "요청이 많습니다. 잠시 기다린 뒤 다시 시도해주세요.";
        return "기기 연결 요청을 처리하지 못했습니다. 잠시 후 다시 시도해주세요.";
    }

    private void inspectCode() {
        String code = DevicePairing.normalizedCode(codeInput.getText().toString());
        if (authorization == null || code.isEmpty() || claimCall != null) return;
        verifiedCode = null;
        previewLayout.setVisibility(View.GONE);
        statusText.setText("연결할 기기의 종류와 이름을 확인하고 있습니다.");
        previewCall = ApiClient.getApiService().previewDevice(authorization, code);
        updateControls();
        previewCall.enqueue(new Callback<DeviceInfo>() {
            @Override public void onResponse(Call<DeviceInfo> call, Response<DeviceInfo> response) {
                if (!active() || call != previewCall) return;
                previewCall = null;
                DeviceInfo device = response.body();
                if (response.isSuccessful() && device != null && DevicePairing.isKnownRole(device.getRole())
                        && code.equals(DevicePairing.normalizedCode(codeInput.getText().toString()))) {
                    verifiedCode = code;
                    previewText.setText(deviceName(device) + "\n" + DevicePairing.roleLabel(device.getRole()));
                    previewLayout.setVisibility(View.VISIBLE);
                    statusText.setText("내 기기의 이름과 종류가 맞는지 확인한 뒤 연결해주세요.");
                } else statusText.setText(errorMessage(response.code()));
                updateControls();
            }
            @Override public void onFailure(Call<DeviceInfo> call, Throwable error) {
                if (!active() || call != previewCall || call.isCanceled()) return;
                previewCall = null;
                statusText.setText("서버에 연결하지 못했습니다. 네트워크를 확인한 후 다시 확인해주세요.");
                updateControls();
            }
        });
    }

    private void claim() {
        if (authorization == null || verifiedCode == null || claimCall != null) return;
        if (!verifiedCode.equals(DevicePairing.normalizedCode(codeInput.getText().toString()))) return;
        statusText.setText("확인한 기기를 현재 계정에 연결하고 있습니다.");
        claimCall = ApiClient.getApiService().claimDevice(authorization, new DeviceClaimRequest(verifiedCode));
        updateControls();
        claimCall.enqueue(new Callback<DeviceInfo>() {
            @Override public void onResponse(Call<DeviceInfo> call, Response<DeviceInfo> response) {
                if (!active() || call != claimCall) return;
                claimCall = null;
                DeviceInfo device = response.body();
                if (response.isSuccessful() && device != null && device.isPaired()
                        && device.getUserNo() != null && device.getUserNo() == session.getUserNo()) {
                    statusText.setText(deviceName(device) + " 기기를 계정에 연결했습니다. 기기가 켜지면 설정을 자동으로 받습니다.");
                    verifiedCode = null;
                    codeInput.setText("");
                    previewLayout.setVisibility(View.GONE);
                } else statusText.setText(errorMessage(response.code()));
                updateControls();
                if (authorization != null) loadDevices();
            }
            @Override public void onFailure(Call<DeviceInfo> call, Throwable error) {
                if (!active() || call != claimCall || call.isCanceled()) return;
                claimCall = null;
                statusText.setText("연결 결과를 확인하지 못했습니다. 연결 목록을 새로고침해 등록 여부를 확인해주세요.");
                updateControls();
                loadDevices();
            }
        });
    }

    private String deviceName(DeviceInfo device) {
        return device.getName() == null || device.getName().trim().isEmpty() ? "연결 기기" : device.getName();
    }

    private void listMessage(String text) {
        deviceList.removeAllViews();
        TextView message = new TextView(this);
        message.setText(text);
        message.setPadding(0, 20, 0, 20);
        deviceList.addView(message);
    }

    private void loadDevices() {
        if (authorization == null || listCall != null) return;
        listMessage("연결 목록을 불러오고 있습니다.");
        listCall = ApiClient.getApiService().getDevices(authorization);
        updateControls();
        listCall.enqueue(new Callback<List<DeviceInfo>>() {
            @Override public void onResponse(Call<List<DeviceInfo>> call, Response<List<DeviceInfo>> response) {
                if (!active() || call != listCall) return;
                listCall = null;
                if (response.isSuccessful() && response.body() != null) renderDevices(response.body());
                else {
                    if (response.code() == 401) errorMessage(401);
                    listMessage("연결 목록을 확인하지 못했습니다. 다시 불러와주세요.");
                }
                updateControls();
            }
            @Override public void onFailure(Call<List<DeviceInfo>> call, Throwable error) {
                if (!active() || call != listCall || call.isCanceled()) return;
                listCall = null;
                listMessage("서버 연결이 끊겨 목록을 확인하지 못했습니다.");
                updateControls();
            }
        });
    }

    private void renderDevices(List<DeviceInfo> devices) {
        deviceList.removeAllViews();
        if (devices.isEmpty()) {
            listMessage("아직 연결한 기기가 없습니다.");
            return;
        }
        for (DeviceInfo device : devices) {
            if (device == null || device.getDeviceId() == null) continue;
            TextView title = new TextView(this);
            title.setText(deviceName(device) + "\n" + DevicePairing.roleLabel(device.getRole()) + " · 계정에 연결됨");
            title.setTextSize(16);
            title.setPadding(0, 24, 0, 8);
            deviceList.addView(title);
            Button remove = new Button(this);
            remove.setText("연결 해제");
            remove.setOnClickListener(v -> new MaterialAlertDialogBuilder(this)
                    .setTitle(deviceName(device) + " 연결 해제")
                    .setMessage("이 기기의 계정 연결을 해제합니다. 기존 보안 기록은 유지됩니다.")
                    .setNegativeButton("취소", null)
                    .setPositiveButton("연결 해제", (dialog, which) -> unlink(device)).show());
            deviceList.addView(remove);
        }
    }

    private void unlink(DeviceInfo device) {
        if (authorization == null || unlinkCall != null) return;
        unlinkCall = ApiClient.getApiService().unlinkDevice(authorization, device.getDeviceId());
        unlinkCall.enqueue(new Callback<Void>() {
            @Override public void onResponse(Call<Void> call, Response<Void> response) {
                if (!active() || call != unlinkCall) return;
                unlinkCall = null;
                statusText.setText(response.isSuccessful() ? "기기 연결을 해제했습니다." : errorMessage(response.code()));
                loadDevices();
            }
            @Override public void onFailure(Call<Void> call, Throwable error) {
                if (!active() || call != unlinkCall || call.isCanceled()) return;
                unlinkCall = null;
                statusText.setText("연결 해제 결과를 확인하지 못했습니다. 목록을 다시 확인해주세요.");
                loadDevices();
            }
        });
    }

    @Override protected void onDestroy() {
        if (previewCall != null) previewCall.cancel();
        if (claimCall != null) claimCall.cancel();
        if (listCall != null) listCall.cancel();
        if (unlinkCall != null) unlinkCall.cancel();
        authorization = null;
        super.onDestroy();
    }
}
