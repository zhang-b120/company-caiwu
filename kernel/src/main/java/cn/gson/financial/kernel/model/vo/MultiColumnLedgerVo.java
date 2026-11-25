package cn.gson.financial.kernel.model.vo;

import cn.gson.financial.kernel.model.entity.VoucherDetails;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * <p>***************************************</p>
 * <ul style="margin:15px;">
 * <li>Description : financial</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2024年11月12日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * <p>***************************************</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class MultiColumnLedgerVo extends VoucherDetails {
    private String subjectCode;
    private String word;
    private String balanceDirection;
    private BigDecimal balance;
    private Map<String, BigDecimal> subjectAmount = new HashMap<>();

    @Override
    public BigDecimal getCreditAmount() {
        return Optional.ofNullable(super.getCreditAmount()).orElse(BigDecimal.ZERO);
    }

    @Override
    public BigDecimal getDebitAmount() {
        return Optional.ofNullable(super.getDebitAmount()).orElse(BigDecimal.ZERO);
    }

    public BigDecimal getBalance() {
        return Optional.ofNullable(balance).orElse(BigDecimal.ZERO);
    }

    @Override
    public BigDecimal getAmount() {
        return switch (balanceDirection) {
            case "借" -> {
                if (super.getDebitAmount() != null && BigDecimal.ZERO.compareTo(this.getDebitAmount()) != 0) {
                    yield super.getDebitAmount();
                } else {
                    yield BigDecimal.ZERO.subtract(this.getCreditAmount());
                }
            }
            default -> {
                if (super.getCreditAmount() != null && BigDecimal.ZERO.compareTo(this.getCreditAmount()) != 0) {
                    yield super.getCreditAmount();
                } else {
                    yield BigDecimal.ZERO.subtract(this.getDebitAmount());
                }
            }
        };
    }
}
