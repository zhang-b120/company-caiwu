package cn.gson.financial.kernel.model.dto;

import cn.gson.financial.kernel.model.entity.AcDetails;
import cn.gson.financial.kernel.model.entity.AccountingCategory;
import cn.gson.financial.kernel.model.enums.AccountingCategoryType;
import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * A DTO for the {@link cn.gson.financial.kernel.model.entity.AccountingCategory} entity
 */
@Data
public class AccountingCategoryDto implements Serializable {
    private Long id;
    private Long accountSetsId;
    private AccountingCategoryType categoryType;
    private String name;
    private List<AccountingCategory.AssistingAccountingColumn> columns = new ArrayList<>(0);
    private List<? extends AcDetails> details = new ArrayList<>(0);
    private Boolean systemDefault;
    private Boolean canEdit;
}
