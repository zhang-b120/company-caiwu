package cn.gson.financial.controller;

import cn.gson.financial.kernel.annotation.SessionAccountSet;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.model.entity.AccountSets;
import cn.gson.financial.kernel.model.entity.CashierAccount;
import cn.gson.financial.kernel.service.CashierAccountService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

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
@RequestMapping("/cashier/account")
@RequiredArgsConstructor
public class CashierAccountController {

    private final CashierAccountService cashierAccountService;

    @GetMapping
    public JsonResult list(@SessionAccountSet AccountSets accountSets, String filter) {
        return JsonResult.successful(cashierAccountService.list(accountSets.getId(), filter));
    }

    @PostMapping
    public JsonResult create(@RequestBody CashierAccount cashierAccount, @SessionAccountSet AccountSets accountSets) {
        cashierAccount.setAccountSetsId(accountSets.getId());
        cashierAccountService.save(cashierAccount);
        return JsonResult.successful();
    }

    @DeleteMapping("{cashierAccountId:\\d+}")
    public JsonResult delete(@PathVariable Long cashierAccountId, @SessionAccountSet AccountSets accountSets) {
        cashierAccountService.delete(accountSets.getId(), cashierAccountId);
        return JsonResult.successful();
    }
}
