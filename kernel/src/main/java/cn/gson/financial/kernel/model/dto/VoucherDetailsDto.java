package cn.gson.financial.kernel.model.dto;

import cn.gson.financial.kernel.model.entity.AcDetails;
import cn.gson.financial.kernel.model.vo.SubjectVo;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.NumberUtil;
import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * A DTO for the {@link cn.gson.financial.kernel.model.entity.VoucherDetails} entity
 */
@Data
public class VoucherDetailsDto implements Serializable {
    private Long id;
    private Long accountSetsId;
    private Long voucherId;
    private Integer year;
    private Integer month;
    private LocalDate voucherDate;
    private Long currencyId;
    private String currencyName;
    private String currencyCode;
    private BigDecimal originalAmount;
    private String summary;
    private Long subjectId;
    private String subjectName;
    private String subjectCode;
    private String customerName;
    private SubjectVo subject;
    private BigDecimal debitAmount;
    private BigDecimal creditAmount;
    private BigDecimal num;
    private BigDecimal price;
    private Long auxiliaryDetailId;
    private List<AcDetails> auxiliary;
    private BigDecimal debitAmountFor;
    private BigDecimal creditAmountFor;
    @JsonSerialize(using = ToStringSerializer.class)
    private BigDecimal exchangeRate;
    private BigDecimal debitNum;
    private BigDecimal creditNum;
    private Boolean carryForward;
    private Boolean cashFlow;

    /**
     * 获取科目名称
     *
     * @return
     */
    public String getSubjectName() {
        if (StrUtil.isNotEmpty(this.subjectCode) && StrUtil.isNotEmpty(subjectName)) {
            return subjectName.startsWith(this.subjectCode) ? subjectName : this.subjectCode + " " + StrUtil.replace(subjectName, "-", "_");
        }
        return subjectName;
    }

    /**
     * 获取辅助核算
     *
     * @return
     */
    public String getAuxiliaryTitle() {
        if (CollUtil.isNotEmpty(this.auxiliary)) {
            return this.auxiliary.stream().filter(Objects::nonNull)
                    .sorted(Comparator.comparing(AcDetails::getCategoryId))
                    .map(a -> a.getCode() + "_" + a.getName()).collect(Collectors.joining("_"));
        }
        return "";
    }

    /**
     * 获取摘要
     *
     * @return
     */
    public void setSummary(String summary) {
        this.summary = StrUtil.trimToEmpty(summary);
    }

    public String getSubjectPrintTitle() {
        String auxiliaryTitle = getAuxiliaryTitle();
        if (StrUtil.isNotBlank(auxiliaryTitle)) {
            return getSubjectName() + "_" + auxiliaryTitle;
        }
        return getSubjectName();
    }

    /**
     * 获取金额
     *
     * @return
     */
    public BigDecimal getAmount() {
        if (debitAmount != null && !NumberUtil.equals(debitAmount, BigDecimal.ZERO)) {
            return debitAmount;
        } else {
            return creditAmount;
        }
    }

    /**
     * 获取数量
     *
     * @return
     */
    public BigDecimal getNum() {
        return Optional.ofNullable(Optional.ofNullable(num).orElse(debitNum)).orElse(creditNum);
    }

    /**
     * 获取数量
     *
     * @return
     */
    public BigDecimal getPrice() {
        // 假设NumberUtil.div能够处理除数为零的情况
        BigDecimal num = getNum();
        if (num != null && num.compareTo(BigDecimal.ZERO) != 0) {
            BigDecimal originalAmount = getOriginalAmount();
            BigDecimal amount = getAmount();
            // 优先使用originalAmount，如果它不为null,切不为0
            return (originalAmount != null && originalAmount.compareTo(BigDecimal.ZERO) != 0) ? NumberUtil.div(originalAmount, num) : NumberUtil.div(amount, num);
        }
        return BigDecimal.ZERO;
    }

    /**
     * 获取外币金额
     *
     * @return
     */
    public BigDecimal getOriginalAmount() {
        if (debitAmountFor != null && !NumberUtil.equals(debitAmountFor, BigDecimal.ZERO)) {
            return debitAmountFor;
        } else {
            return creditAmountFor;
        }
    }

    /**
     * 获取外币金额
     *
     * @return
     */
    public BigDecimal originalAmount() {
        return originalAmount;
    }

    public void setDebitAmount(BigDecimal debitAmount) {
        if (debitAmount != null) {
            Assert.isTrue(NumberUtil.isLessOrEqual(debitAmount, BigDecimal.valueOf(999999999.99999)), "借方金额最大支持999999999.99999");
            this.debitAmount = debitAmount.setScale(5, RoundingMode.HALF_DOWN);
        }
    }

    public void setCreditAmount(BigDecimal creditAmount) {
        if (creditAmount != null) {
            Assert.isTrue(NumberUtil.isLessOrEqual(creditAmount, BigDecimal.valueOf(999999999.99999)), "贷方方金额最大支持999999999.99999");
            this.creditAmount = creditAmount.setScale(5, RoundingMode.HALF_DOWN);
        }
    }

    /**
     * 过滤掉非必填，没有填写信息的辅助信息
     * @return
     */
    public List<AcDetails> getAuxiliary() {
        if (CollUtil.isNotEmpty(this.auxiliary)) {
            return this.auxiliary.stream()
                    .filter(Objects::nonNull)
                    .filter(d -> d.getCategoryType() != null && d.getId() != null && d.getCategoryId() != null)
                    .toList();
        }
        return null;
    }
}
