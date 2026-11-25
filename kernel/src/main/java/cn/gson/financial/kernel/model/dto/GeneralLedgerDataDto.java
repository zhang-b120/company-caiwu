package cn.gson.financial.kernel.model.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 
 * <p><b>Copyright © 2010-2023 开发公司 All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.kernel.model.dto</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2023年04月11日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@Data
public class GeneralLedgerDataDto {
    private String code;
    private String subjectName;
    private String unit;
    private Long subjectId;
    private BigDecimal beginBalance;
    private BigDecimal beginBalanceFor;
    private BigDecimal endBalance;
    private BigDecimal endBalanceFor;
    private BigDecimal creditFor;
    private BigDecimal credit;
    private BigDecimal debit;
    private BigDecimal debitFor;
    private BigDecimal yearTotalDebit;
    private BigDecimal yearTotalCredit;
    private BigDecimal yearTotalDebitFor;
    private BigDecimal yearTotalCreditFor;
    private String balanceDirection;
    private String date;
}
