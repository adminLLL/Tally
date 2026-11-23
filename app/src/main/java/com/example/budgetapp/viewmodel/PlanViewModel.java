package com.example.budgetapp.viewmodel;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Transformations;

import com.example.budgetapp.BackupManager;
import com.example.budgetapp.database.AppDatabase;
import com.example.budgetapp.database.ExpensePlan;
import com.example.budgetapp.database.ExpensePlanDao;
import com.example.budgetapp.database.Transaction;
import com.example.budgetapp.database.TransactionDao;
import com.example.budgetapp.domain.PlanMath;

import java.util.ArrayList;
import java.util.List;

/**
 * 「计划」页的 ViewModel：月份切换 + 月度计划 CRUD + 完成结算 + 当月流水读取。
 * 所有数据库写操作都走 {@link AppDatabase#databaseWriteExecutor}，多行改动（重排优先度）包在事务里。
 */
public class PlanViewModel extends AndroidViewModel {

    private final AppDatabase db;
    private final ExpensePlanDao planDao;
    private final TransactionDao transactionDao;

    private final MutableLiveData<String> monthKey = new MutableLiveData<>();
    private final LiveData<List<ExpensePlan>> plans;
    private final LiveData<PlanMath.Summary> summary;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public interface CountCallback {
        void onCount(int count);
    }

    public interface TransactionsCallback {
        void onLoaded(List<Transaction> transactions);
    }

    public PlanViewModel(@NonNull Application application) {
        super(application);
        db = AppDatabase.getDatabase(application);
        planDao = db.expensePlanDao();
        transactionDao = db.transactionDao();

        plans = Transformations.switchMap(monthKey, planDao::observeByMonth);
        summary = Transformations.map(plans, PlanMath::summarize);
    }

    public void setMonth(String key) {
        if (key == null) return;
        String current = monthKey.getValue();
        if (key.equals(current)) return;
        monthKey.setValue(key);
    }

    public LiveData<String> getMonthKey() {
        return monthKey;
    }

    public LiveData<List<ExpensePlan>> getPlans() {
        return plans;
    }

    public LiveData<PlanMath.Summary> getSummary() {
        return summary;
    }

    private String requireMonth() {
        String key = monthKey.getValue();
        return key == null ? PlanMath.currentMonthKey() : key;
    }

    /** 所有写操作的统一入口：写库 + 数据变更后触发自动备份（AGENTS.md 约定）。 */
    private void write(Runnable task) {
        db.databaseWriteExecutor.execute(() -> {
            task.run();
            BackupManager.triggerAutoUploadIfEnabled(getApplication());
        });
    }

    /** 新增：默认排到当月末尾。 */
    public void addPlan(String title, double amount, String month) {
        final String targetMonth = PlanMath.isValidMonthKey(month) ? month : requireMonth();
        write(() -> db.runInTransaction(() -> {
            List<ExpensePlan> list = planDao.getByMonthSync(targetMonth);
            ExpensePlan plan = new ExpensePlan(title, amount, targetMonth, list.size());
            planDao.insert(plan);
            applyOrder(list, plan, list.size());
        }));
    }

    /**
     * 修改标题/金额/月份/优先度。targetIndex 是「在目标月份里排第几位」（0 起，size 表示末尾）。
     * 换月份时：旧月份整月重排（补齐空洞），目标月份按 targetIndex 插入并重排。
     */
    public void updatePlan(int id, String title, double amount, String month, int targetIndex) {
        final String targetMonth = PlanMath.isValidMonthKey(month) ? month : requireMonth();
        write(() -> db.runInTransaction(() -> {
            ExpensePlan plan = planDao.getByIdSync(id);
            if (plan == null) return;
            String oldMonth = plan.monthKey;

            plan.title = title;
            plan.amount = amount;
            plan.monthKey = targetMonth;
            plan.updatedAt = System.currentTimeMillis();
            planDao.update(plan);

            List<ExpensePlan> targetList = planDao.getByMonthSync(targetMonth);
            // 先把移动中的条目从列表里摘掉（它可能已经被 update 到新月份，也可能还在旧月份）
            List<ExpensePlan> ordered = new ArrayList<>();
            for (ExpensePlan item : targetList) {
                if (item.id != plan.id) ordered.add(item);
            }
            int index = PlanMath.clampIndex(targetIndex, ordered.size());
            ordered.add(index, plan);
            for (int i = 0; i < ordered.size(); i++) {
                ExpensePlan item = ordered.get(i);
                if (item.priority != i) {
                    item.priority = i;
                    planDao.update(item);
                }
            }

            if (oldMonth != null && !oldMonth.equals(targetMonth)) {
                List<ExpensePlan> oldList = planDao.getByMonthSync(oldMonth);
                for (int i = 0; i < oldList.size(); i++) {
                    ExpensePlan item = oldList.get(i);
                    if (item.priority != i) {
                        item.priority = i;
                        planDao.update(item);
                    }
                }
            }
        }));
    }

    /** 新增后把整月重排一次，保证计划内的顺序与 priority 一致。 */
    private void applyOrder(List<ExpensePlan> existing, ExpensePlan inserted, int targetIndex) {
        List<ExpensePlan> ordered = PlanMath.moveWithinMonth(existing, inserted, targetIndex);
        for (int i = 0; i < ordered.size(); i++) {
            ExpensePlan item = ordered.get(i);
            if (item.priority != i) {
                item.priority = i;
                planDao.update(item);
            }
        }
    }

    /** delta = -1 上移，+1 下移；越界时什么都不做。 */
    public void movePlan(int id, int delta) {
        write(() -> db.runInTransaction(() -> {
            ExpensePlan plan = planDao.getByIdSync(id);
            if (plan == null || plan.monthKey == null) return;
            List<ExpensePlan> list = planDao.getByMonthSync(plan.monthKey);
            int index = -1;
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i).id == id) {
                    index = i;
                    break;
                }
            }
            if (index < 0) return;
            int target = index + delta;
            if (target < 0 || target >= list.size()) return;
            List<ExpensePlan> ordered = PlanMath.moveWithinMonth(list, plan, target);
            for (int i = 0; i < ordered.size(); i++) {
                ExpensePlan item = ordered.get(i);
                if (item.priority != i) {
                    item.priority = i;
                    planDao.update(item);
                }
            }
        }));
    }

    public void deletePlan(int id) {
        write(() -> db.runInTransaction(() -> {
            ExpensePlan plan = planDao.getByIdSync(id);
            if (plan == null) return;
            String month = plan.monthKey;
            planDao.delete(plan);
            if (month != null) {
                List<ExpensePlan> list = planDao.getByMonthSync(month);
                for (int i = 0; i < list.size(); i++) {
                    ExpensePlan item = list.get(i);
                    if (item.priority != i) {
                        item.priority = i;
                        planDao.update(item);
                    }
                }
            }
        }));
    }

    /** 标记完成：记录勾选的流水与金额合计。 */
    public void completePlan(int id, List<Long> transactionIds, double actualAmount) {
        write(() -> db.runInTransaction(() -> {
            ExpensePlan plan = planDao.getByIdSync(id);
            if (plan == null) return;
            plan.completed = true;
            plan.actualAmount = actualAmount;
            plan.completedAt = System.currentTimeMillis();
            plan.updatedAt = plan.completedAt;
            plan.linkedTransactionIds = joinIds(transactionIds);
            planDao.update(plan);
        }));
    }

    /** 取消完成：清空关联流水与实付金额，回到未完成状态。 */
    public void reopenPlan(int id) {
        write(() -> db.runInTransaction(() -> {
            ExpensePlan plan = planDao.getByIdSync(id);
            if (plan == null) return;
            plan.completed = false;
            plan.actualAmount = 0d;
            plan.linkedTransactionIds = null;
            plan.completedAt = 0L;
            plan.updatedAt = System.currentTimeMillis();
            planDao.update(plan);
        }));
    }

    public static String joinIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(ids.get(i));
        }
        return sb.toString();
    }

    public static List<Long> parseIds(String joined) {
        List<Long> ids = new ArrayList<>();
        if (joined == null || joined.trim().isEmpty()) return ids;
        for (String part : joined.split(",")) {
            try {
                ids.add(Long.parseLong(part.trim()));
            } catch (NumberFormatException ignored) {
            }
        }
        return ids;
    }

    /** 读取某个月的计划条数，用于新增/编辑时计算优先度位置。 */
    public void loadMonthPlanCount(String month, CountCallback callback) {
        final String key = PlanMath.isValidMonthKey(month) ? month : requireMonth();
        db.databaseWriteExecutor.execute(() -> {
            int count = planDao.getByMonthSync(key).size();
            mainHandler.post(() -> {
                if (callback != null) callback.onCount(count);
            });
        });
    }

    /** 读取某个月的支出流水（type=0、已生效），用于完成弹窗勾选。 */
    public void loadMonthExpenseTransactions(String month, TransactionsCallback callback) {
        final String key = PlanMath.isValidMonthKey(month) ? month : requireMonth();
        final long start = PlanMath.monthStartMillis(key);
        final long end = PlanMath.monthEndMillis(key);
        db.databaseWriteExecutor.execute(() -> {
            List<Transaction> raw = transactionDao.getTransactionsByRange(start, end);
            List<Transaction> expenses = new ArrayList<>();
            if (raw != null) {
                for (Transaction t : raw) {
                    if (t == null || !t.isSettled) continue;
                    if (t.type != 0) continue;
                    if ("资产互转".equals(t.category)) continue;
                    expenses.add(t);
                }
            }
            mainHandler.post(() -> {
                if (callback != null) callback.onLoaded(expenses);
            });
        });
    }
}
