package cn.gson.financial.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.gson.financial.kernel.annotation.SessionAccountSet;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.model.entity.AccountSets;
import cn.gson.financial.kernel.model.entity.VoucherWord;
import cn.gson.financial.kernel.service.VoucherWordService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;


/**
 *
 * <p><b>Copyright © 2010-2021开发公司All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : 凭证字(VoucherWord)</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2022-10-19 14:49:38</li>
 * <li>@author     : 开发者</li>
 * </ul>
 *
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/voucher-word")
public class VoucherWordController {

    private final VoucherWordService voucherWordService;

    @GetMapping
    public JsonResult list(@SessionAccountSet AccountSets accountSets) {
        return JsonResult.successful(voucherWordService.list(accountSets.getId()));
    }

    @PostMapping
    @SaCheckPermission("setting-vouchergroup-canedit")
    public JsonResult create(@RequestBody VoucherWord voucherWord, @SessionAccountSet AccountSets accountSets) {
        voucherWord.setAccountSetsId(accountSets.getId());
        voucherWordService.save(voucherWord);
        return JsonResult.successful();
    }

    @PutMapping
    @SaCheckPermission("setting-vouchergroup-canedit")
    public JsonResult update(@RequestBody VoucherWord voucherWord, @SessionAccountSet AccountSets accountSets) {
        voucherWord.setAccountSetsId(accountSets.getId());
        voucherWordService.save(voucherWord);
        return JsonResult.successful();
    }

    @DeleteMapping("{voucherWordId:\\d+}")
    @SaCheckPermission("setting-vouchergroup-candelete")
    public JsonResult delete(@PathVariable Long voucherWordId, @SessionAccountSet AccountSets accountSets) {
        voucherWordService.remove(voucherWordId, accountSets.getId());
        return JsonResult.successful();
    }
}

