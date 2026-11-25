package cn.gson.financial.controller;

import cn.gson.financial.kernel.annotation.SessionAccountSet;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.model.entity.AccountSets;
import cn.gson.financial.kernel.model.entity.CashierAccount;
import cn.gson.financial.kernel.model.entity.CashierIoType;
import cn.gson.financial.kernel.model.entity.CashierIoTypeVoucherTemplate;
import cn.gson.financial.kernel.service.CashierIoTypeService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 *
 * <p><b>Copyright © 2010-2022开发公司All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.controller</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2022年10月26日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 *
 */
@RestController
@RequestMapping("/cashier/io")
@RequiredArgsConstructor
public class CashierIoTypeController {

    private final CashierIoTypeService cashierIoTypeService;

    @GetMapping
    public JsonResult list(@SessionAccountSet AccountSets accountSets, String filter) {
        return JsonResult.successful(cashierIoTypeService.list(accountSets.getId(), filter));
    }

    @PostMapping
    public JsonResult create(@RequestBody CashierIoType cashierIoType, @SessionAccountSet AccountSets accountSets) {
        cashierIoType.setAccountSetsId(accountSets.getId());
        cashierIoTypeService.save(cashierIoType);
        return JsonResult.successful();
    }

    @DeleteMapping("{cashierIoTypeId:\\d+}")
    public JsonResult delete(@PathVariable Long cashierIoTypeId, @SessionAccountSet AccountSets accountSets) {
        cashierIoTypeService.delete(accountSets.getId(), cashierIoTypeId);
        return JsonResult.successful();
    }

    @GetMapping("template")
    public JsonResult templateList(@SessionAccountSet AccountSets accountSets, CashierIoType.Type type, CashierAccount.CashierAccountType accountType) {
        return JsonResult.successful(cashierIoTypeService.templateList(accountSets.getId(), type, accountType));
    }

    @PostMapping("template")
    public JsonResult createTemplate(@RequestBody List<CashierIoTypeVoucherTemplate> cashierIoTypeVoucherTemplateList, @SessionAccountSet AccountSets accountSets) {
        cashierIoTypeVoucherTemplateList.forEach(item -> {
            item.setAccountSetsId(accountSets.getId());
            cashierIoTypeService.saveTemplate(item);
        });
        return JsonResult.successful();
    }

    @DeleteMapping("template")
    public JsonResult deleteTemplate(Long[] templateId, @SessionAccountSet AccountSets accountSets) {
        cashierIoTypeService.deleteTemplate(accountSets.getId(), templateId);
        return JsonResult.successful();
    }
}
