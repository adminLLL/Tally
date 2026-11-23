package com.example.budgetapp.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.budgetapp.R;
import com.example.budgetapp.database.ExpensePlan;
import com.example.budgetapp.database.Transaction;
import com.example.budgetapp.domain.PlanMath;
import com.example.budgetapp.viewmodel.PlanViewModel;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 底栏「计划」页：按月维护支出计划，支持优先级排序、标记完成（关联当月支出流水）与当月汇总。
 * 数据全部落在 expense_plans 表（DB v29 新增），老库升级走迁移、老备份缺字段时跳过。
 */
public class PlanFragment extends Fragment implements ExpensePlanAdapter.Listener {

    private PlanViewModel viewModel;
    private RecyclerView rvPlans;
    private TextView tvMonth, tvEmpty;
    private TextView tvSummaryPlanned, tvSummaryActual, tvSummaryOverspend, tvSummaryProgress;
    private ExpensePlanAdapter adapter;

    private String currencySymbol = "¥";
    private String currentMonth;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_plan, container, false);

        viewModel = new ViewModelProvider(requireActivity()).get(PlanViewModel.class);

        tvMonth = view.findViewById(R.id.tv_month);
        tvEmpty = view.findViewById(R.id.tv_empty);
        tvSummaryPlanned = view.findViewById(R.id.tv_summary_planned);
        tvSummaryActual = view.findViewById(R.id.tv_summary_actual);
        tvSummaryOverspend = view.findViewById(R.id.tv_summary_overspend);
        tvSummaryProgress = view.findViewById(R.id.tv_summary_progress);

        rvPlans = view.findViewById(R.id.rv_plans);
        rvPlans.setLayoutManager(new LinearLayoutManager(getContext()));
        adapter = new ExpensePlanAdapter(this);
        adapter.setCurrencySymbol(currencySymbol);
        rvPlans.setAdapter(adapter);

        ImageButton btnPrev = view.findViewById(R.id.btn_prev_month);
        ImageButton btnNext = view.findViewById(R.id.btn_next_month);
        btnPrev.setOnClickListener(v -> changeMonth(-1));
        btnNext.setOnClickListener(v -> changeMonth(1));

        view.findViewById(R.id.btn_add_plan).setOnClickListener(v -> showEditDialog(null));

        if (viewModel.getMonthKey().getValue() == null) {
            viewModel.setMonth(PlanMath.currentMonthKey());
        }
        currentMonth = viewModel.getMonthKey().getValue();

        viewModel.getMonthKey().observe(getViewLifecycleOwner(), key -> {
            currentMonth = key;
            tvMonth.setText(PlanMath.monthLabel(key));
        });

        viewModel.getPlans().observe(getViewLifecycleOwner(), plans -> {
            adapter.submitList(plans);
            boolean empty = plans == null || plans.isEmpty();
            tvEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
            rvPlans.setVisibility(empty ? View.GONE : View.VISIBLE);
        });

        viewModel.getSummary().observe(getViewLifecycleOwner(), summary -> {
            if (summary == null) return;
            tvSummaryPlanned.setText(money(summary.planned));
            tvSummaryActual.setText(money(summary.actual));
            tvSummaryOverspend.setText(money(summary.overspend));
            String progress = "已完成 " + summary.progressText();
            if (summary.totalCount > 0) progress += " · " + summary.percent() + "%";
            tvSummaryProgress.setText(progress);
        });

        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshCurrencySymbol();
    }

    private void refreshCurrencySymbol() {
        if (getContext() == null) return;
        SharedPreferences prefs = getContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE);
        currencySymbol = prefs.getString("default_currency_symbol", "¥");
        if (adapter != null) {
            adapter.setCurrencySymbol(currencySymbol);
            adapter.notifyDataSetChanged();
        }
    }

    private void changeMonth(int delta) {
        if (currentMonth == null) currentMonth = PlanMath.currentMonthKey();
        viewModel.setMonth(PlanMath.shiftMonthKey(currentMonth, delta));
    }

    private String money(double value) {
        return currencySymbol + String.format(Locale.CHINA, "%.2f", value);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void toast(String text) {
        if (getContext() != null) Toast.makeText(getContext(), text, Toast.LENGTH_SHORT).show();
    }

    // ---------------- 列表交互 ----------------

    @Override
    public void onToggleComplete(ExpensePlan plan) {
        if (plan.completed) {
            viewModel.reopenPlan(plan.id);
            toast("已取消完成");
        } else {
            showCompleteDialog(plan);
        }
    }

    @Override
    public void onOpenDetail(ExpensePlan plan) {
        showDetailDialog(plan);
    }

    // ---------------- 新增 / 编辑 ----------------

    private void showEditDialog(@Nullable ExpensePlan editing) {
        if (getContext() == null) return;
        View view = LayoutInflater.from(getContext()).inflate(R.layout.dialog_plan_edit, null);
        AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
        builder.setView(view);
        AlertDialog dialog = builder.create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }

        TextView tvTitle = view.findViewById(R.id.tv_plan_dialog_title);
        EditText etTitle = view.findViewById(R.id.et_plan_title);
        EditText etAmount = view.findViewById(R.id.et_plan_amount);
        TextView tvMonthLabel = view.findViewById(R.id.tv_edit_month);
        TextView tvPriority = view.findViewById(R.id.tv_edit_priority);

        tvTitle.setText(editing == null ? "添加支出计划" : "编辑支出计划");

        // 月份：新增时默认当前选中月；编辑时用原月份
        String[] monthHolder = {editing == null ? currentMonth : editing.monthKey};
        if (monthHolder[0] == null) monthHolder[0] = PlanMath.currentMonthKey();

        // 目标位置：0 起；等于该月条数表示排最后
        int[] countHolder = {0};
        int[] indexHolder = {0};

        if (editing != null) {
            etTitle.setText(editing.title);
            etAmount.setText(String.format(Locale.CHINA, "%.2f", editing.amount));
            indexHolder[0] = editing.priority;
            if (monthHolder[0].equals(currentMonth)) {
                countHolder[0] = adapter.getItemCount();
                if (indexHolder[0] > countHolder[0]) indexHolder[0] = countHolder[0];
                tvMonthLabel.setText(PlanMath.monthLabel(monthHolder[0]));
                updatePriorityLabel(tvPriority, indexHolder[0], countHolder[0]);
            } else {
                refreshMonthCount(monthHolder[0], (month, count) -> {
                    countHolder[0] = count;
                    tvMonthLabel.setText(PlanMath.monthLabel(month));
                    updatePriorityLabel(tvPriority, indexHolder[0], count);
                });
            }
        } else {
            String startMonth = monthHolder[0];
            tvMonthLabel.setText(PlanMath.monthLabel(startMonth));
            if (startMonth.equals(currentMonth)) {
                countHolder[0] = adapter.getItemCount();
                indexHolder[0] = countHolder[0];
                updatePriorityLabel(tvPriority, indexHolder[0], countHolder[0]);
            } else {
                refreshMonthCount(startMonth, (month, count) -> {
                    countHolder[0] = count;
                    indexHolder[0] = count;
                    updatePriorityLabel(tvPriority, indexHolder[0], count);
                });
            }
        }

        view.findViewById(R.id.btn_edit_month_prev).setOnClickListener(v -> {
            monthHolder[0] = PlanMath.shiftMonthKey(monthHolder[0], -1);
            refreshMonthCount(monthHolder[0], (month, count) -> {
                countHolder[0] = count;
                // 换月份后默认排到该月最后（需求：修改月份到新月份默认优先度排最后）
                indexHolder[0] = count;
                tvMonthLabel.setText(PlanMath.monthLabel(month));
                updatePriorityLabel(tvPriority, indexHolder[0], count);
            });
        });
        view.findViewById(R.id.btn_edit_month_next).setOnClickListener(v -> {
            monthHolder[0] = PlanMath.shiftMonthKey(monthHolder[0], 1);
            refreshMonthCount(monthHolder[0], (month, count) -> {
                countHolder[0] = count;
                indexHolder[0] = count;
                tvMonthLabel.setText(PlanMath.monthLabel(month));
                updatePriorityLabel(tvPriority, indexHolder[0], count);
            });
        });

        view.findViewById(R.id.btn_edit_priority_up).setOnClickListener(v -> {
            if (indexHolder[0] > 0) indexHolder[0]--;
            updatePriorityLabel(tvPriority, indexHolder[0], countHolder[0]);
        });
        view.findViewById(R.id.btn_edit_priority_down).setOnClickListener(v -> {
            if (indexHolder[0] < countHolder[0]) indexHolder[0]++;
            updatePriorityLabel(tvPriority, indexHolder[0], countHolder[0]);
        });

        view.findViewById(R.id.btn_cancel_plan).setOnClickListener(v -> dialog.dismiss());
        view.findViewById(R.id.btn_save_plan).setOnClickListener(v -> {
            String title = etTitle.getText().toString().trim();
            String amountText = etAmount.getText().toString().trim();
            if (title.isEmpty()) {
                toast("请输入支出项");
                return;
            }
            double amount;
            try {
                amount = Double.parseDouble(amountText);
            } catch (NumberFormatException e) {
                toast("请输入正确的金额");
                return;
            }
            if (amount <= 0) {
                toast("金额需要大于 0");
                return;
            }
            String month = monthHolder[0];
            if (editing == null) {
                viewModel.addPlan(title, amount, month);
                toast("已添加");
            } else {
                viewModel.updatePlan(editing.id, title, amount, month, indexHolder[0]);
                toast("已保存");
            }
            dialog.dismiss();
            if (!month.equals(currentMonth)) {
                viewModel.setMonth(month);
            }
        });

        dialog.show();
    }

    private interface MonthCountCallback {
        void onCount(String month, int count);
    }

    private void refreshMonthCount(String month, MonthCountCallback callback) {
        viewModel.loadMonthPlanCount(month, count -> {
            if (!isAdded()) return;
            if (callback != null) callback.onCount(month, count);
        });
    }

    private void updatePriorityLabel(TextView tv, int index, int count) {
        if (index >= count) {
            tv.setText("排最后");
        } else {
            tv.setText("第 " + (index + 1) + " 位");
        }
    }

    // ---------------- 详情 ----------------

    private void showDetailDialog(ExpensePlan plan) {
        if (getContext() == null) return;
        View view = LayoutInflater.from(getContext()).inflate(R.layout.dialog_plan_detail, null);
        AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
        builder.setView(view);
        AlertDialog dialog = builder.create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }

        TextView tvTitle = view.findViewById(R.id.tv_detail_title);
        TextView tvAmount = view.findViewById(R.id.tv_detail_amount);
        TextView tvMonthLabel = view.findViewById(R.id.tv_detail_month);
        TextView tvPriority = view.findViewById(R.id.tv_detail_priority);
        TextView tvStatus = view.findViewById(R.id.tv_detail_status);
        TextView tvLinksLabel = view.findViewById(R.id.tv_detail_links_label);
        TextView tvLinks = view.findViewById(R.id.tv_detail_links);
        Button btnToggle = view.findViewById(R.id.btn_detail_toggle);

        tvTitle.setText(plan.title);
        tvAmount.setText(money(plan.completed ? plan.actualAmount : plan.amount));
        tvMonthLabel.setText(PlanMath.monthLabel(plan.monthKey));
        tvPriority.setText("第 " + (plan.priority + 1) + " 位");

        if (plan.completed) {
            tvStatus.setText("已完成 √");
            tvStatus.setTextColor(getResources().getColor(R.color.expense_green, null));
            btnToggle.setText("取消完成");
            int count = PlanViewModel.parseIds(plan.linkedTransactionIds).size();
            tvLinksLabel.setVisibility(View.VISIBLE);
            tvLinks.setVisibility(View.VISIBLE);
            tvLinks.setText("已关联 " + count + " 笔支出流水，合计 " + money(plan.actualAmount));
        } else {
            tvStatus.setText("未完成 ×");
            tvStatus.setTextColor(getResources().getColor(R.color.income_red, null));
            btnToggle.setText("标记完成");
        }

        view.findViewById(R.id.btn_detail_up).setOnClickListener(v -> {
            viewModel.movePlan(plan.id, -1);
            dialog.dismiss();
        });
        view.findViewById(R.id.btn_detail_down).setOnClickListener(v -> {
            viewModel.movePlan(plan.id, 1);
            dialog.dismiss();
        });
        view.findViewById(R.id.btn_detail_edit).setOnClickListener(v -> {
            dialog.dismiss();
            showEditDialog(plan);
        });
        btnToggle.setOnClickListener(v -> {
            dialog.dismiss();
            onToggleComplete(plan);
        });
        view.findViewById(R.id.btn_detail_delete).setOnClickListener(v -> {
            dialog.dismiss();
            confirmDelete(plan);
        });
        view.findViewById(R.id.btn_detail_close).setOnClickListener(v -> dialog.dismiss());

        dialog.show();
    }

    private void confirmDelete(ExpensePlan plan) {
        if (getContext() == null) return;
        View view = LayoutInflater.from(getContext()).inflate(R.layout.dialog_confirm_delete, null);
        AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
        builder.setView(view);
        AlertDialog dialog = builder.create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }

        TextView tvMessage = view.findViewById(R.id.tv_dialog_message);
        if (tvMessage != null) {
            tvMessage.setText("确定要删除支出计划 \"" + plan.title + "\" 吗？");
        }
        View btnConfirm = view.findViewById(R.id.btn_dialog_confirm);
        if (btnConfirm != null) {
            btnConfirm.setOnClickListener(v -> {
                viewModel.deletePlan(plan.id);
                toast("已删除");
                dialog.dismiss();
            });
        }
        View btnCancel = view.findViewById(R.id.btn_dialog_cancel);
        if (btnCancel != null) {
            btnCancel.setOnClickListener(v -> dialog.dismiss());
        }
        dialog.show();
    }

    // ---------------- 完成结算 ----------------

    private void showCompleteDialog(ExpensePlan plan) {
        if (getContext() == null) return;
        View view = LayoutInflater.from(getContext()).inflate(R.layout.dialog_plan_complete, null);
        AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
        builder.setView(view);
        AlertDialog dialog = builder.create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }

        LinearLayout container = view.findViewById(R.id.ll_transaction_container);
        ScrollView scrollView = view.findViewById(R.id.sv_complete_list);
        TextView tvTotal = view.findViewById(R.id.tv_complete_total);
        TextView tvEmpty = view.findViewById(R.id.tv_complete_empty);
        Button btnConfirm = view.findViewById(R.id.btn_confirm_complete);

        List<Long> selectedIds = new ArrayList<>();
        double[] selectedTotal = {0d};
        btnConfirm.setEnabled(false);
        tvTotal.setText("已选 0 笔 · " + money(0));

        viewModel.loadMonthExpenseTransactions(plan.monthKey, transactions -> {
            if (!isAdded() || container == null) return;
            if (transactions == null || transactions.isEmpty()) {
                tvEmpty.setVisibility(View.VISIBLE);
                return;
            }
            SimpleDateFormat dateFormat = new SimpleDateFormat("MM-dd", Locale.CHINA);
            for (Transaction transaction : transactions) {
                CheckBox checkBox = new CheckBox(requireContext());
                checkBox.setTextSize(13f);
                checkBox.setTextColor(getResources().getColor(R.color.text_primary, null));
                checkBox.setPadding(0, 8, 0, 8);
                String label = dateFormat.format(new Date(transaction.date)) + "  "
                        + (transaction.category == null ? "" : transaction.category) + "  "
                        + currencySymbol + String.format(Locale.CHINA, "%.2f", transaction.amount);
                if (transaction.note != null && !transaction.note.trim().isEmpty()) {
                    String note = transaction.note.trim();
                    if (note.length() > 12) note = note.substring(0, 12) + "…";
                    label += "  " + note;
                }
                checkBox.setText(label);
                final long transactionId = transaction.id;
                final double amount = transaction.amount;
                checkBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
                    if (isChecked) {
                        selectedIds.add(transactionId);
                        selectedTotal[0] += amount;
                    } else {
                        selectedIds.remove(Long.valueOf(transactionId));
                        selectedTotal[0] -= amount;
                    }
                    btnConfirm.setEnabled(!selectedIds.isEmpty());
                    tvTotal.setText("已选 " + selectedIds.size() + " 笔 · " + money(selectedTotal[0]));
                });
                container.addView(checkBox);
            }
            ViewGroup.LayoutParams lp = scrollView.getLayoutParams();
            lp.height = transactions.size() > 4 ? dp(260) : ViewGroup.LayoutParams.WRAP_CONTENT;
            scrollView.setLayoutParams(lp);
        });

        view.findViewById(R.id.btn_cancel_complete).setOnClickListener(v -> dialog.dismiss());
        btnConfirm.setOnClickListener(v -> {
            if (selectedIds.isEmpty()) return;
            viewModel.completePlan(plan.id, new ArrayList<>(selectedIds), selectedTotal[0]);
            toast("已标记完成");
            dialog.dismiss();
        });

        dialog.show();
    }
}
