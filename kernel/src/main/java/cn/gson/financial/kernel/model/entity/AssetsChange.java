package cn.gson.financial.kernel.model.entity;

import cn.gson.financial.kernel.model.enums.AssetsChangeType;
import cn.gson.financial.kernel.model.hibernate.AbsEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.DynamicInsert;
import org.hibernate.annotations.DynamicUpdate;

import java.time.LocalDate;

/**
 * 
 * <p><b>Copyright © 2010-2022开发公司All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : 固定资产变更记录</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2022年10月22日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@DynamicInsert
@DynamicUpdate
@Getter
@Setter
@Entity
@NoArgsConstructor
@Comment("资产变更")
public class AssetsChange extends AbsEntity {
    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    @Comment("变动单类型")
    private AssetsChangeType changeType;
    @Comment("固定资产ID")
    private Long assetsId;
    @Comment("期间年")
    private Integer checkYear;
    @Comment("期间月")
    private Integer checkMonth;
    @Comment("期间年月")
    private LocalDate checkDate;
    @Comment("变动前")
    private String beforeChange;
    @Comment("变动后")
    private String afterChange;
    @Comment("凭证id")
    private Long voucherId;
    @CreationTimestamp
    private LocalDate createDate;
}

