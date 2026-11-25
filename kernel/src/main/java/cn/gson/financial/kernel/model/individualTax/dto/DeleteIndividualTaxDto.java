package cn.gson.financial.kernel.model.individualTax.dto;

import lombok.Data;

import java.util.List;

@Data
public class DeleteIndividualTaxDto {
    /**
     * 账期
     */
    private String period;

    /**
     * 需要删除的账套ids
     */
    private List<Long> accountSetsIdList;
}

