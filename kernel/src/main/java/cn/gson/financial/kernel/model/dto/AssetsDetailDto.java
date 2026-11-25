package cn.gson.financial.kernel.model.dto;

import cn.gson.financial.kernel.annotation.CopyField;
import cn.hutool.core.util.NumberUtil;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.time.LocalDate;

/**
 * A DTO for the {@link cn.gson.financial.kernel.model.entity.AssetsDetail} entity
 */
@Getter
@Setter
public class AssetsDetailDto implements Serializable {
    private Long id;
    private Long accountSetsId;
    private Long voucherId;
    private String voucherTitle;
    private LocalDate checkDate;
    private String digest;
    @CopyField
    private Double originalDebit;
    @CopyField
    private Double originalCredit;
    @CopyField
    private Double originalBalance;
    @CopyField
    private Double depreciationDebit;
    @CopyField
    private Double depreciationCredit;
    @CopyField
    private Double depreciationBalance;
    @CopyField
    private Double impairmentDebit;
    @CopyField
    private Double impairmentCredit;
    @CopyField
    private Double impairmentBalance;
    private String useState;
    private String deptName;
    private Long deptId;
    @CopyField
    private Double netValue;

    public void setOriginalDebit(Double originalDebit) {
        this.originalDebit = NumberUtil.add(this.originalDebit, originalDebit);
    }

    public void setOriginalCredit(Double originalCredit) {
        this.originalCredit = NumberUtil.add(this.originalCredit, originalCredit);
    }


    public void setDepreciationDebit(Double depreciationDebit) {
        this.depreciationDebit = NumberUtil.add(this.depreciationDebit, depreciationDebit);
    }

    public void setDepreciationCredit(Double depreciationCredit) {
        this.depreciationCredit = NumberUtil.add(this.depreciationCredit, depreciationCredit);
    }


    public void setImpairmentDebit(Double impairmentDebit) {
        this.impairmentDebit = NumberUtil.add(this.impairmentDebit, impairmentDebit);
    }

    public void setImpairmentCredit(Double impairmentCredit) {
        this.impairmentCredit = NumberUtil.add(this.impairmentCredit, impairmentCredit);
    }

}
