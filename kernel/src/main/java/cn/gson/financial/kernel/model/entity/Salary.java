package cn.gson.financial.kernel.model.entity;

import cn.gson.financial.kernel.converter.PeriodDeserializer;
import cn.gson.financial.kernel.model.bean.FieldValue;
import cn.gson.financial.kernel.model.hibernate.AbsEntity;
import com.alibaba.fastjson.annotation.JSONField;
import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.*;
import org.hibernate.type.SqlTypes;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;


/**
 * 
 * <p><b>Copyright © 2010-2020 开发公司 All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : 員工薪酬管理</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2023/7/29 11:45</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@Getter
@Setter
@Entity
@DynamicInsert
@Comment("薪资")
public class Salary extends AbsEntity {

    @Comment("期间")
    @JsonFormat(pattern = "yyyy年MM月")
    @JSONField(deserializeUsing = PeriodDeserializer.class)
    private LocalDate period;

    @Comment("会计年")
    private Integer year;

    @Comment("会计月")
    private Integer month;

    @Comment("状态：未复合/已复核")
    @Column(nullable = false)
    @ColumnDefault("b'0'")
    private Boolean state;

    @Comment("创建时间")
    @CreationTimestamp
    private LocalDateTime createDate;

    @Comment("更新时间")
    @UpdateTimestamp
    private LocalDateTime updateDate;

    @Comment("工资字段合计值")
    @JdbcTypeCode(SqlTypes.JSON)
    private FieldValue fieldValue;

    @Comment("工资项")
    @JdbcTypeCode(SqlTypes.JSON)
    private List<SalaryField> salaryFields;
}
