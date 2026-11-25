package cn.gson.financial.kernel.model.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 首页取票任务详情 DTO
 */
@Data
public class InvoiceStatusDetailDto {

    private Long accountSetsId;
    private LocalDate period;
    private Integer status;
    private Integer result;
    private String resultMessage;

    private List<Item> items;

    @Data
    public static class Item {
        private Long id;
        private String invoiceType;
        private String screenshotUrl;
        private BigDecimal outputTaxTotal;
        private BigDecimal inputTaxTotal;
        private BigDecimal diffTaxTotal;
        private Integer status;
        private Integer result;
        private String resultMessage;
    }
}

