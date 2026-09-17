package com.example.na_honja_ansanda.ui.report;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.Button;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.example.na_honja_ansanda.R;
import com.example.na_honja_ansanda.data.model.ReportResponse;
import com.example.na_honja_ansanda.data.model.LoadState;
import com.example.na_honja_ansanda.data.model.DailyReportStatus;
import com.example.na_honja_ansanda.data.remote.ApiClient;
import com.example.na_honja_ansanda.data.session.SessionManager;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class ReportFragment extends Fragment {

    private TextView tvCalendarTitle, tvSelectedDateHeader, tvStatusDotBadge;
    private TextView tvLoadStatus;
    private Button btnRetryReports;
    private RecyclerView rvCalendar, rvReportList;
    private ImageButton btnPrevMonth, btnNextMonth;

    private ReportAdapter reportAdapter;
    private CalendarAdapter calendarAdapter;
    private SessionManager sessionManager;

    private List<ReportResponse> masterReportList = new ArrayList<>();
    private Calendar currentCalendar = Calendar.getInstance();
    private String currentTargetDate = "";
    private final SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
    private LoadState reportLoadState = LoadState.LOADING;
    private Call<List<ReportResponse>> reportsCall;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.activity_report_list, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        if (getContext() == null) return;
        sessionManager = SessionManager.getInstance(getContext());

        tvCalendarTitle = view.findViewById(R.id.tv_calendar_title);
        tvSelectedDateHeader = view.findViewById(R.id.tv_selected_date_header);
        tvStatusDotBadge = view.findViewById(R.id.tv_status_dot_badge);
        tvLoadStatus = view.findViewById(R.id.tv_report_load_status);
        btnRetryReports = view.findViewById(R.id.btn_retry_reports);
        btnRetryReports.setOnClickListener(v -> loadReports());
        btnPrevMonth = view.findViewById(R.id.btn_prev_month);
        btnNextMonth = view.findViewById(R.id.btn_next_month);
        rvCalendar = view.findViewById(R.id.rv_calendar);
        rvReportList = view.findViewById(R.id.rv_report_list);

        rvReportList.setLayoutManager(new LinearLayoutManager(getContext()));
        reportAdapter = new ReportAdapter(new ArrayList<>());
        rvReportList.setAdapter(reportAdapter);

        rvCalendar.setLayoutManager(new GridLayoutManager(getContext(), 7));
        calendarAdapter = new CalendarAdapter(new ArrayList<>(), clickedDate -> {
            if (clickedDate == null || clickedDate.isEmpty()) return;
            currentTargetDate = clickedDate;
            if (tvSelectedDateHeader != null) {
                tvSelectedDateHeader.setText("📂 " + currentTargetDate + " 데일리 요약");
            }
            dispatchFilteredData();
        });
        rvCalendar.setAdapter(calendarAdapter);

        currentTargetDate = sdf.format(Calendar.getInstance().getTime());
        if (tvSelectedDateHeader != null) {
            tvSelectedDateHeader.setText("📂 " + currentTargetDate + " 데일리 요약");
        }

        if (btnPrevMonth != null) {
            btnPrevMonth.setOnClickListener(v -> { currentCalendar.add(Calendar.MONTH, -1); rebuildCalendarGrid(); });
        }
        if (btnNextMonth != null) {
            btnNextMonth.setOnClickListener(v -> { currentCalendar.add(Calendar.MONTH, 1); rebuildCalendarGrid(); });
        }

        rebuildCalendarGrid();
        loadReports();
    }

    private void loadReports() {
        if (!isAdded() || getView() == null || sessionManager == null) return;
        int userNo = sessionManager.getUserNo();
        if (userNo == -1) {
            reportLoadState = LoadState.FAILED;
            rebuildCalendarGrid();
            dispatchFilteredData();
            return;
        }
        if (reportsCall != null) reportsCall.cancel();
        reportLoadState = LoadState.LOADING;
        rebuildCalendarGrid();
        dispatchFilteredData();
        reportsCall = ApiClient.getApiService().getUserReports(userNo);
        reportsCall.enqueue(new Callback<List<ReportResponse>>() {
            @Override
            public void onResponse(Call<List<ReportResponse>> call, Response<List<ReportResponse>> response) {
                if (!isAdded() || getView() == null || call != reportsCall) return;
                if (response.isSuccessful() && response.body() != null) {
                    reportLoadState = LoadState.READY;
                    masterReportList = response.body();
                } else {
                    reportLoadState = LoadState.FAILED;
                    masterReportList = new ArrayList<>();
                }
                rebuildCalendarGrid();
                dispatchFilteredData();
            }
            @Override
            public void onFailure(Call<List<ReportResponse>> call, Throwable t) {
                if (!isAdded() || getView() == null || call.isCanceled() || call != reportsCall) return;
                reportLoadState = LoadState.FAILED;
                masterReportList = new ArrayList<>();
                rebuildCalendarGrid();
                dispatchFilteredData();
            }
        });
    }

    private void rebuildCalendarGrid() {
        if (tvCalendarTitle == null || calendarAdapter == null) return;

        SimpleDateFormat titleSdf = new SimpleDateFormat("yyyy년 MM월", Locale.getDefault());
        tvCalendarTitle.setText(titleSdf.format(currentCalendar.getTime()));

        List<DayCell> cellList = new ArrayList<>();
        Calendar tempCal = (Calendar) currentCalendar.clone();
        tempCal.set(Calendar.DAY_OF_MONTH, 1);

        int startSpace = tempCal.get(Calendar.DAY_OF_WEEK) - 1;
        int maxDays = tempCal.getActualMaximum(Calendar.DAY_OF_MONTH);

        Calendar todayCal = Calendar.getInstance();
        todayCal.set(Calendar.HOUR_OF_DAY, 0);
        todayCal.set(Calendar.MINUTE, 0);
        todayCal.set(Calendar.SECOND, 0);
        todayCal.set(Calendar.MILLISECOND, 0);

        for (int i = 0; i < startSpace; i++) {
            cellList.add(new DayCell("", ""));
        }

        for (int i = 1; i <= maxDays; i++) {
            Calendar cellCal = (Calendar) currentCalendar.clone();
            cellCal.set(Calendar.DAY_OF_MONTH, i);
            cellCal.set(Calendar.HOUR_OF_DAY, 0);
            cellCal.set(Calendar.MINUTE, 0);
            cellCal.set(Calendar.SECOND, 0);
            cellCal.set(Calendar.MILLISECOND, 0);

            String fullDate = String.format(Locale.getDefault(), "%04d-%02d-%02d",
                    cellCal.get(Calendar.YEAR), cellCal.get(Calendar.MONTH) + 1, i);

            // 🛠️ 기본값은 색상 표시 없음(투명)으로 설정합니다.
            String colorHex = "#00000000";

            if (!cellCal.after(todayCal)) {
                DailyReportStatus status = DailyReportStatus.forDate(reportLoadState, masterReportList, fullDate);
                switch (status.getKind()) {
                    case SAFE: colorHex = "#10B981"; break;
                    case CAUTION: colorHex = "#FF9E00"; break;
                    case DANGER: colorHex = "#F04452"; break;
                    default: break; // Loading, errors, and absent records have no safety dot.
                }
            }

            cellList.add(new DayCell(String.valueOf(i), fullDate, colorHex));
        }
        calendarAdapter.updateItems(cellList);
    }

    private void dispatchFilteredData() {
        if (reportAdapter == null) return;

        List<ReportResponse> filtered = new ArrayList<>();
        if (reportLoadState == LoadState.READY && masterReportList != null) {
            for (ReportResponse r : masterReportList) {
                if (r != null && r.getReportDate() != null && r.getReportDate().equals(currentTargetDate)) {
                    filtered.add(r);
                }
            }
        }
        reportAdapter.updateData(filtered);
        DailyReportStatus status = DailyReportStatus.forDate(reportLoadState, masterReportList, currentTargetDate);
        updateStatusBadge(status);
        btnRetryReports.setVisibility(reportLoadState == LoadState.FAILED ? View.VISIBLE : View.GONE);
        if (reportLoadState == LoadState.FAILED) {
            tvLoadStatus.setText("리포트를 불러오지 못했습니다. 연결을 확인한 후 다시 시도해주세요.");
        } else if (reportLoadState == LoadState.LOADING) {
            tvLoadStatus.setText("리포트를 불러오고 있습니다.");
        } else {
            tvLoadStatus.setText("선택한 날짜에 등록된 리포트가 없습니다.");
        }
        tvLoadStatus.setVisibility(filtered.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void updateStatusBadge(DailyReportStatus dailyData) {
        if (tvStatusDotBadge == null || getContext() == null) return;

        float density = getContext().getResources().getDisplayMetrics().density;
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius((int) (12 * density));

        if (dailyData.getKind() == DailyReportStatus.Kind.LOADING
                || dailyData.getKind() == DailyReportStatus.Kind.ERROR
                || dailyData.getKind() == DailyReportStatus.Kind.NO_DATA) {
            tvStatusDotBadge.setText(dailyData.getKind() == DailyReportStatus.Kind.LOADING ? "● 조회 중"
                    : dailyData.getKind() == DailyReportStatus.Kind.ERROR ? "● 조회 실패" : "● 기록 없음");
            tvStatusDotBadge.setTextColor(Color.parseColor("#6B7280"));
            bg.setColor(Color.parseColor("#E5E7EB"));
        } else {
            int risk = dailyData.getHighRiskEvents();
            if (risk == 0) {
                tvStatusDotBadge.setText("● 안전 (0건)");
                tvStatusDotBadge.setTextColor(Color.parseColor("#10B981"));
                bg.setColor(Color.parseColor("#E6F4EA"));
            } else if (risk >= 1 && risk <= 3) {
                tvStatusDotBadge.setText("● 주의 (" + risk + "건)");
                tvStatusDotBadge.setTextColor(Color.parseColor("#FF9E00"));
                bg.setColor(Color.parseColor("#FFF9F0"));
            } else if (risk >= 4) {
                tvStatusDotBadge.setText("● 위험 (" + risk + "건)");
                tvStatusDotBadge.setTextColor(Color.parseColor("#F04452"));
                bg.setColor(Color.parseColor("#FFF0F2"));
            }
        }
        tvStatusDotBadge.setBackground(bg);
    }

    @Override public void onDestroyView() {
        if (reportsCall != null) reportsCall.cancel();
        super.onDestroyView();
    }

    private static class DayCell {
        String dayNum, fullDate, dotColor;
        DayCell(String dayNum, String fullDate) { this(dayNum, fullDate, "#00000000"); }
        DayCell(String dayNum, String fullDate, String dotColor) {
            this.dayNum = dayNum; this.fullDate = fullDate; this.dotColor = dotColor;
        }
    }

    private static class CalendarAdapter extends RecyclerView.Adapter<CalendarAdapter.CalViewHolder> {
        private final List<DayCell> items;
        private final OnItemClickListener clickListener;
        interface OnItemClickListener { void onItemClick(String date); }

        CalendarAdapter(List<DayCell> items, OnItemClickListener clickListener) {
            this.items = items; this.clickListener = clickListener;
        }
        void updateItems(List<DayCell> newItems) {
            this.items.clear();
            if (newItems != null) this.items.addAll(newItems);
            notifyDataSetChanged();
        }
        @NonNull @Override
        public CalViewHolder onCreateViewHolder(@NonNull ViewGroup p, int t) {
            return new CalViewHolder(LayoutInflater.from(p.getContext()).inflate(R.layout.item_calendar_day, p, false));
        }
        @Override
        public void onBindViewHolder(@NonNull CalViewHolder h, int position) {
            DayCell cell = items.get(position);
            h.tvNum.setText(cell.dayNum);
            if (cell.dayNum != null && !cell.dayNum.isEmpty()) {
                if (cell.dotColor.equals("#00000000")) {
                    h.vDot.setVisibility(View.INVISIBLE);
                } else {
                    h.vDot.setVisibility(View.VISIBLE);
                    GradientDrawable drawable = new GradientDrawable();
                    drawable.setShape(GradientDrawable.OVAL);
                    try {
                        drawable.setColor(Color.parseColor(cell.dotColor));
                    } catch (Exception e) {
                        drawable.setColor(Color.parseColor("#10B981"));
                    }
                    h.vDot.setBackground(drawable);
                }
                h.itemView.setOnClickListener(v -> clickListener.onItemClick(cell.fullDate));
            } else {
                h.vDot.setVisibility(View.INVISIBLE);
                h.itemView.setOnClickListener(null);
            }
        }
        @Override public int getItemCount() { return items.size(); }
        static class CalViewHolder extends RecyclerView.ViewHolder {
            TextView tvNum; View vDot;
            CalViewHolder(View v) {
                super(v);
                tvNum = v.findViewById(R.id.tv_day_number);
                vDot = v.findViewById(R.id.view_dot);
            }
        }
    }
}
