package cn.gson.financial.controller;

import cn.gson.financial.kernel.annotation.SessionAccountSet;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.controller.Page;
import cn.gson.financial.kernel.model.entity.AccountSets;
import cn.gson.financial.kernel.model.entity.Department;
import cn.gson.financial.kernel.model.enums.AccountingCategoryType;
import cn.gson.financial.kernel.model.hibernate.PrimaryKey;
import cn.gson.financial.kernel.service.DepartmentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;


/**
 *
 * <p><b>Copyright © 2010-2021开发公司All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : (Department)</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2023-04-07 20:10:08</li>
 * <li>@author     : 开发者</li>
 * </ul>
 *
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/department")
public class DepartmentController {

    private final DepartmentService departmentService;

    @GetMapping
    public JsonResult list(Page page, String filter, @SessionAccountSet AccountSets accountSets) {
        return JsonResult.successful(departmentService.loadPageData(accountSets.getId(), page, filter));
    }

    @GetMapping("all")
    public JsonResult listAll(@SessionAccountSet AccountSets accountSets) {
        return JsonResult.successful(departmentService.loadData(accountSets.getId()));
    }

    @PostMapping
    public JsonResult create(@RequestBody Department department, @SessionAccountSet AccountSets accountSets) {
        department.setAccountSetsId(accountSets.getId());
        department.setCategoryId((long) AccountingCategoryType.DEPARTMENT.ordinal());
        department.setCategoryType(AccountingCategoryType.DEPARTMENT);
        return JsonResult.successful(departmentService.save(department));
    }

    @PutMapping
    public JsonResult update(@RequestBody Department department, @SessionAccountSet AccountSets accountSets) {
        department.setAccountSetsId(accountSets.getId());
        department.setCategoryId((long) AccountingCategoryType.DEPARTMENT.ordinal());
        department.setCategoryType(AccountingCategoryType.DEPARTMENT);
        return JsonResult.successful(departmentService.save(department));
    }

    @DeleteMapping("{departmentId:\\d+}")
    public JsonResult delete(@PathVariable Long departmentId, @SessionAccountSet AccountSets accountSets) {
        departmentService.delete(new PrimaryKey(departmentId, accountSets.getId()));
        return JsonResult.successful();
    }
}


