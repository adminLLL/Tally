package com.example.budgetapp.domain;

import com.example.budgetapp.database.ExpensePlan;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 「计划」页的纯逻辑：月份换算、优先度重排、当月汇总。
 * 不依赖 Android，可在 JVM 单测里直接跑（见 PlanMathTest）。
 */
public final class PlanMath {

    /** 月份 key 统一 "yyyy-MM"，数据库与界面都用它。 */
    public static final DateTimeFormatter MONTH_KEY_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM", Locale.CHINA);
    public static final DateTimeFormatter MONTH_LABEL_FORMAT = DateTimeFormatter.ofPattern("yyyy年M月", Locale.CHINA);

    private PlanMath() {
    }

    public static String currentMonthKey() {
        return YearMonth.now().format(MONTH_KEY_FORMAT);
    }

    public static String monthKeyOf(LocalDate date) {
        return YearMonth.from(date).format(MONTH_KEY_FORMAT);
    }

    public static boolean isValidMonthKey(String key) {
        if (key == null || key.length() != 7 || key.charAt(4) != '-') return false;
        try {
            YearMonth.parse(key, MONTH_KEY_FORMAT);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static String monthLabel(String key) {
        try {
            return YearMonth.parse(key, MONTH_KEY_FORMAT).format(MONTH_LABEL_FORMAT);
        } catch (Exception e) {
            return key == null ? "" : key;
        }
    }

    /** key 前后移动 delta 个月，例如 "2026-01" 减 1 得 "2025-12"。 */
    public static String shiftMonthKey(String key, int delta) {
        YearMonth ym = YearMonth.parse(key, MONTH_KEY_FORMAT);
        return ym.plusMonths(delta).format(MONTH_KEY_FORMAT);
    }

    public static long monthStartMillis(String key) {
        YearMonth ym = YearMonth.parse(key, MONTH_KEY_FORMAT);
        return ym.atDay(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    public static long monthEndMillis(String key) {
        YearMonth ym = YearMonth.parse(key, MONTH_KEY_FORMAT);
        return ym.atEndOfMonth().atTime(23, 59, 59, 999_000_000)
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    /** 把当月条目按当前顺序重新编号 0..n-1，返回同一个 list，便于链式调用。 */
    public static List<ExpensePlan> normalizePriorities(List<ExpensePlan> plansOfMonth) {
        if (plansOfMonth == null) return new ArrayList<>();
        for (int i = 0; i < plansOfMonth.size(); i++) {
            ExpensePlan plan = plansOfMonth.get(i);
            if (plan != null) plan.priority = i;
        }
        return plansOfMonth;
    }

    /** 目标位置越界时夹到 [0, size]，size 表示排到最后。 */
    public static int clampIndex(int index, int size) {
        if (index < 0) return 0;
        return Math.min(index, Math.max(size, 0));
    }

    /**
     * 把 moving 从 list 里摘掉后插入到 targetIndex，返回新的顺序（不含重编号）。
     * list 必须是同一月份的完整列表；moving 不在 list 里时按其 monthKey 追加。
     */
    public static List<ExpensePlan> moveWithinMonth(List<ExpensePlan> list, ExpensePlan moving, int targetIndex) {
        List<ExpensePlan> result = new ArrayList<>();
        if (list != null) {
            for (ExpensePlan plan : list) {
                if (plan != null && moving != null
                        && (plan == moving || (plan.id > 0 && plan.id == moving.id))) continue;
                result.add(plan);
            }
        }
        int index = clampIndex(targetIndex, result.size());
        if (moving != null) result.add(index, moving);
        return result;
    }

    public static Summary summarize(List<ExpensePlan> plansOfMonth) {
        Summary summary = new Summary();
        if (plansOfMonth == null) return summary;
        for (ExpensePlan plan : plansOfMonth) {
            if (plan == null) continue;
            summary.totalCount++;
            summary.planned += plan.amount;
            if (plan.completed) {
                summary.completedCount++;
                summary.actual += plan.actualAmount;
            }
        }
        summary.overspend = Math.max(0d, summary.actual - summary.planned);
        return summary;
    }

    /** 当月汇总：计划支出 / 已支出 / 超支 / 完成进度。 */
    public static class Summary {
        public double planned;
        public double actual;
        public double overspend;
        public int completedCount;
        public int totalCount;

        public boolean isAllDone() {
            return totalCount > 0 && completedCount == totalCount;
        }

        /** 完成百分比，0..100，用于进度条。 */
        public int percent() {
            if (totalCount <= 0) return 0;
            return (int) Math.round(completedCount * 100d / totalCount);
        }

        public String progressText() {
            return completedCount + "/" + totalCount;
        }
    }
}
