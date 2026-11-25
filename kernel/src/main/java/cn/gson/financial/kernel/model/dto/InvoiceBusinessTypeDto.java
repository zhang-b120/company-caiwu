package cn.gson.financial.kernel.model.dto;

import cn.gson.financial.kernel.model.entity.InvoiceBusinessType;
import cn.gson.financial.kernel.model.enums.InvoiceType;
import com.alibaba.fastjson.JSONArray;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * DTO for {@link cn.gson.financial.kernel.model.entity.InvoiceBusinessType}
 */
@Data
public class InvoiceBusinessTypeDto implements Serializable {
    private Long id;
    private Long accountSetsId;
    private InvoiceType invoiceType;
    private String title;
    private Long voucherWord;
    private String word;
    private List<InvoiceBusinessType.InvoiceVoucherTemplateItems> items;
    private JSONArray matchWords;
}
