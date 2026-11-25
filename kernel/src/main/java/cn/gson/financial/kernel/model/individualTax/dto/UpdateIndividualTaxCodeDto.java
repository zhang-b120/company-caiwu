package cn.gson.financial.kernel.model.individualTax.dto;

import lombok.Data;

/**
 * 更新个税密码DTO
 */
@Data
public class UpdateIndividualTaxCodeDto {
    /**
     * 账套ID
     */
    private Long id;

    /**
     * 个税密码
     */
    private String individualTaxCode;
}

