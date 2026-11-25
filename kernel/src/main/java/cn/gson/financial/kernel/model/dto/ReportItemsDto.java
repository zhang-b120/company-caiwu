package cn.gson.financial.kernel.model.dto;

import cn.gson.financial.kernel.model.entity.ReportItemsFormula;
import cn.gson.financial.kernel.model.enums.ReportItemType;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.*;

/**
 * A DTO for the {@link cn.gson.financial.kernel.model.entity.ReportItems} entity
 */
@Data
public class ReportItemsDto implements Serializable {
    private Long id;
    private Long accountSetsId;
    private Long templateId;
    private String title;
    private Long parentId;
    private Boolean isBolder;
    private Boolean isFolding;
    private Boolean isClassified;
    private Integer pos;
    private List<ReportItemsFormula> formulas;
    private Map<String, BigDecimal> values = new HashMap<>();
    private Map<String, BigDecimal> colInitAmount;
    private Set<ReportItemType> itemType;
    private Integer level;
    private String rowNum;
    private Boolean editFormulas;
    private Map<String, List<List<Object>>> formulaValue = new HashMap<>();

    public Set<ReportItemType> getItemType() {
        if (itemType == null) {
            Set<ReportItemType> types = new HashSet<>();
            types.add(ReportItemType.常规);
            types.add(ReportItemType.重分类);
            return types;
        }
        return itemType;
    }
}
