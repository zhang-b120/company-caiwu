package cn.gson.financial.ua.controller;/* Copyright © 2020 Yuech and/or its affiliates. All rights reserved. */

import cn.dev33.satoken.annotation.SaIgnore;
import cn.gson.financial.kernel.annotation.SessionAccountSetId;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.model.entity.SalarySocial;
import cn.gson.financial.kernel.model.ua.request.GetCommonReq;
import cn.gson.financial.kernel.model.vo.BatchSocialVo;
import cn.gson.financial.kernel.service.SalaryEmployeeService;
import cn.gson.financial.kernel.service.SalarySocialService;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.ObjectUtils;
import org.springframework.web.bind.annotation.*;

/**
 * @Author zhb
 * @Date 2025-05-15
 * @Description
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/ua/salary/social")
public class UaSalarySocialController {

    private final SalarySocialService socialService;
    private final SalaryEmployeeService employeeDetailService;

    /**
     * 查询五险一金配置
     * @param req
     * @return
     */
    @SaIgnore
    @PostMapping("/uaGetSalarySocial")
    private JsonResult uaGetSalarySocial(@RequestBody GetCommonReq req) {
        if(ObjectUtils.isEmpty(req.getAccountSetsId())){
            return JsonResult.successful();
        }
        return JsonResult.successful(socialService.list(req.getAccountSetsId()));
    }

    /**
     * 保存更新五险一金配置
     * @param social
     * @return
     */
    @SaIgnore
    @PostMapping("/uaUpdateSalarySocial")
    private JsonResult uaUpdateSalarySocial(@RequestBody SalarySocial social) {
        social.setAccountSetsId(social.getAccountSetsId());
        socialService.save(social);
        return JsonResult.successful();
    }

    /**
     * 加载员工社保信息
     *
     * @param accountSetsId
     * @return
     */
    @SaIgnore
    @GetMapping("social")
    public JsonResult social(Long accountSetsId, Long empId) {
        return JsonResult.successful(employeeDetailService.loadSocial(accountSetsId, empId));
    }
}
