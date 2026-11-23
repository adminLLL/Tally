package com.example.budgetapp.domain;

/**
 * 余额变动的“唯一算术真相源”。
 *
 * <p>改造前，资产余额的加减在 5 处各写了一遍（RecordFragment / FinanceViewModel /
 * QuickAddTileService / RecurringTransactionManager / DailyInterestService），
 * 每处符号约定略有不同，导致“删单不回补、改单双扣、周期账单重复激活”这类问题。
 *
 * <p>这里把符号规则收敛成两个纯函数，所有调用方只负责“取账户 → 加增量 → 写回”。
 * 纯函数没有 Android 依赖，可在 JVM 单元测试里直接覆盖（见 LedgerMathTest）。
 */
public final class LedgerMath {

    private LedgerMath() {
    }

    /** 对象账户视为“已清零”的容差：历史数据用 double 存储，长期加减后会有 1e-12 级残值。 */
    public static final double ZERO_EPSILON = 0.01;

    /** 支出(0) 与 借出(4)：钱从己方账户流出。 */
    public static boolean isOutflow(int txType) {
        return txType == 0 || txType == 4;
    }

    /** 收入(1) 与 借入(3)：钱流入己方账户。 */
    public static boolean isInflow(int txType) {
        return txType == 1 || txType == 3;
    }

    /**
     * 己方资产账户（支出/收入时选中的那个账户）在一次记账被“应用”后的余额增量，
     * 返回值已经带符号，调用方直接 {@code asset.amount += delta} 即可。
     *
     * <ul>
     *   <li>普通资产(type=0)：支出/借出 → 减少；收入/借入 → 增加</li>
     *   <li>负债账户(type=1)：支出/借出 → 欠款增加；收入/借入 → 还款，欠款减少</li>
     *   <li>借出账户(type=2)：支出/借出 → 借出增加；收入/借入 → 收回，借出减少</li>
     * </ul>
     *
     * <p>转账(type=2) 与未知类型不计入，返回 0。
     */
    public static double ownAssetDelta(int assetType, int txType, double amount) {
        if (!isOutflow(txType) && !isInflow(txType)) {
            return 0;
        }
        double accountDirection = (assetType == 0) ? -1d : 1d;
        double flowDirection = isOutflow(txType) ? 1d : -1d;
        return accountDirection * flowDirection * amount;
    }

    /**
     * 撤回一次记账时，己方资产账户应加的增量：正好是 {@link #ownAssetDelta} 的相反数。
     */
    public static double ownAssetRevertDelta(int assetType, int txType, double amount) {
        return -ownAssetDelta(assetType, txType, amount);
    }

    /**
     * 负债/借出“对象账户”（按名字匹配的那个账户）在一次记账被应用后的余额增量。
     *
     * <ul>
     *   <li>借入(3) / 借出(4)：对象欠我的 / 我欠对象的金额增加</li>
     *   <li>支出(0) 还债 / 收入(1) 收回：对象余额减少</li>
     * </ul>
     */
    public static double counterpartyDelta(int txType, double amount) {
        if (txType == 3 || txType == 4) {
            return amount;
        }
        if (txType == 0 || txType == 1) {
            return -amount;
        }
        return 0;
    }

    /** 撤回时对象账户的增量。 */
    public static double counterpartyRevertDelta(int txType, double amount) {
        return -counterpartyDelta(txType, amount);
    }

    /**
     * 负债/借出账户不允许为负：还款超过欠款时压到 0（与改造前各处行为一致）。
     */
    public static double clampNonNegative(double value) {
        return value <= 0 ? 0 : value;
    }

    /** 对象账户撤回后是否已经结清（可以删除该账户）。 */
    public static boolean isSettled(double value) {
        return value <= ZERO_EPSILON;
    }
}
