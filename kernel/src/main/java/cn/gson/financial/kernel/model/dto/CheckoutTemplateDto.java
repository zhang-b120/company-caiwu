package cn.gson.financial.kernel.model.dto;

import cn.gson.financial.kernel.model.entity.CheckoutTemplate;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;

/**
 * A DTO for the {@link CheckoutTemplate} entity
 */
@Data
public class CheckoutTemplateDto implements Serializable {
    private Long id;
    private Long accountSetsId;
    private String title;
    private Long voucherWordId;
    private Long voucherId;
    private BigDecimal amount;
    private Boolean isDefault;
    private List<CheckoutTemplate.CtItem> items;
}
