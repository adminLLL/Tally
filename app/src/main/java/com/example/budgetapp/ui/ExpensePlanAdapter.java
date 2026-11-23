package com.example.budgetapp.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.example.budgetapp.R;
import com.example.budgetapp.database.ExpensePlan;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 「计划」页列表：每行显示优先度、支出项、金额、完成状态（×／√）。
 * 点状态按钮切换完成，点行的其他位置进详情。
 */
public class ExpensePlanAdapter extends RecyclerView.Adapter<ExpensePlanAdapter.PlanViewHolder> {

    public interface Listener {
        void onToggleComplete(ExpensePlan plan);

        void onOpenDetail(ExpensePlan plan);
    }

    private final List<ExpensePlan> items = new ArrayList<>();
    private final Listener listener;
    private String currencySymbol = "¥";

    public ExpensePlanAdapter(Listener listener) {
        this.listener = listener;
    }

    public void setCurrencySymbol(String symbol) {
        this.currencySymbol = (symbol == null || symbol.isEmpty()) ? "¥" : symbol;
    }

    public void submitList(List<ExpensePlan> plans) {
        items.clear();
        if (plans != null) items.addAll(plans);
        notifyDataSetChanged();
    }

    public ExpensePlan getItem(int position) {
        if (position < 0 || position >= items.size()) return null;
        return items.get(position);
    }

    @NonNull
    @Override
    public PlanViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_expense_plan, parent, false);
        return new PlanViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull PlanViewHolder holder, int position) {
        ExpensePlan plan = items.get(position);
        holder.bind(plan, position, currencySymbol, listener);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class PlanViewHolder extends RecyclerView.ViewHolder {
        private final TextView tvPriority;
        private final TextView tvTitle;
        private final TextView tvSub;
        private final TextView tvAmount;
        private final TextView tvStatus;

        PlanViewHolder(@NonNull View itemView) {
            super(itemView);
            tvPriority = itemView.findViewById(R.id.tv_plan_priority);
            tvTitle = itemView.findViewById(R.id.tv_plan_title);
            tvSub = itemView.findViewById(R.id.tv_plan_sub);
            tvAmount = itemView.findViewById(R.id.tv_plan_amount);
            tvStatus = itemView.findViewById(R.id.tv_plan_status);
        }

        void bind(ExpensePlan plan, int position, String symbol, Listener listener) {
            tvPriority.setText(String.valueOf(position + 1));
            tvTitle.setText(plan.title == null ? "未命名支出项" : plan.title);
            tvAmount.setText(symbol + String.format(Locale.CHINA, "%.2f", plan.amount));

            if (plan.completed) {
                int links = 0;
                if (plan.linkedTransactionIds != null && !plan.linkedTransactionIds.trim().isEmpty()) {
                    links = plan.linkedTransactionIds.split(",").length;
                }
                String sub = "已支出 " + symbol + String.format(Locale.CHINA, "%.2f", plan.actualAmount);
                if (links > 0) sub += " · 关联 " + links + " 笔流水";
                tvSub.setText(sub);
                tvStatus.setText("√");
                tvStatus.setContentDescription("取消完成");
                tvStatus.setBackgroundResource(R.drawable.bg_plan_status_done);
                tvStatus.setTextColor(ContextCompat.getColor(itemView.getContext(), R.color.expense_green));
            } else {
                tvSub.setText("未完成 · 优先度 " + (position + 1));
                tvStatus.setText("×");
                tvStatus.setContentDescription("标记完成");
                tvStatus.setBackgroundResource(R.drawable.bg_plan_status_pending);
                tvStatus.setTextColor(ContextCompat.getColor(itemView.getContext(), R.color.income_red));
            }

            tvStatus.setOnClickListener(v -> {
                if (listener != null) listener.onToggleComplete(plan);
            });
            itemView.setOnClickListener(v -> {
                if (listener != null) listener.onOpenDetail(plan);
            });
        }
    }
}
