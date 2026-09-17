package com.example.na_honja_ansanda.ui.security;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import com.example.na_honja_ansanda.R;
import com.example.na_honja_ansanda.data.model.FaceTaskResponse;
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
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class FaceScanFragment extends Fragment {
    private static final long POLL_DELAY_MS = 2000;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<Bitmap> capturedBitmaps = new ArrayList<>();
    private final Runnable captureNext = this::startSequentialCapture;
    private final Runnable pollTask = this::checkTaskStatus;
    private LinearLayout layoutAuth, layoutCamera;
    private EditText etPw;
    private PreviewView viewFinder;
    private Button btnCapture, btnAuth, btnRetry;
    private TextView tvStatus;
    private FaceDetector detector;
    private ProcessCameraProvider cameraProvider;
    private SharedPreferences taskPreferences;
    private String userId, taskPreferenceKey, pendingTaskId;
    private int captureCount, captureGeneration;
    private boolean cameraAuthorized, cameraReady, isCapturing, isUploading;
    private Call<User> authCall;
    private Call<FaceTaskResponse> statusCall;

    private final ActivityResultLauncher<String> cameraPermission = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> {
                if (!hasView()) return;
                if (granted && cameraAuthorized) startCamera();
                else showStatus("얼굴 촬영에 카메라 권한이 필요합니다. 권한 요청이 나타나지 않으면 앱 설정에서 허용해주세요.",
                        "카메라 권한 다시 요청", this::requestCamera);
            });

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_face_scan, container, false);
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        layoutAuth = view.findViewById(R.id.layout_auth);
        layoutCamera = view.findViewById(R.id.layout_camera);
        etPw = view.findViewById(R.id.et_face_verify_pw);
        viewFinder = view.findViewById(R.id.viewFinder);
        btnCapture = view.findViewById(R.id.btn_capture);
        btnAuth = view.findViewById(R.id.btn_face_auth);
        btnRetry = view.findViewById(R.id.btn_retry_face_status);
        tvStatus = view.findViewById(R.id.tv_face_status);
        btnAuth.setOnClickListener(v -> authenticateForCamera());
        btnCapture.setOnClickListener(v -> startCaptureProcess());
        btnCapture.setEnabled(false);
        detector = FaceDetection.getClient(new FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE).build());
        User user = SessionManager.getInstance(requireContext()).getLoginUser();
        if (user == null || user.getUserId() == null) {
            btnAuth.setEnabled(false);
            showStatus("로그인 정보가 만료되었습니다. 로그아웃 후 다시 로그인해주세요.", null, null);
            return;
        }
        userId = user.getUserId();
        taskPreferences = requireContext().getSharedPreferences("face_registration_tasks", Context.MODE_PRIVATE);
        taskPreferenceKey = "pending_task_" + user.getUserNo();
        pendingTaskId = taskPreferences.getString(taskPreferenceKey, null);
        if (pendingTaskId != null) showPendingTask();
    }

    private boolean hasView() { return isAdded() && getView() != null; }

    private void showStatus(String message, @Nullable String retryLabel, @Nullable Runnable retry) {
        if (!hasView()) return;
        tvStatus.setText(message);
        tvStatus.setVisibility(View.VISIBLE);
        btnRetry.setVisibility(retry == null ? View.GONE : View.VISIBLE);
        btnRetry.setOnClickListener(retry == null ? null : v -> retry.run());
        if (retryLabel != null) btnRetry.setText(retryLabel);
    }

    private void authenticateForCamera() {
        if (userId == null || pendingTaskId != null || isUploading) return;
        String inputPw = etPw.getText().toString();
        if (inputPw.isEmpty()) {
            showStatus("계정 비밀번호를 입력해주세요.", null, null);
            return;
        }
        btnAuth.setEnabled(false);
        authCall = ApiClient.getApiService().login(new LoginRequest(userId, inputPw));
        authCall.enqueue(new Callback<User>() {
            @Override public void onResponse(Call<User> call, Response<User> response) {
                if (!hasView()) return;
                btnAuth.setEnabled(true);
                if (response.isSuccessful() && response.body() != null) {
                    cameraAuthorized = true;
                    InputMethodManager imm = (InputMethodManager) requireContext().getSystemService(Context.INPUT_METHOD_SERVICE);
                    if (imm != null) imm.hideSoftInputFromWindow(etPw.getWindowToken(), 0);
                    etPw.setText("");
                    requestCamera();
                } else {
                    showStatus(response.code() == 401 || response.code() == 403
                            ? "계정 정보가 일치하지 않습니다. 다시 확인해주세요."
                            : "계정을 확인하지 못했습니다. 잠시 후 다시 시도해주세요.", null, null);
                }
            }
            @Override public void onFailure(Call<User> call, Throwable error) {
                if (!hasView() || call.isCanceled()) return;
                btnAuth.setEnabled(true);
                showStatus("서버에 연결하지 못했습니다. 연결을 확인한 후 다시 인증해주세요.", null, null);
            }
        });
    }

    private void requestCamera() {
        if (!hasView() || !cameraAuthorized) return;
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) startCamera();
        else cameraPermission.launch(Manifest.permission.CAMERA);
    }

    private void startCamera() {
        if (!hasView()) return;
        layoutAuth.setVisibility(View.GONE);
        layoutCamera.setVisibility(View.VISIBLE);
        showStatus("카메라를 준비하고 있습니다.", null, null);
        ProcessCameraProvider.getInstance(requireContext()).addListener(() -> {
            if (!hasView() || pendingTaskId != null) return;
            try {
                ProcessCameraProvider provider = ProcessCameraProvider.getInstance(requireContext()).get();
                cameraProvider = provider;
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(viewFinder.getSurfaceProvider());
                provider.unbindAll();
                provider.bindToLifecycle(getViewLifecycleOwner(), CameraSelector.DEFAULT_FRONT_CAMERA, preview);
                cameraReady = true;
                btnCapture.setEnabled(true);
                showStatus("얼굴이 잘 보이도록 정면을 보고 촬영해주세요. 사진 3장으로 기기에서 학습합니다.", null, null);
            } catch (Exception error) {
                cameraReady = false;
                showStatus("카메라를 시작하지 못했습니다. 권한과 전면 카메라를 확인해주세요.",
                        "카메라 다시 시작", this::requestCamera);
            }
        }, ContextCompat.getMainExecutor(requireContext()));
    }

    private void startCaptureProcess() {
        if (!cameraReady || isCapturing || isUploading || pendingTaskId != null) return;
        capturedBitmaps.clear();
        captureCount = 0;
        captureGeneration++;
        isCapturing = true;
        btnCapture.setEnabled(false);
        startSequentialCapture();
    }

    private void startSequentialCapture() {
        if (!hasView() || !isResumed() || !isCapturing) return;
        if (captureCount == 3) {
            uploadFaceDataToServer();
            return;
        }
        showStatus((captureCount + 1) + "번째 사진 촬영 중입니다.", null, null);
        Bitmap bitmap = viewFinder.getBitmap();
        if (bitmap == null) {
            stopCapture();
            showStatus("사진을 가져오지 못했습니다. 다시 촬영해주세요.", null, null);
            return;
        }
        final int generation = captureGeneration;
        detector.process(InputImage.fromBitmap(bitmap, 0)).addOnSuccessListener(faces -> {
            if (!hasView() || !isCapturing || generation != captureGeneration) return;
            if (faces.size() != 1) {
                showStatus("한 사람의 얼굴이 선명하게 보이도록 위치를 조정해주세요.", null, null);
                handler.postDelayed(captureNext, 1000);
            } else {
                capturedBitmaps.add(bitmap);
                captureCount++;
                handler.postDelayed(captureNext, 800);
            }
        }).addOnFailureListener(error -> {
            if (!hasView() || generation != captureGeneration) return;
            stopCapture();
            showStatus("얼굴을 확인하지 못했습니다. 다시 촬영해주세요.", null, null);
        });
    }

    private void stopCapture() {
        isCapturing = false;
        captureGeneration++;
        handler.removeCallbacks(captureNext);
        capturedBitmaps.clear();
        if (hasView()) btnCapture.setEnabled(cameraReady && !isUploading && pendingTaskId == null);
    }

    private void uploadFaceDataToServer() {
        if (capturedBitmaps.size() != 3) return;
        isCapturing = false;
        isUploading = true;
        showStatus("사진을 전송하고 있습니다. 전송 후 기기의 학습 결과를 확인합니다.", null, null);
        RequestBody user = RequestBody.create(MediaType.parse("text/plain"), userId);
        Call<FaceTaskResponse> upload = ApiClient.getApiService().registerFace(user,
                bitmapToMultipart(capturedBitmaps.get(0), "file1"),
                bitmapToMultipart(capturedBitmaps.get(1), "file2"),
                bitmapToMultipart(capturedBitmaps.get(2), "file3"));
        capturedBitmaps.clear();
        // Preserve an accepted task even if this view is destroyed during upload.
        upload.enqueue(new Callback<FaceTaskResponse>() {
            @Override public void onResponse(Call<FaceTaskResponse> call, Response<FaceTaskResponse> response) {
                isUploading = false;
                FaceTaskResponse task = response.body();
                if (response.isSuccessful() && task != null && task.hasTaskId()) {
                    pendingTaskId = task.getTaskId();
                    taskPreferences.edit().putString(taskPreferenceKey, pendingTaskId).apply();
                    if (hasView()) {
                        showPendingTask();
                        if (isResumed()) applyTaskState(task);
                    }
                } else if (hasView()) {
                    btnCapture.setEnabled(cameraReady);
                    showStatus(response.code() == 413 ? "사진 크기가 너무 큽니다. 다시 촬영해주세요."
                            : "사진 등록을 확인하지 못했습니다. 잠시 후 다시 촬영해주세요.", null, null);
                }
            }
            @Override public void onFailure(Call<FaceTaskResponse> call, Throwable error) {
                isUploading = false;
                if (!hasView()) return;
                btnCapture.setEnabled(cameraReady);
                showStatus("사진 전송 결과를 확인하지 못했습니다. 연결을 확인한 후 다시 시도해주세요.", null, null);
            }
        });
    }

    private MultipartBody.Part bitmapToMultipart(Bitmap bitmap, String name) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, bytes);
        RequestBody file = RequestBody.create(bytes.toByteArray(), MediaType.parse("image/jpeg"));
        return MultipartBody.Part.createFormData(name, name + ".jpg", file);
    }

    private void showPendingTask() {
        if (cameraProvider != null) cameraProvider.unbindAll();
        cameraReady = false;
        layoutAuth.setVisibility(View.GONE);
        layoutCamera.setVisibility(View.GONE);
        btnCapture.setEnabled(false);
        showStatus("얼굴 등록 작업의 학습 상태를 확인하고 있습니다.", null, null);
    }

    private void checkTaskStatus() {
        if (!hasView() || !isResumed() || pendingTaskId == null || statusCall != null) return;
        handler.removeCallbacks(pollTask);
        final String requestedTaskId = pendingTaskId;
        statusCall = ApiClient.getApiService().getFaceTask(requestedTaskId, userId);
        statusCall.enqueue(new Callback<FaceTaskResponse>() {
            @Override public void onResponse(Call<FaceTaskResponse> call, Response<FaceTaskResponse> response) {
                if (call != statusCall) return;
                statusCall = null;
                if (!hasView() || !requestedTaskId.equals(pendingTaskId)) return;
                FaceTaskResponse task = response.body();
                if (response.isSuccessful() && task != null && requestedTaskId.equals(task.getTaskId())) {
                    applyTaskState(task);
                } else if (response.code() == 404) {
                    clearTask();
                    showRegistrationForm();
                    showStatus("등록 작업을 찾을 수 없습니다. 다시 인증하고 촬영해주세요.", null, null);
                } else {
                    showStatus("학습 상태를 확인하지 못했습니다. 등록 완료 여부는 아직 확인되지 않았습니다.",
                            "상태 다시 확인", FaceScanFragment.this::checkTaskStatus);
                }
            }
            @Override public void onFailure(Call<FaceTaskResponse> call, Throwable error) {
                if (call != statusCall) return;
                statusCall = null;
                if (!hasView() || call.isCanceled()) return;
                showStatus("서버 연결이 끊겨 학습 결과를 확인하지 못했습니다. 연결 후 다시 확인해주세요.",
                        "상태 다시 확인", FaceScanFragment.this::checkTaskStatus);
            }
        });
    }

    private void applyTaskState(FaceTaskResponse task) {
        if (!hasView()) return;
        switch (task.getState()) {
            case SUCCEEDED:
                clearTask();
                Toast.makeText(requireContext(), "기기 학습이 완료되어 안면 등록을 마쳤습니다.", Toast.LENGTH_LONG).show();
                if (isResumed() && getActivity() instanceof MainActivity) {
                    ((MainActivity) getActivity()).updateBottomNavigationToHome();
                    requireActivity().getSupportFragmentManager().beginTransaction()
                            .replace(R.id.main_container, new HomeFragment()).commit();
                }
                break;
            case FAILED:
                clearTask();
                showRegistrationForm();
                showStatus("기기에서 얼굴 학습에 실패했습니다. 기기 연결과 촬영 환경을 확인한 후 다시 등록해주세요.", null, null);
                break;
            case QUEUED:
            case RUNNING:
                showStatus(task.getState() == FaceTaskResponse.State.QUEUED
                        ? "사진이 접수되었습니다. 기기가 연결되어 학습을 시작하기를 기다리고 있습니다."
                        : "기기에서 얼굴을 학습하고 있습니다. 학습이 끝나면 등록 완료를 알려드립니다.", null, null);
                if (isResumed()) handler.postDelayed(pollTask, POLL_DELAY_MS);
                break;
            default:
                showStatus("학습 상태 응답을 확인할 수 없습니다. 등록 완료 여부를 다시 확인해주세요.",
                        "상태 다시 확인", this::checkTaskStatus);
        }
    }

    private void clearTask() {
        pendingTaskId = null;
        handler.removeCallbacks(pollTask);
        taskPreferences.edit().remove(taskPreferenceKey).apply();
    }

    private void showRegistrationForm() {
        cameraAuthorized = false;
        cameraReady = false;
        layoutAuth.setVisibility(View.VISIBLE);
        layoutCamera.setVisibility(View.GONE);
        btnAuth.setEnabled(true);
    }

    @Override public void onResume() {
        super.onResume();
        if (taskPreferences != null) {
            pendingTaskId = taskPreferences.getString(taskPreferenceKey, null);
        }
        if (pendingTaskId != null) checkTaskStatus();
    }

    @Override public void onPause() {
        handler.removeCallbacks(pollTask);
        if (statusCall != null) {
            statusCall.cancel();
            statusCall = null;
        }
        if (isCapturing) stopCapture();
        super.onPause();
    }

    @Override public void onDestroyView() {
        handler.removeCallbacksAndMessages(null);
        if (authCall != null) authCall.cancel();
        if (statusCall != null) statusCall.cancel();
        statusCall = null;
        captureGeneration++;
        isCapturing = false;
        cameraReady = false;
        capturedBitmaps.clear();
        if (detector != null) detector.close();
        if (cameraProvider != null) cameraProvider.unbindAll();
        cameraProvider = null;
        super.onDestroyView();
    }
}
