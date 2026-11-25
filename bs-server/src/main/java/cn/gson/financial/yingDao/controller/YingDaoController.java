package cn.gson.financial.yingDao.controller;

import cn.dev33.satoken.annotation.SaIgnore;
import cn.gson.financial.kernel.common.DateUtil;
import cn.gson.financial.kernel.model.dto.FinancialProcessDTO;
import cn.gson.financial.kernel.model.entity.AccountSets;
import cn.gson.financial.kernel.model.entity.SyncEmployeeInformation;
import cn.gson.financial.kernel.model.yingDao.req.*;
import cn.gson.financial.kernel.model.yingDao.resp.GetExportEmployeeInfoResp;
import cn.gson.financial.kernel.model.yingDao.resp.GetUserIndividualAccountSetResp;
import cn.gson.financial.kernel.service.FinancialProcessService;
import cn.gson.financial.kernel.tool.DateUtils;
import cn.gson.financial.yingDao.service.YingDaoService;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import jakarta.servlet.http.HttpServletResponse;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * @Author zhb
 * @Date 2025-09-01
 * @Description
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/yingDao")
public class YingDaoController {
    private final FinancialProcessService financialProcessService;
    private final YingDaoService yingDaoService;
    //导出路径
    private final static String FILE_SAVE_URL = "C:\\Users\\23614\\Downloads\\";
    private final static String FILE_SAVE_SUFFIX = ".xls";

    /**
     * 获取用户账套
     *
     * @param req
     * @return
     */
    @SaIgnore
    @PostMapping("/getUserIndividualAccountSet")
    public GetUserIndividualAccountSetResp getUserIndividualAccountSet(@RequestBody GetUserIndividualAccountSetReq req) {
        LocalDate lastPeriod = LocalDate.now().minusMonths(1).withDayOfMonth(1);
        lastPeriod = DateUtil.timeStrToLocalDate(String.valueOf(lastPeriod), DateUtil.YYYY_MM_DD);
        req.setPeriod(String.valueOf(lastPeriod));
        GetUserIndividualAccountSetResp I = yingDaoService.getUserIndividualAccountSet(req);
        return I;
    }

    /**
     * 系统导入员工信息
     *
     * @param
     * @return
     */
    @SaIgnore
    @PostMapping(value = "/importEmployeeInfo")
    public Boolean importEmployeeInfo(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "accountSetsId", required = false) Long accountSetsId,
            @RequestParam(value = "creditCode", required = false) String creditCode) {
        ImportEmployeeInfoReq importEmployeeInfoReq = new ImportEmployeeInfoReq();
        importEmployeeInfoReq.setAccountSetsId(accountSetsId);
        importEmployeeInfoReq.setCreditCode(creditCode);
        yingDaoService.importEmployeeInfo(file, importEmployeeInfoReq);
        return Boolean.TRUE;
    }

    /**
     * 在运行影刀部分的上传人员的时候，反馈的结果
     * @param syncEmployeeInformation
     */
    @SaIgnore
    @PostMapping("/resultOfSyncEmployeeInformation")
    public void importEmployeeInfoFail(@RequestBody SyncEmployeeInformation syncEmployeeInformation) {
        yingDaoService.addSyncEmployeeInformation2(syncEmployeeInformation);
    }


    /**
     * 导出员工信息
     *
     * @param req
     * @param response
     * @throws IOException
     */
    @SaIgnore
    @PostMapping("/exportEmployeeInfo")
    public void exportEmployeeInfo(@RequestBody GetCommonReq req, HttpServletResponse response) throws IOException {
        AccountSets accountSets = yingDaoService.resolveAccountSets(req);
        if (accountSets == null) {
            return;
        }
        LocalDate period = yingDaoService.resolvePeriod(req);
        String fileName = buildExportFileName(accountSets.getCreditCode(), accountSets.getId(), "人员", period);
        ByteArrayOutputStream outputStream = yingDaoService.exportEmployeeInfo(accountSets, period);
        if (outputStream != null) {
            writeExcelResponse(response, fileName, outputStream);
            yingDaoService.deleteExportFileInfo(accountSets.getId(), period, "ry", "yingdao");
            yingDaoService.addExportFileInfo(accountSets.getId(), period, FILE_SAVE_URL, fileName, FILE_SAVE_SUFFIX, FILE_SAVE_URL + fileName, "ry", "yingdao");
        }

    }

    /**
     * 导出员工工资信息
     *
     * @param req
     * @param response
     * @throws IOException
     */
    @SaIgnore
    @PostMapping("/exportEmployeeSalary")
    public void exportEmployeeSalary(@RequestBody GetCommonReq req, HttpServletResponse response) throws IOException {
        AccountSets accountSets = yingDaoService.resolveAccountSets(req);
        if (accountSets == null) {
            return;
        }
        LocalDate period = yingDaoService.resolvePeriod(req);
        String fileName = buildExportFileName(accountSets.getCreditCode(), accountSets.getId(), "工资", period);
        ByteArrayOutputStream outputStream = yingDaoService.exportEmployeeSalary(accountSets, period);
        if (outputStream != null) {
            writeExcelResponse(response, fileName, outputStream);
            yingDaoService.deleteExportFileInfo(accountSets.getId(), period, "gz", "yingdao");
            yingDaoService.addExportFileInfo(accountSets.getId(), period, FILE_SAVE_URL, fileName, FILE_SAVE_SUFFIX, FILE_SAVE_URL + fileName, "gz", "yingdao");
        }

    }

    private String buildExportFileName(String creditCode, Long accountSetsId, String typeLabel, LocalDate period) {
        String prefix = StringUtils.isNotBlank(creditCode) ? creditCode : String.valueOf(accountSetsId);
        String periodStr = period.format(DateTimeFormatter.ofPattern("yyyyMM"));
        return prefix + "_" + typeLabel + "_" + periodStr + FILE_SAVE_SUFFIX;
    }

    private void writeExcelResponse(HttpServletResponse response, String fileName, ByteArrayOutputStream outputStream) throws IOException {
        String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
        response.setContentType("application/vnd.ms-excel");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Content-Disposition",
                "attachment; filename=\"" + encoded + "\"; filename*=UTF-8''" + encoded);
        response.getOutputStream().write(outputStream.toByteArray());
        response.getOutputStream().flush();
    }


    /**
     * 获取导出员工文件信息
     *
     * @param req
     * @return
     */
    @SaIgnore
    @PostMapping("/getExportEmployeeInfo")
    public GetExportEmployeeInfoResp getExportEmployeeInfo(@RequestBody GetExportEmployeeInfoReq req) {
        return yingDaoService.getExportEmployeeInfo(req);
    }

    /**
     * 保存个税报税完成信息
     *
     * @param req
     * @return
     */
    @SaIgnore
    @PostMapping("/updateIndividualTax")
    public Boolean updateIndividualTax(@RequestBody UpdateIndividualTaxReq req) {
        return yingDaoService.updateIndividualTax(req);
    }

    /**
     * 更新财务流程个税申报状态
     */
    @SaIgnore
    @PostMapping("/updateProccessOfIndividual")
    public void updateProccessOfIndividual(@RequestBody Long accountSetsId) {
        String firstDayOfLastMonthAsString = DateUtils.getFirstDayOfLastMonthAsString();
        FinancialProcessDTO financialProcessConditional = new FinancialProcessDTO();
        financialProcessConditional.setAccountSetsId(accountSetsId);
        financialProcessConditional.setAccountingPeriod(firstDayOfLastMonthAsString);
        financialProcessConditional.setIsIndividualTaxCompleted("是");
        financialProcessService.updateFinancialProcess(financialProcessConditional);
    }
}
