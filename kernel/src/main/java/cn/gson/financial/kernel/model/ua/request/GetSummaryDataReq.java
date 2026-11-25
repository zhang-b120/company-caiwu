package cn.gson.financial.kernel.model.ua.request;/* Copyright © 2020 Yuech and/or its affiliates. All rights reserved. */

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * @Author zhb
 * @Date 2025-05-20
 * @Description
 */
@EqualsAndHashCode(callSuper = true)
@Data
public class GetSummaryDataReq extends GetCommonReq{

    /**
     * 开始时间
     */
    private LocalDateTime beginDate;

    /**
     * 结束时间
     */
    private LocalDateTime endDate;
}
