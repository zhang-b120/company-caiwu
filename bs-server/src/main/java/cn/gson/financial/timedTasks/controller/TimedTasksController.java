package cn.gson.financial.timedTasks.controller;

import cn.dev33.satoken.annotation.SaIgnore;
import cn.dev33.satoken.stp.StpUtil;
import cn.gson.financial.kernel.annotation.SessionUser;
import cn.gson.financial.kernel.model.entity.User;
import cn.gson.financial.kernel.model.vo.UserVo;
import cn.gson.financial.kernel.service.FinancialProcessService;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.model.entity.AccountSets;
import cn.gson.financial.kernel.model.entity.TaxReportResult;
import cn.gson.financial.kernel.model.enums.VatType;
import cn.gson.financial.kernel.service.AccountSetsService;
import cn.gson.financial.kernel.service.ReportService;
import cn.gson.financial.kernel.tool.RedisUtil;
import cn.gson.financial.timedTasks.domain.req.*;
import cn.gson.financial.timedTasks.domain.resp.*;
import cn.gson.financial.timedTasks.service.TimedTaskService;
import cn.hutool.json.JSONUtil;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static cn.gson.financial.kernel.common.Constants.S_USER_KEY;

@RestController
@RequestMapping("/timedTasks")
public class TimedTasksController {
    @Autowired
    private TimedTaskService timedTaskService;
    @Autowired
    private RedisUtil redisUtil;
    @Autowired
    private ReportService reportService;
    @Autowired
    private AccountSetsService accountSetsService;
    @Autowired
    private FinancialProcessService financialProcessService;

    /**
     * 查询任务表
     */
    @SaIgnore
    @GetMapping("/getTaskOfInvoice")
    public JsonResult getTaskOfInvoice() {
        return JsonResult.successful(timedTaskService.doTask());
    }

    /**
     * 读取应申报税费种信息的任务（供另一个系统查询）
     * 返回一条尚未同步过应申报税费种的账套（accountSetsId、creditCode、province、companyName），
     * 另一系统据此去税局抓「应申报税费种信息」后，调用 saveTaxDeclare 回传存库。
     */
    @SaIgnore
    @GetMapping("/getTaxDeclareTask")
    public JsonResult getTaxDeclareTask() {
        return JsonResult.successful(timedTaskService.getTaxDeclareTask());
    }

    /**
     * 保存应申报税费种信息（另一系统抓取税局「应申报税费种信息」后 POST 此接口存库）
     */
    @SaIgnore
    @PostMapping("/saveTaxDeclare")
    public JsonResult saveTaxDeclare(@RequestBody SaveTaxDeclareReq req) {
        return JsonResult.successful(timedTaskService.saveTaxDeclare(req));
    }

    /**
     * 按账套查询应申报税费种列表及最近更新时间（供前端展示「更新时间」、判定是否重新抓取）
     * 返回 list + lastUpdateTime，lastUpdateTime 为该账套下数据最近一次同步时间。
     */
    @GetMapping("/listTaxDeclare")
    public JsonResult listTaxDeclare(@RequestParam Long accountSetsId) {
        return JsonResult.successful(timedTaskService.listTaxDeclare(accountSetsId));
    }

    /**
     * 将获取的报税条目存入数据库
     */
    @SaIgnore
    @PostMapping("/saveTaxItem")
    public JsonResult saveTaxItem(@RequestBody SaveTaxItemReq saveTaxItemReq) {
        return JsonResult.successful(timedTaskService.SaveTaxItem(saveTaxItemReq));
    }

    /**
     * 保存发票
     */
    @SaIgnore
    @PostMapping(value = "/saveInvoice", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public void saveInvoice(
            @RequestParam("file") MultipartFile file,
            @RequestParam("creditCode") String creditCode,
            @RequestParam("accountSetsId") Long accountSetsId,
            @RequestParam("invoiceType") String invoiceType) throws Exception {
        // FN-INVOICE-COLLECT-MODE-CONFIG：按账套级采集模式分流（季度→季末归属/月度→真实月），替代旧 vatType==SMALL_SCALE 判据。
        if (timedTaskService.isQuarterlyInvoiceCollect(accountSetsId)) {
            timedTaskService.saveImportInvoiceSeason(creditCode, file.getInputStream(), accountSetsId, invoiceType);
        } else {
            timedTaskService.saveImportInvoice(creditCode, file.getInputStream(), accountSetsId, invoiceType);
        }
    }

    /**
     * 删除除了因不是办税而失败的取票信息
     */
    @SaIgnore
    @GetMapping("/deleteTaskOfInvoice")
    public void deleteTaskOfInvoice() {
        timedTaskService.deleteTaskOfInvoice();
    }

    /**
     * 保存勾选发票
     */
    @SaIgnore
    @PostMapping(value = "/saveInvoiceByCheck", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public void saveInvoiceByCheck(
            @RequestParam("file") MultipartFile file,
            @RequestParam("invoiceType") String invoiceType) throws Exception {
        timedTaskService.saveGouXuanInvoice(file.getInputStream(), invoiceType);
    }

    /*
      保存未勾选发票
     */
    @SaIgnore
    @PostMapping(value = "/saveUnCheckInvoice", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public void saveUnCheckInvoice(
            @RequestParam("file") MultipartFile file,
            @RequestParam("invoiceType") String invoiceType) throws Exception {
        timedTaskService.saveWeiGouXuanInvoice(file.getInputStream(), invoiceType);
    }

    /**
     * 更新任务发票执行结果
     */
    @SaIgnore
    @PostMapping("/updateTaskStatusOfInvoice")
    public Boolean updateInvoiceTask(@RequestBody UpdateStateOfInvoiceReq updateStateOfInvoiceReq) {
        timedTaskService.updateInvoiceTask(updateStateOfInvoiceReq);
        /*FinancialProcessDTO financialProcessDTO = new FinancialProcessDTO();
        financialProcessDTO.setAccountSetsId(updateStateOfInvoiceReq.getAccountSetsId());
        financialProcessDTO.setAccountingPeriod("2025-09-01");
        if (updateStateOfInvoiceReq.getResult()) {
            financialProcessDTO.setIsTicketObtained("是");
            financialProcessDTO.setIsTaxItemCompleted("是");
        } else {
            financialProcessDTO.setIsTicketObtained("失败");
            financialProcessDTO.setIsTaxItemCompleted("失败");
        }
        financialProcessService.updateFinancialProcess(financialProcessDTO);*/
        return Boolean.TRUE;
    }


    /**
     * 短信接收 ？
     *
     * @return
     */
    @GetMapping(value = "/getCode2")
    @SaIgnore
    public String getCode2(String province) {
        if (StringUtils.isBlank(province)) {
            return null;
        }

        Set<String> keys = redisUtil.keys(province + "ka2");
        if (CollectionUtils.isEmpty(keys)) {
            return null;
        }

        String foundCode = null;
        String foundKey = null;

        for (String key : keys) {
            if (StringUtils.isBlank(key)) {
                continue;
            }

            try {
                String value = redisUtil.get(key);
                if (StringUtils.isBlank(value)) {
                    continue;
                }

                Map<String, String> checkCodeInfo = JSONUtil.toBean(value, Map.class);
                if (checkCodeInfo != null) {
                    String code = checkCodeInfo.get("code");
                    if (StringUtils.isNotBlank(code)) {
                        foundCode = code;
                        foundKey = key;
                        break; // 找到第一个有效的就停止
                    }
                }
            } catch (Exception e) {
                //继续进行下个遍历
                continue;
            }
        }

        // 找到验证码后再删除
        if (foundKey != null) {
            redisUtil.delByKey(foundKey);
        }

        return foundCode;
    }

    /**
     * 短信接收 ？
     *
     * @return
     */
    @GetMapping(value = "/getCode")
    @SaIgnore
    public String getCode(String province) {
        if (StringUtils.isBlank(province)) {
            return null;
        }

        Set<String> keys = redisUtil.keys(province + "CheckCode");
        if (CollectionUtils.isEmpty(keys)) {
            return null;
        }

        String foundCode = null;
        String foundKey = null;

        for (String key : keys) {
            if (StringUtils.isBlank(key)) {
                continue;
            }

            try {
                String value = redisUtil.get(key);
                if (StringUtils.isBlank(value)) {
                    continue;
                }

                Map<String, String> checkCodeInfo = JSONUtil.toBean(value, Map.class);
                if (checkCodeInfo != null) {
                    String code = checkCodeInfo.get("code");
                    if (StringUtils.isNotBlank(code)) {
                        foundCode = code;
                        foundKey = key;
                        break; // 找到第一个有效的就停止
                    }
                }
            } catch (Exception e) {
                //继续进行下个遍历
                continue;
            }
        }

        // 找到验证码后再删除
        if (foundKey != null) {
            redisUtil.delByKey(foundKey);
        }

        return foundCode;
    }
    /**
     * 短信接收 — Box通道（按省份 + 手机号 slot 读取）
     * GET /timedTasks/getCodeBox?province=xxx&slot=手机号
     */
    @GetMapping(value = "/getCodeBox")
    @SaIgnore
    public String getCodeBox(String province, String slot) {
        if (StringUtils.isBlank(province) || StringUtils.isBlank(slot)) {
            return null;
        }

        Set<String> keys = redisUtil.keys(province + "Box_" + slot);
        if (CollectionUtils.isEmpty(keys)) {
            return null;
        }

        String foundCode = null;
        String foundKey = null;

        for (String key : keys) {
            if (StringUtils.isBlank(key)) {
                continue;
            }

            try {
                String value = redisUtil.get(key);
                if (StringUtils.isBlank(value)) {
                    continue;
                }

                Map<String, String> checkCodeInfo = JSONUtil.toBean(value, Map.class);
                if (checkCodeInfo != null) {
                    String code = checkCodeInfo.get("code");
                    if (StringUtils.isNotBlank(code)) {
                        foundCode = code;
                        foundKey = key;
                        break;
                    }
                }
            } catch (Exception e) {
                continue;
            }
        }

        // 找到验证码后再删除
        if (foundKey != null) {
            redisUtil.delByKey(foundKey);
        }

        return foundCode;
    }

    /**
     * 获取报税事项
     *
     * @param
     */
    @SaIgnore
    @GetMapping("/selectTimedTaskOfTax")
    public List<TimedTaskOfTaxResp> selectTimedTaskOfTax() {
        return timedTaskService.selectTimedTaskOfTax();
    }

    /**
     * 同步报税结果
     */
    @SaIgnore
    @PostMapping("/updateStateOfTax")
    public Boolean updateTaskOfTaxStatus(@RequestBody UpdateStateOfTaxReq updateStateOfTaxReq) {
        timedTaskService.updateTaskOfTaxStatus(updateStateOfTaxReq);
        return Boolean.TRUE;
    }

    /**
     * 同步报税条目结果
     */
    @SaIgnore
    @PostMapping("/updateStateOfTaxItem")
    public Boolean updateTaskOfTaxItemStatus(@RequestBody UpdateStateOfTaxItemReq updateStateOfTaxItemReq) {
        timedTaskService.updateTaskOfTaxItemStatus(updateStateOfTaxItemReq);
        return Boolean.TRUE;
    }

    /**
     * 同步税款信息和截图
     */
    @SaIgnore
    @PostMapping("/updateTaxPayment")
    public void updateTaxPayment(@RequestBody UpdateTaxPaymentReq updateTaxPaymentReq) {
        timedTaskService.updateTaxPayment(updateTaxPaymentReq);
    }

    /**
     * 下载合并财务报表（资产负债表 + 利润表）
     * - isYear=true：返回年报.xls（去年12月31日，或指定 voucherDate）
     * - isYear=false/不传：返回月季报.xls
     *   - dimension=TERM（默认）：月报，取上个月末或指定 voucherDate
     *   - dimension=QUARTER：季报，取上个季度末或指定 voucherDate 所在季度末
     */
    @PostMapping("/selectTimedTaskOfTaxFile")
    @SaIgnore
    public ResponseEntity<StreamingResponseBody> exportReport(@RequestBody ImportTaxFileReq importTaxFileReq) {
        try {
            AccountSets accountSets = accountSetsService.getById(importTaxFileReq.getAccountSetId());
            if (accountSets == null) {
                throw new IllegalArgumentException("账套不存在，ID: " + importTaxFileReq.getAccountSetId());
            }
            boolean isYear = Boolean.TRUE.equals(importTaxFileReq.getIsYear());
            String dimension = importTaxFileReq.getDimension() != null ? importTaxFileReq.getDimension() : "TERM";
            boolean isQuarter = "QUARTER".equals(dimension);

            LocalDate voucherDate;
            if (isYear) {
                int year = importTaxFileReq.getVoucherDate() != null
                        ? LocalDate.parse(importTaxFileReq.getVoucherDate()).getYear()
                        : LocalDate.now().getYear() - 1;
                voucherDate = LocalDate.of(year, 12, 31);
            } else {
                voucherDate = resolveDate(importTaxFileReq.getVoucherDate(), isQuarter, false);
            }

            String templateSuffix = isYear ? "年报" : "月季报";
            String encodedFileName = URLEncoder.encode("财务报表报送与信息采集_" + templateSuffix + "_" + importTaxFileReq.getAccountSetId() + ".xls", StandardCharsets.UTF_8);

            final LocalDate finalVoucherDate = voucherDate;
            final String finalDimension = dimension;
            StreamingResponseBody stream = outputStream -> {
                try {
                    reportService.exportCombinedReport(accountSets, finalVoucherDate, finalDimension, isYear, outputStream);
                } catch (Exception e) {
                    throw new RuntimeException("导出报表失败", e);
                }
            };
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + encodedFileName + "\"")
                    .contentType(MediaType.parseMediaType("application/vnd.ms-excel"))
                    .body(stream);
        } catch (Exception e) {
            return ResponseEntity.ok().body(null);
        }
    }

    /**
     * 计算报表日期
     * @param voucherDateStr 前端传入的日期字符串（可为 null）
     * @param isQuarter      是否季报
     * @param isYear         是否年报（保留扩展）
     * @return 对应期间最后一天
     */
    private LocalDate resolveDate(String voucherDateStr, boolean isQuarter, boolean isYear) {
        if (voucherDateStr != null) {
            LocalDate input = LocalDate.parse(voucherDateStr);
            if (isQuarter) {
                // 传了日期 + 季报：计算该日期所在季度末月的最后一天
                int quarterEndMonth = ((input.getMonthValue() - 1) / 3 + 1) * 3;
                return LocalDate.of(input.getYear(), quarterEndMonth, 1)
                        .withDayOfMonth(LocalDate.of(input.getYear(), quarterEndMonth, 1).lengthOfMonth());
            } else {
                // 传了日期 + 月报：该月最后一天
                return input.withDayOfMonth(input.lengthOfMonth());
            }
        }
        // 未传日期
        LocalDate today = LocalDate.now();
        if (isQuarter) {
            // 上个季度末月最后一天
            int month = today.getMonthValue();
            int lastQuarterEndMonth = ((month - 1) / 3) * 3;
            if (lastQuarterEndMonth == 0) lastQuarterEndMonth = 12;
            int year = lastQuarterEndMonth == 12 && month <= 3 ? today.getYear() - 1 : today.getYear();
            return LocalDate.of(year, lastQuarterEndMonth, 1)
                    .withDayOfMonth(LocalDate.of(year, lastQuarterEndMonth, 1).lengthOfMonth());
        } else {
            // 上个月最后一天
            return today.minusMonths(1).withDayOfMonth(today.minusMonths(1).lengthOfMonth());
        }
    }

    /**
     * 是否存在特殊发票
     */
    @SaIgnore
    @PostMapping("/specialInvoiceCount")
    public String specialInvoiceCount(@RequestBody Map<String, String> params) {
        //将map变为map<String,Object>模式的
        return timedTaskService.getSpecialCount(params);
    }


    /**
     * 在职员工人数
     */
    @SaIgnore
    @PostMapping("/getEmployeecount")
    public long getEmployeecount(@RequestBody Long accountSetsId) {
        return timedTaskService.getEmployeecount(accountSetsId);
    }

    /**
     * 获取价税合计
     */
    @SaIgnore
    @PostMapping("/getInvoiceAmount")
    public Map<String, String> getInvoiceAmount(@RequestBody Long accountSetsId) {
        return timedTaskService.getInvoiceAmount(accountSetsId);
    }

    /**
     * 获取报税条目截图
     */
    @SaIgnore
    @PostMapping("/addTaxReportResult")
    public void addTaxReportResult(@RequestBody TaxReportResult result) {
        timedTaskService.insertTaxReportResult(result);
    }

    /**
     * 获取税款截图的企业
     */
    @SaIgnore
    @GetMapping("/getTaxPaymentCompany")
    public TimedTaskOfPaymentResp getTaxPaymentCompany() {
        return timedTaskService.getTaxPaymentCompany();
    }

    @SaIgnore
    @PostMapping("/addTimedTaskInvoice")
    public JsonResult addTimedTaskInvoice(@RequestBody AddTimedTaskInvoiceReq req) {
        return JsonResult.successful(timedTaskService.addTimedTaskInvoice(req));
    }

    /**
     * 获取每个账套的发票情况 财务系统用
     *
     * @param user
     * @return
     */
    @GetMapping("/getInvoiceTaskDetail")
    public JsonResult getInvoiceTaskDetail(@SessionUser UserVo user, String currentBillDate, String companyName, String vatType, Integer result, String province) {
        List<AccountSets> accountSetsList = user.getAccountSetsList();
        List<GetInvoiceTaskDetailResp> invoiceTaskDetail = timedTaskService.getInvoiceTaskDetail(accountSetsList, currentBillDate, companyName, vatType, result, province);
        return JsonResult.successful(invoiceTaskDetail);
    }

    /**
     * 获取发票销项/进项汇总信息
     *
     * @param user               当前登录用户
     * @param startAssignmentDate 发票分配开始日期（yyyy-MM-dd）
     * @param endAssignmentDate   发票分配结束日期（yyyy-MM-dd）
     * @param checkDate           取票任务账期（与定时任务表 check_date 格式一致）
     * @param vatType             增值税类型（如 COMMONLY）
     */
    @GetMapping("/getInvoiceTaxSummary")
    public JsonResult getInvoiceTaxSummary(@SessionUser UserVo user,
                                           String startAssignmentDate,
                                           String endAssignmentDate,
                                           String checkDate,
                                           String vatType) {
        if (StringUtils.isBlank(vatType)) {
            vatType = VatType.COMMONLY.name();
        }
        return JsonResult.successful(
                timedTaskService.getInvoiceTaxSummary(user.getId(), startAssignmentDate, endAssignmentDate, checkDate, vatType)
        );
    }

    /**
     * 根据账期查询报税任务表，每个账套id组中id最大的记录，只查询state为0的记录，只返回一条
     */
    @SaIgnore
    @GetMapping("/selectTimedTaskTaxByCheckDate")
    public JsonResult selectTimedTaskTaxByCheckDate(String checkDate, String province) {
        LocalDate date = LocalDate.parse(checkDate);
        return JsonResult.successful(timedTaskService.selectTimedTaskTaxByCheckDate(date, province));
    }

    /**
     * 根据账期查询报税项任务表，每个账套id组中id最大的记录，只查询state为0的记录，只返回一条
     */
    @SaIgnore
    @GetMapping("/selectTimedTaskTaxItemByCheckDate")
    public JsonResult selectTimedTaskTaxItemByCheckDate(String checkDate, String province) {
        LocalDate date = LocalDate.parse(checkDate);
        return JsonResult.successful(timedTaskService.selectTimedTaskTaxItemByCheckDate(date, province));
    }

    /**
     * 根据账期查询税款表，每个账套id组中id最大的记录，只查询state为0的记录，只返回一条
     */
    @SaIgnore
    @GetMapping("/selectTimedTaskPaymentByCheckDate")
    public JsonResult selectTimedTaskPaymentByCheckDate(String checkDate, String province) {
        LocalDate date = LocalDate.parse(checkDate);
        return JsonResult.successful(timedTaskService.selectTimedTaskPaymentByCheckDate(date, province));
    }

    /**
     * 检查报税状态
     * 查询报税任务表的result字段是否为1，以及报税表的tax_state字段是否为"已申报"
     */
    @SaIgnore
    @PostMapping("/checkTaxStatus")
    public JsonResult checkTaxStatus(@RequestBody CheckTaxStatusReq req) {
        LocalDate checkDate = LocalDate.parse(req.getCheckDate());
        return JsonResult.successful(timedTaskService.checkTaxStatus(checkDate, req.getAccountSetsId(), req.getTaxType()));
    }

    /**
     * 查询自检项的的任务
     */
    @SaIgnore
    @PostMapping("/getSelfInspectionTask")
    public JsonResult getSelfInspectionTask(@RequestBody LocalDate period) {
        return JsonResult.successful(timedTaskService.getSelfInspectionTask(period));
    }

    /**
     * 更新自检项的状态
     */
    @SaIgnore
    @PostMapping("/updateSelfInspectionTaskStatus")
    public JsonResult updateSelfInspectionTask(@RequestBody TimedTaskDeclareSelfInspectionReq req) {
        return JsonResult.successful(timedTaskService.updateSelfInspectionTaskStatus(req));
    }

    /**
     * 获取手机号的办税情况
     *
     * @param flag 1-手机号1(18963408530 小程序 娄朝), 2-手机号2(19906406841 小程序 王兴鹏), 3-手机号3(18006443296 本地 张宝)
     * @return 办税情况列表
     */
    @SaIgnore
    @PostMapping("/getTaxPayerStatus")
    public JsonResult getTaxPayer1Status(@RequestBody String flag) {
        TaxPayerStatusResp result = timedTaskService.getTaxPayerStatus(flag);
        if (result == null) {
            return JsonResult.failure("无该配置项，手机号");
        }
        return JsonResult.successful(result);
    }

    /**
     * 根据flag来修改办税表，更改该手机字段是否是否是办税员的状态
     */
    @SaIgnore
    @PostMapping("/updateTaxPayerStatus")
    public JsonResult updateTaxPayerStatus(@RequestBody TaxPayerStatusReq taxPayerStatusReq) {
        Boolean result = timedTaskService.updateTaxPayerStatus(taxPayerStatusReq);
        if (result == null) {
            return JsonResult.failure("无该配置项，手机号");
        }
        return JsonResult.successful(result);
    }

    /**
     * 为手机号添加办税，这个是根据一个拥有办税的手机号为另一个手机号添加办税
     *
     * @param flag 1-手机号1(18963408530 小程序 娄朝), 2-手机号2(19906406841 小程序 王兴鹏), 3-手机号3(18006443296 本地 张宝)
     * @return 添加办税情况列表
     */
    @SaIgnore
    @PostMapping("/addTaxPayer")
    public JsonResult addTaxPayer(@RequestBody String flag) {
        AddTaxPayerResp result = timedTaskService.addTaxPayer(flag);
        if (result == null) {
            return JsonResult.failure("无该配置项，手机号");
        }
        return JsonResult.successful(result);
    }

    /**
     * 查询代理渠道信息（需申报但尚未申报的账套列表）
     */
    @SaIgnore
    @PostMapping("/getAgentChannelInformation")
    public JsonResult getAgentChannelInformation(@RequestBody GetAgentChannelInfoReq req) {
        return JsonResult.successful(timedTaskService.getAgentChannelInformation(req));
    }
}
