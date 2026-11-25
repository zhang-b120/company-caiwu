package cn.gson.financial.ua.controller;/* Copyright © 2020 Yuech and/or its affiliates. All rights reserved. */

import cn.dev33.satoken.annotation.SaIgnore;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.model.entity.AccountSets;
import cn.gson.financial.kernel.service.UserService;
import cn.gson.financial.kernel.model.ua.request.GetCommonReq;
import cn.gson.financial.kernel.model.ua.resp.GetUserInfoResp;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * @Author zhb
 * @Date 2025-05-16
 * @Description
 */

@RestController
@RequiredArgsConstructor
@RequestMapping("/ua/user")
public class UaUserController {

    private final UserService userService;

    @SaIgnore
    @PostMapping("/uaGetAccountSetsId")
    public JsonResult uaGetAccountSetsId(@RequestParam String creditCode){
        AccountSets accountSets = userService.uaGetAccountSetsId(creditCode);
        return JsonResult.successful(accountSets);
    }

    /**
     * 获取登录用户信息
     * @param req
     * @return
     */
    @SaIgnore
    @PostMapping("/uaGetUserInfo")
    public JsonResult uaGetUserInfo(@RequestBody GetCommonReq req){
        GetUserInfoResp resp = userService.uaGetUserInfo(req.getAccountSetsId());
        return JsonResult.successful(resp);
    }

    @SaIgnore
    @PostMapping("/uaGetAccountSetsIdList")
    public JsonResult uaGetAccountSetsIdList(@RequestParam String creditCodes){
        return JsonResult.successful(userService.uaGetAccountSetsIdList(creditCodes));
    }
}