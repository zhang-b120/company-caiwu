package cn.gson.financial.kernel.model.vo;

import cn.gson.financial.kernel.model.dto.InvoiceDto;
import cn.gson.financial.kernel.model.enums.InvoiceType;
import lombok.Data;

import java.util.List;

@Data
public class InvoiceBatchUpdateVo{

    private InvoiceType invoiceType;

    private List<Long> invoiceIdList;
}
