package com.example.na_honja_ansanda.ui.security;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.example.na_honja_ansanda.R;
import com.example.na_honja_ansanda.data.model.User;
import com.example.na_honja_ansanda.data.remote.ApiClient;
import com.example.na_honja_ansanda.data.session.SessionManager;
import com.example.na_honja_ansanda.dto.LoginRequest;
import com.example.na_honja_ansanda.ui.main.HomeFragment;
import com.example.na_honja_ansanda.ui.main.MainActivity;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class FaceScanFragment extends Fragment {

    private LinearLayout layoutAuth, layoutCamera;
    private EditText etPw;
    private PreviewView viewFinder;
    private FaceDetector detector;
    private Button btnCapture;

    private final List<Bitmap> capturedBitmaps = new ArrayList<>();
    private int captureCount = 0;
    private boolean isProcessing = false;
    private SessionManager sessionManager;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_face_scan, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        sessionManager = SessionManager.getInstance(getContext());
        initViews(view);
        initFaceDetector();
    }

    private void initViews(View view) {
        layoutAuth = view.findViewById(R.id.layout_auth);
        layoutCamera = view.findViewById(R.id.layout_camera);
        etPw = view.findViewById(R.id.et_face_verify_pw);
        viewFinder = view.findViewById(R.id.viewFinder);
        btnCapture = view.findViewById(R.id.btn_capture);
        Button btnAuth = view.findViewById(R.id.btn_face_auth);

        btnAuth.setOnClickListener(v -> authenticateForCamera());
        btnCapture.setOnClickListener(v -> startCaptureProcess());
    }

    private void initFaceDetector() {
        FaceDetectorOptions options = new FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .build();
        detector = FaceDetection.getClient(options);
    }

    private void authenticateForCamera() {
        String inputPw = etPw.getText().toString();
        if (inputPw.isEmpty()) {
            Toast.makeText(getContext(), "비밀번호를 입력해주세요.", Toast.LENGTH_SHORT).show();
            return;
        }

        String userId = sessionManager.getLoginUser().getUserId();
        ApiClient.getApiService().login(new LoginRequest(userId, inputPw)).enqueue(new Callback<User>() {
            @Override
            public void onResponse(Call<User> call, Response<User> response) {
                if (response.isSuccessful()) {
                    hideKeyboard();
                    layoutAuth.setVisibility(View.GONE);
                    layoutCamera.setVisibility(View.VISIBLE);
                    startCamera();
                } else {
                    Toast.makeText(getContext(), "비밀번호가 일치하지 않습니다.", Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onFailure(Call<User> call, Throwable t) {
                Toast.makeText(getContext(), "서버 통신 오류", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void startCaptureProcess() {
        if (isProcessing) return;
        capturedBitmaps.clear();
        captureCount = 0;
        isProcessing = true;
        btnCapture.setEnabled(false);
        startSequentialCapture();
    }

    private void startSequentialCapture() {
        if (captureCount < 3) {
            Toast.makeText(getContext(), (captureCount + 1) + "번째 사진 촬영 중...", Toast.LENGTH_SHORT).show();
            captureAndProcess();
        } else {
            uploadFaceDataToServer();
        }
    }

    private void captureAndProcess() {
        Bitmap bitmap = viewFinder.getBitmap();
        if (bitmap == null) {
            resetCaptureState();
            return;
        }

        InputImage image = InputImage.fromBitmap(bitmap, 0);
        detector.process(image)
                .addOnSuccessListener(faces -> {
                    if (faces.isEmpty()) {
                        Toast.makeText(getContext(), "얼굴 인식 실패. 재시도합니다.", Toast.LENGTH_SHORT).show();
                        new Handler(Looper.getMainLooper()).postDelayed(this::startSequentialCapture, 1000);
                    } else {
                        capturedBitmaps.add(bitmap);
                        captureCount++;
                        new Handler(Looper.getMainLooper()).postDelayed(this::startSequentialCapture, 800);
                    }
                })
                .addOnFailureListener(e -> resetCaptureState());
    }

    private void uploadFaceDataToServer() {
        Toast.makeText(getContext(), "서버로 전송 중입니다...", Toast.LENGTH_SHORT).show();
        String userIdValue = sessionManager.getLoginUser().getUserId();
        RequestBody userIdBody = RequestBody.create(MediaType.parse("text/plain"), userIdValue);

        if (capturedBitmaps.size() < 3) {
            resetCaptureState();
            return;
        }

        MultipartBody.Part part1 = bitmapToMultipart(capturedBitmaps.get(0), "file1");
        MultipartBody.Part part2 = bitmapToMultipart(capturedBitmaps.get(1), "file2");
        MultipartBody.Part part3 = bitmapToMultipart(capturedBitmaps.get(2), "file3");

        ApiClient.getApiService().registerFace(userIdBody, part1, part2, part3).enqueue(new Callback<ResponseBody>() {
            @Override
            public void onResponse(Call<ResponseBody> call, Response<ResponseBody> response) {
                resetCaptureState();
                if (response.isSuccessful()) {
                    completeAndMoveToHome();
                } else {
                    Toast.makeText(getContext(), "전송 실패 (" + response.code() + ")", Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onFailure(Call<ResponseBody> call, Throwable t) {
                resetCaptureState();
            }
        });
    }

    private MultipartBody.Part bitmapToMultipart(Bitmap bitmap, String name) {
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, stream);
        RequestBody requestFile = RequestBody.create(stream.toByteArray(), MediaType.parse("image/jpeg"));
        return MultipartBody.Part.createFormData(name, name + "_" + System.currentTimeMillis() + ".jpg", requestFile);
    }

    private void resetCaptureState() {
        isProcessing = false;
        btnCapture.setEnabled(true);
    }

    private void completeAndMoveToHome() {
        Toast.makeText(getContext(), "안면 등록 성공!", Toast.LENGTH_SHORT).show();
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).updateBottomNavigationToHome();
            getActivity().getSupportFragmentManager().beginTransaction()
                    .replace(R.id.main_container, new HomeFragment())
                    .commit();
        }
    }

    private void startCamera() {
        ProcessCameraProvider.getInstance(requireContext()).addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = ProcessCameraProvider.getInstance(requireContext()).get();
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(viewFinder.getSurfaceProvider());
                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, preview);
            } catch (Exception e) {
                Log.e("FaceScan", "Camera setup failed", e);
            }
        }, ContextCompat.getMainExecutor(requireContext()));
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) requireActivity().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null && etPw != null) {
            imm.hideSoftInputFromWindow(etPw.getWindowToken(), 0);
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (detector != null) detector.close();
    }
}