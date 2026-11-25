package cn.gson.financial.kernel.model.dto;

import cn.gson.financial.kernel.model.entity.AccountSets;
import cn.gson.financial.kernel.model.entity.Invoice;
import lombok.Data;

import java.util.List;
@Data
public class InvoiceTaskDto {
    List<Invoice> invoiceList;
    List<AllTaskDto>  taskDtoList;
    AccountSets accountSets;
}
