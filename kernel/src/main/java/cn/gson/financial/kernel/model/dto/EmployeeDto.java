package cn.gson.financial.kernel.model.dto;

import cn.gson.financial.kernel.model.enums.AccountingCategoryType;
import cn.gson.financial.kernel.model.enums.Gender;
import com.alibaba.fastjson2.JSONObject;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * A DTO for the {@link cn.gson.financial.kernel.model.entity.Employee} entity
 */
@Data
public class EmployeeDto implements Serializable {
    private Long id;
    private Long accountSetsId;
    private Long categoryId;
    private AccountingCategoryType categoryType;
    private String code;
    private String name;
    private String mnemonics;
    private String remark;
    private Boolean enable;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private Gender gender;
    private Long departmentId;
    private Long provisionId;
    private String departmentName;
    private String post;
    private String job;
    private String telPhone;
    private LocalDate birthDate;
    private LocalDate onboardingDate;
    private LocalDate dimissionDate;
    private String idCard;
    private String email;
    private String edu;
    private String status;
    private String provisionName;
    private JSONObject scheduleCol;
    private List<SocialEmployeeDto> socialEmployeeList;
    private String employeeStatus;
    // 境内/境外区分及境外专属字段
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
}
