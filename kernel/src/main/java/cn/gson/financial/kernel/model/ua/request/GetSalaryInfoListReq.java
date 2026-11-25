package cn.gson.financial.kernel.model.ua.request;/* Copyright © 2020 Yuech and/or its affiliates. All rights reserved. */

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;

/**
 * @Author zhb
 * @Date 2025-05-29
 * @Description
 */
@EqualsAndHashCode(callSuper = true)
@Data
public class GetSalaryInfoListReq extends GetCommonReq{

    private LocalDate period;
}
