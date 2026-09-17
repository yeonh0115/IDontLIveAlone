package com.example.na_honja_ansanda.ui.auth;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.widget.EditText;

import androidx.appcompat.app.AppCompatActivity;

import com.example.na_honja_ansanda.R;
import com.example.na_honja_ansanda.data.remote.ApiClient;
import com.example.na_honja_ansanda.dto.SignUpRequest;
import com.google.android.material.snackbar.Snackbar;

import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class SignUPActivity extends AppCompatActivity {

    private EditText etName, etId, etPw, etPhone, etEmail;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_signup);
        initViews();
    }

    private void initViews() {
        etName = findViewById(R.id.etName);
        etId = findViewById(R.id.etUserId);
        etPw = findViewById(R.id.etPassword);
        etPhone = findViewById(R.id.etPhone);
        etEmail = findViewById(R.id.etEmail);

        findViewById(R.id.btnDoSignup).setOnClickListener(v -> processSignUp());
    }

    private void processSignUp() {
        String name = etName.getText().toString().trim();
        String id = etId.getText().toString().trim();
        String pw = etPw.getText().toString().trim();
        String phone = etPhone.getText().toString().trim();
        String email = etEmail.getText().toString().trim();

        ApiClient.getApiService().signup(new SignUpRequest(id, pw, name, phone, email))
                .enqueue(new Callback<ResponseBody>() {
                    @Override
                    public void onResponse(Call<ResponseBody> call, Response<ResponseBody> response) {
                        if (response.isSuccessful()) {
                            Snackbar.make(etName, "회원가입이 완료되었습니다.", Snackbar.LENGTH_SHORT).show();
                            startActivity(new Intent(SignUPActivity.this, LoginActivity.class));
                            finish();
                        } else {
                            Snackbar.make(etName, "회원가입에 실패했습니다.", Snackbar.LENGTH_SHORT).show();
                        }
                    }

                    @Override
                    public void onFailure(Call<ResponseBody> call, Throwable t) {
                        Log.e("SIGN_UP_ERROR", t.getMessage());
                    }
                });
    }
}