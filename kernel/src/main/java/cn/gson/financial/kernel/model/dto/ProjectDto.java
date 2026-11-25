package cn.gson.financial.kernel.model.dto;

import cn.gson.financial.kernel.model.enums.AccountingCategoryType;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A DTO for the {@link cn.gson.financial.kernel.model.entity.Project} entity
 */
@Data
public class ProjectDto implements Serializable {
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
    private Long departmentId;
    private String departmentName;
    private String leader;
    private String telPhone;
    private LocalDate startDate;
    private LocalDate acceptanceDate;
}
