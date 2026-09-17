package com.example.na_honja_ansanda.ui.main;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.cardview.widget.CardView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.example.na_honja_ansanda.R;
import com.example.na_honja_ansanda.data.model.IntegratedLog;
import com.example.na_honja_ansanda.data.model.LoadState;
import com.example.na_honja_ansanda.data.remote.ApiClient;
import com.example.na_honja_ansanda.data.session.SessionManager;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import com.example.na_honja_ansanda.data.model.ServerTimestamp;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class HomeFragment extends Fragment {

    private static final String TAG = "SENSOR_DEBUG";
    private static final int REQUEST_RECORD_AUDIO_PERMISSION = 200;

    private ProgressBar audioProgressBar;
    private WebView webView;
    private WebSocket webSocket;
    private AudioRecord audioRecord;

    private boolean isRecording = false;

    private LinearLayout sensorContainer;
    private TextView tvDangerCount, tvDangerTypes, tvTimelineTitle, tvFilterBadge;
    private LinearLayout btnDangerBucket, btnTypeBucket, btnTimelineHeader;
    private SessionManager sessionManager;

    private ImageButton btnMic;

    private List<IntegratedLog> originalLogList = new ArrayList<>();
    private String currentFilterMode = "all";
    private LoadState logLoadState = LoadState.LOADING;
    private Call<List<IntegratedLog>> logsCall;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_home, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        if (getContext() == null) return;
        sessionManager = SessionManager.getInstance(getContext());

        initViews(view);
        setupWebView();
        fetchSensorLogs();
    }

    private void initViews(View view) {
        audioProgressBar = view.findViewById(R.id.audio_progress);
        webView = view.findViewById(R.id.cctv_view);
        sensorContainer = view.findViewById(R.id.sensor_container);

        tvDangerCount = view.findViewById(R.id.tv_danger_count);
        tvDangerTypes = view.findViewById(R.id.tv_danger_types);
        tvTimelineTitle = view.findViewById(R.id.tv_timeline_title);
        tvFilterBadge = view.findViewById(R.id.tv_filter_badge);

        btnDangerBucket = view.findViewById(R.id.layout_danger_count_bucket);
        btnTypeBucket = view.findViewById(R.id.layout_danger_type_bucket);
        btnTimelineHeader = view.findViewById(R.id.layout_timeline_header);

        if (btnDangerBucket != null) {
            btnDangerBucket.setOnClickListener(v -> toggleFilter("high"));
        }
        if (btnTypeBucket != null) {
            btnTypeBucket.setOnClickListener(v -> toggleFilter("medium"));
        }
        if (btnTimelineHeader != null) {
            btnTimelineHeader.setOnClickListener(v -> toggleFilter("all"));
        }

        btnMic = view.findViewById(R.id.btn_mic);
        if (btnMic != null) {
            // 동그라미 버튼을 감싸고 있는 네모 박스 영역(Parent View)에도 클릭 리스너 등록
            if (btnMic.getParent() instanceof View) {
                View voiceBox = (View) btnMic.getParent();
                voiceBox.setOnClickListener(v -> checkPermissionAndToggleVoice());
            }
            btnMic.setOnClickListener(v -> checkPermissionAndToggleVoice());
            updateMicButtonUI();
        }
    }

    private void setupWebView() {
        if (webView == null) return;
        WebSettings settings = webView.getSettings();

        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }

        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                Log.d("WEBVIEW_DEBUG", "CCTV 스트리밍 주소 로딩 시작: " + url);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                Log.d("WEBVIEW_DEBUG", "CCTV 스트리밍 주소 로딩 완료!");
            }

            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                super.onReceivedError(view, errorCode, description, failingUrl);
                Log.e("WEBVIEW_DEBUG", "WebView 로드 에러: " + description + " (코드: " + errorCode + ")");
            }
        });

        String authorization = sessionManager == null ? null : sessionManager.getAuthorizationHeader();
        if (authorization == null) {
            webView.loadData("<html><body style='color:white;background:#182132'>카메라 영상을 보려면 다시 로그인하고 기기를 연결해주세요.</body></html>",
                    "text/html; charset=utf-8", "UTF-8");
            return;
        }
        webView.loadUrl("https://idontlivealone.onrender.com/video_feed",
                java.util.Collections.singletonMap("Authorization", authorization));
    }

    private void fetchSensorLogs() {
        if (!isAdded() || getView() == null) return;
        if (sessionManager == null || sessionManager.getUserNo() == -1) {
            logLoadState = LoadState.FAILED;
            showSensorState("로그인 정보를 확인할 수 없습니다. 다시 로그인해주세요.", false);
            return;
        }
        if (logsCall != null) logsCall.cancel();
        logLoadState = LoadState.LOADING;
        showSensorState("보안 기록을 불러오고 있습니다.", false);
        logsCall = ApiClient.getApiService().getIntegratedLogs(sessionManager.getUserNo());
        logsCall.enqueue(new Callback<List<IntegratedLog>>() {
                    @Override
                    public void onResponse(@NonNull Call<List<IntegratedLog>> call, @NonNull Response<List<IntegratedLog>> response) {
                        if (!isAdded() || getView() == null || call != logsCall) return;

                        if (response.isSuccessful() && response.body() != null) {
                            logLoadState = LoadState.READY;
                            originalLogList = response.body();
                            updateRealSensorUI(originalLogList);
                        } else {
                            logLoadState = LoadState.FAILED;
                            showSensorState("보안 기록을 불러오지 못했습니다. 다시 시도해주세요.", true);
                        }
                    }

                    @Override
                    public void onFailure(@NonNull Call<List<IntegratedLog>> call, @NonNull Throwable t) {
                        if (!isAdded() || getView() == null || call.isCanceled() || call != logsCall) return;
                        logLoadState = LoadState.FAILED;
                        showSensorState("서버에 연결하지 못했습니다. 연결을 확인한 후 다시 시도해주세요.", true);
                    }
                });
    }

    private void showSensorState(String message, boolean canRetry) {
        if (tvDangerCount != null) tvDangerCount.setText("—");
        if (tvDangerTypes != null) tvDangerTypes.setText("—");
        if (sensorContainer == null || getContext() == null) return;
        sensorContainer.removeAllViews();
        TextView status = new TextView(getContext());
        status.setText(message);
        status.setTextSize(14);
        status.setGravity(Gravity.CENTER);
        status.setPadding(16, 40, 16, 24);
        sensorContainer.addView(status);
        if (canRetry) {
            android.widget.Button retry = new android.widget.Button(getContext());
            retry.setText("다시 불러오기");
            retry.setOnClickListener(v -> fetchSensorLogs());
            sensorContainer.addView(retry);
        }
    }

    private void toggleFilter(String targetFilter) {
        if (getContext() == null) return;

        if (currentFilterMode.equals(targetFilter)) {
            currentFilterMode = "all";
        } else {
            currentFilterMode = targetFilter;
        }

        float density = getContext().getResources().getDisplayMetrics().density;
        int cornerRadiusPx = (int) (20 * density);

        GradientDrawable defaultBg = new GradientDrawable();
        defaultBg.setColor(Color.parseColor("#FFFFFF"));
        defaultBg.setCornerRadius(cornerRadiusPx);

        if (btnDangerBucket != null) btnDangerBucket.setBackground(defaultBg);
        if (btnTypeBucket != null) btnTypeBucket.setBackground(defaultBg);

        if (tvFilterBadge == null) return;

        if ("all".equals(currentFilterMode)) {
            tvFilterBadge.setVisibility(View.GONE);
        } else if ("high".equals(currentFilterMode)) {
            GradientDrawable highSelect = new GradientDrawable();
            highSelect.setColor(Color.parseColor("#FFF0F2"));
            highSelect.setCornerRadius(cornerRadiusPx);
            highSelect.setStroke((int) (2 * density), Color.parseColor("#F04452"));
            if (btnDangerBucket != null) btnDangerBucket.setBackground(highSelect);

            tvFilterBadge.setVisibility(View.VISIBLE);
            tvFilterBadge.setText("위험 필터링 중");
            GradientDrawable badgeBg = new GradientDrawable();
            badgeBg.setColor(Color.parseColor("#FEE2E2"));
            badgeBg.setCornerRadius((int) (12 * density));
            tvFilterBadge.setBackground(badgeBg);
            tvFilterBadge.setTextColor(Color.parseColor("#F04452"));
        } else if ("medium".equals(currentFilterMode)) {
            GradientDrawable mediumSelect = new GradientDrawable();
            mediumSelect.setColor(Color.parseColor("#FFF9F0"));
            mediumSelect.setCornerRadius(cornerRadiusPx);
            mediumSelect.setStroke((int) (2 * density), Color.parseColor("#FF9E00"));
            if (btnTypeBucket != null) btnTypeBucket.setBackground(mediumSelect);

            tvFilterBadge.setVisibility(View.VISIBLE);
            tvFilterBadge.setText("경고 필터링 중");
            GradientDrawable badgeBg = new GradientDrawable();
            badgeBg.setColor(Color.parseColor("#FFF3C7"));
            badgeBg.setCornerRadius((int) (12 * density));
            tvFilterBadge.setBackground(badgeBg);
            tvFilterBadge.setTextColor(Color.parseColor("#FF9E00"));
        }

        renderTimelineList();
    }

    private void updateRealSensorUI(List<IntegratedLog> logList) {
        if (sensorContainer == null || getContext() == null) return;

        int highCounter = 0;
        int mediumCounter = 0;

        for (IntegratedLog log : logList) {
            if (log.getSeverity() != null) {
                if ("high".equalsIgnoreCase(log.getSeverity())) {
                    highCounter++;
                } else if ("medium".equalsIgnoreCase(log.getSeverity())) {
                    mediumCounter++;
                }
            }
        }

        if (tvDangerCount != null) {
            tvDangerCount.setText(highCounter + "건");
        }

        if (tvDangerTypes != null) {
            tvDangerTypes.setText(mediumCounter + "건");
        }

        renderTimelineList();
    }

    private void renderTimelineList() {
        if (sensorContainer == null || logLoadState != LoadState.READY) return;
        sensorContainer.removeAllViews();

        int renderedCount = 0;
        for (IntegratedLog log : originalLogList) {
            if (log.getSeverity() == null) continue;

            String logSeverity = log.getSeverity().toLowerCase();

            if ("high".equals(currentFilterMode) && !"high".equals(logSeverity)) continue;
            if ("medium".equals(currentFilterMode) && !"medium".equals(logSeverity)) continue;

            createLogCard(log);
            renderedCount++;
        }

        if (renderedCount == 0) {
            showEmptyState();
        }
    }

    private String formatLogTimeToKst(String timeSource) {
        return ServerTimestamp.clock(timeSource);
    }

    private void createLogCard(IntegratedLog log) {
        if (getContext() == null) return;

        float density = getContext().getResources().getDisplayMetrics().density;

        CardView cardWrapper = new CardView(getContext());
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardParams.setMargins(0, (int) (4 * density), 0, (int) (8 * density));
        cardWrapper.setLayoutParams(cardParams);
        cardWrapper.setRadius((int) (16 * density));
        cardWrapper.setCardElevation(0);

        LinearLayout cardInner = new LinearLayout(getContext());
        cardInner.setOrientation(LinearLayout.HORIZONTAL);
        cardInner.setGravity(Gravity.CENTER_VERTICAL);
        cardInner.setPadding((int) (20 * density), (int) (16 * density), (int) (20 * density), (int) (16 * density));

        String bgColor = "#FFFFFF";
        String tagColor = "#3182F6";
        String icon = "ℹ️";

        if ("high".equalsIgnoreCase(log.getSeverity())) {
            bgColor = "#FFF0F2";
            tagColor = "#F04452";
            icon = "🔥";
        } else if ("medium".equalsIgnoreCase(log.getSeverity())) {
            bgColor = "#FFF9F0";
            tagColor = "#FF9E00";
            icon = "⚠️";
        }

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor(bgColor));
        bg.setCornerRadius((int) (16 * density));
        cardInner.setBackground(bg);

        TextView iconView = new TextView(getContext());
        iconView.setText(icon);
        iconView.setTextSize(18);
        cardInner.addView(iconView);

        LinearLayout textLayout = new LinearLayout(getContext());
        textLayout.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        textParams.setMargins((int) (14 * density), 0, 0, 0);
        textLayout.setLayoutParams(textParams);

        TextView tvHeader = new TextView(getContext());

        String time = formatLogTimeToKst(log.getCreatedAt());
        String logType = log.getLogType() != null ? log.getLogType() : "알림";
        String subType = log.getSubType() != null ? log.getSubType() : "일반";

        tvHeader.setText("[" + time + "] " + logType + " (" + subType + ")");
        tvHeader.setTextSize(14);
        tvHeader.setTextColor(Color.parseColor(tagColor));
        tvHeader.setTypeface(null, Typeface.BOLD);
        textLayout.addView(tvHeader);

        TextView tvBody = new TextView(getContext());
        String desc = log.getDescription() != null ? log.getDescription() : "세부 내용이 없습니다.";
        tvBody.setText(desc);
        tvBody.setTextSize(13);
        tvBody.setTextColor(Color.parseColor("#4E5968"));

        LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bodyParams.setMargins(0, (int) (4 * density), 0, 0);
        tvBody.setLayoutParams(bodyParams);
        textLayout.addView(tvBody);

        cardInner.addView(textLayout);
        cardWrapper.addView(cardInner);
        sensorContainer.addView(cardWrapper);
    }

    private void showEmptyState() {
        if (sensorContainer == null) return;
        sensorContainer.removeAllViews();
        TextView tvEmpty = new TextView(getContext());
        tvEmpty.setText("조회된 조건에 해당하는 보안 기록이 없습니다.");
        tvEmpty.setTextSize(14);
        tvEmpty.setTextColor(Color.parseColor("#8B95A1"));
        tvEmpty.setGravity(Gravity.CENTER);
        tvEmpty.setPadding(0, 60, 0, 60);
        sensorContainer.addView(tvEmpty);
    }

    private void checkPermissionAndToggleVoice() {
        if (getContext() == null) return;

        if (ContextCompat.checkSelfPermission(getContext(), Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_RECORD_AUDIO_PERMISSION);
        } else {
            toggleVoiceStreaming();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_RECORD_AUDIO_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                toggleVoiceStreaming();
            } else {
                Toast.makeText(getContext(), "실시간 음성 전송을 위해 마이크 권한이 필요합니다.", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void toggleVoiceStreaming() {
        if (!isRecording) {
            isRecording = true;
            updateMicButtonUI();
            connectWebSocket();
            startAudioRecord();
            Toast.makeText(getContext(), "실시간 대화를 시작합니다.", Toast.LENGTH_SHORT).show();
        } else {
            isRecording = false;
            updateMicButtonUI();
            stopAudioRecord();
            Toast.makeText(getContext(), "실시간 대화를 종료합니다.", Toast.LENGTH_SHORT).show();
        }
    }

    private void updateMicButtonUI() {
        if (btnMic == null || getActivity() == null) return;

        getActivity().runOnUiThread(() -> {
            GradientDrawable micShape = new GradientDrawable();
            micShape.setShape(GradientDrawable.OVAL);

            if (isRecording) {
                micShape.setColor(Color.parseColor("#3182F6"));
                btnMic.setBackground(micShape);
            } else {
                micShape.setColor(Color.parseColor("#9CA3AF"));
                btnMic.setBackground(micShape);
            }
        });
    }

    private void connectWebSocket() {
        try {
            String serverUrl = "wss://idontlivealone.onrender.com/audio-stream";
            Request.Builder builder = new Request.Builder().url(serverUrl);
            String authorization = sessionManager == null ? null : sessionManager.getAuthorizationHeader();
            if (authorization != null) builder.header("Authorization", authorization);
            Request request = builder.build();

            webSocket = new OkHttpClient().newWebSocket(request, new WebSocketListener() {
                @Override
                public void onOpen(@NonNull WebSocket webSocket, @NonNull okhttp3.Response response) {
                    Log.d("AUDIO_SOCKET", "🟢 실시간 오디오 백엔드 연결 성공!");
                }

                @Override
                public void onFailure(@NonNull WebSocket webSocket, @NonNull Throwable t, @Nullable okhttp3.Response response) {
                    Log.e("AUDIO_SOCKET", "❌ 웹소켓 연결 실패 사유: " + t.getMessage());
                }

                @Override
                public void onClosed(@NonNull WebSocket webSocket, int code, @NonNull String reason) {
                    Log.d("AUDIO_SOCKET", "🔴 웹소켓 정상 종료");
                }
            });
        } catch (Exception e) {
            Log.e("AUDIO_SOCKET", "WebSocket Connection Error", e);
        }
    }

    private void startAudioRecord() {
        if (getContext() == null || ActivityCompat.checkSelfPermission(getContext(), Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            isRecording = false;
            updateMicButtonUI();
            return;
        }

        try {
            // OpenAI 권장 사양 (24kHz, MONO, PCM 16-BIT)
            int sampleRate = 24000;
            int minBufSize = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);

            audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBufSize * 2);
            audioRecord.startRecording();

            new Thread(() -> {
                short[] buffer = new short[512];
                while (isRecording && audioRecord != null) {
                    try {
                        int read = audioRecord.read(buffer, 0, buffer.length);
                        if (read > 0 && isRecording && webSocket != null) {
                            ByteBuffer byteBuf = ByteBuffer.allocate(read * 2);
                            byteBuf.order(ByteOrder.LITTLE_ENDIAN);
                            for (int i = 0; i < read; i++) {
                                byteBuf.putShort(buffer[i]);
                            }

                            // 24kHz RAW PCM 패킷 스트리밍 전송
                            webSocket.send(ByteString.of(byteBuf.array()));
                            updateVolumeProgress(buffer, read);
                        }
                    } catch (Exception e) {
                        Log.e("AUDIO_THREAD", "Recording loop error", e);
                        break;
                    }
                }
            }).start();
        } catch (Exception e) {
            Log.e("AUDIO_RECORD", "Failed to start audio recording", e);
            isRecording = false;
            updateMicButtonUI();
        }
    }

    private void updateVolumeProgress(short[] buffer, int read) {
        if (!isRecording || read <= 0) return;
        try {
            long sum = 0;
            for (int i = 0; i < read; i++) {
                sum += (long) buffer[i] * buffer[i];
            }

            double rms = Math.sqrt((double) sum / read);

            if (getActivity() != null && isRecording) {
                getActivity().runOnUiThread(() -> {
                    if (audioProgressBar != null && isRecording) {
                        int progress = (int) (rms / 30);
                        audioProgressBar.setProgress(Math.min(progress, 100));
                    }
                });
            }
        } catch (Exception e) {
            Log.e("VOLUME_PROGRESS", "Volume UI calculation error", e);
        }
    }

    private void stopAudioRecord() {
        try {
            if (audioRecord != null) {
                if (audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                    audioRecord.stop();
                }
                audioRecord.release();
            }
        } catch (Exception e) {
            Log.e("STOP_AUDIO", "Error stopping AudioRecord", e);
        } finally {
            audioRecord = null;
        }

        try {
            if (webSocket != null) {
                webSocket.close(1000, "Normal Closure");
            }
        } catch (Exception e) {
            Log.e("STOP_SOCKET", "Error closing WebSocket", e);
        } finally {
            webSocket = null;
        }

        if (audioProgressBar != null) {
            audioProgressBar.setProgress(0);
        }
    }

    @Override
    public void onDestroyView() {
        if (logsCall != null) logsCall.cancel();
        isRecording = false;
        stopAudioRecord();
        super.onDestroyView();
    }
}
