package cn.gson.financial.kernel.model.entity;/* Copyright © 2020 Yuech and/or its affiliates. All rights reserved. */

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.*;

import java.time.LocalDate;

/**
 * @Author zhb
 * @Date 2025-05-29
 * @Description
 */
@Data
@Entity
@DynamicInsert
@DynamicUpdate
@EqualsAndHashCode(callSuper=false)
@Comment("报税表")
public class TaxTable {

    @Id
    @GenericGenerator(name = "CosIdGenerator", strategy = "cn.gson.financial.kernel.model.hibernate.CosIdGenerator")
    @GeneratedValue(generator = "CosIdGenerator")
    @Comment("主键")
    private Long taxId;

    @Comment("账套")
    @Column(nullable = false)
    @ColumnDefault("0")
    private Long accountSetsId;

    /**
     * 公司名称
     */
    @Column(name = "company_name", length = 100)
    @Comment("公司名称")
    private String companyName;

    /**
     * 报税类型
     */
    @Column(name = "tax_type", length = 100)
    @Comment("报税类型")
    private String taxType;

    /**
     * 报税状态
     */
    @Column(name = "tax_state", length = 100)
    @Comment("报税状态")
    private String taxState;

    /**
     * 报税状态
     */
    @Column(name = "tax_end_time")
    @Comment("报税截止时间")
    private LocalDate taxEndTime;

    /**
     * 核验日期（最近一次成功核验的日期）
     */
    @Column(name = "check_date")
    @Comment("核验日期（最近一次成功核验的日期）")
    private LocalDate checkDate;

}