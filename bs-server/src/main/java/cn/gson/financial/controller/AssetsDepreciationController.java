package cn.gson.financial.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.gson.financial.kernel.annotation.SessionAccountSet;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.controller.Page;
import cn.gson.financial.kernel.model.entity.AccountSets;
import cn.gson.financial.kernel.model.entity.AssetsDepreciation;
import cn.gson.financial.kernel.model.hibernate.PrimaryKey;
import cn.gson.financial.kernel.service.AssetsDepreciationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;


/**
 *
 * <p><b>Copyright © 2010-2021开发公司All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : (AssetsDepreciation)</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2022-11-25 12:13:01</li>
 * <li>@author     : 开发者</li>
 * </ul>
 *
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/assets/depreciation")
public class AssetsDepreciationController {

    private final AssetsDepreciationService assetsDepreciationService;

    @GetMapping
    public JsonResult list(@SessionAccountSet AccountSets accountSets, Page page) {
        return JsonResult.successful(assetsDepreciationService.list(accountSets.getId(), page));
    }

    @PostMapping("detail")
    public JsonResult detail(@SessionAccountSet AccountSets accountSets,
                             @RequestBody AssetsDepreciationService.Query query) {
        query.setAccountSetsId(accountSets.getId());
        return JsonResult.successful(assetsDepreciationService.detail(query));
    }

    @GetMapping("{depreciationId:\\d+}")
    public JsonResult depreciation(@SessionAccountSet AccountSets accountSets, @PathVariable Long depreciationId) {
        assetsDepreciationService.depreciation(new PrimaryKey(depreciationId, accountSets.getId()));
        return JsonResult.successful();
    }

    @GetMapping("cancel/{depreciationId:\\d+}")
    @SaCheckPermission("fixedassets-fixedassets-card-candepreciation")
    public JsonResult cancelDepreciation(@SessionAccountSet AccountSets accountSets, @PathVariable Long depreciationId) {
        assetsDepreciationService.cancelDepreciation(new PrimaryKey(depreciationId, accountSets.getId()));
        return JsonResult.successful();
    }

    @PostMapping
    @SaCheckPermission("fixedassets-fixedassets-card-candepreciation")
    public JsonResult create(@RequestBody AssetsDepreciation assetsDepreciation) {
        return JsonResult.successful(assetsDepreciationService.save(assetsDepreciation));
    }

    @PutMapping
    @SaCheckPermission("fixedassets-fixedassets-card-candepreciation")
    public JsonResult update(@RequestBody AssetsDepreciation assetsDepreciation) {
        return JsonResult.successful(assetsDepreciationService.save(assetsDepreciation));
    }

    @GetMapping("generate/voucher/{assetsDepreciationId:\\d+}")
    @SaCheckPermission("fixedassets-fixedassets-card-cancreatevoucher")
    public JsonResult generateVoucher(@SessionAccountSet AccountSets accountSets, @PathVariable Long assetsDepreciationId) {
        return JsonResult.successful(assetsDepreciationService.generateVoucher(new PrimaryKey(assetsDepreciationId, accountSets.getId())));
    }

    @GetMapping("relate/{assetsDepreciationId:\\d+}")
    public JsonResult relateVoucher(@SessionAccountSet AccountSets accountSets, Long voucherId, @PathVariable Long assetsDepreciationId) {
        assetsDepreciationService.relateVoucher(assetsDepreciationId, voucherId, accountSets.getId());
        return JsonResult.successful();
    }

    @PostMapping("auto-depreciation")
    @SaCheckPermission("fixedassets-fixedassets-card-candepreciation")
    public JsonResult autoDepreciationToCurrentDate(@SessionAccountSet AccountSets accountSets) {
        assetsDepreciationService.autoDepreciationToCurrentDate(accountSets.getId());
        return JsonResult.successful();
    }
}

