package cn.gson.financial.ua.controller;/* Copyright © 2020 Yuech and/or its affiliates. All rights reserved. */

import cn.dev33.satoken.annotation.SaIgnore;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.service.CashierAccountService;
import cn.gson.financial.kernel.model.ua.request.GetCommonReq;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * @Author zhb
 * @Date 2025-05-19
 * @Description
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/ua/cashier/account")
public class UaCashierAccountController {

    private final CashierAccountService cashierAccountService;

    /**
     * 获取记账类型信息
     * @param req
     * @return
     */
    @SaIgnore
    @PostMapping("/uaGetCashierAccountList")
    public JsonResult UaGetCashierAccountList(@RequestBody GetCommonReq req) {
        return JsonResult.successful(cashierAccountService.list(req.getAccountSetsId(), null));
    }
}