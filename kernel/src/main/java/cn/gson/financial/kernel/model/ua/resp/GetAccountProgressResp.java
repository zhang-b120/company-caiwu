package cn.gson.financial.kernel.model.ua.resp;/* Copyright © 2020 Yuech and/or its affiliates. All rights reserved. */

import lombok.Builder;
import lombok.Data;

/**
 * @Author zhb
 * @Date 2025-05-29
 * @Description
 */
@Data
public class GetAccountProgressResp {

    private Boolean salary = Boolean.FALSE;

    private Boolean invoice = Boolean.FALSE;

    private Boolean bankStatement = Boolean.FALSE;

    private Boolean taxDeclare = Boolean.FALSE;

    private Integer count;
}