package cn.gson.financial.kernel.model.vo;

import cn.hutool.core.util.NumberUtil;
import lombok.Data;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 *  cn.gson.financial.kernel.model.vo
 *  <p>Creation: 2025年01月09日</p>
 * @version 1.0
 * @author 开发者
 */
@Data
public class AgeAnalysisVo {
    private Long subjectId;
    private Long detailId;
    private String balanceDirection;
    private boolean auxiliary = false;
    private Set<Long> auxiliaryDetailIds;
    private String code;
    private String name;
    private String params;
    private BigDecimal initial = BigDecimal.ZERO;
    private BigDecimal debit = BigDecimal.ZERO;
    private BigDecimal credit = BigDecimal.ZERO;
    private BigDecimal total = BigDecimal.ZERO;
    private BigDecimal totalPercent = BigDecimal.ZERO;
    private Map<Integer, AgeDetail> ages = new HashMap<>();

    public void setInitial(BigDecimal initial) {
        this.initial = Optional.ofNullable(initial).orElse(BigDecimal.ZERO);
    }

    public void setDebit(BigDecimal debit) {
        this.debit = Optional.ofNullable(debit).orElse(BigDecimal.ZERO);
    }

    public void setCredit(BigDecimal credit) {
        this.credit = Optional.ofNullable(credit).orElse(BigDecimal.ZERO);
    }

    public void setTotal(BigDecimal total) {
        this.total = Optional.ofNullable(total).orElse(BigDecimal.ZERO);
    }

    public void setTotalPercent(BigDecimal totalPercent) {
        this.totalPercent = Optional.ofNullable(totalPercent).orElse(BigDecimal.ZERO);
    }

    public void addAges(Integer day, BigDecimal debit, BigDecimal credit, AtomicReference<BigDecimal> endBalance) {
        AgeDetail ad = new AgeDetail();
        switch (balanceDirection) {
            case "借" -> ad.setAmount(debit);
            case "贷" -> ad.setAmount(credit);
        }
        BigDecimal bigDecimal = endBalance.get();
        endBalance.updateAndGet(v -> v.subtract(ad.getAmount()));
        if (NumberUtil.isGreater(endBalance.get(), BigDecimal.ZERO)) {
            if (ad.getAmount() != null && ad.getAmount().compareTo(BigDecimal.ZERO) != 0) {
                ad.setPercent(NumberUtil.mul(NumberUtil.div(ad.getAmount(), total), BigDecimal.valueOf(100)));
            }
            ages.put(day, ad);
        } else if (NumberUtil.isGreater(bigDecimal, BigDecimal.ZERO)) {
            ad.setAmount(bigDecimal);
            ad.setPercent(NumberUtil.mul(NumberUtil.div(ad.getAmount(), total), BigDecimal.valueOf(100)));
            ages.put(day, ad);
        }
    }

    @Data
    public static class AgeDetail {
        private BigDecimal amount = BigDecimal.ZERO;
        private BigDecimal percent = BigDecimal.ZERO;

        public void setAmount(BigDecimal amount) {
            this.amount = Optional.ofNullable(amount).orElse(BigDecimal.ZERO);
        }

        public void setPercent(BigDecimal percent) {
            this.percent = Optional.ofNullable(percent).orElse(BigDecimal.ZERO);
        }

        public String getPercent() {
            return NumberUtil.round(percent, 2).toString();
        }
    }
}
