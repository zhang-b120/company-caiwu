package cn.gson.financial.kernel.model.individualTax.dto;

import lombok.Data;

import java.util.List;

/**
 * 审核个税任务DTO
 *
 * @author 开发者
 * @date 2025-01-XX
 */
@Data
public class AuditIndividualTaxDto {

    /**
     * 账期（格式：YYYY-MM-DD）
     */
    private String period;

    /**
     * 账套ID列表
     */
    private List<Long> accountSetsIdList;
}

