package cn.gson.financial.kernel.model.ua.resp;/* Copyright © 2020 Yuech and/or its affiliates. All rights reserved. */

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * @Author zhb
 * @Date 2025-05-20
 * @Description
 */
@Data
public class GetBankStatementListResp implements Serializable {

    private Long id;

    /**
     * 上传时间
     */
    private LocalDateTime createTime;

    /**
     * 上传人
     */
    private String realName;
}