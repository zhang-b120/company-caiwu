package cn.gson.financial.kernel.model.individualTax.dto;

import lombok.Data;

import java.util.List;

@Data
public class AddIndividualTaxDto {
    /**
     * 账期
     */
    private String period;

    /**
     * 需要修改的账套ids
     */
    private List<Long> accountSetsIdList;
}
