package cn.gson.financial.kernel.model.entity;

import cn.gson.financial.kernel.model.bean.FieldValue;
import cn.gson.financial.kernel.model.hibernate.AbsEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.DynamicInsert;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDate;

/**
 * <p>****************************************************************************</p >
 * <p><b>Copyright  2013-2023-09-06 开发公司 All Rights Reserved<b></p >
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.kernel.model.entity</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2023-09-06</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * <p>****************************************************************************</p >
 */
@Getter
@Setter
@Entity
@Table(uniqueConstraints = {
        @UniqueConstraint(name = "uc_empId_accountsetsid_period", columnNames = {"accountSetsId", "salaryId", "empId", "period"})
})
@DynamicInsert
@DynamicUpdate
@Comment("薪资字段值")
public class SalaryFieldValue extends AbsEntity {
    @Comment("期间")
    private LocalDate period;

    @Comment("薪资ID")
    private Long salaryId;

    @Comment("员工ID")
    private Long empId;

    @Comment("工资条查看密码")
    private String password;

    @Comment("工资字段值")
    @JdbcTypeCode(SqlTypes.JSON)
    private FieldValue fieldValue;
}
