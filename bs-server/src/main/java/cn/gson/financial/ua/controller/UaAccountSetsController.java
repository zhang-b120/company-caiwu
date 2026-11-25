package cn.gson.financial.ua.controller;

import cn.dev33.satoken.annotation.SaIgnore;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.model.ua.request.GetCommonReq;
import cn.gson.financial.kernel.model.ua.request.SetZeroDeclareReq;
import cn.gson.financial.kernel.service.AccountSetsService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * @Author zhb
 * @Date 2025-06-20
 * @Description
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/ua/accountSets")
public class UaAccountSetsController {

    private final AccountSetsService accountSetsService;

    @PostMapping("/uaSetZeroDeclaring")
    @SaIgnore
    public JsonResult uaSetZeroDeclare(@RequestBody SetZeroDeclareReq req){
        return JsonResult.successful(accountSetsService.uaSetZeroDeclare(req));
    }

    @PostMapping("/uaGetSetZeroDeclare")
    @SaIgnore
    public JsonResult uaGetSetZeroDeclare(@RequestBody GetCommonReq req){
        return JsonResult.successful(accountSetsService.uaGetSetZeroDeclare(req));
    }
}