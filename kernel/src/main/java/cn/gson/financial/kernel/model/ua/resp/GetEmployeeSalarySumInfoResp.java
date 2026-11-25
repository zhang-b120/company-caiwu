package cn.gson.financial.kernel.model.ua.resp;/* Copyright © 2020 Yuech and/or its affiliates. All rights reserved. */

import lombok.Data;

import java.math.BigDecimal;

/**
 * @Author zhb
 * @Date 2025-05-29
 * @Description
 */
@Data
public class GetEmployeeSalarySumInfoResp {

    private Long employeeCount;

    private BigDecimal salarySum;
}