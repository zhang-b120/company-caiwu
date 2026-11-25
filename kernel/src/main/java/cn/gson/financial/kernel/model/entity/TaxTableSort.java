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
@Comment("报税条目排序表")
public class TaxTableSort {

    @Id
    @GenericGenerator(name = "CosIdGenerator", strategy = "cn.gson.financial.kernel.model.hibernate.CosIdGenerator")
    @GeneratedValue(generator = "CosIdGenerator")
    @Comment("主键")
    private Long id;

    /**
     * 报税类型
     */
    @Column(name = "tax_type", length = 255)
    @Comment("报税类型")
    private String taxType;

    /**
     * 报税状态
     */
    @Column(name = "sort")
    @Comment("排序")
    private Integer sort;

}