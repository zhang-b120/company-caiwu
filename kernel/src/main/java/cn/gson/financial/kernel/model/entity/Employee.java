package cn.gson.financial.kernel.model.entity;

import cn.gson.financial.kernel.annotation.ColName;
import cn.gson.financial.kernel.model.enums.Gender;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.DynamicInsert;
import org.hibernate.annotations.DynamicUpdate;

import java.time.LocalDate;

/**
 *
 * <p><b>Copyright © 2010-2023 开发公司 All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : 辅助核算-员工</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2023年04月05日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 *
 */
@Getter
@Setter
@Entity
@DynamicInsert
@Table(uniqueConstraints = {
        @UniqueConstraint(name = "uc_code_accountSetsId", columnNames = {"code", "accountSetsId"})
})
@DynamicUpdate
@Comment("辅助项-员工")
public class Employee extends AcDetails {
    @Comment("性别")
    @Enumerated(EnumType.STRING)
    private Gender gender;

    @Comment("部门")
    private Long departmentId;

    @Transient
    private String departmentName;

    @Comment("职务")
    private String post;

    @Comment("岗位")
    private String job;

    @Comment("手机")
    private String telPhone;

    @Comment("出生日期")
    private LocalDate birthDate;

    @Comment("入职日期")
    private LocalDate onboardingDate;

    @Comment("离职日期")
    private LocalDate dimissionDate;

    @Comment("身份证号码")
    private String idCard;

    @Comment("银行名称")
    private String bankName;

    @Comment("银行账号")
    private String bankAccount;

    @Comment("邮箱")
    private String email;

    @Comment("学历")
    private String edu;

    @Comment("在职状态")
    private String status;

    @Comment("计提ID")
    private Long provisionId;

    @Comment("购买社保")
    @ColumnDefault("b'0'")
    private Boolean purchase;

    @Comment("人员状态")
    private String employeeStatus;

    /* ========== 境内/境外人员区分 ========== */
    @Comment("人员类型：境内/境外")
    @ColumnDefault("'境内'")
    private String personType;

    @Comment("证件类型")
    private String idType;

    @Comment("国籍(地区)")
    private String nationality;

    @Comment("任职受雇从业类型")
    private String employmentType;

    @Comment("入职年度就业情形")
    private String employmentSituation;

    /* ========== 境外人员专属字段 ========== */
    @Comment("中文名")
    private String chineseName;

    @Comment("涉税事由")
    private String taxReason;

    @Comment("出生国家(地区)")
    private String birthCountry;

    @Comment("首次入境时间")
    private LocalDate firstEntryDate;

    @Comment("预计离境时间")
    private LocalDate expectedDepartureDate;

    @Comment("其他证件类型")
    private String otherIdType;

    @Comment("其他证件号码")
    private String otherIdNumber;
}
