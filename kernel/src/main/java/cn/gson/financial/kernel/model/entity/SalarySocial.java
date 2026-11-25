package cn.gson.financial.kernel.model.entity;

import cn.gson.financial.kernel.model.enums.SocialType;
import cn.gson.financial.kernel.model.hibernate.AbsEntity;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.DynamicInsert;
import org.hibernate.annotations.DynamicUpdate;

import java.math.BigDecimal;

/**
 * <p>****************************************************************************</p >
 * <p><b>Copyright  2013-2023-09-05 开发公司 All Rights Reserved<b></p >
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.kernel.model.entity</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2023-09-05</li>
 * <li>@author     : 超豪</li>
 * </ul>
 * <p>****************************************************************************</p >
 */
@Getter
@Setter
@Entity
@DynamicInsert
@Table(uniqueConstraints = {
        @UniqueConstraint(name = "uc_name_accountSetsId", columnNames = {"name", "accountSetsId"})
})
@DynamicUpdate
@Comment("薪资社保配置")
public class SalarySocial extends AbsEntity {

    @Comment("名称")
    private String name;

    @Comment("类型：社保、公积金")
    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private SocialType type;

    @Comment("个人比例")
    @Column(scale = 3, precision = 19)
    @JsonSerialize(using = ToStringSerializer.class)
    private BigDecimal personRatio;

    @Comment("公司比例")
    @Column(scale = 3, precision = 19)
    @JsonSerialize(using = ToStringSerializer.class)
    private BigDecimal companyRatio;

    @Comment("缴存基数")
    private BigDecimal depositBase;

    @Comment("个人金额")
    private BigDecimal personNum;

    @Comment("公司金额")
    private BigDecimal companyNum;
}
