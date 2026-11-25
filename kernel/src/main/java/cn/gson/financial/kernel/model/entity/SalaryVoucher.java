package cn.gson.financial.kernel.model.entity;

import cn.gson.financial.kernel.converter.PeriodDeserializer;
import cn.gson.financial.kernel.model.hibernate.AbsEntity;
import com.alibaba.fastjson.annotation.JSONField;
import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.DynamicInsert;
import org.hibernate.annotations.DynamicUpdate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * <p>****************************************************************************</p >
 * <p><b>Copyright  2013-2023-09-08 开发公司 All Rights Reserved<b></p >
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.kernel.model.entity</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2023-09-08</li>
 * <li>@author     : 超豪</li>
 * </ul>
 * <p>****************************************************************************</p >
 */
@Getter
@Setter
@Entity
@DynamicInsert
@DynamicUpdate
@Table(uniqueConstraints = {
        @UniqueConstraint(columnNames = {"templateId", "accountSetsId", "salaryId", "period"})
})
public class SalaryVoucher extends AbsEntity {
    @Comment("凭证ID")
    private Long voucherId;

    @Comment("期间")
    @JsonFormat(pattern = "yyyy年MM月")
    @JSONField(deserializeUsing = PeriodDeserializer.class)
    private LocalDate period;

    @Comment("凭证ID")
    private Long templateId;

    @Comment("薪资ID")
    private Long salaryId;

    @Comment("年")
    private Integer year;

    @Comment("月")
    private Integer month;

    @Comment("凭证金额")
    private BigDecimal amount;

    @CreationTimestamp
    private LocalDateTime createTime;
}
