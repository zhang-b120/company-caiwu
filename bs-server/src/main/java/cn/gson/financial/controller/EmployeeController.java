package cn.gson.financial.controller;

import cn.gson.financial.kernel.annotation.SessionAccountSet;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.controller.Page;
import cn.gson.financial.kernel.model.entity.AccountSets;
import cn.gson.financial.kernel.model.entity.Employee;
import cn.gson.financial.kernel.model.enums.AccountingCategoryType;
import cn.gson.financial.kernel.model.hibernate.PrimaryKey;
import cn.gson.financial.kernel.service.EmployeeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;


/**
 *
 * <p><b>Copyright © 2010-2021开发公司All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : (Employee)</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2023-04-07 20:08:38</li>
 * <li>@author     : 开发者</li>
 * </ul>
 *
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/employee")
public class EmployeeController {

    private final EmployeeService employeeService;

    @GetMapping
    public JsonResult list(Page page, String filter, @SessionAccountSet AccountSets accountSets) {
        return JsonResult.successful(employeeService.list(page, filter, accountSets.getId()));
    }

    @PostMapping
    public JsonResult create(@RequestBody Employee employee, @SessionAccountSet AccountSets accountSets) {
        employee.setAccountSetsId(accountSets.getId());
        employee.setCategoryId((long) AccountingCategoryType.EMPLOYEE.ordinal());
        employee.setCategoryType(AccountingCategoryType.EMPLOYEE);
        return JsonResult.successful(employeeService.save(employee));
    }

    @PutMapping
    public JsonResult update(@RequestBody Employee employee, @SessionAccountSet AccountSets accountSets) {
        employee.setAccountSetsId(accountSets.getId());
        employee.setCategoryId((long) AccountingCategoryType.EMPLOYEE.ordinal());
        employee.setCategoryType(AccountingCategoryType.EMPLOYEE);
        return JsonResult.successful(employeeService.save(employee));
    }

    @DeleteMapping("{employeeId:\\d+}")
    public JsonResult delete(@PathVariable Long employeeId, @SessionAccountSet AccountSets accountSets) {
        employeeService.delete(new PrimaryKey(employeeId, accountSets.getId()));
        return JsonResult.successful();
    }
}


