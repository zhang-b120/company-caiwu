package cn.gson.financial.kernel.model.dto;

import cn.gson.financial.kernel.model.bean.FieldValue;
import cn.gson.financial.kernel.model.enums.AccountingCategoryType;
import cn.gson.financial.kernel.model.enums.Gender;
import cn.hutool.core.annotation.Alias;
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSONObject;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 
 * <p><b>Copyright © 2010-2023 开发公司 All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.kernel.model.dto</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2023年11月06日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@Data
public class SalaryEmployeeDto {
    private Long id;
    private Long accountSetsId;
    private Long categoryId;
    private AccountingCategoryType categoryType;
    @Alias("编码")
    private String code;
    @Alias("名称")
    private String name;
    private String mnemonics;
    @Alias("备注")
    private String remark;
    private Boolean enable;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    @Alias("性别")
    private Gender gender;
    private Long departmentId;
    private Long provisionId;
    @Alias("部门")
    private String departmentName;
    @Alias("职务")
    private String post;
    @Alias("岗位")
    private String job;
    @Alias("手机")
    private String telPhone;
    @Alias("出生日期")
    private LocalDate birthDate;
    @Alias("入职日期")
    private LocalDate onboardingDate;
    @Alias("离职日期")
    private LocalDate dimissionDate;
    @Alias("身份证")
    private String idCard;
    @Alias("邮箱")
    private String email;
    @Alias("学历")
    private String edu;
    @Alias("在职状态")
    private String status;
    private String provisionName;
    private String provisionCode;
    private JSONObject scheduleCol;
    private Boolean purchase;
    @Alias("银行名称")
    private String bankName;
    @Alias("银行账号")
    private String bankAccount;
    private String social;
    // 境内/境外区分及境外专属字段（不加 @Alias，避免影响 Excel 导入导出列）
    private String personType;
    private String idType;
    private String nationality;
    private String employmentType;
    private String employmentSituation;
    private String chineseName;
    private String taxReason;
    private String birthCountry;
    private LocalDate firstEntryDate;
    private LocalDate expectedDepartureDate;
    private String otherIdType;
    private String otherIdNumber;
    private FieldValue fieldValue = new FieldValue();
    private List<String> errors = new ArrayList<>();

    public void setCode(String code) {
        this.code = StrUtil.removePrefix(code, "`");
    }

    public void setBankAccount(String bankAccount) {
        this.bankAccount = StrUtil.removePrefix(bankAccount, "`");
    }

    public void setTelPhone(String telPhone) {
        this.telPhone = StrUtil.removePrefix(telPhone, "`");
    }

    public void setIdCard(String idCard) {
        this.idCard = StrUtil.removePrefix(idCard, "`");
    }
}
