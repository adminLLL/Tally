package com.example.budgetapp.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * {@link LedgerMath} 的纯 JVM 单元测试。
 *
 * <p>这一层是整个记账链路里唯一不依赖 Android 的部分，所以也是唯一可以在这里
 * 用毫秒级反馈锁住的正确性：符号一旦写反，资产余额会“支出反而变多”，
 * 这类问题在真机上很难复现，但在这里一眼就能看到。
 */
public class LedgerMathTest {

    private static final double DELTA = 1e-9;

    // ---------- 类型判定 ----------

    @Test
    public void outflowTypes_areExpenseAndLend() {
        assertTrue(LedgerMath.isOutflow(0));  // 支出
        assertTrue(LedgerMath.isOutflow(4));  // 借出
        assertFalse(LedgerMath.isOutflow(1)); // 收入
        assertFalse(LedgerMath.isOutflow(3)); // 借入
        assertFalse(LedgerMath.isOutflow(2)); // 转账
    }

    @Test
    public void inflowTypes_areIncomeAndBorrow() {
        assertTrue(LedgerMath.isInflow(1));   // 收入
        assertTrue(LedgerMath.isInflow(3));   // 借入
        assertFalse(LedgerMath.isInflow(0));
        assertFalse(LedgerMath.isInflow(4));
    }

    // ---------- 己方账户：支出 / 收入 ----------

    @Test
    public void normalAsset_expenseDecreases_incomeIncreases() {
        // 普通资产(type=0)：花 100 少 100，收 100 多 100
        assertEquals(-100d, LedgerMath.ownAssetDelta(0, 0, 100d), DELTA);
        assertEquals(100d, LedgerMath.ownAssetDelta(0, 1, 100d), DELTA);
    }

    @Test
    public void liabilityAccount_expenseIncreasesDebt_incomeRepaysIt() {
        // 负债账户(type=1)：刷信用卡 100 → 欠款 +100；还款 40 → 欠款 -40
        assertEquals(100d, LedgerMath.ownAssetDelta(1, 0, 100d), DELTA);
        assertEquals(-40d, LedgerMath.ownAssetDelta(1, 1, 40d), DELTA);
    }

    @Test
    public void lendingAccount_expenseIncreasesLent_incomeCollectsBack() {
        // 借出账户(type=2)：借出 100 → 借出额 +100；收回 30 → 借出额 -30
        assertEquals(100d, LedgerMath.ownAssetDelta(2, 0, 100d), DELTA);
        assertEquals(-30d, LedgerMath.ownAssetDelta(2, 1, 30d), DELTA);
    }

    // ---------- 己方账户：借入 / 借出 ----------

    @Test
    public void borrow_increasesCash_andReducesLiabilityBalance() {
        // 借入 500：现金账户 +500
        assertEquals(500d, LedgerMath.ownAssetDelta(0, 3, 500d), DELTA);
        // 若选中的是负债账户(信用卡)，借入=往卡里还钱，欠款减少 500。
        // 注意这里沿用改造前的既有语义（原 applyAssetBalance 对 type=1、type=3 也是减），
        // 本次重构只收敛符号、不改业务口径；要改语义请单独提，别顺手在这里翻号。
        assertEquals(-500d, LedgerMath.ownAssetDelta(1, 3, 500d), DELTA);
    }

    @Test
    public void lend_movesMoneyOutOfNormalAsset_andAddsLentAmount() {
        // 借出 200：现金 -200，同时借出账户 +200
        assertEquals(-200d, LedgerMath.ownAssetDelta(0, 4, 200d), DELTA);
        assertEquals(200d, LedgerMath.ownAssetDelta(2, 4, 200d), DELTA);
    }

    // ---------- 转账与未知类型 ----------

    @Test
    public void transferAndUnknownTypes_doNotTouchOwnAssetByDefault() {
        assertEquals(0d, LedgerMath.ownAssetDelta(0, 2, 100d), DELTA);
        assertEquals(0d, LedgerMath.ownAssetDelta(0, 99, 100d), DELTA);
    }

    // ---------- 撤回必须严格互逆 ----------

    @Test
    public void revertIsAlwaysTheExactOppositeOfApply() {
        int[] assetTypes = {0, 1, 2};
        int[] txTypes = {0, 1, 2, 3, 4};
        double[] amounts = {0.01d, 1d, 99.99d, 12345.67d};

        for (int assetType : assetTypes) {
            for (int txType : txTypes) {
                for (double amount : amounts) {
                    double applied = LedgerMath.ownAssetDelta(assetType, txType, amount);
                    double reverted = LedgerMath.ownAssetRevertDelta(assetType, txType, amount);
                    assertEquals("apply+revert 必须回到 0：assetType=" + assetType
                                    + " txType=" + txType + " amount=" + amount,
                            0d, applied + reverted, DELTA);
                }
            }
        }
    }

    // ---------- 负债/借出对象 ----------

    @Test
    public void counterparty_borrowAndLendIncrease_targetRepaymentDecreases() {
        // 借入/借出 → 对象余额增加
        assertEquals(300d, LedgerMath.counterpartyDelta(3, 300d), DELTA);
        assertEquals(300d, LedgerMath.counterpartyDelta(4, 300d), DELTA);
        // 支出还债 / 收入收回 → 对象余额减少
        assertEquals(-300d, LedgerMath.counterpartyDelta(0, 300d), DELTA);
        assertEquals(-300d, LedgerMath.counterpartyDelta(1, 300d), DELTA);
        // 其他类型不联动
        assertEquals(0d, LedgerMath.counterpartyDelta(2, 300d), DELTA);
    }

    @Test
    public void counterpartyRevert_undoesApply() {
        double[] amounts = {1d, 42.5d, 9999.99d};
        for (int txType = 0; txType <= 4; txType++) {
            for (double amount : amounts) {
                assertEquals(0d,
                        LedgerMath.counterpartyDelta(txType, amount)
                                + LedgerMath.counterpartyRevertDelta(txType, amount),
                        DELTA);
            }
        }
    }

    // ---------- 清零与容差 ----------

    @Test
    public void clampNonNegative_floorsAtZero() {
        assertEquals(0d, LedgerMath.clampNonNegative(-1d), DELTA);
        assertEquals(0d, LedgerMath.clampNonNegative(0d), DELTA);
        assertEquals(12.5d, LedgerMath.clampNonNegative(12.5d), DELTA);
    }

    @Test
    public void isSettled_usesCentToleranceForDoubleResidue() {
        assertTrue(LedgerMath.isSettled(0d));
        assertTrue(LedgerMath.isSettled(0.005d));   // 多次加减后的浮点残值
        assertTrue(LedgerMath.isSettled(-0.004d));  // 略微为负也算结清
        assertFalse(LedgerMath.isSettled(0.02d));
        assertFalse(LedgerMath.isSettled(100d));
    }
}
