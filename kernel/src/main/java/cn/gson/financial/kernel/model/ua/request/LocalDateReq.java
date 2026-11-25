package cn.gson.financial.kernel.model.ua.request;/* Copyright © 2020 Yuech and/or its affiliates. All rights reserved. */

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;

/**
 * @Author zhb
 * @Date 2025-05-19
 * @Description
 */
@EqualsAndHashCode(callSuper = true)
@Data
public class LocalDateReq extends GetCommonReq{

    private LocalDate startDate;

    private LocalDate endDate;
}
