package cn.gson.financial.kernel.model.dto;

import cn.hutool.core.util.StrUtil;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * A DTO for the {@link cn.gson.financial.kernel.model.entity.InvoiceItems} entity
 */
@Getter
@Setter
public class InvoiceItemsDto implements Serializable {
    private Integer id;
    private Long invoiceId;
    private Long accountSetsId;
    private String goodsName;
    private String specification;
    private String unit;
    private Double quantity;
    private BigDecimal amount;
    private Integer taxRate;
    private BigDecimal tax;
    private String taxedItems;
    private String taxCalculationMethod;

    public String getGoodsName() {
        return StrUtil.emptyToDefault(goodsName, "");
    }
}
