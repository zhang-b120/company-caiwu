package cn.gson.financial.controller;

import cn.gson.financial.kernel.annotation.SessionAccountSet;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.controller.Page;
import cn.gson.financial.kernel.model.entity.AccountSets;
import cn.gson.financial.kernel.model.entity.CashFlow;
import cn.gson.financial.kernel.model.enums.AccountingCategoryType;
import cn.gson.financial.kernel.model.hibernate.PrimaryKey;
import cn.gson.financial.kernel.service.CashFlowService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;


/**
 *
 * <p><b>Copyright © 2010-2021开发公司All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : (CashFlow)</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2023-04-07 20:10:29</li>
 * <li>@author     : 开发者</li>
 * </ul>
 *
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/cashFlow")
public class CashFlowController {

    private final CashFlowService cashFlowService;

    @GetMapping
    public JsonResult list(Page page, String filter, @SessionAccountSet AccountSets accountSets) {
        return JsonResult.successful(cashFlowService.loadPageData(accountSets.getId(), page, filter));
    }

    @PostMapping
    public JsonResult create(@RequestBody CashFlow cashFlow, @SessionAccountSet AccountSets accountSets) {
        cashFlow.setAccountSetsId(accountSets.getId());
        cashFlow.setCategoryId((long) AccountingCategoryType.CASH_FLOW.ordinal());
        cashFlow.setCategoryType(AccountingCategoryType.CASH_FLOW);
        return JsonResult.successful(cashFlowService.save(cashFlow));
    }

    @PutMapping
    public JsonResult update(@RequestBody CashFlow cashFlow, @SessionAccountSet AccountSets accountSets) {
        cashFlow.setAccountSetsId(accountSets.getId());
        cashFlow.setCategoryId((long) AccountingCategoryType.CASH_FLOW.ordinal());
        cashFlow.setCategoryType(AccountingCategoryType.CASH_FLOW);
        return JsonResult.successful(cashFlowService.save(cashFlow));
    }

    @DeleteMapping("{cashFlowId:\\d+}")
    public JsonResult delete(@PathVariable Long cashFlowId, @SessionAccountSet AccountSets accountSets) {
        cashFlowService.delete(new PrimaryKey(cashFlowId, accountSets.getId()));
        return JsonResult.successful();
    }
}


