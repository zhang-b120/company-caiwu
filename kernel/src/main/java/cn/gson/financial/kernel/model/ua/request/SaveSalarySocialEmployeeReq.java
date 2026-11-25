package cn.gson.financial.kernel.model.ua.request;/* Copyright © 2020 Yuech and/or its affiliates. All rights reserved. */

import lombok.Data;

import java.math.BigDecimal;

/**
 * @Author zhb
 * @Date 2025-05-15
 * @Description
 */
@Data
public class SaveSalarySocialEmployeeReq {

    /**
     * 默认社保项目ID
     */
    private Long socialId;

    /**
     * 默认社保项目名称
     */
    private String name;
    /**
     * 个人比例
     */
    private BigDecimal personRatio;

    /**
     * 个人缴纳金额
     */
    private BigDecimal personNum;

    /**
     * 公司缴纳比例
     */
    private BigDecimal companyRatio;

    /**
     * 公司缴纳金额
     */
    private BigDecimal companyNum;

    /**
     * 缴纳技术
     */
    private BigDecimal depositBase;

}