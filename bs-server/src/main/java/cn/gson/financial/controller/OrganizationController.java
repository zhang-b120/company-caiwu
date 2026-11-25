package cn.gson.financial.controller;

import cn.gson.financial.kernel.annotation.SessionAccountSet;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.controller.Page;
import cn.gson.financial.kernel.model.entity.AccountSets;
import cn.gson.financial.kernel.model.entity.Organization;
import cn.gson.financial.kernel.model.enums.AccountingCategoryType;
import cn.gson.financial.kernel.model.hibernate.PrimaryKey;
import cn.gson.financial.kernel.service.OrganizationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;


/**
 *
 * <p><b>Copyright © 2010-2021开发公司All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : (Organization)</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2023-04-07 20:08:38</li>
 * <li>@author     : 开发者</li>
 * </ul>
 *
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/organization")
public class OrganizationController {

    private final OrganizationService organizationService;

    @GetMapping
    public JsonResult list(Page page, String filter, @SessionAccountSet AccountSets accountSets) {
        return JsonResult.successful(organizationService.loadPageData(accountSets.getId(), page, filter));
    }

    @PostMapping
    public JsonResult create(@RequestBody Organization organization, @SessionAccountSet AccountSets accountSets) {
        organization.setAccountSetsId(accountSets.getId());
        organization.setCategoryId((long) AccountingCategoryType.ORGANIZATION.ordinal());
        organization.setCategoryType(AccountingCategoryType.ORGANIZATION);
        return JsonResult.successful(organizationService.save(organization));
    }

    @PutMapping
    public JsonResult update(@RequestBody Organization organization, @SessionAccountSet AccountSets accountSets) {
        organization.setAccountSetsId(accountSets.getId());
        organization.setCategoryId((long) AccountingCategoryType.ORGANIZATION.ordinal());
        organization.setCategoryType(AccountingCategoryType.ORGANIZATION);
        return JsonResult.successful(organizationService.save(organization));
    }

    @DeleteMapping("{organizationId:\\d+}")
    public JsonResult delete(@PathVariable Long organizationId, @SessionAccountSet AccountSets accountSets) {
        organizationService.delete(new PrimaryKey(organizationId, accountSets.getId()));
        return JsonResult.successful();
    }
}


