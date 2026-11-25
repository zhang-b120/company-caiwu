package cn.gson.financial.ua.controller;/* Copyright © 2020 Yuech and/or its affiliates. All rights reserved. */

import cn.dev33.satoken.annotation.SaIgnore;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.model.ua.request.GetSummaryDataReq;
import cn.gson.financial.kernel.service.BankStatementDetailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * @Author zhb
 * @Date 2025-05-20
 * @Description
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/ua/bank/statement/detail")
public class UaBankStatementDetailController {

    private final BankStatementDetailService bankStatementDetailService;

    /**
     * 获取流水明细
     * @param req
     * @return
     */
    @SaIgnore
    @PostMapping("/uaGetSummaryData")
    public JsonResult uaGetSummaryData(@RequestBody GetSummaryDataReq req){
        return JsonResult.successful(bankStatementDetailService.uaGetSummaryData(req));
    }

    /**
     * 获取明细列表
     * @param bankStatementId
     * @return
     */
    @SaIgnore
    @GetMapping("/uaGetBankStatementDetailList")
    public JsonResult uaGetBankStatementDetailList(@RequestParam Long bankStatementId){
        return JsonResult.successful(bankStatementDetailService.uaGetBankStatementDetailList(bankStatementId));
    }
}