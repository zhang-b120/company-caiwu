package cn.gson.financial.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.gson.financial.kernel.annotation.SessionAccountSet;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.model.entity.AccountSets;
import cn.gson.financial.kernel.model.entity.Currency;
import cn.gson.financial.kernel.model.hibernate.PrimaryKey;
import cn.gson.financial.kernel.service.CurrencyService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 *
 * <p><b>Copyright © 2010-2019开发公司All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.controller</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2019年07月30日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 *
 */
@RestController
@RequestMapping("/currency")
@RequiredArgsConstructor
public class CurrencyController {
    private final CurrencyService currencyService;

    @GetMapping
    public JsonResult list(@SessionAccountSet AccountSets accountSets) {
        return JsonResult.successful(currencyService.list(accountSets.getId()));
    }

    @PostMapping
    @SaCheckPermission("setting-currency-canedit")
    public JsonResult create(@RequestBody Currency currency, @SessionAccountSet AccountSets accountSets) {
        currency.setAccountSetsId(accountSets.getId());
        if (currency.getPrimaryKey() == null) {
            currency.setLocalCurrency(false);
        }
        currencyService.save(currency);
        return JsonResult.successful();
    }

    @PutMapping
    @SaCheckPermission("setting-currency-canedit")
    public JsonResult update(@RequestBody Currency currency, @SessionAccountSet AccountSets accountSets) {
        currency.setAccountSetsId(accountSets.getId());
        currencyService.save(currency);
        return JsonResult.successful();
    }

    @DeleteMapping("{currencyId:\\d+}")
    @SaCheckPermission("setting-currency-candelete")
    public JsonResult delete(@PathVariable Long currencyId, @SessionAccountSet AccountSets accountSets) {
        currencyService.delete(new PrimaryKey(currencyId, accountSets.getId()));
        return JsonResult.successful();
    }
}
