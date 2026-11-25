package cn.gson.financial.kernel.model.dto;

import cn.gson.financial.kernel.annotation.CopyField;
import cn.gson.financial.kernel.config.NumBigDecimalSerializer;
import cn.gson.financial.kernel.model.entity.AuxiliaryDetail;
import cn.gson.financial.kernel.service.BalanceCalcService;
import cn.hutool.core.util.NumberUtil;
import cn.hutool.core.util.ReflectUtil;
import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/**
 * A DTO for the {@link cn.gson.financial.kernel.model.entity.Balance} entity
 */
@Getter
@Setter
@ToString
public class BalanceDto implements Serializable {
    private Long id;
    private Long accountSetsId;
    private Long subjectId;
    private String queryCode;
    @CopyField
    private String subjectCode;
    private Long auxiliaryId;
    private boolean auxiliary = false;
    private String auxiliaryCode = "";
    private String auxiliaryName = "";
    @CopyField
    private Integer level;
    @CopyField
    private String subjectName;
    @CopyField
    private String subjectFullName;
    @CopyField
    private Boolean leaf;
    @CopyField
    private String balanceDirection;
    @CopyField
    private List<Long> currencyAccounting = new ArrayList<>();
    @CopyField
    private List<Long> auxiliaryAccounting = new ArrayList<>();
    @CopyField
    private List<Long> nonAuxiliaryAccounting = new ArrayList<>();
    private List<AuxiliaryDetail.Detail> details;
    @CopyField
    private String unit;
    @CopyField
    private Long treeId;
    @CopyField
    private Long parentTreeId;
    private Long auxiliaryDetailId = 0L;
    private Long currencyId;
    private LocalDate date;
    private Integer year;
    private Integer period;
    private Integer adjustPeriod;

    private BigDecimal beginBalance = BigDecimal.ZERO;
    private BigDecimal beginBalanceFor = BigDecimal.ZERO;

    private BigDecimal endBalance = BigDecimal.ZERO;
    private BigDecimal endBalanceFor = BigDecimal.ZERO;

    private BigDecimal debitFor = BigDecimal.ZERO;
    private BigDecimal debit = BigDecimal.ZERO;
    private BigDecimal creditFor = BigDecimal.ZERO;
    private BigDecimal credit = BigDecimal.ZERO;

    //cf开头为结转金额
    private BigDecimal cfDebitFor = BigDecimal.ZERO;
    private BigDecimal cfDebit = BigDecimal.ZERO;
    private BigDecimal cfCreditFor = BigDecimal.ZERO;
    private BigDecimal cfCredit = BigDecimal.ZERO;

    private BigDecimal yearTotalDebitFor = BigDecimal.ZERO;
    private BigDecimal yearTotalDebit = BigDecimal.ZERO;
    private BigDecimal yearTotalCreditFor = BigDecimal.ZERO;
    private BigDecimal yearTotalCredit = BigDecimal.ZERO;
    private BigDecimal yearTotalFor = BigDecimal.ZERO;
    private BigDecimal yearTotal = BigDecimal.ZERO;
    @JsonSerialize(using = NumBigDecimalSerializer.class)
    private BigDecimal beginQuantity = BigDecimal.ZERO;
    @JsonSerialize(using = NumBigDecimalSerializer.class)
    private BigDecimal endQuantity = BigDecimal.ZERO;
    @JsonSerialize(using = NumBigDecimalSerializer.class)
    private BigDecimal creditQuantity = BigDecimal.ZERO;
    @JsonSerialize(using = NumBigDecimalSerializer.class)
    private BigDecimal debitQuantity = BigDecimal.ZERO;
    @JsonSerialize(using = NumBigDecimalSerializer.class)
    private BigDecimal cfCreditQuantity = BigDecimal.ZERO;
    @JsonSerialize(using = NumBigDecimalSerializer.class)
    private BigDecimal cfDebitQuantity = BigDecimal.ZERO;
    @JsonSerialize(using = NumBigDecimalSerializer.class)
    private BigDecimal yearTotalCreditQuantity = BigDecimal.ZERO;
    @JsonSerialize(using = NumBigDecimalSerializer.class)
    private BigDecimal yearTotalDebitQuantity = BigDecimal.ZERO;
    @JsonSerialize(using = NumBigDecimalSerializer.class)
    private BigDecimal yearTotalQuantity = BigDecimal.ZERO;

    private String type;

    private BigDecimal beginDebitBalance = BigDecimal.ZERO;
    private BigDecimal beginDebitBalanceFor = BigDecimal.ZERO;
    private BigDecimal endDebitBalance = BigDecimal.ZERO;
    private BigDecimal endDebitBalanceFor = BigDecimal.ZERO;

    private BigDecimal beginCreditBalance = BigDecimal.ZERO;
    private BigDecimal beginCreditBalanceFor = BigDecimal.ZERO;
    private BigDecimal endCreditBalance = BigDecimal.ZERO;
    private BigDecimal endCreditBalanceFor = BigDecimal.ZERO;

    private BigDecimal yearTotalBeginDebit = BigDecimal.ZERO;
    private BigDecimal yearTotalBeginCredit = BigDecimal.ZERO;
    private BigDecimal yearTotalBeginDebitFor = BigDecimal.ZERO;
    private BigDecimal yearTotalBeginCreditFor = BigDecimal.ZERO;

    @JsonIgnore
    private Set<BalanceCalcService.CurrencyAuxiliary> merged = new HashSet<>();
    @JsonSerialize(using = NumBigDecimalSerializer.class)
    private BigDecimal endCreditQuantity = BigDecimal.ZERO;
    @JsonSerialize(using = NumBigDecimalSerializer.class)
    private BigDecimal endDebitQuantity = BigDecimal.ZERO;

    /**
     * 是否有值不为0，有不为0的则表示已使用
     *
     * @return
     */
    public boolean isUsed() {
        Field[] fields = ReflectUtil.getFields(this.getClass(), s -> s.getType().equals(BigDecimal.class));
        return Arrays.stream(fields).anyMatch(field -> {
            BigDecimal val = (BigDecimal) ReflectUtil.getFieldValue(this, field);
            return val != null && BigDecimal.ZERO.compareTo(val) != 0;
        });
    }

    /**
     * 合并
     *
     * @return
     */
    public void add(BalanceDto dto) {
        if (dto != null) {
            BalanceCalcService.CurrencyAuxiliary key = new BalanceCalcService.CurrencyAuxiliary(dto.getSubjectId(), dto.getCurrencyId(), dto.getAuxiliaryDetailId());
            if (!merged.contains(key)) {
                Field[] fields = ReflectUtil.getFields(this.getClass(), s -> s.getType().equals(BigDecimal.class));
                Arrays.stream(fields).forEach(field -> {
                    BigDecimal val = (BigDecimal) ReflectUtil.getFieldValue(this, field);
                    BigDecimal val2 = (BigDecimal) ReflectUtil.getFieldValue(dto, field);
                    ReflectUtil.setFieldValue(this, field, NumberUtil.add(val, val2));
                });
                this.calculateEndBalance();
                merged.add(key);
            }
        }
    }

    /**
     * 发生额
     *
     * @return 金额
     */
    public BigDecimal getAmount() {
        if (StrUtil.isNotEmpty(this.getBalanceDirection())) {
            switch (this.getBalanceDirection()) {
                case "借" -> {
                    return NumberUtil.sub(this.debit, this.credit);
                }
                case "贷" -> {
                    return NumberUtil.sub(this.credit, this.debit);
                }
            }
        }
        return BigDecimal.ZERO;
    }

    /**
     * 外币发生额
     *
     * @return 金额
     */
    public BigDecimal getAmountFor() {
        if (StrUtil.isNotEmpty(this.getBalanceDirection())) {
            switch (this.getBalanceDirection()) {
                case "借" -> {
                    return NumberUtil.sub(this.debitFor, this.creditFor);
                }
                case "贷" -> {
                    return NumberUtil.sub(this.creditFor, this.debitFor);
                }
            }
        }
        return BigDecimal.ZERO;
    }

    /**
     * 发生额
     *
     * @return 金额
     */
    public BigDecimal getDebitAmount() {
        return NumberUtil.sub(this.debit, this.credit);
    }

    /**
     * 外币发生额
     *
     * @return 金额
     */
    public BigDecimal getDebitAmountFor() {
        return NumberUtil.sub(this.debitFor, this.creditFor);
    }

    /**
     * 发生额
     *
     * @return 金额
     */
    public BigDecimal getCreditAmount() {
        return NumberUtil.sub(this.credit, this.debit);
    }

    /**
     * 外币发生额
     *
     * @return 金额
     */
    public BigDecimal getCreditAmountFor() {
        return NumberUtil.sub(this.creditFor, this.debitFor);
    }

    /**
     * 是否是辅助核算
     *
     * @return
     */
    public boolean isAd() {
        return this.auxiliaryDetailId != 0L;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        BalanceDto that = (BalanceDto) o;
        return Objects.equals(accountSetsId, that.accountSetsId) && Objects.equals(subjectId, that.subjectId) && Objects.equals(auxiliaryDetailId, that.auxiliaryDetailId) && Objects.equals(currencyId, that.currencyId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(accountSetsId, subjectId, auxiliaryDetailId, currencyId);
    }

    /**
     * 设置本期
     */
    public void setCurrentPeriod(BalanceDto balanceDto) {
        this.credit = NumberUtil.add(balanceDto.credit, this.credit);
        this.debit = NumberUtil.add(balanceDto.debit, this.debit);
        this.creditFor = NumberUtil.add(balanceDto.creditFor, this.creditFor);
        this.debitFor = NumberUtil.add(balanceDto.debitFor, this.debitFor);
        this.debitQuantity = NumberUtil.add(balanceDto.debitQuantity, this.debitQuantity);
        this.creditQuantity = NumberUtil.add(balanceDto.creditQuantity, this.creditQuantity);
    }

    /**
     * 汇总子科目
     */
    public void summarySubAccount(BalanceDto balanceDto) {
        this.credit = NumberUtil.add(balanceDto.credit, this.credit);
        this.debit = NumberUtil.add(balanceDto.debit, this.debit);
        this.creditFor = NumberUtil.add(balanceDto.creditFor, this.creditFor);
        this.debitFor = NumberUtil.add(balanceDto.debitFor, this.debitFor);
        this.yearTotalCredit = NumberUtil.add(balanceDto.yearTotalCredit, this.yearTotalCredit);
        this.yearTotalDebit = NumberUtil.add(balanceDto.yearTotalDebit, this.yearTotalDebit);
        this.yearTotalCreditFor = NumberUtil.add(balanceDto.yearTotalCreditFor, this.yearTotalCreditFor);
        this.yearTotalDebitFor = NumberUtil.add(balanceDto.yearTotalDebitFor, this.yearTotalDebitFor);
        this.debitFor = NumberUtil.add(balanceDto.debitFor, this.debitFor);
        this.debitQuantity = NumberUtil.add(balanceDto.debitQuantity, this.debitQuantity);
        this.creditQuantity = NumberUtil.add(balanceDto.creditQuantity, this.creditQuantity);
    }

    /**
     * 汇总下级
     *
     * @param balanceDto 下级
     */
    public void summary(BalanceDto balanceDto) {
        //方向相同直接相加
        this.setCurrentPeriod(balanceDto);

        this.beginDebitBalance = NumberUtil.add(balanceDto.beginDebitBalance, this.beginDebitBalance);
        this.beginCreditBalance = NumberUtil.add(balanceDto.beginCreditBalance, this.beginCreditBalance);
        this.beginDebitBalanceFor = NumberUtil.add(balanceDto.beginDebitBalanceFor, this.beginDebitBalanceFor);
        this.beginCreditBalanceFor = NumberUtil.add(balanceDto.beginCreditBalanceFor, this.beginCreditBalanceFor);

        this.yearTotalBeginCredit = NumberUtil.add(balanceDto.yearTotalBeginCredit, this.yearTotalBeginCredit);
        this.yearTotalBeginDebit = NumberUtil.add(balanceDto.yearTotalBeginDebit, this.yearTotalBeginDebit);
        this.yearTotalBeginCreditFor = NumberUtil.add(balanceDto.yearTotalBeginCreditFor, this.yearTotalBeginCreditFor);
        this.yearTotalBeginDebitFor = NumberUtil.add(balanceDto.yearTotalBeginDebitFor, this.yearTotalBeginDebitFor);

        this.cfCredit = NumberUtil.add(balanceDto.cfCredit, this.cfCredit);
        this.cfDebit = NumberUtil.add(balanceDto.cfDebit, this.cfDebit);
        this.cfCreditFor = NumberUtil.add(balanceDto.cfCreditFor, this.cfCreditFor);
        this.cfDebitFor = NumberUtil.add(balanceDto.cfDebitFor, this.cfDebitFor);
        this.cfDebitQuantity = NumberUtil.add(balanceDto.cfDebitQuantity, this.cfDebitQuantity);
        this.cfCreditQuantity = NumberUtil.add(balanceDto.cfCreditQuantity, this.cfCreditQuantity);

    }

    /**
     * 期末转换到年初
     */
    public void endToBeginYear() {
        this.yearTotalCredit = this.credit;
        this.yearTotalCreditFor = this.creditFor;
        this.yearTotalDebit = this.debit;
        this.yearTotalDebitFor = this.debitFor;
        this.yearTotalCreditQuantity = this.creditQuantity;
        this.yearTotalDebitQuantity = this.debitQuantity;

        this.yearTotal = this.endBalance;
        this.yearTotalFor = this.endBalanceFor;
        this.yearTotalQuantity = this.endQuantity;

        this.yearTotalBeginCredit = this.endCreditBalance;
        this.yearTotalBeginDebit = this.endDebitBalance;
        this.yearTotalBeginCreditFor = this.endCreditBalanceFor;
        this.yearTotalBeginDebitFor = this.endDebitBalanceFor;
    }

    /**
     * 计算期末
     */
    public void calculateEnd() {
        this.calculateEndBalance();

        this.endCreditBalance = NumberUtil.add(this.beginCreditBalance, NumberUtil.sub(this.credit, this.debit));
        this.endCreditBalanceFor = NumberUtil.add(this.beginCreditBalanceFor, NumberUtil.sub(this.creditFor, this.debitFor));

        this.endDebitBalance = NumberUtil.add(this.beginDebitBalance, NumberUtil.sub(this.debit, this.credit));
        this.endDebitBalanceFor = NumberUtil.add(this.beginDebitBalanceFor, NumberUtil.sub(this.debitFor, this.creditFor));

        this.endDebitQuantity = NumberUtil.add(this.yearTotalDebitQuantity, this.debitQuantity);
        this.endCreditQuantity = NumberUtil.add(this.yearTotalCreditQuantity, this.creditQuantity);
    }

    private void calculateEndBalance() {
        switch (this.balanceDirection) {
            case "借" -> {
                this.endBalance = NumberUtil.add(this.beginBalance, NumberUtil.sub(this.debit, this.credit));
                this.endBalanceFor = NumberUtil.add(this.beginBalanceFor, NumberUtil.sub(this.debitFor, this.creditFor));
                this.endQuantity = NumberUtil.add(this.beginQuantity, NumberUtil.sub(this.debitQuantity, this.creditQuantity));
            }
            case "贷" -> {
                this.endBalance = NumberUtil.add(this.beginBalance, NumberUtil.sub(this.credit, this.debit));
                this.endBalanceFor = NumberUtil.add(this.beginBalanceFor, NumberUtil.sub(this.creditFor, this.debitFor));
                this.endQuantity = NumberUtil.add(this.beginQuantity, NumberUtil.sub(this.creditQuantity, this.debitQuantity));
            }
        }
    }

    public void calculateBeginBalance(BalanceDto dto) {
        switch (this.balanceDirection) {
            case "借" -> {
                switch (dto.balanceDirection) {
                    case "借" -> {
                        this.beginBalance = NumberUtil.add(this.beginBalance, dto.getBeginBalance());
                        this.beginBalanceFor = NumberUtil.add(this.beginBalanceFor, dto.getBeginBalanceFor());
                        this.beginQuantity = NumberUtil.add(this.beginQuantity, dto.getBeginQuantity());
                    }
                    case "贷" -> {
                        this.beginBalance = NumberUtil.sub(this.beginBalance, dto.getBeginBalance());
                        this.beginBalanceFor = NumberUtil.sub(this.beginBalanceFor, dto.getBeginBalanceFor());
                        this.beginQuantity = NumberUtil.sub(this.beginQuantity, dto.getBeginQuantity());
                    }
                }
            }
            case "贷" -> {
                switch (dto.balanceDirection) {
                    case "借" -> {
                        this.beginBalance = NumberUtil.sub(this.beginBalance, dto.getBeginBalance());
                        this.beginBalanceFor = NumberUtil.sub(this.beginBalanceFor, dto.getBeginBalanceFor());
                        this.beginQuantity = NumberUtil.sub(this.beginQuantity, dto.getBeginQuantity());
                    }
                    case "贷" -> {
                        this.beginBalance = NumberUtil.add(this.beginBalance, dto.getBeginBalance());
                        this.beginBalanceFor = NumberUtil.add(this.beginBalanceFor, dto.getBeginBalanceFor());
                        this.beginQuantity = NumberUtil.add(this.beginQuantity, dto.getBeginQuantity());
                    }
                }
            }
        }
    }
}
