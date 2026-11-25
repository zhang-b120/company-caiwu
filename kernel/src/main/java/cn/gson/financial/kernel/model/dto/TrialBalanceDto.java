package cn.gson.financial.kernel.model.dto;

import cn.hutool.core.util.NumberUtil;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 
 * <p><b>Copyright © 2010-2023 开发公司 All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.kernel.model.dto</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2023年04月21日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@Data
@Builder
public class TrialBalanceDto {
    @Builder.Default
    private BigDecimal creditTotal = BigDecimal.ZERO;
    @Builder.Default
    private BigDecimal debitTotal = BigDecimal.ZERO;
    @Builder.Default
    private BigDecimal initalCredit = BigDecimal.ZERO;
    @Builder.Default
    private BigDecimal initalDebit = BigDecimal.ZERO;
    @Builder.Default
    private BigDecimal totalCredit = BigDecimal.ZERO;
    @Builder.Default
    private BigDecimal totalDebit = BigDecimal.ZERO;

    public BigDecimal getDiffTotal() {
        return NumberUtil.sub(debitTotal, creditTotal);
    }

    public BigDecimal getTotalDiff() {
        return NumberUtil.sub(totalDebit, totalCredit);
    }

    public BigDecimal getDiffInital() {
        return NumberUtil.sub(initalDebit, initalCredit);
    }

    /**
     * 是否平衡
     *
     * @return
     */
    public boolean isFlat() {
        return NumberUtil.round(debitTotal, 2).equals(NumberUtil.round(creditTotal, 2)) &&
               NumberUtil.round(totalDebit, 2).equals(NumberUtil.round(totalCredit, 2));
    }

    /**
     * 期初是否平衡
     *
     * @return
     */
    public boolean isInitalFlat() {
        return NumberUtil.round(initalDebit, 2).equals(NumberUtil.round(initalCredit, 2));
    }
}
