package cn.gson.financial.controller;

import cn.gson.financial.kernel.annotation.SessionAccountSet;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.model.entity.AccountSets;
import cn.gson.financial.kernel.model.entity.SalaryField;
import cn.gson.financial.kernel.model.hibernate.PrimaryKey;
import cn.gson.financial.kernel.service.SalaryFiledService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 *
 * <p><b>Copyright © 2010-2023 开发公司 All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : 薪资自定义字段</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2023年12月28日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 *
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/salary/field")
public class SalaryFiledController {

    private final SalaryFiledService salaryFiledService;

    /**
     * 自定义字段列表
     *
     * @param accountSetsId
     * @return
     */
    @GetMapping
    public JsonResult list(@SessionAccountSet AccountSets accountSets, SalaryFiledService.Query query) {
        return JsonResult.successful(salaryFiledService.list(accountSets.getId(), query));
    }

    /**
     * 自定义字段列表
     *
     * @param accountSetsId
     * @return
     */
    @PostMapping
    public JsonResult save(@SessionAccountSet AccountSets accountSets, @RequestBody List<SalaryField> salaryFields) {
        salaryFiledService.save(accountSets.getId(), salaryFields);
        return JsonResult.successful();
    }

    /**
     * 自定义字段列表
     *
     * @param accountSetsId
     * @return
     */
    @DeleteMapping("{filedId}")
    public JsonResult delete(@SessionAccountSet AccountSets accountSets, @PathVariable Long filedId) {
        salaryFiledService.delete(new PrimaryKey(filedId, accountSets.getId()));
        return JsonResult.successful();
    }
}
