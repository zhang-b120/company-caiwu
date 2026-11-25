package cn.gson.financial.controller;

import cn.gson.financial.kernel.annotation.SessionAccountSet;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.controller.Page;
import cn.gson.financial.kernel.model.entity.AccountSets;
import cn.gson.financial.kernel.model.entity.Customer;
import cn.gson.financial.kernel.model.enums.AccountingCategoryType;
import cn.gson.financial.kernel.model.hibernate.PrimaryKey;
import cn.gson.financial.kernel.service.CustomerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;


/**
 *
 * <p><b>Copyright © 2010-2021开发公司All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : (Customer)</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2023-04-07 20:08:42</li>
 * <li>@author     : 开发者</li>
 * </ul>
 *
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/customer")
public class CustomerController {

    private final CustomerService customerService;

    @GetMapping
    public JsonResult list(Page page, String filter, @SessionAccountSet AccountSets accountSets) {
        return JsonResult.successful(customerService.loadPageData(accountSets.getId(), page, filter));
    }

    @PostMapping
    public JsonResult create(@RequestBody Customer customer, @SessionAccountSet AccountSets accountSets) {
        customer.setAccountSetsId(accountSets.getId());
        customer.setCategoryId((long) AccountingCategoryType.CUSTOMER.ordinal());
        customer.setCategoryType(AccountingCategoryType.CUSTOMER);
        return JsonResult.successful(customerService.save(customer));
    }

    @PutMapping
    public JsonResult update(@RequestBody Customer customer, @SessionAccountSet AccountSets accountSets) {
        customer.setAccountSetsId(accountSets.getId());
        customer.setCategoryId((long) AccountingCategoryType.CUSTOMER.ordinal());
        customer.setCategoryType(AccountingCategoryType.CUSTOMER);
        return JsonResult.successful(customerService.save(customer));
    }

    @DeleteMapping("{customerId:\\d+}")
    public JsonResult delete(@PathVariable Long customerId, @SessionAccountSet AccountSets accountSets) {
        customerService.delete(new PrimaryKey(customerId, accountSets.getId()));
        return JsonResult.successful();
    }
}


