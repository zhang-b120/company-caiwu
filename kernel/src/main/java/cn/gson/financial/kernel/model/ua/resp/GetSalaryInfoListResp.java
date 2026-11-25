package cn.gson.financial.kernel.model.ua.resp;/* Copyright © 2020 Yuech and/or its affiliates. All rights reserved. */

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * @Author zhb
 * @Date 2025-05-21
 * @Description
 */
@Data
public class GetSalaryInfoListResp implements Serializable {

    private Long id;
    private Long accountSetsId;
    @JsonFormat(pattern = "yyyy年MM月")
    private LocalDate period;
    private Integer year;
    private Integer month;
    private Boolean state;

    private Double salarySum;
}