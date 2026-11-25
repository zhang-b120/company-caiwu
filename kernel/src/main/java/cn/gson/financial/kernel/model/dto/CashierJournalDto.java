package cn.gson.financial.kernel.model.dto;

import cn.gson.financial.kernel.model.entity.AcDetails;
import cn.gson.financial.kernel.model.entity.CashierAccount;
import cn.gson.financial.kernel.model.entity.CashierIoType;
import cn.gson.financial.kernel.model.enums.AccountingCategoryType;
import cn.gson.financial.kernel.model.hibernate.FileInfo;
import cn.hutool.core.collection.CollUtil;
import jakarta.persistence.Transient;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A DTO for the {@link cn.gson.financial.kernel.model.entity.CashierJournal} entity
 */
@Getter
@Setter
public class CashierJournalDto implements Serializable {
    private Long id;
    private String digest;
    private String code;
    private CashierAccount.CashierAccountType type;
    private String serialNumber;
    private Integer serial;
    private LocalDate billDate;
    private Long accountSetsId;
    private Long accountId;
    private String accountName;
    private String accountType;
    private String accountCode;
    private Long ioTypeId;
    private String ioTypeValue;
    private CashierIoType.Type ioType;
    private Long accountingCategoryId;
    private Long accountingCategoryDetailsId;
    private String accountingCategoryDetailsValue;
    private Long voucherId;
    private String voucherCode;
    private Boolean init;
    private Boolean edit = true;
    private String remark;
    private String settlementTypeTitle;
    private String billNo;
    private String projectName;
    private String deptName;
    /**
     * 项目分类ID
     */
    private Long cashierTransferId;
    private Long projectCategoryId;
    /**
     * 项目ID
     */
    private Long projectCategoryDetailsId;
    /**
     * 部门分类ID
     */
    private Long deptCategoryId;
    /**
     * 部门ID
     */
    private Long deptCategoryDetailsId;
    private Long settlementTypeId;
    private BigDecimal debit;
    private BigDecimal credit;
    private BigDecimal balance;
    private List<FileInfo> billList;
    //转账信息
    private Long inAccountId;
    private Long inSubjectId;
    private String inAccountName;
    private Long outAccountId;
    private Long outSubjectId;
    private String outAccountName;
    private Long subjectId;
    private List<AcDetails> auxiliary;
    private Long creatorId;
    private String creatorName;
    private String transactionPostscript;//交易附言
    private String oppositeAccountName;//对方户名

    @Transient
    public BigDecimal getAmount() {
        return Optional.ofNullable(credit).orElse(debit);
    }

    public void setAuxiliary(List<AcDetails> auxiliary) {
        this.auxiliary = auxiliary;
        if (CollUtil.isNotEmpty(auxiliary)) {
            auxiliary.forEach(acDetails -> {
                if (acDetails.getCategoryType() == AccountingCategoryType.PROJECT) {
                    this.projectName = acDetails.getName();
                    this.projectCategoryDetailsId = acDetails.getId();
                } else if (acDetails.getCategoryType() == AccountingCategoryType.DEPARTMENT) {
                    this.deptName = acDetails.getName();
                    this.deptCategoryDetailsId = acDetails.getId();
                } else {
                    this.accountingCategoryDetailsValue = acDetails.getName();
                    this.accountingCategoryDetailsId = acDetails.getId();
                    this.accountingCategoryId = acDetails.getCategoryId();
                }
            });
        }
    }

    public List<AcDetails> getAuxiliary() {
        if (CollUtil.isNotEmpty(auxiliary)) {
            return auxiliary;
        }

        List<AcDetails> auxiliary = new ArrayList<>();
        if (projectCategoryDetailsId != null) {
            AcDetails ad = new AcDetails();
            ad.setId(projectCategoryDetailsId);
            ad.setName(projectName);
            ad.setCategoryId((long) AccountingCategoryType.PROJECT.ordinal());
            ad.setCategoryType(AccountingCategoryType.PROJECT);
            auxiliary.add(ad);
        }
        if (deptCategoryDetailsId != null) {
            AcDetails ad = new AcDetails();
            ad.setId(deptCategoryDetailsId);
            ad.setName(deptName);
            ad.setCategoryId((long) AccountingCategoryType.DEPARTMENT.ordinal());
            ad.setCategoryType(AccountingCategoryType.DEPARTMENT);
            auxiliary.add(ad);
        }
        if (accountingCategoryDetailsId != null) {
            AcDetails ad = new AcDetails();
            ad.setId(accountingCategoryDetailsId);
            ad.setCategoryId(accountingCategoryId);
            ad.setName(accountingCategoryDetailsValue);
            ad.setCategoryType(AccountingCategoryType.values()[Math.toIntExact(accountingCategoryId)]);
            auxiliary.add(ad);
        }
        return auxiliary.isEmpty() ? null : auxiliary;
    }
}
