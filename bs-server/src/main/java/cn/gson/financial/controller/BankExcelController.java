package cn.gson.financial.controller;

import cn.dev33.satoken.annotation.SaIgnore;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.controller.Page;
import cn.gson.financial.kernel.model.vo.RecognizeBankExcelVo;
import cn.gson.financial.kernel.model.vo.SaveBankLibVo;
import cn.gson.financial.kernel.model.vo.SaveExcelMappingVo;
import cn.gson.financial.kernel.model.vo.SaveExcelRecognizeVo;
import cn.gson.financial.kernel.service.BankExcelService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * @Author zhb
 * @Date 2025-09-17
 * @Description
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/bankExcel")
public class BankExcelController {

    private final BankExcelService bankExcelService;

    /**
     * 银行流水识别
     * @param vo
     * @return
     */
    @PostMapping("/recognizeBankExcel")
    public JsonResult recognizeBankExcel(@RequestBody RecognizeBankExcelVo vo){
        return JsonResult.successful(bankExcelService.recognizeBankExcel(vo.getFilePath(), vo.getBusinessCode(), vo.getBusinessType()));
    }

    /**
     * 保存识别字段模板
     * @param voList
     * @return
     */
    @PostMapping("/saveExcelRecognizeTemplate")
    public JsonResult saveExcelRecognizeTemplate(@RequestBody List<SaveExcelRecognizeVo> voList){
        return JsonResult.successful(bankExcelService.saveExcelRecognizeField(voList));
    }

    /**
     * 获取识别字段模板
     * @param businessCode
     * @param businessType
     * @return
     */
    @GetMapping("/getExcelRecognizeTemplate")
    public JsonResult getExcelRecognizeTemplate(String businessCode, String businessType){
        return JsonResult.successful(bankExcelService.getExcelRecognizeTemplate(businessCode, businessType));
    }

    /**
     * 保存文件解析字段模板
     * @param voList
     * @return
     */
    @PostMapping("/saveExcelMappingTemplate")
    public JsonResult saveExcelMappingTemplate(@RequestBody List<SaveExcelMappingVo> voList){
        return JsonResult.successful(bankExcelService.saveExcelMappingTemplate(voList));
    }

    /**
     * 获取文件解析字段模板
     * @param businessCode
     * @param businessType
     * @return
     */
    @GetMapping("/getExcelMappingTemplate")
    public JsonResult getExcelMappingTemplate(String businessCode, String businessType){
        return JsonResult.successful(bankExcelService.getExcelMappingTemplate(businessCode, businessType));
    }

    /**
     * 获取映射字段
     * @return
     */
    @GetMapping("/getExcelMappingField")
    public JsonResult getExcelMappingField(){
        return JsonResult.successful(bankExcelService.getExcelMappingField());
    }

    /**
     * 获取公共银行库
     * @param bankCode
     * @param bankName
     * @param page
     * @return
     */
    @GetMapping("/getBankLibPage")
    public JsonResult getBankLibPage(String bankCode, String bankName, Page page){
        return JsonResult.successful(bankExcelService.getBankLibPage(bankCode, bankName, page));
    }

    /**
     * 删除
     * @param id
     * @return
     */
    @PostMapping("/deleteBankLib")
    public JsonResult deleteBankLib(@RequestParam Long id){
        return JsonResult.successful(bankExcelService.deleteBankLib(id));
    }

    /**
     * 启用银行
     * @param id
     * @return
     */
    @PostMapping("/enabledBankLib")
    public JsonResult enabledBankLib(@RequestParam Long id){
        return JsonResult.successful(bankExcelService.enabledBankLib(id));
    }

    /**
     * 禁用银行
     * @param id
     * @return
     */
    @PostMapping("/unEnabledBankLib")
    public JsonResult unEnabledBankLib(@RequestParam Long id){
        return JsonResult.successful(bankExcelService.unEnabledBankLib(id));
    }

    /**
     * 启用自动识别
     * @param id
     * @return
     */
    @PostMapping("/autoRecognize")
    public JsonResult autoRecognize(@RequestParam Long id){
        return JsonResult.successful(bankExcelService.autoRecognize(id));
    }

    /**
     * 关闭自动识别
     * @param id
     * @return
     */
    @PostMapping("/unAutoRecognize")
    public JsonResult unAutoRecognize(@RequestParam Long id){
        return JsonResult.successful(bankExcelService.unAutoRecognize(id));
    }

    /**
     * 获取公共银行库
     * @param bankCode
     * @param bankName
     * @return
     */
    @GetMapping("/getBankLibList")
    public JsonResult getBankLibList(String bankCode, String bankName){
        return JsonResult.successful(bankExcelService.getBankLibList(bankCode, bankName));
    }

    /**
     * 保存银行
     * @param vo
     * @return
     */
    @PostMapping("/saveBankLib")
    public JsonResult saveBankLib(@RequestBody SaveBankLibVo vo){
        return JsonResult.successful(bankExcelService.saveBankLib(vo));
    }
}