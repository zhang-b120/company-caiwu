package cn.gson.financial.kernel.model.vo;

import cn.gson.financial.kernel.config.NumBigDecimalSerializer;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 
 * <p><b>Copyright © 2010-2024 开发公司 All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.kernel.model.vo</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2024年02月27日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@Data
public class AbGeneralLedgerVo {
    private String code;
    private String subjectName;
    private String unit;
    private String subjectId;
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
    @JsonSerialize(using = NumBigDecimalSerializer.class)
    private BigDecimal beginQuantity;
    private BigDecimal beginPrice;
    private BigDecimal endPrice;
    @JsonSerialize(using = NumBigDecimalSerializer.class)
    private BigDecimal endQuantity;
    @JsonSerialize(using = NumBigDecimalSerializer.class)
    private BigDecimal yearTotalQuantity;
    @JsonSerialize(using = NumBigDecimalSerializer.class)
    private BigDecimal yearTotalCreditQuantity;
    @JsonSerialize(using = NumBigDecimalSerializer.class)
    private BigDecimal yearTotalDebitQuantity;
    @JsonSerialize(using = NumBigDecimalSerializer.class)
    private BigDecimal debitQuantity;
    @JsonSerialize(using = NumBigDecimalSerializer.class)
    private BigDecimal creditQuantity;
    @JsonSerialize(using = NumBigDecimalSerializer.class)
    private BigDecimal endDebitQuantity;
    @JsonSerialize(using = NumBigDecimalSerializer.class)
    private BigDecimal endCreditQuantity;
    private String date;
}
