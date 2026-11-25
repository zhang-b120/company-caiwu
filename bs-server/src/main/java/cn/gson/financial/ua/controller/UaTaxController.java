package cn.gson.financial.ua.controller;/* Copyright © 2020 Yuech and/or its affiliates. All rights reserved. */

import cn.dev33.satoken.annotation.SaIgnore;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.model.dto.TaxDto;
import cn.gson.financial.kernel.model.ua.request.GetCommonReq;
import cn.gson.financial.kernel.model.ua.request.LocalDateReq;
import cn.gson.financial.kernel.service.AccountSetsService;
import cn.gson.financial.kernel.service.TaxService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * @Author zhb
 * @Date 2025-05-19
 * @Description
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/ua/tax")
public class UaTaxController {

    private final AccountSetsService accountSetsService;
    private final TaxService taxService;

    @SaIgnore
    @PostMapping("/newMyAccountSets")
    public JsonResult getMyAccountSetsList(@RequestBody LocalDateReq req) {
        List<TaxDto> accountSetsList = accountSetsService.newMyAccountSets(null,req.getAccountSetsId(),req.getEndDate(),null);
        return JsonResult.successful(accountSetsList);
    }

    /**
     * 获取报税结果
     * @param req
     * @return
     */
    @SaIgnore
    @PostMapping("/getTaxTableResult")
    public JsonResult getTaxTableResult(@RequestBody GetCommonReq req){
        return JsonResult.successful(taxService.getTaxTableResult(req));
    }
}
