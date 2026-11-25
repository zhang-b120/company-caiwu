package cn.gson.financial.kernel.model.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 
 * <ul style="margin:15px;">
 * <li>Description : financial</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2024年07月12日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@Data
public class FeeDetailBalanceDto {
    private Long subjectId;
    private Integer year;
    private Integer period;
    private BigDecimal amount;
    private boolean yearTotal = false;
}
