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
public class AbBalanceDto {
    private BigDecimal credit = BigDecimal.ZERO;
    private BigDecimal creditFor = BigDecimal.ZERO;
    private BigDecimal debit = BigDecimal.ZERO;
    private BigDecimal debitFor = BigDecimal.ZERO;
    private BigDecimal endBalance = BigDecimal.ZERO;
    private BigDecimal endBalanceFor = BigDecimal.ZERO;
    private BigDecimal num = BigDecimal.ZERO;
    private BigDecimal price = BigDecimal.ZERO;
    private BigDecimal creditNum = BigDecimal.ZERO;
    private BigDecimal debitNum = BigDecimal.ZERO;
    private BigDecimal creditPrice = BigDecimal.ZERO;
    private BigDecimal debitPrice = BigDecimal.ZERO;
}
