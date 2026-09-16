package com.example.na_honja_ansanda.ui.mypage;

import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.example.na_honja_ansanda.R;

public class AccordionAdapter extends RecyclerView.Adapter<AccordionAdapter.ViewHolder> {

    private final String[][] mData;
    private final String mPointColor;
    private int mExpandedPosition = -1;

    public AccordionAdapter(String[][] data, String pointColor) {
        this.mData = data;
        this.mPointColor = pointColor;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_accordion, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        int currentPos = holder.getAdapterPosition();
        holder.tvTitle.setText(mData[currentPos][0]);
        holder.tvContent.setText(mData[currentPos][1]);

        final boolean isExpanded = currentPos == mExpandedPosition;
        holder.tvContent.setVisibility(isExpanded ? View.VISIBLE : View.GONE);
        holder.tvTitle.setTextColor(isExpanded ? Color.parseColor(mPointColor) : Color.parseColor("#334155"));

        holder.itemView.setOnClickListener(v -> {
            mExpandedPosition = isExpanded ? -1 : currentPos;
            notifyDataSetChanged(); // 상태 전환 연출
        });
    }

    @Override
    public int getItemCount() {
        return mData.length;
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvTitle, tvContent;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            tvTitle = itemView.findViewById(R.id.tv_item_title);
            tvContent = itemView.findViewById(R.id.tv_item_content);
        }
    }
}