package cn.gson.financial.kernel.model.ua.resp;/* Copyright © 2020 Yuech and/or its affiliates. All rights reserved. */

import jakarta.persistence.Column;
import lombok.Data;
import org.hibernate.annotations.Comment;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * @Author zhb
 * @Date 2025-05-20
 * @Description
 */
@Data
public class GetBankStatementDetailListResp {

    private Long id;
    /**
     * 交易类型
     */
    private String transactionType;

    /**
     * 业务类型
     */
    private String businessType;

    /**
     * 付款人开户行号
     */
    private String payBankNum;

    /**
     * 付款人开户行名称
     */
    private String payBankName;

    /**
     * 付款人账号
     */
    private String payAccountNum;

    /**
     * 付款人名称
     */
    private String payName;

    /**
     * 收款人开户行号
     */
    private String receiveBankNum;

    /**
     * 收款人开户行名称
     */
    private String receiveBankName;

    /**
     * 收款人账号
     */
    private String receiveAccountNum;

    /**
     * 收款人名称
     */
    private String receiveName;

    /**
     * 交易日期
     */
    private LocalDateTime tradeDate;

    /**
     * 货币类型
     */
    private String currencyType;

    /**
     * 交易金额
     */
    private BigDecimal tradeAmount;

    /**
     * 备注
     */
    private String remarks;


    /**
     * 银行名称
     */
    private String bankName;
}