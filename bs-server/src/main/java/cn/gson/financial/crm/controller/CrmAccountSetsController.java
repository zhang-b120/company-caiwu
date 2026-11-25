package cn.gson.financial.crm.controller;

import cn.dev33.satoken.annotation.SaIgnore;
import cn.gson.financial.crm.service.CrmAccountSetsService;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.controller.Page;
import cn.gson.financial.kernel.model.crm.req.GetAccountSetInfoReq;
import cn.gson.financial.kernel.model.crm.req.GetMyAccountSetsReq;
import cn.gson.financial.kernel.model.dto.TaxDto;
import cn.gson.financial.kernel.model.ua.request.GetCommonReq;
import cn.gson.financial.kernel.model.ua.request.LocalDateReq;
import cn.gson.financial.kernel.model.ua.request.SetZeroDeclareReq;
import cn.gson.financial.kernel.service.AccountSetsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * @Author zhb
 * @Date 2025-06-30
 * @Description
 */
@RestController
@RequestMapping("/crmAccountSets")
@RequiredArgsConstructor
@Slf4j
public class CrmAccountSetsController {
    private final AccountSetsService accountSetsService;
    private final CrmAccountSetsService crmAccountSetsService;

    @SaIgnore
    @PostMapping("/crmGetAccountSetInfo")
    public JsonResult crmGetAccountSetInfo(@RequestBody GetAccountSetInfoReq req, Page page){
        return JsonResult.successful(accountSetsService.crmGetAccountSetInfo(req, page));
    }

    @PostMapping("/crmSetZeroDeclaring")
    @SaIgnore
    public JsonResult crmSetZeroDeclaring(@RequestBody SetZeroDeclareReq req){
        return JsonResult.successful(accountSetsService.uaSetZeroDeclare(req));
    }

    @SaIgnore
    @PostMapping("/crmNewMyAccountSets")
    public JsonResult crmNewMyAccountSets(@RequestBody GetMyAccountSetsReq req) {
        return JsonResult.successful(crmAccountSetsService.newMyAccountSets(req));
    }

    @SaIgnore
    @PostMapping("/updateIndividualTaxCodeByCreditCode")
    public void crmUpdateIndividualTaxCodeByCreditCode(@RequestParam String creditCode ,@RequestParam String individualTaxCode) {
        crmAccountSetsService.updateIndividualTaxCodeByCreditCode(creditCode,individualTaxCode);
    }
}
