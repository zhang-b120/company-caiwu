package cn.gson.financial.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.gson.financial.kernel.annotation.SessionAccountSet;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.controller.Page;
import cn.gson.financial.kernel.model.entity.AcDetails;
import cn.gson.financial.kernel.model.entity.AccountSets;
import cn.gson.financial.kernel.model.entity.AccountingCategory;
import cn.gson.financial.kernel.model.entity.AccountingCategoryDetails;
import cn.gson.financial.kernel.model.enums.AccountingCategoryType;
import cn.gson.financial.kernel.model.hibernate.PrimaryKey;
import cn.gson.financial.kernel.service.AccountingCategoryDetailsService;
import cn.gson.financial.kernel.service.AuxiliaryDataService;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.extra.spring.SpringUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/accounting-category-details")
@SaCheckLogin
public class AccountingCategoryDetailsController {

    private final AccountingCategoryDetailsService categoryDetailsService;

    @GetMapping
    public JsonResult list(Long categoryId, Page page, String keyword, @SessionAccountSet AccountSets accountSets) {
        return JsonResult.successful(categoryDetailsService.page(page, new PrimaryKey(categoryId, accountSets.getId()), keyword));
    }

    @PostMapping
    @SaCheckPermission("setting-assistingaccount-canedit")
    public JsonResult save(@RequestBody AccountingCategoryDetails acDetails, @SessionAccountSet AccountSets accountSets) {
        acDetails.setAccountSetsId(accountSets.getId());
        return JsonResult.successful(categoryDetailsService.save(acDetails));
    }

    @PostMapping("ad")
    @SaCheckPermission("setting-assistingaccount-canedit")
    public JsonResult saveAcDetails(@RequestBody AcDetails acDetails, @SessionAccountSet AccountSets accountSets) {
        acDetails.setAccountSetsId(accountSets.getId());
        AuxiliaryDataService dataService = SpringUtil.getBean(acDetails.getCategoryType().dataService);
        dataService.save(BeanUtil.toBean(acDetails, acDetails.getCategoryType().entity));
        return JsonResult.successful();
    }

    @PutMapping
    @SaCheckPermission("setting-assistingaccount-canedit")
    public JsonResult update(@RequestBody AccountingCategoryDetails accountingCategoryDetails, @SessionAccountSet AccountSets accountSets) {
        accountingCategoryDetails.setAccountSetsId(accountSets.getId());
        categoryDetailsService.save(accountingCategoryDetails);
        return JsonResult.successful();
    }

    @DeleteMapping("{id:\\d+}")
    @SaCheckPermission("setting-assistingaccount-candelete")
    public JsonResult delete(@PathVariable Long id, @SessionAccountSet AccountSets accountSets) {
        categoryDetailsService.delete(new PrimaryKey(id, accountSets.getId()));
        return JsonResult.successful();
    }

    /**
     * 清空元素
     *
     * @param categoryId
     * @return
     */
    @DeleteMapping("/clear/{categoryId:\\d+}")
    @SaCheckPermission("setting-assistingaccount-candelete\n")
    public JsonResult clear(@PathVariable Long categoryId, AccountingCategoryType categoryType, @SessionAccountSet AccountSets accountSets) {
        try {
            AuxiliaryDataService dataService = SpringUtil.getBean(categoryType.dataService);
            dataService.clear(categoryId, accountSets.getId());
            return JsonResult.successful();
        } catch (Exception e) {
            log.error("清空失败！", e);
            return JsonResult.failure(e.getMessage());
        }
    }

    /**
     * 加载核算项目
     *
     * @param categories
     * @return
     */
    @PostMapping("loadAuxiliaryAccountingData")
    public JsonResult loadAuxiliaryAccountingData(@RequestBody List<AccountingCategory> categories, @SessionAccountSet AccountSets accountSets) {
        Long accountSetsId = accountSets.getId();
        Map<Long, List<? extends AcDetails>> listMap = new HashMap<>(categories.size());
        categories.forEach(category -> {
            listMap.put(category.getId(), SpringUtil.getBean(category.getCategoryType().dataService).loadData(accountSetsId, category.getId()));
        });
        return JsonResult.successful(listMap);
    }

    /**
     * 加载核算项目
     *
     * @return
     */
    @PostMapping("loadAuxiliaryAccountingDataByName")
    public JsonResult loadAuxiliaryAccountingDataByName(@RequestBody List<AccountingCategoryType> nameList, @SessionAccountSet AccountSets accountSets) {
        Long accountSetsId = accountSets.getId();
        Map<String, List<?>> rows = new HashMap<>();
        nameList.forEach(categoryType -> {
            AuxiliaryDataService dataService = SpringUtil.getBean(categoryType.dataService);
            rows.put(categoryType.name(), dataService.loadData(accountSetsId));
        });
        return JsonResult.successful(rows);
    }

    /**
     * 加载核算项目
     *
     * @return
     */
    @GetMapping("loadAuxiliaryAccountingDataByName")
    public JsonResult loadAuxiliaryAccountingDataByName(String name, @SessionAccountSet AccountSets accountSets) {
        return JsonResult.successful(categoryDetailsService.loadAuxiliaryAccountingDataByName(accountSets.getId(), name));
    }
}
