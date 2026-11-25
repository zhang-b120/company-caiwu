package cn.gson.financial.kernel.model.entity;/* Copyright © 2020 Yuech and/or its affiliates. All rights reserved. */

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.DynamicInsert;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.GenericGenerator;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * @Author zhb
 * @Date 2025-05-20
 * @Description
 */
@Data
@Entity
@DynamicInsert
@DynamicUpdate
@EqualsAndHashCode(callSuper=false)
@Comment("银行流水数据明细表")
public class BankStatementDetail implements Serializable {

    @Id
    @GenericGenerator(name = "CosIdGenerator", strategy = "cn.gson.financial.kernel.model.hibernate.CosIdGenerator")
    @GeneratedValue(generator = "CosIdGenerator")
    @Comment("主键")
    private Long id;

    @Column(name = "bank_statement_id")
    @Comment("流水ID")
    private Long bankStatementId;

    /**
     * 交易类型
     */
    @Column(name = "transaction_type", length = 64)
    @Comment("交易类型")
    private String transactionType;

    /**
     * 业务类型
     */
    @Column(name = "business_type", length = 64)
    @Comment("业务类型")
    private String businessType;

    /**
     * 付款人开户行号
     */
    @Column(name = "pay_bank_num", length = 64)
    @Comment("付款人开户行号")
    private String payBankNum;

    /**
     * 付款人开户行名称
     */
    @Column(name = "pay_bank_name", length = 255)
    @Comment("付款人开户行名称")
    private String payBankName;

    /**
     * 付款人账号
     */
    @Column(name = "pay_account_num", length = 64)
    @Comment("付款人账号")
    private String payAccountNum;

    /**
     * 付款人名称
     */
    @Column(name = "pay_name", length = 64)
    @Comment("付款人名称")
    private String payName;

    /**
     * 收款人开户行号
     */
    @Column(name = "receive_bank_num", length = 64)
    @Comment("收款人开户行号")
    private String receiveBankNum;

    /**
     * 收款人开户行名称
     */
    @Column(name = "receive_bank_name", length = 255)
    @Comment("收款人开户行名称")
    private String receiveBankName;

    /**
     * 收款人账号
     */
    @Column(name = "receive_account_num", length = 64)
    @Comment("收款人账号")
    private String receiveAccountNum;

    /**
     * 收款人名称
     */
    @Column(name = "receive_name", length = 64)
    @Comment("收款人名称")
    private String receiveName;

    /**
     * 交易日期
     */
    @Column(name = "trade_date")
    @Comment("交易日期")
    private LocalDateTime tradeDate;

    /**
     * 货币类型
     */
    @Column(name = "currency_type", length = 16)
    @Comment("货币类型")
    private String currencyType;

    /**
     * 交易金额
     */
    @Column(name = "trade_amount", precision = 20, scale = 4)
    @Comment("交易金额")
    private BigDecimal tradeAmount;

    /**
     * 备注
     */
    @Column(name = "remarks", length = 255)
    @Comment("备注")
    private String remarks;


    /**
     * 银行名称
     */
    @Column(name = "bank_name", length = 64)
    @Comment("银行名称")
    private String bankName;

    @Column(name = "is_delete")
    @Comment("是否删除 0-否 1-是")
    private int isDelete;

    @Column(name = "cashier_journal_id")
    @Comment("关联日记账ID（AI处理后回写，null表示未处理）")
    private Long cashierJournalId;

    @Column(name = "voucher_id")
    @Comment("关联凭证ID（xiaowei生成凭证后回写，null表示未做账）")
    private Long voucherId;

    @Column(name = "account_set_id")
    @Comment("账套ID")
    private Long accountSetId;

    @Column(name = "cashier_account_id")
    @Comment("关联系统银行账户ID")
    private Long cashierAccountId;

    @Column(name = "account_number", length = 64)
    @Comment("本方账号（从流水文件提取）")
    private String accountNumber;

    @Column(name = "serial_no", length = 64)
    @Comment("银行流水号（原始唯一编号，用于去重）")
    private String serialNo;

    @Column(name = "balance", precision = 20, scale = 4)
    @Comment("交易后账户余额")
    private BigDecimal balance;

    @Column(name = "subject_id")
    @Comment("对方科目ID（仅销售收入/服务收入/购买材料/工资社保类别时填入）")
    private Long subjectId;

    @Column(name = "io_type_id")
    @Comment("收支类别ID（AI匹配系统现有类别后回填）")
    private Long ioTypeId;
}
