package com.example.budgetapp.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.example.budgetapp.database.ExpensePlan;

import org.junit.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 计划页的纯逻辑测试：月份运算、优先度归一化与移动、当月汇总。
 * 这些类不依赖 Android，可直接在 JVM 上跑（bash tools/test.sh）。
 */
public class PlanMathTest {

    private ExpensePlan plan(String title, double amount, int priority, boolean completed, double actual) {
        ExpensePlan plan = new ExpensePlan(title, amount, "2026-11", priority);
        plan.completed = completed;
        plan.actualAmount = actual;
        return plan;
    }

    @Test
    public void monthKeyOf_formatsYearMonth() {
        assertEquals("2026-03", PlanMath.monthKeyOf(LocalDate.of(2026, 3, 15)));
        assertEquals("2026-12", PlanMath.monthKeyOf(LocalDate.of(2026, 12, 1)));
    }

    @Test
    public void currentMonthKey_isValid() {
        String key = PlanMath.currentMonthKey();
        assertTrue(PlanMath.isValidMonthKey(key));
        assertEquals(7, key.length());
    }

    @Test
    public void isValidMonthKey_rejectsGarbage() {
        assertTrue(PlanMath.isValidMonthKey("2026-01"));
        assertFalse(PlanMath.isValidMonthKey("2026-13"));
        assertFalse(PlanMath.isValidMonthKey("2026-00"));
        assertFalse(PlanMath.isValidMonthKey("2026-1"));
        assertFalse(PlanMath.isValidMonthKey("202601"));
        assertFalse(PlanMath.isValidMonthKey(null));
        assertFalse(PlanMath.isValidMonthKey(""));
    }

    @Test
    public void shiftMonthKey_crossesYearBoundary() {
        assertEquals("2025-12", PlanMath.shiftMonthKey("2026-01", -1));
        assertEquals("2027-01", PlanMath.shiftMonthKey("2026-12", 1));
        assertEquals("2026-06", PlanMath.shiftMonthKey("2026-06", 0));
        assertEquals("2025-11", PlanMath.shiftMonthKey("2026-02", -3));
    }

    @Test
    public void monthLabel_usesChineseFormat() {
        assertEquals("2026年3月", PlanMath.monthLabel("2026-03"));
        assertEquals("2026年12月", PlanMath.monthLabel("2026-12"));
    }

    @Test
    public void monthRange_coversWholeMonth() {
        long start = PlanMath.monthStartMillis("2026-03");
        long end = PlanMath.monthEndMillis("2026-03");
        assertTrue(end > start);
        // monthEnd 取当月最后一毫秒，因此比整月少 1ms
        assertEquals(31L * 24 * 60 * 60 * 1000 - 1, end - start);
    }

    @Test
    public void normalizePriorities_rewritesToSequentialIndex() {
        List<ExpensePlan> plans = new ArrayList<>();
        plans.add(plan("房租", 3000, 5, false, 0));
        plans.add(plan("水电", 200, 9, false, 0));
        plans.add(plan("餐饮", 1200, 0, false, 0));

        PlanMath.normalizePriorities(plans);

        assertEquals(0, plans.get(0).priority);
        assertEquals(1, plans.get(1).priority);
        assertEquals(2, plans.get(2).priority);
    }

    @Test
    public void clampIndex_keepsInsideList() {
        assertEquals(0, PlanMath.clampIndex(-5, 3));
        assertEquals(3, PlanMath.clampIndex(99, 3));
        assertEquals(2, PlanMath.clampIndex(2, 3));
        assertEquals(0, PlanMath.clampIndex(0, 0));
    }

    @Test
    public void moveWithinMonth_movesItemToTarget() {
        List<ExpensePlan> plans = new ArrayList<>();
        ExpensePlan a = plan("A", 1, 0, false, 0);
        ExpensePlan b = plan("B", 2, 1, false, 0);
        ExpensePlan c = plan("C", 3, 2, false, 0);
        plans.add(a);
        plans.add(b);
        plans.add(c);

        List<ExpensePlan> moved = PlanMath.moveWithinMonth(plans, c, 0);

        assertEquals("C", moved.get(0).title);
        assertEquals("A", moved.get(1).title);
        assertEquals("B", moved.get(2).title);

        // moveWithinMonth 只负责顺序，调用方（ViewModel.updatePlan/movePlan）负责重编号
        PlanMath.normalizePriorities(moved);
        assertEquals(0, moved.get(0).priority);
        assertEquals(1, moved.get(1).priority);
        assertEquals(2, moved.get(2).priority);
    }

    @Test
    public void summarize_sumsPlannedAndActual() {
        List<ExpensePlan> plans = new ArrayList<>();
        plans.add(plan("房租", 3000, 0, false, 0));
        plans.add(plan("餐饮", 1200, 1, true, 1350));
        plans.add(plan("交通", 300, 2, true, 280));

        PlanMath.Summary summary = PlanMath.summarize(plans);

        assertEquals(4500, summary.planned, 0.001);
        assertEquals(1630, summary.actual, 0.001);
        assertEquals(0, summary.overspend, 0.001);
        assertEquals(2, summary.completedCount);
        assertEquals(3, summary.totalCount);
        assertFalse(summary.isAllDone());
        assertEquals(67, summary.percent());
        assertEquals("2/3", summary.progressText());
    }

    @Test
    public void summarize_reportsOverspend() {
        List<ExpensePlan> plans = new ArrayList<>();
        plans.add(plan("装修", 5000, 0, true, 6200));

        PlanMath.Summary summary = PlanMath.summarize(plans);

        assertEquals(5000, summary.planned, 0.001);
        assertEquals(6200, summary.actual, 0.001);
        assertEquals(1200, summary.overspend, 0.001);
        assertTrue(summary.isAllDone());
        assertEquals(100, summary.percent());
    }

    @Test
    public void summarize_handlesEmptyList() {
        PlanMath.Summary summary = PlanMath.summarize(new ArrayList<>());

        assertEquals(0, summary.planned, 0.001);
        assertEquals(0, summary.actual, 0.001);
        assertEquals(0, summary.overspend, 0.001);
        assertEquals(0, summary.totalCount);
        assertEquals(0, summary.percent());
        assertEquals("0/0", summary.progressText());
    }
}
