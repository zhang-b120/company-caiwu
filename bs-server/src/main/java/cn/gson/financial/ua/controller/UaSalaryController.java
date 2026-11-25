package cn.gson.financial.ua.controller;/* Copyright © 2020 Yuech and/or its affiliates. All rights reserved. */

import cn.dev33.satoken.annotation.SaIgnore;
import cn.gson.financial.kernel.annotation.SessionAccountSetId;
import cn.gson.financial.kernel.common.LocalDateRange;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.controller.Page;
import cn.gson.financial.kernel.controller.PageResults;
import cn.gson.financial.kernel.model.dto.SalaryDto;
import cn.gson.financial.kernel.model.dto.SalaryFieldDto;
import cn.gson.financial.kernel.model.ua.request.*;
import cn.gson.financial.kernel.service.SalaryService;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.ObjectUtils;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * @Author zhb
 * @Date 2025-05-16
 * @Description
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/ua/salary")
public class UaSalaryController {

    private final SalaryService salaryService;

    /**
     * 获取员工工资信息
     * @param accountSetsId
     * @param page
     * @param dateRange
     * @return
     */
    @SaIgnore
    @GetMapping("/uaGetSalaryList")
    public JsonResult uaGetSalaryList(Long accountSetsId, Page page, LocalDateRange dateRange) {
        if(ObjectUtils.isEmpty(accountSetsId)){
            return JsonResult.successful();
        }
        PageResults<SalaryDto> list = salaryService.list(accountSetsId, page, dateRange);
        List<SalaryFieldDto> salaryFields = new ArrayList<>();
        for (SalaryDto result : list.getResults()) {
            for (SalaryFieldDto s : result.getSalaryFields()) {
                if (!salaryFields.contains(s) && s.getEnabled()) {
                    salaryFields.add(s);
                }
            }
            //前台不单独返回字段了
            result.setSalaryFields(null);
        }
        return JsonResult.successful()
                .data("list", list)
                .data("fields", salaryFields.stream().filter(SalaryFieldDto::getEnabled).toList());
    }

    /**
     * 新增员工工资
     * @param req
     * @return
     */
    @SaIgnore
    @PostMapping("/uaSaveSalary")
    public JsonResult uaSaveSalary(@RequestBody SaveSalaryReq req) {
        return JsonResult.successful(salaryService.save(req.getAccountSetsId(), req.getPeriod(), req.getDetailVo(),Boolean.TRUE));
    }

    /**
     *
     * @param req
     * @return
     */
    @SaIgnore
    @PostMapping("/uaGetSalaryDetail")
    public JsonResult uaGetSalaryDetail(@RequestBody GetSalaryDetailReq req) {
        if(ObjectUtils.isEmpty(req.getAccountSetsId())){
            return JsonResult.successful();
        }
        return JsonResult.successful(salaryService.detail(req.getAccountSetsId(), req.getSalaryId()));
    }

    /**
     * 获取工资基本信息
     * @param req
     * @return
     */
    @SaIgnore
    @PostMapping("/uaGetSalaryInfoList")
    public JsonResult uaGetSalaryInfoList(@RequestBody GetSalaryInfoListReq req) {
        return JsonResult.successful(salaryService.uaGetSalaryInfoList(req.getAccountSetsId(), req.getPeriod()));
    }

    @SaIgnore
    @PostMapping("/review")
    public JsonResult review(@RequestBody GetSalaryReviewReq req) {
        salaryService.review(req.getAccountSetsId(), req.getState(),req.getSalaryId(),Boolean.TRUE);
        return JsonResult.successful();
    }
    @SaIgnore
    @PostMapping("/delete")
    public JsonResult delete(@RequestBody GetSalaryReviewReq req) {
        salaryService.delete(req.getAccountSetsId(), req.getSalaryId(),Boolean.TRUE);
        return JsonResult.successful();
    }
}
