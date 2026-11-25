package cn.gson.financial.kernel.model.dto;

import lombok.Data;

@Data
public class UpdateIndividualTaxDto {
    /**
     * 是否能报个税
     */
    private String individualTax;
    /**
     * 账套id
     */
    private Long id;
    /**
     * 账期（格式：YYYY-MM-DD），用于删除指定账期的个税任务记录
     */
    private String period;
}
