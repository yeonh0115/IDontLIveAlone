package com.example.na_honja_ansanda.ui.report;

import android.graphics.Color;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.cardview.widget.CardView;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.example.na_honja_ansanda.R;
import com.example.na_honja_ansanda.data.model.ReportResponse;
import com.example.na_honja_ansanda.data.model.ServerTimestamp;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

public class ReportAdapter extends RecyclerView.Adapter<ReportAdapter.ViewHolder> {
    private List<ReportResponse> reports;

    public ReportAdapter(List<ReportResponse> reports) {
        this.reports = reports;
    }

    public void updateData(List<ReportResponse> newReports) {
        this.reports = newReports;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_report, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        ReportResponse report = reports.get(position);
        holder.bind(report);
    }

    @Override
    public int getItemCount() {
        return reports != null ? reports.size() : 0;
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        private final TextView tvDate, tvSummary;
        private final CardView cvImageContainer;
        private final LinearLayout llImageGallery;

        ViewHolder(View view) {
            super(view);
            tvDate = view.findViewById(R.id.tv_date);
            tvSummary = view.findViewById(R.id.tv_summary);
            cvImageContainer = view.findViewById(R.id.cv_image_container);

            View photoView = view.findViewById(R.id.iv_captured_photo);
            if (photoView != null && photoView.getParent() instanceof ViewGroup) {
                ViewGroup parent = (ViewGroup) photoView.getParent();
                parent.removeView(photoView);

                android.widget.HorizontalScrollView hsv = new android.widget.HorizontalScrollView(view.getContext());
                hsv.setLayoutParams(new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
                hsv.setHorizontalScrollBarEnabled(false);

                llImageGallery = new LinearLayout(view.getContext());
                llImageGallery.setOrientation(LinearLayout.HORIZONTAL);
                llImageGallery.setLayoutParams(new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));

                hsv.addView(llImageGallery);
                parent.addView(hsv);
            } else {
                llImageGallery = null;
            }
        }

        /** Server createdAt is KST unless the response includes an explicit offset. */
        private Date parseToKstDate(ReportResponse report) {
            return ServerTimestamp.toDate(report.getCreatedAt());
        }

        /**
         * HH:mm:ss 형태 반환
         */
        private String formatDisplayTime(Date date) {
            if (date == null) return "--:--";
            SimpleDateFormat kstFormat = new SimpleDateFormat("HH:mm:ss", Locale.KOREA);
            kstFormat.setTimeZone(TimeZone.getTimeZone("Asia/Seoul"));
            return kstFormat.format(date);
        }

        /**
         * yyyy-MM-dd HH:mm:ss 형태 반환
         */
        private String formatFullDisplayDateTime(Date date) {
            if (date == null) return "시각 정보 없음";
            SimpleDateFormat kstFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.KOREA);
            kstFormat.setTimeZone(TimeZone.getTimeZone("Asia/Seoul"));
            return kstFormat.format(date);
        }

        /**
         * 사진 개수에 따라 사진별 예상/실제 발생 시각 목록을 생성 (기본 3초 간격 시뮬레이션)
         */
        private List<Date> generatePhotoTimestamps(Date baseDate, int count) {
            List<Date> timestamps = new ArrayList<>();
            if (baseDate == null) {
                for (int i = 0; i < count; i++) timestamps.add(null);
                return timestamps;
            }
            Calendar cal = Calendar.getInstance();
            cal.setTime(baseDate);

            for (int i = 0; i < count; i++) {
                timestamps.add(cal.getTime());
                // 사진 간격이 별도로 없는 경우 3초 간격으로 연속 촬영 시각 부여
                cal.add(Calendar.SECOND, 3);
            }
            return timestamps;
        }

        void bind(ReportResponse report) {
            Date baseDate = parseToKstDate(report);
            String displayTime = formatDisplayTime(baseDate);
            tvDate.setText(displayTime);

            tvSummary.setText(String.format(Locale.getDefault(), "총 발생 이벤트: %d건 (위험 요소: %d건)",
                    report.getTotalEvents(), report.getHighRiskEvents()));

            int highRisk = report.getHighRiskEvents();
            if (highRisk >= 4) {
                tvDate.setTextColor(Color.parseColor("#F04452"));
            } else if (highRisk >= 1) {
                tvDate.setTextColor(Color.parseColor("#FF9E00"));
            } else {
                tvDate.setTextColor(Color.parseColor("#3182F6"));
            }

            // photo_url 콤마(,) 분리 로직
            String rawPhotoUrls = report.getPhotoUrl();
            String[] urlArray = (rawPhotoUrls != null && !rawPhotoUrls.trim().isEmpty())
                    ? rawPhotoUrls.split(",")
                    : new String[0];

            List<Date> photoTimestamps = generatePhotoTimestamps(baseDate, urlArray.length);

            if (urlArray.length > 0 && llImageGallery != null) {
                cvImageContainer.setVisibility(View.VISIBLE);
                llImageGallery.removeAllViews();

                float density = itemView.getContext().getResources().getDisplayMetrics().density;
                int imgSizePx = (int) (180 * density);
                int marginPx = (int) (8 * density);

                for (int i = 0; i < urlArray.length; i++) {
                    String cleanUrl = urlArray[i].trim();
                    if (cleanUrl.isEmpty()) continue;

                    CardView cardView = new CardView(itemView.getContext());
                    LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(imgSizePx, imgSizePx);
                    cardParams.setMargins(0, 0, marginPx, 0);
                    cardView.setLayoutParams(cardParams);
                    cardView.setRadius(8 * density);
                    cardView.setCardElevation(0);

                    FrameLayout frameLayout = new FrameLayout(itemView.getContext());
                    frameLayout.setLayoutParams(new ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT));

                    ImageView imageView = new ImageView(itemView.getContext());
                    imageView.setLayoutParams(new ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT));
                    imageView.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    imageView.setBackgroundColor(Color.parseColor("#F4F7FC"));

                    Glide.with(itemView.getContext())
                            .load(cleanUrl)
                            .into(imageView);

                    frameLayout.addView(imageView);

                    // 💡 사진 내부 우측 하단에 [사진별 시각 뱃지] 표시
                    TextView tvTimeTag = new TextView(itemView.getContext());
                    String timeText = formatDisplayTime(photoTimestamps.get(i));
                    tvTimeTag.setText(timeText);
                    tvTimeTag.setTextSize(11);
                    tvTimeTag.setTextColor(Color.WHITE);
                    tvTimeTag.setPadding((int) (6 * density), (int) (2 * density), (int) (6 * density), (int) (2 * density));
                    tvTimeTag.setBackgroundColor(Color.parseColor("#99000000")); // 반투명 검은 배경

                    FrameLayout.LayoutParams tagParams = new FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT);
                    tagParams.gravity = Gravity.BOTTOM | Gravity.END;
                    tagParams.setMargins(0, 0, (int) (6 * density), (int) (6 * density));
                    tvTimeTag.setLayoutParams(tagParams);

                    frameLayout.addView(tvTimeTag);
                    cardView.addView(frameLayout);
                    llImageGallery.addView(cardView);
                }
            } else {
                cvImageContainer.setVisibility(View.GONE);
            }

            // 상세보기 다이얼로그
            itemView.setOnClickListener(v -> {
                float density = v.getContext().getResources().getDisplayMetrics().density;
                int paddingPx = (int) (24 * density);

                LinearLayout dialogLayout = new LinearLayout(v.getContext());
                dialogLayout.setOrientation(LinearLayout.VERTICAL);
                dialogLayout.setPadding(paddingPx, paddingPx / 2, paddingPx, paddingPx);

                if (urlArray.length > 0) {
                    android.widget.HorizontalScrollView dialogHsv = new android.widget.HorizontalScrollView(v.getContext());
                    dialogHsv.setLayoutParams(new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT));
                    dialogHsv.setHorizontalScrollBarEnabled(false);

                    LinearLayout dialogGallery = new LinearLayout(v.getContext());
                    dialogGallery.setOrientation(LinearLayout.HORIZONTAL);
                    dialogGallery.setLayoutParams(new ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT));

                    int dialogImgWidth = (int) (260 * density);
                    int dialogImgHeight = (int) (200 * density);
                    int marginPx = (int) (10 * density);

                    for (int i = 0; i < urlArray.length; i++) {
                        String cleanUrl = urlArray[i].trim();
                        if (cleanUrl.isEmpty()) continue;

                        CardView dialogCard = new CardView(v.getContext());
                        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(dialogImgWidth, dialogImgHeight);
                        cardParams.setMargins(0, 0, marginPx, (int) (16 * density));
                        dialogCard.setLayoutParams(cardParams);
                        dialogCard.setRadius(12 * density);
                        dialogCard.setCardElevation(0);

                        FrameLayout frameLayout = new FrameLayout(v.getContext());
                        frameLayout.setLayoutParams(new ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT));

                        ImageView ivDialogPhoto = new ImageView(v.getContext());
                        ivDialogPhoto.setLayoutParams(new ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT));
                        ivDialogPhoto.setScaleType(ImageView.ScaleType.CENTER_CROP);
                        ivDialogPhoto.setBackgroundColor(Color.parseColor("#F4F7FC"));

                        Glide.with(v.getContext())
                                .load(cleanUrl)
                                .into(ivDialogPhoto);

                        frameLayout.addView(ivDialogPhoto);

                        // 다이얼로그 큼직한 사진 뱃지
                        TextView tvDialogTimeTag = new TextView(v.getContext());
                        String timeText = formatFullDisplayDateTime(photoTimestamps.get(i));
                        tvDialogTimeTag.setText("📸 #" + (i + 1) + "  " + timeText);
                        tvDialogTimeTag.setTextSize(12);
                        tvDialogTimeTag.setTextColor(Color.WHITE);
                        tvDialogTimeTag.setPadding((int) (8 * density), (int) (4 * density), (int) (8 * density), (int) (4 * density));
                        tvDialogTimeTag.setBackgroundColor(Color.parseColor("#B3000000"));

                        FrameLayout.LayoutParams tagParams = new FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT);
                        tagParams.gravity = Gravity.BOTTOM | Gravity.START;
                        tagParams.setMargins((int) (8 * density), 0, 0, (int) (8 * density));
                        tvDialogTimeTag.setLayoutParams(tagParams);

                        frameLayout.addView(tvDialogTimeTag);
                        dialogCard.addView(frameLayout);
                        dialogGallery.addView(dialogCard);
                    }

                    dialogHsv.addView(dialogGallery);
                    dialogLayout.addView(dialogHsv);
                }

                // 💡 [핵심] 본문에 사진별 캡처 시각 타임라인을 구성하여 출력
                StringBuilder contentBuilder = new StringBuilder();
                contentBuilder.append("리포트 날짜: ").append(report.getReportDate()).append("\n");
                contentBuilder.append("⏱️ 리포트 생성 시각: ").append(formatFullDisplayDateTime(baseDate)).append("\n\n");

                if (urlArray.length > 0) {
                    contentBuilder.append("📸 사진별 캡처 타임라인 (총 ").append(urlArray.length).append("장):\n");
                    for (int i = 0; i < urlArray.length; i++) {
                        contentBuilder.append("  • 사진 #").append(i + 1).append(" : ")
                                .append(formatFullDisplayDateTime(photoTimestamps.get(i))).append("\n");
                    }
                    contentBuilder.append("\n");
                }

                contentBuilder.append("-----------------------------------\n\n");
                contentBuilder.append("📋 감지 및 분석 내용:\n");
                contentBuilder.append(report.getReportText() != null ? report.getReportText() : "상세 리포트 내용이 없습니다.");

                TextView tvMessage = new TextView(v.getContext());
                tvMessage.setText(contentBuilder.toString());
                tvMessage.setTextSize(14);
                tvMessage.setTextColor(Color.parseColor("#4E5968"));
                tvMessage.setTextIsSelectable(true);
                tvMessage.setLineSpacing(0, 1.3f);

                dialogLayout.addView(tvMessage);

                new com.google.android.material.dialog.MaterialAlertDialogBuilder(v.getContext())
                        .setTitle("보안 리포트 상세")
                        .setView(dialogLayout)
                        .setPositiveButton("확인", null)
                        .show();
            });
        }
    }
}
