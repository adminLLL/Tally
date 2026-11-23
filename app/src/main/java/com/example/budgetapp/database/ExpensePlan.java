package com.example.budgetapp.database;

import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * 月度支出计划（底栏「计划」页的数据来源）。
 *
 * <p>设计约定：
 * <ul>
 *   <li>{@link #monthKey} 使用 "yyyy-MM" 字符串而不是时间戳，避免时区/月初边界把一条计划算到相邻月份；
 *       同一个月内的条目用 {@link #priority} 从 0 开始连续编号，顺序即界面上看到的先后。</li>
 *   <li>{@link #actualAmount} 与 {@link #linkedTransactionIds} 只在「标记完成」时写入：
 *       用户在当月流水里勾选一条或多条，金额合计存 actualAmount，勾选到的流水 id 以逗号拼接存下来。</li>
 *   <li>全部字段都是新增的，且只新增表、不改动已有表 —— 老库升级走 MIGRATION_28_29，老备份里没有这个字段时按 null 跳过。</li>
 * </ul>
 */
@Entity(tableName = "expense_plans", indices = {@Index("monthKey")})
public class ExpensePlan {

    @PrimaryKey(autoGenerate = true)
    public int id;

    /** 支出项名称，例如「房租」。 */
    public String title;

    /** 计划支出金额。 */
    public double amount;

    /** 所属月份，"yyyy-MM"。 */
    public String monthKey;

    /** 月内优先度，0 表示排最前；每次增删改都会整月重排，保证连续。 */
    public int priority;

    /** 完成情况，默认 false（界面显示 ×）。 */
    public boolean completed;

    /** 完成时勾选的流水金额合计；未完成为 0。 */
    public double actualAmount;

    /** 完成时勾选的流水 id，逗号分隔；未完成或未勾选为 null。 */
    public String linkedTransactionIds;

    /** 标记完成的时间戳；未完成为 0。 */
    public long completedAt;

    public long createdAt;

    public long updatedAt;

    public ExpensePlan() {
    }

    public ExpensePlan(String title, double amount, String monthKey, int priority) {
        this.title = title;
        this.amount = amount;
        this.monthKey = monthKey;
        this.priority = priority;
        long now = System.currentTimeMillis();
        this.createdAt = now;
        this.updatedAt = now;
    }
}
