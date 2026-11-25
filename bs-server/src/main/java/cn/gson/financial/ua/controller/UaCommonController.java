package cn.gson.financial.ua.controller;/* Copyright © 2020 Yuech and/or its affiliates. All rights reserved. */

import cn.dev33.satoken.annotation.SaIgnore;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.model.ua.request.GetCommonReq;
import cn.gson.financial.kernel.service.CommonService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * @Author zhb
 * @Date 2025-05-29
 * @Description
 */

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/ua/common")
public class UaCommonController {

    private final CommonService commonService;


    /**
     * 获取记账进度
     * @param req
     * @return
     */
    @PostMapping("/uaGetAccountProgress")
    @SaIgnore
    public JsonResult uaGetAccountProgress(@RequestBody GetCommonReq req){
        return JsonResult.successful(commonService.getAccountProgress(req.getAccountSetsId(), req.getPeriod()));
    }
}