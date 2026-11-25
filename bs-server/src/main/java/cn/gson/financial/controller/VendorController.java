package cn.gson.financial.controller;

import cn.gson.financial.kernel.annotation.SessionAccountSet;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.controller.Page;
import cn.gson.financial.kernel.model.entity.AccountSets;
import cn.gson.financial.kernel.model.entity.Vendor;
import cn.gson.financial.kernel.model.enums.AccountingCategoryType;
import cn.gson.financial.kernel.model.hibernate.PrimaryKey;
import cn.gson.financial.kernel.service.VendorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;


/**
 *
 * <p><b>Copyright © 2010-2021开发公司All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : (Vendor)</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2023-04-07 20:08:38</li>
 * <li>@author     : 开发者</li>
 * </ul>
 *
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/vendor")
public class VendorController {

    private final VendorService vendorService;

    @GetMapping
    public JsonResult list(Page page, String filter, @SessionAccountSet AccountSets accountSets) {
        return JsonResult.successful(vendorService.loadPageData(accountSets.getId(), page, filter));
    }

    @PostMapping
    public JsonResult create(@RequestBody Vendor vendor, @SessionAccountSet AccountSets accountSets) {
        vendor.setAccountSetsId(accountSets.getId());
        vendor.setCategoryId((long) AccountingCategoryType.VENDOR.ordinal());
        vendor.setCategoryType(AccountingCategoryType.VENDOR);
        return JsonResult.successful(vendorService.save(vendor));
    }

    @PutMapping
    public JsonResult update(@RequestBody Vendor vendor, @SessionAccountSet AccountSets accountSets) {
        vendor.setAccountSetsId(accountSets.getId());
        vendor.setCategoryId((long) AccountingCategoryType.VENDOR.ordinal());
        vendor.setCategoryType(AccountingCategoryType.VENDOR);
        return JsonResult.successful(vendorService.save(vendor));
    }

    @DeleteMapping("{vendorId:\\d+}")
    public JsonResult delete(@PathVariable Long vendorId, @SessionAccountSet AccountSets accountSets) {
        vendorService.delete(new PrimaryKey(vendorId, accountSets.getId()));
        return JsonResult.successful();
    }
}

