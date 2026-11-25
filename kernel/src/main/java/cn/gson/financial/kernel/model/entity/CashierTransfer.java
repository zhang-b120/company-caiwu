package cn.gson.financial.kernel.model.entity;

import cn.gson.financial.kernel.model.hibernate.AbsEntity;
import jakarta.persistence.Entity;
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
 * 
 * <p><b>Copyright © 2010-2022开发公司All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : 转账</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2022年11月09日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@Getter
@Setter
@Entity
@DynamicInsert
@DynamicUpdate
@Comment("资金-转账")
public class CashierTransfer extends AbsEntity {
    /**
     * 记账日期
     */
    @Comment("记账日期")
    private LocalDate transferDate;

    @Comment("摘要")
    private String digest;

    @Comment("转入账户ID")
    private Long inAccountId;

    @Comment("转出账户ID")
    private Long outAccountId;

    @Comment("金额")
    private BigDecimal amount;

    @Comment("备注")
    private String remark;

    @Comment("凭证ID")
    private Long voucherId;

    @Comment("生成时间")
    @CreationTimestamp
    private LocalDateTime createTime;
}
