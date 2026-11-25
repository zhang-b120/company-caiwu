package cn.gson.financial.ua.controller;

import cn.dev33.satoken.annotation.SaIgnore;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.service.BankExcelService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * @Author zhb
 * @Date 2025-09-28
 * @Description
 */@RestController
@RequiredArgsConstructor
@RequestMapping("/ua/bankLib")
public class UaBankLibController {

     private final BankExcelService bankExcelService;

    /**
     * 获取公共银行库
     * @return
     */
    @SaIgnore
    @GetMapping("/getBankLibList")
    public JsonResult uaGetBankLibList(){
        return JsonResult.successful(bankExcelService.getBankLibList(null, null));
    }
}