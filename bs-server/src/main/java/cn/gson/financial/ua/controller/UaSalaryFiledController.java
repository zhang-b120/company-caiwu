package cn.gson.financial.ua.controller;/* Copyright © 2020 Yuech and/or its affiliates. All rights reserved. */

import cn.dev33.satoken.annotation.SaIgnore;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.model.hibernate.PrimaryKey;
import cn.gson.financial.kernel.service.SalaryFiledService;
import cn.gson.financial.kernel.model.ua.request.DeleteSalaryFiledReq;
import cn.gson.financial.kernel.model.ua.request.SaveSalaryFiledReq;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.ObjectUtils;
import org.springframework.web.bind.annotation.*;

/**
 * @Author zhb
 * @Date 2025-05-16
 * @Description
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/ua/salary/field")
public class UaSalaryFiledController {

    private final SalaryFiledService salaryFiledService;

    /**
     * 自定义字段列表
     *
     * @param accountSetsId
     * @return
     */
    @SaIgnore
    @GetMapping("/uaGetSalaryFiledList")
    public JsonResult uaGetSalaryFiledList(Long accountSetsId, SalaryFiledService.Query query) {
        if(ObjectUtils.isEmpty(accountSetsId)){
            return JsonResult.successful();
        }
        return JsonResult.successful(salaryFiledService.list(accountSetsId, query));
    }

    /**
     * 保存工资字段
     * @param req
     * @return
     */
    @SaIgnore
    @PostMapping("/uaSaveSalaryFiled")
    public JsonResult uaSaveSalaryFiled(@RequestBody SaveSalaryFiledReq req) {
        salaryFiledService.save(req.getAccountSetsId(), req.getSalaryFields());
        return JsonResult.successful();
    }

    /**
     * 删除工资字段
     * @param req
     * @return
     */
    @SaIgnore
    @PostMapping("/uaDeleteSalaryFiled")
    public JsonResult uaDeleteSalaryFiled(@RequestBody DeleteSalaryFiledReq req) {
        salaryFiledService.delete(new PrimaryKey(req.getFiledId(), req.getAccountSetsId()));
        return JsonResult.successful();
    }
}