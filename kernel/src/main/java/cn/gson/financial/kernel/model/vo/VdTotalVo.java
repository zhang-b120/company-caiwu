package cn.gson.financial.kernel.model.vo;

import cn.gson.financial.kernel.model.entity.AuxiliaryDetail;
import cn.hutool.core.util.NumberUtil;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 
 * <p><b>Copyright © 2010-2023 开发公司 All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.kernel.model.vo</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2023年03月15日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@Data
public class VdTotalVo {
    private Long subjectId;
    private Long currencyId;
    private String balanceDirection;
    private AuxiliaryDetail auxiliaryDetail;
    private BigDecimal debitAmount;
    private BigDecimal debitAmountFor;
    private BigDecimal creditAmount;
    private BigDecimal creditAmountFor;
    private BigDecimal debitNum;
    private BigDecimal creditNum;
    private BigDecimal num;

    public BigDecimal getAmount() {
        if ("借".equals(balanceDirection)) {
            return NumberUtil.sub(debitAmount, creditAmount);
        }
        return NumberUtil.sub(creditAmount, debitAmount);
    }

    public BigDecimal getFor() {
        if ("借".equals(balanceDirection)) {
            return NumberUtil.sub(debitAmountFor, creditAmountFor);
        }
        return NumberUtil.sub(creditAmountFor, debitAmountFor);
    }
}
