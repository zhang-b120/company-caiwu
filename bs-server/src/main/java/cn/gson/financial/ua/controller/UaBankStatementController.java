package cn.gson.financial.ua.controller;/* Copyright © 2020 Yuech and/or its affiliates. All rights reserved. */

import cn.dev33.satoken.annotation.SaIgnore;
import cn.gson.financial.kernel.common.ExcelUtils;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.controller.Page;
import cn.gson.financial.kernel.model.vo.ImportBankStatementVo;
import cn.gson.financial.kernel.service.BankStatementService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * @Author zhb
 * @Date 2025-05-19
 * @Description
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/ua/bank/statement")
public class UaBankStatementController {

    private final BankStatementService bankStatementService;

    /**
     * 上传银行流水
     * @param file
     * @param accountSetsId
     * @return
     * @throws Exception
     */
    @SaIgnore
    @PostMapping("/uaImportBankStatement")
    public JsonResult uaImportBankStatement(@RequestParam(value = "file") MultipartFile file, Long accountSetsId) throws Exception {

        List<ImportBankStatementVo> importDataList = ExcelUtils.importFun(file.getInputStream(), ImportBankStatementVo.class);
        bankStatementService.importData(importDataList, accountSetsId);
        return JsonResult.successful();
    }

    /**
     * 获取流水列表
     * @param accountSetsId
     * @param page
     * @return
     */
    @SaIgnore
    @GetMapping("/uaGetBankStatementList")
    public JsonResult uaGetBankStatementList(Long accountSetsId,  Page page){
        return JsonResult.successful(bankStatementService.uaGetBankStatementList(accountSetsId, page));
    }

    /**
     * 删除流水
     * @param bankStatementId
     * @return
     */
    @SaIgnore
    @GetMapping("/uaDeleteBankStatement")
    public JsonResult uaDeleteBankStatement(@RequestParam Long bankStatementId){
        bankStatementService.uaDeleteBankStatement(bankStatementId);
        return JsonResult.successful();
    }
}