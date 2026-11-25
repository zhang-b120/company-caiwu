package cn.gson.financial.kernel.model.entity;

import cn.gson.financial.kernel.model.hibernate.AbsEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.DynamicInsert;
import org.hibernate.annotations.DynamicUpdate;

import java.time.LocalDate;

/**
 * 
 * <p><b>Copyright © 2010-2019开发公司All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.kernel.model.entity</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2019年09月04日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@Getter
@Setter
@Entity
@Table(uniqueConstraints = {
        @UniqueConstraint(name = "uc_checkout_accountsetsid", columnNames = {"accountSetsId", "checkDate"})
})
@DynamicInsert
@DynamicUpdate
@Comment("结账期间")
public class Checkout extends AbsEntity {

    @Comment("期间年")
    @Column(nullable = false)
    private Integer checkYear;

    @Comment("期间月")
    @Column(nullable = false)
    private Integer checkMonth;

    /**
     * 0,未结转损益、未结账,1,已结转损益、未结账,2,已结转损益、已结账
     */
    @Comment("状态，0,未结转损益、未结账,1,已结转损益、未结账,2,已结转损益、已结账")
    @Column(nullable = false)
    @ColumnDefault("0")
    private Integer status;

    @Comment("期间年月")
    @Column(nullable = false)
    private LocalDate checkDate;
}
