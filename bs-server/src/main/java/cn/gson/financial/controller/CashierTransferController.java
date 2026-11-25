package cn.gson.financial.controller;

import cn.gson.financial.kernel.annotation.SessionAccountSet;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.controller.Page;
import cn.gson.financial.kernel.model.entity.AccountSets;
import cn.gson.financial.kernel.model.entity.CashierTransfer;
import cn.gson.financial.kernel.model.hibernate.PrimaryKey;
import cn.gson.financial.kernel.service.CashierTransferService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;


/**
 *
 * <p><b>Copyright © 2010-2021开发公司All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : (CashierTransfer)</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2022-11-09 20:29:56</li>
 * <li>@author     : 开发者</li>
 * </ul>
 *
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/cashier/transfer")
public class CashierTransferController {

    private final CashierTransferService cashierTransferService;

    @GetMapping
    public JsonResult list(Page page, @SessionAccountSet AccountSets accountSets, CashierTransferService.TransferQuery query) {
        return JsonResult.successful(cashierTransferService.list(accountSets.getId(), page, query));
    }

    @PostMapping
    public JsonResult create(@RequestBody CashierTransfer cashierTransfer,
                             @SessionAccountSet AccountSets accountSets) {
        cashierTransfer.setAccountSetsId(accountSets.getId());
        return JsonResult.successful(cashierTransferService.save(cashierTransfer, accountSets.getEnableDate()));
    }

    @PutMapping
    public JsonResult update(@RequestBody CashierTransfer cashierTransfer,
                             @SessionAccountSet AccountSets accountSets) {
        cashierTransfer.setAccountSetsId(accountSets.getId());
        return JsonResult.successful(cashierTransferService.save(cashierTransfer, accountSets.getEnableDate()));
    }

    @DeleteMapping("{cashierTransferId:\\d+}")
    public JsonResult delete(@PathVariable Long cashierTransferId, @SessionAccountSet AccountSets accountSets) {
        cashierTransferService.delete(new PrimaryKey(cashierTransferId, accountSets.getId()));
        return JsonResult.successful();
    }

    @DeleteMapping("batch")
    public JsonResult batchDelete(@RequestParam("id") Long[] ids, @SessionAccountSet AccountSets accountSets) {
        for (Long id : ids) {
            cashierTransferService.delete(new PrimaryKey(id, accountSets.getId()));
        }
        return JsonResult.successful();
    }
}


