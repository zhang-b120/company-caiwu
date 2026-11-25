package cn.gson.financial.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.gson.financial.kernel.annotation.SessionAccountSet;
import cn.gson.financial.kernel.annotation.SessionUser;
import cn.gson.financial.kernel.common.SubjectExcelUtils;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.event.SubjectChangeEvent;
import cn.gson.financial.kernel.model.dto.AccountingCategoryDto;
import cn.gson.financial.kernel.model.dto.SubjectBalanceForm;
import cn.gson.financial.kernel.model.entity.AccountSets;
import cn.gson.financial.kernel.model.entity.Subject;
import cn.gson.financial.kernel.model.vo.SubjectVo;
import cn.gson.financial.kernel.model.vo.UserVo;
import cn.gson.financial.kernel.service.AccountingCategoryService;
import cn.gson.financial.kernel.service.BalanceService;
import cn.gson.financial.kernel.service.CurrencyService;
import cn.gson.financial.kernel.service.SubjectService;
import cn.gson.financial.rpa.ExcelReaderUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.extra.spring.SpringUtil;
import cn.hutool.poi.excel.ExcelUtil;
import cn.hutool.poi.excel.ExcelWriter;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 *
 * <p><b>Copyright © 2010-2019开发公司All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.controller</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2019年07月30日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 *
 */
@Slf4j
@RestController
@RequestMapping("/subject")
@RequiredArgsConstructor
public class SubjectController {

    private final AccountingCategoryService accountingCategoryService;
    private final CurrencyService currencyService;
    private final SubjectService subjectService;
    private final BalanceService balanceService;
    private final ExcelReaderUtil excelReaderUtil;
    private final SubjectExcelUtils excelUtils;


    @GetMapping
    public JsonResult list(String type, @SessionAccountSet AccountSets accountSets, Boolean status) {
        List<SubjectVo> result = subjectService.list(accountSets.getId(), type, status);
        return JsonResult.successful(result);
    }

    @GetMapping("list/ByCode")
    public JsonResult list(@RequestParam("code") String[] codeList, @SessionAccountSet AccountSets accountSets) {
        return JsonResult.successful(subjectService.listByCode(accountSets.getId(), Arrays.asList(codeList)));
    }

    @GetMapping("list/ast")
    public JsonResult listByAstId(@RequestParam("astId") Long astId) {
        return JsonResult.successful(subjectService.listByAstId(astId));
    }

    @PostMapping
    @SaCheckPermission("setting-accountsubject-canedit")
    public JsonResult save(@RequestBody Subject subject, @SessionAccountSet AccountSets accountSets) {
        subject.setAccountSetsId(accountSets.getId());
        subject.setSystemDefault(false);
        Integer encLen = accountSets.getEncoding().stream().reduce(Integer::sum).orElse(0);
        return JsonResult.successful(subjectService.save(subject, encLen));
    }

    @PutMapping
    @SaCheckPermission("setting-accountsubject-canedit")
    public JsonResult update(@RequestBody Subject subject, @SessionAccountSet AccountSets accountSets) {
        subject.setAccountSetsId(accountSets.getId());
        Integer encLen = accountSets.getEncoding().stream().reduce(Integer::sum).orElse(0);
        subjectService.save(subject, encLen);

        SpringUtil.publishEvent(SubjectChangeEvent.create(subject));

        return JsonResult.successful();
    }

    @PutMapping("status")
    @SaCheckPermission("setting-accountsubject-canedit")
    public JsonResult updateStatus(@SessionAccountSet AccountSets accountSets, @RequestBody Subject subject) {
        subject.setAccountSetsId(accountSets.getId());
        subjectService.updateStatus(subject);

        SpringUtil.publishEvent(SubjectChangeEvent.create(subject));

        return JsonResult.successful();
    }

    @DeleteMapping("{subjectId:\\d+}")
    @SaCheckPermission("setting-accountsubject-candelete")
    public JsonResult delete(@SessionAccountSet AccountSets accountSets, @PathVariable Long subjectId) {
        subjectService.delete(subjectId, accountSets.getId());
        balanceService.clearSubjectInit(subjectId, accountSets.getId());
        return JsonResult.successful();
    }

    /**
     * 凭证下拉数据
     *
     * @return
     */
    @RequestMapping("voucher/select")
    public JsonResult voucherSelect(@RequestParam(defaultValue = "0") boolean showAll, @SessionAccountSet AccountSets accountSets) {
        Long accountSetsId = accountSets.getId();
        return JsonResult.successful(showAll ? subjectService.selectData(accountSetsId) : subjectService.selectLeafData(accountSetsId));
    }

    /**
     * 凭证下拉数据
     *
     * @return
     */
    @RequestMapping("node/select")
    public JsonResult nodeSelect(@SessionAccountSet AccountSets accountSets) {
        return JsonResult.successful(subjectService.nodeSelect(accountSets.getId()));
    }

    /**
     * 明细转辅助项
     *
     * @return
     */
    @RequestMapping("to/auxiliary")
    @SaCheckPermission("setting-accountsubject-canedit")
    public JsonResult toAuxiliary(@SessionUser UserVo userVo, @SessionAccountSet AccountSets accountSets, String subjectCode, Long subjectId, Long categoryId) {
        subjectService.toAuxiliary(accountSets.getId(), userVo.getId(), subjectCode, subjectId, categoryId);
        return JsonResult.successful();
    }


    /**
     * 使用状态检查
     *
     * @param subjectId
     * @return
     */
    @GetMapping("checkUse/{subjectId}")
    public JsonResult checkUse(@PathVariable Long subjectId, @SessionAccountSet AccountSets accountSets) {
        return JsonResult.successful(subjectService.checkUse(subjectId, accountSets.getId()));
    }

    /**
     * 检查科目是否已经产生凭证数据
     *
     * @param subjectId
     * @return
     */
    @GetMapping("checkHasData/{subjectId}")
    public JsonResult checkHasData(@PathVariable Long subjectId, @SessionAccountSet AccountSets accountSets) {
        return JsonResult.successful(subjectService.checkHasData(subjectId, accountSets.getId()));
    }

    /**
     * 科目余额
     *
     * @param subjectBalanceForm
     * @return
     */
    @PostMapping("balance")
    public JsonResult balance(@RequestBody SubjectBalanceForm subjectBalanceForm, @SessionAccountSet AccountSets accountSets) {
        return JsonResult.successful(balanceService.subjectBalance(accountSets.getId(), subjectBalanceForm));
    }

    /**
     * 导入
     *
     * @param multipartFile
     * @return
     */
    @PostMapping("/import")
    @SaCheckPermission("setting-accountsubject-canimport")
    public JsonResult importVoucher(@RequestParam("file") MultipartFile multipartFile, @SessionUser UserVo userVo) {
        try {
            List<SubjectVo> voucherList = excelUtils.readExcel(multipartFile.getInputStream(), userVo.getAccountSets().getEncoding(), userVo.getAccountSets().getSubjectCategory(), userVo.getAccountSets().getId());
            this.subjectService.importSubject(userVo.getAccountSets(), voucherList);
            return JsonResult.successful();
        } catch (Exception e) {
            log.error("导入失败", e);
            return JsonResult.failure(e.getMessage());
        }
    }

    @PostMapping("/importSubject")
    public JsonResult importSubject(@SessionUser UserVo userVo){
        try {
            excelReaderUtil.importSubject(userVo);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        return JsonResult.successful();
    }

    /**
     * 导出
     *
     * @param accountSetsId
     * @return
     */
    @GetMapping("/download")
    @SaCheckPermission("setting-accountsubject-canexport")
    public void downloadVoucher(@SessionUser UserVo userVo, @SessionAccountSet AccountSets accountSets, HttpServletResponse response) throws IOException {
        List<SubjectVo> voucherList = subjectService.list(accountSets.getId());
        ExcelWriter writer = ExcelUtil.getWriter();
        writer.addHeaderAlias("code", "科目编码");
        writer.addHeaderAlias("name", "科目名称");
        writer.addHeaderAlias("typeAlias", "科目类别");
        writer.addHeaderAlias("balanceDirection", "余额方向");
        writer.addHeaderAlias("unit", "数量核算");
        writer.addHeaderAlias("aan", "辅助核算");
        //writer.addHeaderAlias("can", "外币核算");
        writer.setOnlyAlias(true);
        writer.write(voucherList);
        String fileName = userVo.getAccountSets().getCompanyName() + "-科目数据.xls";
        response.setContentType("application/vnd.ms-excel;");
        response.setHeader("Content-disposition", "attachment; filename=" + new String(fileName.getBytes(StandardCharsets.UTF_8), "ISO8859-1"));
        writer.flush(response.getOutputStream());
        writer.close();
    }


    @GetMapping("template")
    public void downTemplate(HttpServletResponse response, @SessionAccountSet AccountSets accountSets, @SessionUser UserVo userVo) throws IOException {
        List<AccountingCategoryDto> categories = accountingCategoryService.listByAccountSetsId(accountSets.getId());
        List<String> columns = StrUtil.split("科目编码,科目名称,科目类别,余额方向,数量核算,辅助核算", ",");
        List<String> explains = StrUtil.split("编码规则" + StrUtil.join("-", userVo.getAccountSets().getEncoding()) + ",,,借/贷,KG/件...,供应商/项目...,导入请删除本行", ",");
        ExcelWriter writer = ExcelUtil.getWriter();
        writer.renameSheet("待导入科目数据");
        writer.writeHeadRow(columns);
        writer.writeRow(explains);

        writer.setSheet("辅助核算参考表");
        writer.addHeaderAlias("name", "辅助核算类别");
        writer.setOnlyAlias(true);
        writer.write(categories);
//
//        List<CurrencyDto> currencies = currencyService.list(accountSetsId);
//        writer.setSheet("外币核算参考表");
//        writer.addHeaderAlias("name", "名称");
//        writer.setOnlyAlias(true);
//        writer.write(currencies);


        String fileName = "科目导入模板" + LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd")) + ".xls";
        response.setContentType("application/vnd.ms-excel;");
        response.setHeader("Content-disposition", "attachment; filename=" + new String(fileName.getBytes(StandardCharsets.UTF_8), "ISO8859-1"));
        writer.flush(response.getOutputStream());
        writer.close();
    }

}
