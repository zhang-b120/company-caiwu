package cn.gson.financial.kernel.model.entity;

import cn.gson.financial.kernel.model.hibernate.AbsEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.DynamicInsert;
import org.hibernate.annotations.DynamicUpdate;

import java.time.LocalDate;

/**
 * 
 * <p><b>Copyright © 2010-2022开发公司All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : 计提折旧记录</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2022年11月25日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@Getter
@Setter
@Entity
@DynamicInsert
@DynamicUpdate
@Table(uniqueConstraints = {
        @UniqueConstraint(columnNames = {"accountSetsId", "checkDate"})
})
@Comment("资产折旧")
public class AssetsDepreciation extends AbsEntity {

    @Comment("期间年")
    private Integer checkYear;

    @Comment("期间月")
    private Integer checkMonth;

    /**
     * 是否已折旧,1,无数据，2，已计算折旧，3，，4，无需折旧
     */
    @Comment("是否已折旧")
    @Column(columnDefinition = "int not null default 1")
    private Integer status;

    /**
     * 是否可以取消
     */
    @Comment("是否可以取消")
    @Column(columnDefinition = "bit not null default b'1'")
    private Boolean cancel;

    @Comment("关联凭证")
    private Long voucherId;

    @Comment("期间日期")
    private LocalDate checkDate;
}
