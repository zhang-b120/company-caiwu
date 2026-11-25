package cn.gson.financial.kernel.model.dto;

import cn.gson.financial.kernel.model.entity.AccountSetsTemplate;
import cn.gson.financial.kernel.model.enums.AccountStandard;
import cn.gson.financial.kernel.model.enums.SubjectCategory;
import cn.gson.financial.kernel.model.enums.VatType;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * A DTO for the {@link AccountSetsTemplate} entity
 */
@Getter
@Setter
public class AccountSetsTemplateDto implements Serializable {
    private Long id;
    private String title;
    private Boolean enabled;
    private List<Integer> encoding;
    private List<SubjectCategory> subjectCategory;
    private List<String> subjectCategoryDisplay;
    private AccountStandard accountStandard;
    private VatType vatType;
    private LocalDateTime lastUpdateTime;
}
