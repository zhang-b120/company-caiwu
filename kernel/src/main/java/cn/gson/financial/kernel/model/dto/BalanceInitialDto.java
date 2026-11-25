package cn.gson.financial.kernel.model.dto;

import cn.hutool.core.util.NumberUtil;
import cn.hutool.core.util.ReflectUtil;
import lombok.Data;

import java.io.Serializable;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

/**
 * DTO for {@link cn.gson.financial.kernel.model.entity.BalanceInitial}
 */
@Data
public class BalanceInitialDto implements Serializable {
    private Long id;
    private Long accountSetsId;
    private Long subjectId;
    private String subjectCode;
    private Long auxiliaryDetailId;
    private List<Long> auxiliaryDetailIds;
    private Long currencyId;
    private BigDecimal beginBalance;
    private BigDecimal beginBalanceFor;
    private BigDecimal yearTotalDebitFor;
    private BigDecimal yearTotalDebit;
    private BigDecimal yearTotalCreditFor;
    private BigDecimal yearTotalCredit;
    private BigDecimal yearTotalFor;
    private BigDecimal yearTotal;
    private BigDecimal beginQuantity;
    private BigDecimal yearTotalCreditQuantity;
    private BigDecimal yearTotalDebitQuantity;
    private BigDecimal yearTotalQuantity;
    private BigDecimal creditQuantity;
    private BigDecimal debitQuantity;
    private Long treeId;
    private Long parentTreeId;
    private Boolean leaf;
    private Integer level;
    private String balanceDirection;

    public boolean isUsed() {
        Field[] fields = ReflectUtil.getFields(this.getClass(), s -> s.getType().equals(BigDecimal.class));
        return Arrays.stream(fields).anyMatch(field -> {
            BigDecimal val = (BigDecimal) ReflectUtil.getFieldValue(this, field);
            return val != null && BigDecimal.ZERO.compareTo(val) != 0;
        });
    }

    public String getUniCode() {
        if (this.auxiliaryDetailId != null && this.auxiliaryDetailId != 0L) {
            return subjectCode + this.auxiliaryDetailId;
        }
        return subjectCode;
    }

    public void clear() {
        this.beginBalance = BigDecimal.ZERO;
        this.yearTotalCredit = BigDecimal.ZERO;
        this.yearTotalDebit = BigDecimal.ZERO;
        this.yearTotal = BigDecimal.ZERO;
    }

    public void add(BalanceInitialDto dto) {
        switch (this.balanceDirection) {
            case "借" -> {
                switch (dto.balanceDirection) {
                    case "借" -> {
                        this.beginBalance = NumberUtil.add(this.beginBalance, dto.getBeginBalance());
                        this.yearTotalCredit = NumberUtil.add(this.yearTotalCredit, dto.getYearTotalCredit());
                        this.yearTotalDebit = NumberUtil.add(this.yearTotalDebit, dto.getYearTotalDebit());
                    }
                    case "贷" -> {
                        this.beginBalance = NumberUtil.sub(this.beginBalance, dto.getBeginBalance());
                        this.yearTotalCredit = NumberUtil.sub(this.yearTotalCredit, dto.getYearTotalCredit());
                        this.yearTotalDebit = NumberUtil.sub(this.yearTotalDebit, dto.getYearTotalDebit());
                    }
                }
            }
            case "贷" -> {
                switch (dto.balanceDirection) {
                    case "借" -> {
                        this.beginBalance = NumberUtil.sub(this.beginBalance, dto.getBeginBalance());
                        this.yearTotalCredit = NumberUtil.sub(this.yearTotalCredit, dto.getYearTotalCredit());
                        this.yearTotalDebit = NumberUtil.sub(this.yearTotalDebit, dto.getYearTotalDebit());
                    }
                    case "贷" -> {
                        this.beginBalance = NumberUtil.add(this.beginBalance, dto.getBeginBalance());
                        this.yearTotalCredit = NumberUtil.add(this.yearTotalCredit, dto.getYearTotalCredit());
                        this.yearTotalDebit = NumberUtil.add(this.yearTotalDebit, dto.getYearTotalDebit());
                    }
                }
            }
        }
    }

    /**
     * 倒算年初公式如下：
     * 借方科目：年初数 = 期初数 + 本年累计贷方 - 本年累计借方
     * 贷方科目：年初数 = 期初数 + 本年累计借方 - 本年累计贷方
     *
     * @param
     */
    public void calculateYearTotal() {
        switch (this.balanceDirection) {
            case "借" -> {
                this.yearTotal = NumberUtil.sub(NumberUtil.add(this.beginBalance, this.yearTotalCredit), this.yearTotalDebit);
            }
            case "贷" -> {
                this.yearTotal = NumberUtil.sub(NumberUtil.add(this.beginBalance, this.yearTotalDebit), this.yearTotalCredit);
            }
        }
    }
}
