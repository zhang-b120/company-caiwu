package cn.gson.financial.kernel.model.entity;

import cn.gson.financial.kernel.model.hibernate.AbsEntity;
import cn.gson.financial.kernel.model.hibernate.FileInfo;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.DynamicInsert;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 
 * <p><b>Copyright © 2010-2022开发公司All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : 现金日记账</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2022年10月27日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@Getter
@Setter

@Entity
@DynamicInsert
@DynamicUpdate
@Comment("资金-日记账")
public class CashierJournal extends AbsEntity {
    /**
     * 现金/银行
     */
    @Comment("账户类型")
    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private CashierAccount.CashierAccountType type;

    @Comment("摘要")
    private String digest;
    /**
     * 流水号
     */
    @Comment("流水号")
    private String code;

    @Comment("单据号")
    private String billNo;
    /**
     * 序列号
     */
    @Comment("序列号")
    private String serialNumber;
    /**
     * 序列
     */
    @Comment("序列")
    private Integer serial;
    /**
     * 单据日期
     */
    @Comment("单据日期")
    private LocalDate billDate;

    /**
     * 账户ID
     */
    @Comment("账户ID")
    private Long accountId;
    /**
     * 收支类别
     */
    @Comment("收支类别Id")
    private Long ioTypeId;
    /**
     * 往来单位类别ID
     */
    @Comment("往来单位类别ID")
    private Long accountingCategoryId;
    /**
     * 往来单位id
     */
    @Comment("往来单位id")
    private Long accountingCategoryDetailsId;
    /**
     * 项目分类ID
     */
    @Comment("项目分类ID")
    private Long projectCategoryId;
    /**
     * 项目ID
     */
    @Comment("项目ID")
    private Long projectCategoryDetailsId;
    /**
     * 部门分类ID
     */
    @Comment("部门分类ID")
    private Long deptCategoryId;
    /**
     * 部门ID
     */
    @Comment("部门ID")
    private Long deptCategoryDetailsId;
    /**
     * 对方科目ID
     */
    @Comment("对方科目ID")
    private Long subjectId;
    /**
     * 借方
     */
    @Comment("借方金额")
    private BigDecimal debit;
    /**
     * 贷方
     */
    @Comment("贷方金额")
    private BigDecimal credit;
    /**
     * 余额
     */
    @Comment("余额")
    private BigDecimal balance;
    /**
     * 关联凭证ID
     */
    @Comment("凭证ID")
    private Long voucherId;

    @Column(columnDefinition = "bit not null default b'0'")
    @Comment("是否期初")
    private Boolean init;

    @Comment("结算类型")
    private Long settlementTypeId;

    @Comment("创建人")
    private Long creatorId;

    @Comment("备注")
    private String remark;

    @Comment("转账账户ID")
    private Long cashierTransferId;

    @Comment("附件")
    @JdbcTypeCode(SqlTypes.JSON)
    private List<FileInfo> billList;

    @Comment("辅助核算")
    @JdbcTypeCode(SqlTypes.JSON)
    private List<AcDetails> auxiliary;

    @Comment("交易附言")
    private String transactionPostscript;

    @Comment("对方户名")
    private String oppositeAccountName;

    @Comment("导入时间")
    private LocalDateTime importTime;

    @Comment("原始交易日期")
    private LocalDate tradeDate;


    public void setCredit(BigDecimal credit) {
        if (!Optional.ofNullable(credit).orElse(BigDecimal.ZERO).equals(BigDecimal.ZERO)) {
            this.credit = credit;
            this.debit = null;
        }
    }

    public void setDebit(BigDecimal debit) {
        if (!Optional.ofNullable(debit).orElse(BigDecimal.ZERO).equals(BigDecimal.ZERO)) {
            this.debit = debit;
            this.credit = null;
        }
    }
}
