package cn.gson.financial.kernel.model.entity;

import cn.gson.financial.kernel.model.hibernate.AbsEntity;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * <p><b>Copyright  2013-2023-09-08 开发公司 All Rights Reserved<b></p >
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.kernel.model.entity</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2023-09-08</li>
 * <li>@author     : 超豪</li>
 * </ul>
 * </ul> 员工社保明细表
 * <p>****************************************************************************</p >
 */
@Getter
@Setter
@Entity
@DynamicInsert
@Table(uniqueConstraints = {
        @UniqueConstraint(name = "uc_empId_securityId_accountSetsId", columnNames = {"empId", "socialId", "accountSetsId"})
})
@DynamicUpdate
@Comment("薪资员工社保配置")
public class SalarySocialEmployee extends AbsEntity {

    @Comment("默认社保项目ID")
    private Long socialId;

    @Column(nullable = false)
    @Comment("员工")
    private Long empId;

    @Comment("个人比例")
    @Column(scale = 3, precision = 19)
    @JsonSerialize(using = ToStringSerializer.class)
    private BigDecimal personRatio;

    @Comment("个人金额")
    private BigDecimal personNum;

    @Comment("公司比例")
    @Column(scale = 3, precision = 19)
    @JsonSerialize(using = ToStringSerializer.class)
    private BigDecimal companyRatio;

    @Comment("公司金额")
    private BigDecimal companyNum;

    @Comment("缴存基数")
    private BigDecimal depositBase;

    public boolean isEmpty() {
        return personRatio == null && personNum == null && companyRatio == null && companyNum == null && depositBase == null;
    }
}
