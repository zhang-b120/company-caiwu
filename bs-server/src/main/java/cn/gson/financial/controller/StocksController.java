package cn.gson.financial.controller;

import cn.gson.financial.kernel.annotation.SessionAccountSet;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.controller.Page;
import cn.gson.financial.kernel.model.entity.AccountSets;
import cn.gson.financial.kernel.model.entity.Stocks;
import cn.gson.financial.kernel.model.enums.AccountingCategoryType;
import cn.gson.financial.kernel.model.hibernate.PrimaryKey;
import cn.gson.financial.kernel.service.StocksService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;


/**
 *
 * <p><b>Copyright © 2010-2021开发公司All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : (Stocks)</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2023-04-07 20:08:38</li>
 * <li>@author     : 开发者</li>
 * </ul>
 *
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/stocks")
public class StocksController {

    private final StocksService stocksService;

    @GetMapping
    public JsonResult list(Page page, String filter, @SessionAccountSet AccountSets accountSets) {
        return JsonResult.successful(stocksService.loadPageData(accountSets.getId(), page, filter));
    }

    @PostMapping
    public JsonResult create(@RequestBody Stocks stocks, @SessionAccountSet AccountSets accountSets) {
        stocks.setAccountSetsId(accountSets.getId());
        stocks.setCategoryId((long) AccountingCategoryType.STOCKS.ordinal());
        stocks.setCategoryType(AccountingCategoryType.STOCKS);
        return JsonResult.successful(stocksService.save(stocks));
    }

    @PutMapping
    public JsonResult update(@RequestBody Stocks stocks, @SessionAccountSet AccountSets accountSets) {
        stocks.setAccountSetsId(accountSets.getId());
        stocks.setCategoryId((long) AccountingCategoryType.STOCKS.ordinal());
        stocks.setCategoryType(AccountingCategoryType.STOCKS);
        return JsonResult.successful(stocksService.save(stocks));
    }

    @DeleteMapping("{stocksId:\\d+}")
    public JsonResult delete(@PathVariable Long stocksId, @SessionAccountSet AccountSets accountSets) {
        stocksService.delete(new PrimaryKey(stocksId, accountSets.getId()));
        return JsonResult.successful();
    }
}

