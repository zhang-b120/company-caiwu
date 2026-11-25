package cn.gson.financial.kernel.model.ua.request;

import cn.gson.financial.kernel.controller.Page;
import cn.gson.financial.kernel.service.CashierJournalService;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * @Author zhb
 * @Date 2025-05-19
 * @Description
 */
@EqualsAndHashCode(callSuper = true)
@Data
public class GetCashierJournalListReq extends GetCommonReq{

    private Page page;

    private CashierJournalService.CashierJournalQuery query;

}
