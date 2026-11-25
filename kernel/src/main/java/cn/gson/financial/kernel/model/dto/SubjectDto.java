package cn.gson.financial.kernel.model.dto;

import cn.hutool.core.util.StrUtil;
import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * A DTO for the {@link cn.gson.financial.kernel.model.entity.Subject} entity
 */
@Data
public class SubjectDto implements Serializable {
    private Long id;
    private Long accountSetsId;
    private String type;
    private String code;
    private String queryCode;
    private String name;
    private String mnemonicCode;
    private String balanceDirection;
    private Boolean status;
    private Long parentId;
    private Integer level;
    private Boolean systemDefault;
    private String unit;
    private String fullName;
    private Boolean leaf;
    private Boolean cash;
    private Boolean adjustments;
    /** FN-NEW-OPENAPI-ENSURE-SUBJECTS：AI 自动创建·待会计确认标记 */
    private Boolean autoCreated;
    private List<Long> auxiliaryAccounting = new ArrayList<>();
    private List<Long> currencyAccounting = new ArrayList<>();
    private List<Long> nonAuxiliaryAccounting;
    private Integer  encLen;
    public void setName(String name) {
        this.name = StrUtil.trimToEmpty(name);
    }
}
