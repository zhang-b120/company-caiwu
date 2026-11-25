package cn.gson.financial.controller;

import cn.dev33.satoken.annotation.SaIgnore;
import cn.dev33.satoken.stp.StpUtil;
import cn.gson.financial.kernel.annotation.SessionAccountSet;
import cn.gson.financial.kernel.annotation.SessionUser;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.controller.Page;
import cn.gson.financial.kernel.controller.PageResults;
import cn.gson.financial.kernel.model.dto.*;
import cn.gson.financial.kernel.model.entity.AccountSets;
import cn.gson.financial.kernel.model.entity.Invoice;
import cn.gson.financial.kernel.model.entity.InvoiceBusinessType;
import cn.gson.financial.kernel.model.entity.InvoiceItems;
import cn.gson.financial.kernel.model.entity.QAccountSets;
import cn.gson.financial.kernel.model.entity.QInvoice;
import cn.gson.financial.kernel.model.entity.QInvoiceBusinessType;
import cn.gson.financial.kernel.model.entity.QInvoiceItems;
import cn.gson.financial.kernel.model.entity.QSubject;
import cn.gson.financial.kernel.model.entity.QVoucherWord;
import cn.gson.financial.kernel.model.entity.Subject;
import cn.gson.financial.kernel.model.entity.VoucherWord;
import cn.gson.financial.kernel.model.enums.InvoiceType;
import cn.gson.financial.kernel.model.enums.VatType;
import cn.gson.financial.kernel.model.hibernate.BlazeJPAQueryFactory;
import cn.gson.financial.kernel.model.vo.SubjectVo;
import cn.gson.financial.kernel.model.vo.UserVo;
import cn.gson.financial.kernel.service.*;
import cn.gson.financial.kernel.tool.ImportVoUtil;
import cn.gson.financial.rpa.TaxBrowserManager;
import cn.gson.financial.timedTasks.service.TimedTaskService;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.io.IoUtil;
import cn.hutool.core.io.resource.ResourceUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.poi.excel.ExcelUtil;
import cn.hutool.poi.excel.ExcelWriter;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.poi.ss.usermodel.DataFormat;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.HashSet;
import java.util.Set;

import static cn.gson.financial.kernel.common.Constants.S_USER_KEY;


/**
 *
 * <p><b>Copyright © 2010-2021开发公司All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : (Invoice)</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2022-12-21 15:39:47</li>
 * <li>@author     : 开发者</li>
 * </ul>
 *
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/invoice")
public class InvoiceController extends AbsService{

    @Resource
    @Lazy
    protected BlazeJPAQueryFactory bqf;
    private final InvoiceService invoiceService;

    private final InvoiceImportService invoiceImportService;

    private final BalanceCalcService balanceCalcService;

    private final TaxBrowserManager taxBrowserManager;

    private final AllTaskService allTaskService;

    private final AccountSetsService accountSetsService;

    private final TimedTaskService timedTaskService;

    private final InvoiceBusinessTypeService invoiceBusinessTypeService;

    private final SubjectService subjectService;

    private final VoucherWordService voucherWordService;

    private final VoucherService voucherService;

    private final CurrencyService currencyService;

    private final QAccountSets qAccountSets = QAccountSets.accountSets;

    private final QInvoice qInvoice = QInvoice.invoice;

    private final QInvoiceItems qInvoiceItems = QInvoiceItems.invoiceItems;

    private final QSubject qSubject = QSubject.subject;

    private final QInvoiceBusinessType qInvoiceBusinessType = QInvoiceBusinessType.invoiceBusinessType;

    @Value("${agent.deepseek.api-url}")
    private String deepseekApiUrl;

    @Value("${agent.deepseek.api-key}")
    private String deepseekApiKey;

    @Value("${agent.deepseek.model}")
    private String deepseekModel;

    @PostMapping("list/{type}")
    public JsonResult list(@SessionAccountSet AccountSets accountSets, @RequestBody InvoiceService.InvoiceQuery query, Page page, @PathVariable InvoiceType type, HttpServletResponse response) throws IOException {
        Long accountSetId = accountSets.getId();
        PageResults<InvoiceDto> list = invoiceService.list(type, accountSetId, page, query);

        if (query.download) {
            Collection<InvoiceDto> results = list.getResults();
            List<InvoiceExportItemsDto> items = new ArrayList<>();
            results.forEach(s -> {
                if (CollUtil.isNotEmpty(s.getItems())) {
                    s.getItems().forEach(ido -> {
                        InvoiceExportItemsDto itemsDto = BeanUtil.toBean(ido, InvoiceExportItemsDto.class);
                        itemsDto.setTaxRate(ido.getTaxRate());
                        itemsDto.setTradeDate(s.getTradeDate());
                        itemsDto.setInvoiceCode(s.getInvoiceCode());
                        itemsDto.setInvoiceNumber(s.getInvoiceNumber());
                        itemsDto.setCategory(s.getNewCategory());
                        items.add(itemsDto);
                    });
                }
            });
            ExcelWriter writer = ExcelUtil.getWriterWithSheet("发票信息");
            DataFormat dataFormat = writer.getWorkbook().createDataFormat();
            ImportVoUtil.setHeaderAlias(InvoiceDto.class, writer);
            writer.getStyleSet().getCellStyleForDate().setDataFormat(dataFormat.getFormat("yyyy-MM-dd"));
            writer.setOnlyAlias(true);
            writer.write(results);
            writer.setSheet("货物信息");
            // writer.clearHeaderAlias();
            ImportVoUtil.setHeaderAlias(InvoiceExportItemsDto.class, writer);
            writer.write(items);
            response.setContentType(ExcelUtil.XLSX_CONTENT_TYPE);
            writer.flush(response.getOutputStream()).close();
            return null;
        } else {
            InvoiceDto summary = invoiceService.summary(type, accountSetId, query);
            return JsonResult.successful()
                    .data("dataList", list)
                    .data("summary", summary);
        }
    }

    @GetMapping("{type}/goods/name")
    public JsonResult goodsNameList(@PathVariable InvoiceType type, @SessionAccountSet AccountSets accountSets) {
        return JsonResult.successful(invoiceService.goodsNameList(type, accountSets.getId()));
    }

    @PostMapping
    public JsonResult create(@RequestBody InvoiceDto invoice, @SessionAccountSet AccountSets accountSets) {
        invoice.setAccountSetsId(accountSets.getId());
        return JsonResult.successful(invoiceService.save(invoice));
    }

    @DeleteMapping()
    public JsonResult delete(@RequestParam("id") List<Long> invoiceId, @SessionAccountSet AccountSets accountSets) {
        invoiceService.delete(invoiceId, accountSets.getId());
        return JsonResult.successful();
    }

    @PostMapping("batch/{invoiceBusinessTypeId:\\d+}")
    public JsonResult batchUpdateItem(@RequestBody List<Long> invoiceId, @SessionAccountSet AccountSets accountSets, @PathVariable Long invoiceBusinessTypeId) {
        invoiceService.batchUpdateItem(invoiceId, invoiceBusinessTypeId, accountSets.getId());
        return JsonResult.successful();
    }

    @PostMapping("batch/check")
    public JsonResult batchCheck(@RequestBody List<Long> invoiceId, @SessionAccountSet AccountSets accountSets, @RequestParam LocalDate checkDate) {
        return JsonResult.successful(invoiceService.batchCheck(invoiceId, checkDate, accountSets.getId()));
    }

    @PostMapping("batch/un/check")
    public JsonResult batchUnCheck(@RequestBody List<Long> invoiceId, @SessionAccountSet AccountSets accountSets) {
        return JsonResult.successful(invoiceService.batchUnCheck(invoiceId, accountSets.getId()));
    }

    /**
     * 批量修改发票显示状态（普票 / 已勾选 / 未勾选）
     */
    @PostMapping("showStatus")
    public JsonResult batchUpdateShowStatus(@RequestBody Map<String, Object> body, @SessionAccountSet AccountSets accountSets) {
        Long accountSetId = accountSets.getId();
        Object idsObj = body.get("ids");
        String showStatus = body.get("showStatus") != null ? body.get("showStatus").toString().trim() : null;
        if (idsObj == null || StringUtils.isBlank(showStatus)) {
            return JsonResult.failure("参数不完整");
        }
        if (!(idsObj instanceof List)) {
            return JsonResult.failure("参数ids格式错误");
        }
        List<Long> ids = new java.util.ArrayList<>();
        for (Object o : (List<?>) idsObj) {
            if (o instanceof Number) {
                ids.add(((Number) o).longValue());
            } else if (o != null && StringUtils.isNotBlank(o.toString())) {
                try {
                    ids.add(Long.parseLong(o.toString()));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        if (ids.isEmpty()) {
            return JsonResult.failure("请选择要修改的发票");
        }
        long cnt = invoiceService.batchUpdateShowStatus(ids, showStatus, accountSetId);
        return JsonResult.successful().data("count", cnt);
    }

    @PostMapping("generate/voucher")
    public JsonResult generateVoucher(@SessionUser UserVo userVo, @RequestBody List<VoucherDto> voucherDtos, @SessionAccountSet AccountSets accountSet) {
        invoiceService.generateVoucher(voucherDtos, accountSet, userVo.getId(), false);
        voucherDtos.stream().map(VoucherDto::getVoucherDate).min(LocalDate::compareTo).ifPresent(date -> {
            balanceCalcService.calcSynchronization(accountSet.getId(), YearMonth.from(date));
        });
        return JsonResult.successful();
    }

    @GetMapping("template")
    public void template(HttpServletResponse response) throws IOException {
        InputStream stream = ResourceUtil.getStream("invoice/invoice.xlsx");
        response.setContentType(ExcelUtil.XLSX_CONTENT_TYPE);
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "inline;filename=" + java.net.URLEncoder.encode("发票导入模板.xlsx", StandardCharsets.UTF_8));
        response.getOutputStream().write(IoUtil.read(stream, true).toByteArray());
    }

    @PostMapping("import/{type}")
    public JsonResult importData(@PathVariable InvoiceType type, @RequestParam("file") MultipartFile multipartFile, @SessionAccountSet AccountSets accountSets) throws IOException {
        Assert.notEmpty(accountSets.getCreditCode(), "账套未设置统一社会信用代码，请先设置~");
        // 获取type对应的发票类型枚举值
        //将invoiceType转换为map
        Map<String, String> showStatusMap = new HashMap<>();
        showStatusMap.put("showStatus", type.display);
        List<InvoiceDto> invoiceDtos = invoiceImportService.saveImportInvoice(accountSets.getCreditCode(), multipartFile.getInputStream(), accountSets.getId() ,showStatusMap);
        Optional<LocalDate> startDate = invoiceDtos.stream().map(InvoiceDto::getTradeDate).min(LocalDate::compareTo);
        Optional<LocalDate> endDate = invoiceDtos.stream().map(InvoiceDto::getTradeDate).max(LocalDate::compareTo);
        return JsonResult.successful()
                .data("start", startDate.orElse(accountSets.getCurrentAccountDate()))
                .data("end", endDate.orElse(accountSets.getCurrentAccountDate().withDayOfMonth(accountSets.getCurrentAccountDate().lengthOfMonth())));
    }

    /**
     * 导入勾选结果（已勾选发票）
     * 前端上传税务系统导出的“已勾选”Excel
     */
    @PostMapping("import/check")
    public JsonResult importCheckedInvoice(@RequestParam("file") MultipartFile multipartFile) throws IOException {
        timedTaskService.saveGouXuanInvoice(multipartFile.getInputStream(), "已勾选");
        return JsonResult.successful();
    }

    /**
     * 导入未勾选结果（未勾选发票）
     * 前端上传税务系统导出的“未勾选”Excel
     */
    @PostMapping("import/unCheck")
    public JsonResult importUnCheckedInvoice(@RequestParam("file") MultipartFile multipartFile) throws IOException {
        timedTaskService.saveWeiGouXuanInvoice(multipartFile.getInputStream(), "未勾选");
        return JsonResult.successful();
    }

    //检测用户是否存在正在执行的任务

    public JsonResult checnIsLoginStatus(@SessionUser UserVo userVo){
//        return JsonResult.successful(allTaskService.checkIsLoginStatus(userVo.getId()));

        return null;
    }

    /**
     * 判断是否登录，如果需要登录获取验证码
     * @return
     */
    @PostMapping("checkIsLoginStatus")
    public JsonResult checkIsLoginStatus(@RequestBody Map<String,String> map){
        UserVo userVo = (UserVo)StpUtil.getSession().get(S_USER_KEY);
        map.put("phoneNumber",userVo.getMobile());//登录手机号
        map.put("creditCode",userVo.getAccountSets().getCreditCode());//税号
        map.put("password",userVo.getAccountSets().getElectricityTaxCode());//点税密码
        map.put("province",userVo.getAccountSets().getProvince());//省份
        map.put("electricityTaxPhone",String.valueOf(userVo.getAccountSets().getElectricityTaxPhone()));//账套id
        return JsonResult.successful(taxBrowserManager.checkIsLoginStatus(map));
    }
    /**
     * 判断是否登录，如果需要登录获取验证码
     * @return
     */
    @PostMapping("newCheckIsLoginStatus")
    public JsonResult newCheckIsLoginStatus(@RequestBody Map<String,String> map){
        UserVo userVo = (UserVo) StpUtil.getSession().get(S_USER_KEY);
        map.put("phoneNumber",userVo.getMobile());//登录手机号
            map.put("province","shandong");
            map.put("proxyLoginPhone",map.get("proxyLoginPhone"));
        return JsonResult.successful(taxBrowserManager.newCheckIsLoginStatus(map));
    }



    /**
     * 获取验证码，并进行验证
     * @param map
     * @return
     */
    @PostMapping("checkLoginCode")
    public JsonResult checkLoginCode(@RequestBody Map<String,String> map){
        UserVo userVo = (UserVo)StpUtil.getSession().get(S_USER_KEY);
        map.put("phoneNumber",userVo.getMobile());//登录手机号
        map.put("creditCode",userVo.getAccountSets().getCreditCode());//税号
        map.put("password",userVo.getAccountSets().getElectricityTaxCode());//点税密码
        map.put("province",userVo.getAccountSets().getProvince());//省份
        return JsonResult.successful(taxBrowserManager.checkLoginCode(map));
    }
    /**
     * 判断是否登录，如果需要登录获取验证码
     * @return
     */
    @PostMapping("newCheckLoginCode")
    public JsonResult newCheckLoginCode(@RequestBody Map<String,String> map){
//        map.put("phoneNumber",userVo.getMobile());//登录手机号
//        map.put("creditCode",userVo.getAccountSets().getCreditCode());//税号
//        map.put("password",userVo.getAccountSets().getElectricityTaxCode());//点税密码
//        map.put("province",userVo.getAccountSets().getProvince());//省份
//        map.put("electricityTaxPhone",userVo.getAccountSets().getElectricityTaxPhone().toString());//账套id

        map.put("province","shandong");
        map.put("electricityTaxPhone",map.get("proxyLoginPhone"));
        UserVo userVo = (UserVo)StpUtil.getSession().get(S_USER_KEY);
        map.put("phoneNumber",userVo.getMobile());
        if(userVo.getMobile().equals(map.get("proxyLoginPhone"))){
            return JsonResult.successful(taxBrowserManager.checkLoginCode(map));
        }
            return JsonResult.successful(taxBrowserManager.newCheckLoginCode(map));
    }


    /**
     * 下载发票
     * @return
     */
    @PostMapping("downInvoice")
    public JsonResult downInvoice(@RequestBody Map<String,String> map,@SessionAccountSet AccountSets accountSets){
        UserVo userVo = (UserVo)StpUtil.getSession().get(S_USER_KEY);
        map.put("phoneNumber",userVo.getMobile());//登录手机号
        map.put("creditCode",userVo.getAccountSets().getCreditCode());//税号
        map.put("password",userVo.getAccountSets().getElectricityTaxCode());//点税密码
        map.put("province",userVo.getAccountSets().getProvince());//省份
        map.put("accountSetsId",userVo.getAccountSets().getId().toString());//账套id
        return JsonResult.successful(taxBrowserManager.downInvoice(map,accountSets));
    }

    @PostMapping("reDownInvoice")
    public JsonResult reDownInvoice(@RequestBody Map<String,Object> map,@SessionUser UserVo userVo) throws JsonProcessingException {
        AllTaskDto allTaskDto = new AllTaskDto();
        String taskItemId = "";
        taskItemId =  map.get("taskItemId").toString();

        String taskParamsJson = (String) map.get("taskParams");
        ObjectMapper objectMapper = new ObjectMapper();
        Map<String, Object> taskParamsMap = objectMapper.readValue(taskParamsJson, new TypeReference<Map<String, Object>>() {});
        String companyName = map.get("companyName").toString();
        String taskId ="";
        taskId =  taskParamsMap.get("taskId").toString();

        allTaskDto.setTaskItemId(Long.valueOf(taskItemId));
        allTaskDto.setTaskId(Long.valueOf(taskId));

        String invoiceType =  taskParamsMap.get("invoiceType").toString();
        Map<String,String> result = new HashMap<>();
        Map<String,String> params = new HashMap<>();
        params.put("phoneNumber",userVo.getMobile());//登录手机号
        params.put("creditCode",taskParamsMap.get("creditCode").toString());//税号
        params.put("password",taskParamsMap.get("password").toString());//点税密码
        params.put("province",taskParamsMap.get("province").toString());//省份
        params.put("accountSetsId",taskParamsMap.get("accountSetsId").toString());//账套id
        params.put("startDate",taskParamsMap.get("startDate").toString());
        params.put("endDate",taskParamsMap.get("endDate").toString());
        AccountSets accountSets = new AccountSets();
        accountSets.setId(Long.parseLong(taskParamsMap.get("accountSetsId").toString()));
        params.put("invoiceType",invoiceType);
        accountSets.setCreditCode(taskParamsMap.get("creditCode").toString());

        if (invoiceType.equals("已勾选")&&taskParamsMap.get("vatType").equals("COMMONLY")){// invoiceType ["已勾选", "未勾选", "开具发票","取得发票"]
            result = taxBrowserManager.downgouxuanInvoice(params,accountSets);
        }else if(invoiceType.equals("开具发票")||invoiceType.equals("取得发票")){
            result = taxBrowserManager.downInvoice(params,accountSets);
        }else if(invoiceType.equals("未勾选")&&accountSets.getVatType().name().equals("COMMONLY")){
            result = taxBrowserManager.downweigouxuanInvoice(params,accountSets);
        }

        if("error".equals(result.get("status"))){
            allTaskDto.setTaskItemStatus(0);
            allTaskDto.setTaskFailCount(0);
        }else{
            allTaskDto.setTaskItemStatus(1);
            allTaskDto.setTaskSuccessCount(1);
        }
        allTaskDto.setTaskItemLog("[重新下载]"+result.get("msg"));
        allTaskService.reUpdateItemById(allTaskDto);
        allTaskService.reUpdateAllById(allTaskDto);
       return JsonResult.successful();
    }

    @PostMapping("/updateInvoiceAssignmentDate")
    public JsonResult updateInvoiceAssignmentDate(@RequestBody List<Invoice> invoices) {
        //遍历invoices集合然后执行invoiceService.updateInvoiceAssignmentDate(invoice)
        invoices.forEach(invoice -> {
            invoiceService.updateInvoiceAssignmentDate(invoice);
        });
        return JsonResult.successful();
    }

    @PostMapping("downInvoiceAll")
    public JsonResult downInvoiceAll(@RequestBody Map<String,Object> map,@SessionUser UserVo userVo){
        List<String> invoceTypeList = (List<String>)map.get("invoiceType");
        String list = map.get("list").toString();
        List<AccountSets> accountSetsList = JSONArray.parseArray(list,AccountSets.class);
        AllTaskDto allTaskDto = new AllTaskDto();
        allTaskDto.setTaskTime(
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        );
        allTaskDto.setTaskType(String.valueOf(invoceTypeList));
        allTaskDto.setTaskAllCount(accountSetsList.size()*invoceTypeList.size());
        allTaskDto.setTaskCreaterName(userVo.getMobile());
        Long taskId = allTaskService.insert(allTaskDto);
        int successCount = 0;
        int failCount = 0;

       for(AccountSets accountSets:accountSetsList){
           for(String invoiceType:invoceTypeList){
               Map<String,String> taskItemParams = new HashMap<>();
               taskItemParams.put("phoneNumber",userVo.getMobile());//登录手机号
               taskItemParams.put("creditCode",accountSets.getCreditCode());//税号
               taskItemParams.put("password",accountSets.getElectricityTaxCode());//点税密码
               taskItemParams.put("province",accountSets.getProvince());//省份
               taskItemParams.put("accountSetsId",accountSets.getId().toString());//账套id
               taskItemParams.put("startDate",map.get("startDate").toString());
               taskItemParams.put("endDate",map.get("endDate").toString());
               taskItemParams.put("invoiceType",invoiceType);
               taskItemParams.put("taskId",taskId.toString());
               taskItemParams.put("vatType",accountSets.getVatType().name());
               taskItemParams.put("invoiceSaveType",map.get("invoiceSaveType").toString());
               //将taskItemParams转为JSON字符串的数据存放在TaskParams里
               String taskItemParamsJson = JSONObject.toJSONString(taskItemParams);
               allTaskDto.setTaskParams(taskItemParamsJson);
               // 获取任务执行标记
               allTaskDto.setAccountSetId(accountSets.getId());
               allTaskDto.setCompanyName(accountSets.getCompanyName());
               Map<String,String> params = new HashMap<>();
               params.put("phoneNumber",userVo.getMobile());//登录手机号
               params.put("creditCode",accountSets.getCreditCode());//税号
               params.put("password",accountSets.getElectricityTaxCode());//点税密码
               params.put("province",accountSets.getProvince());//省份
               params.put("accountSetsId",accountSets.getId().toString());//账套id
               params.put("startDate",map.get("startDate").toString());
               params.put("endDate",map.get("endDate").toString());
               params.put("invoiceType",invoiceType);//修改-----------------
               params.put("invoiceSaveType",map.get("invoiceSaveType").toString());
                 Map<String,String> result = new HashMap<>();
               if (invoiceType.equals("已勾选")&&accountSets.getVatType().name().equals("COMMONLY")){// invoiceType ["已勾选", "未勾选", "开具发票","取得发票"]
                   result = taxBrowserManager.downgouxuanInvoice(params,accountSets);
               }else if(invoiceType.equals("开具发票")||invoiceType.equals("取得发票")){
                   result = taxBrowserManager.downInvoice(params,accountSets);
               }else if(invoiceType.equals("未勾选")&&accountSets.getVatType().name().equals("COMMONLY")){
                   result = taxBrowserManager.downweigouxuanInvoice(params,accountSets);
               }

               if("error".equals(result.get("status"))){
                   allTaskDto.setTaskItemStatus(0);
                   allTaskDto.setTaskFailCount(++failCount);
               }else{
                   allTaskDto.setTaskItemStatus(1);
                   allTaskDto.setTaskSuccessCount(++successCount);
               }
               allTaskDto.setTaskItemLog(result.get("msg"));
               allTaskService.updateById(allTaskDto);
               allTaskDto.setInvoiceStartDate(LocalDate.parse(map.get("startDate").toString()));
               allTaskDto.setInvoiceEndDate(LocalDate.parse(map.get("endDate").toString()));
               allTaskDto.setInvoiceType(invoiceType);
               allTaskService.insertItem(allTaskDto);
           }
       }
       return null;
    }


    @PostMapping("getInvoiceLog")
    public JsonResult getInvoiceLog(@RequestBody AllTaskDto allTaskDto){
        return JsonResult.successful(allTaskService.selectAllList(allTaskDto));
    }

    @PostMapping("getInvoiceLogItem")
    public JsonResult getInvoiceLogItem(@RequestBody AllTaskDto allTaskDto){
        return JsonResult.successful(allTaskService.selectItemList(allTaskDto));
    }


    @PostMapping("doTask")
    public JsonResult doTask(@SessionUser UserVo user){
        taxBrowserManager.importAccountSet(user);
        return JsonResult.successful();
    }

    /**
     * 获取每个账套的发票情况
     * @param
     * @return
     */
    @SaIgnore
    @GetMapping("/invoiceDetails/getDataList")
    public JsonResult getDataList(String electricityTaxPhone,String currentBillDate,String province){
        Map<String,Object> params = new HashMap<>();
        params.put("electricityTaxPhone",electricityTaxPhone);
        params.put("province",province);
        List<AccountSets> AccountSetsList =  lazyDao.findBySql("getAccountSet",params,AccountSets.class);
        return  JsonResult.successful(invoiceService.getDataList(AccountSetsList, currentBillDate));
    }

    /**
     * 获取每个账套的发票情况 财务系统用
     * @param user
     * @return
     */
    @GetMapping("/invoiceDetails/getDataListOld")
    public JsonResult getDataListOld(@SessionUser UserVo user,String currentBillDate){
        List<AccountSets> AccountSetsList = user.getAccountSetsList();
        return  JsonResult.successful(invoiceService.getDataList(AccountSetsList, currentBillDate));
    }

    @SaIgnore
    @PostMapping("/changeVoucher")
    public JsonResult changeVoucher(@RequestParam(required = false) String companyName,@RequestParam String province){
        List<Long> accountSetId = accountSetsService.getAccountSetsIdByCompanyName(province,companyName);

        // 判断返回的集合是否为空
        if (CollUtil.isEmpty(accountSetId)) {
            return JsonResult.failure("未找到对应的账套信息");
        }

        // 循环调用invoiceService.changeVoucher(accountSetId)
        for (Long id : accountSetId) {
            invoiceService.changeVoucher(id);
        }

        return JsonResult.successful("凭证转换完成");
    }

    /**
     * 统计发票数量（用于做账弹窗显示）
     * @param accountSetId 账套ID
     * @param period 账期，格式：YYYY-MM 或 YYYY年MM期
     * @return 返回进项和销项发票的统计信息（数量、总金额、不含税金额、税额）
     */
    @GetMapping("/count/by-period")
    public JsonResult countByPeriod(@SessionAccountSet AccountSets accountSets, @RequestParam String period) {
        Long accountSetId = accountSets.getId();
        try {
            // 解析账期
            LocalDate periodDate;
            if (period.contains("年") && period.contains("期")) {
                // 格式：YYYY年MM期
                periodDate = LocalDate.parse(period, DateTimeFormatter.ofPattern("yyyy年MM期"));
            } else if (period.contains("-")) {
                // 格式：YYYY-MM
                periodDate = LocalDate.parse(period + "-01");
            } else {
                return JsonResult.failure("账期格式不正确，支持格式：YYYY-MM 或 YYYY年MM期");
            }

            // 计算账期的开始和结束日期
            LocalDate periodStart = periodDate.with(TemporalAdjusters.firstDayOfMonth());
            LocalDate periodEnd = periodDate.with(TemporalAdjusters.lastDayOfMonth());

            log.info("统计发票数量 - 账套ID: {}, 账期: {}, 日期范围: {} 至 {}", accountSetId, period, periodStart, periodEnd);

            // 查询进项发票统计信息（使用assignmentDate字段，如果没有数据则尝试使用tradeDate）
            com.querydsl.core.Tuple inputTuple = bqf.selectFrom(qInvoice)
                    .select(
                            qInvoice.count(),
                            qInvoice.amount.sum().coalesce(BigDecimal.ZERO),
                            qInvoice.taxAmountExcluded.sum().coalesce(BigDecimal.ZERO),
                            qInvoice.taxAmount.sum().coalesce(BigDecimal.ZERO)
                    )
                    .where(qInvoice.accountSetsId.eq(accountSetId)
                            .and(qInvoice.type.eq(InvoiceType.IN))
                            .and(qInvoice.assignmentDate.between(periodStart, periodEnd)))
                    .fetchFirst();

            // 如果使用assignmentDate没有数据，尝试使用tradeDate查询
            if (inputTuple == null || (inputTuple.get(qInvoice.count()) != null && inputTuple.get(qInvoice.count()) == 0)) {
                log.info("使用assignmentDate未查询到进项发票，尝试使用tradeDate查询");
                inputTuple = bqf.selectFrom(qInvoice)
                        .select(
                                qInvoice.count(),
                                qInvoice.amount.sum().coalesce(BigDecimal.ZERO),
                                qInvoice.taxAmountExcluded.sum().coalesce(BigDecimal.ZERO),
                                qInvoice.taxAmount.sum().coalesce(BigDecimal.ZERO)
                        )
                        .where(qInvoice.accountSetsId.eq(accountSetId)
                                .and(qInvoice.type.eq(InvoiceType.IN))
                                .and(qInvoice.tradeDate.between(periodStart, periodEnd)))
                        .fetchFirst();
            }

            log.info("进项发票查询结果: {}", inputTuple != null ?
                    String.format("count=%s, amount=%s, taxExcluded=%s, tax=%s",
                            inputTuple.get(qInvoice.count()),
                            inputTuple.get(qInvoice.amount.sum().coalesce(BigDecimal.ZERO)),
                            inputTuple.get(qInvoice.taxAmountExcluded.sum().coalesce(BigDecimal.ZERO)),
                            inputTuple.get(qInvoice.taxAmount.sum().coalesce(BigDecimal.ZERO))) : "null");

            // 查询销项发票统计信息（使用assignmentDate字段，如果没有数据则尝试使用tradeDate）
            com.querydsl.core.Tuple outputTuple = bqf.selectFrom(qInvoice)
                    .select(
                            qInvoice.count(),
                            qInvoice.amount.sum().coalesce(BigDecimal.ZERO),
                            qInvoice.taxAmountExcluded.sum().coalesce(BigDecimal.ZERO),
                            qInvoice.taxAmount.sum().coalesce(BigDecimal.ZERO)
                    )
                    .where(qInvoice.accountSetsId.eq(accountSetId)
                            .and(qInvoice.type.eq(InvoiceType.OUT))
                            .and(qInvoice.assignmentDate.between(periodStart, periodEnd)))
                    .fetchFirst();

            // 如果使用assignmentDate没有数据，尝试使用tradeDate查询
            if (outputTuple == null || (outputTuple.get(qInvoice.count()) != null && outputTuple.get(qInvoice.count()) == 0)) {
                log.info("使用assignmentDate未查询到销项发票，尝试使用tradeDate查询");
                outputTuple = bqf.selectFrom(qInvoice)
                        .select(
                                qInvoice.count(),
                                qInvoice.amount.sum().coalesce(BigDecimal.ZERO),
                                qInvoice.taxAmountExcluded.sum().coalesce(BigDecimal.ZERO),
                                qInvoice.taxAmount.sum().coalesce(BigDecimal.ZERO)
                        )
                        .where(qInvoice.accountSetsId.eq(accountSetId)
                                .and(qInvoice.type.eq(InvoiceType.OUT))
                                .and(qInvoice.tradeDate.between(periodStart, periodEnd)))
                        .fetchFirst();
            }

            log.info("销项发票查询结果: {}", outputTuple != null ?
                    String.format("count=%s, amount=%s, taxExcluded=%s, tax=%s",
                            outputTuple.get(qInvoice.count()),
                            outputTuple.get(qInvoice.amount.sum().coalesce(BigDecimal.ZERO)),
                            outputTuple.get(qInvoice.taxAmountExcluded.sum().coalesce(BigDecimal.ZERO)),
                            outputTuple.get(qInvoice.taxAmount.sum().coalesce(BigDecimal.ZERO))) : "null");

            Map<String, Object> result = new HashMap<>();

            // 进项发票统计
            Map<String, Object> inputStats = new HashMap<>();
            if (inputTuple != null) {
                inputStats.put("count", inputTuple.get(qInvoice.count()) != null ? inputTuple.get(qInvoice.count()) : 0L);
                inputStats.put("totalAmount", inputTuple.get(qInvoice.amount.sum().coalesce(BigDecimal.ZERO)) != null ?
                        inputTuple.get(qInvoice.amount.sum().coalesce(BigDecimal.ZERO)) : BigDecimal.ZERO);
                inputStats.put("taxExcludedAmount", inputTuple.get(qInvoice.taxAmountExcluded.sum().coalesce(BigDecimal.ZERO)) != null ?
                        inputTuple.get(qInvoice.taxAmountExcluded.sum().coalesce(BigDecimal.ZERO)) : BigDecimal.ZERO);
                inputStats.put("taxAmount", inputTuple.get(qInvoice.taxAmount.sum().coalesce(BigDecimal.ZERO)) != null ?
                        inputTuple.get(qInvoice.taxAmount.sum().coalesce(BigDecimal.ZERO)) : BigDecimal.ZERO);
            } else {
                inputStats.put("count", 0L);
                inputStats.put("totalAmount", BigDecimal.ZERO);
                inputStats.put("taxExcludedAmount", BigDecimal.ZERO);
                inputStats.put("taxAmount", BigDecimal.ZERO);
            }

            // 销项发票统计
            Map<String, Object> outputStats = new HashMap<>();
            if (outputTuple != null) {
                outputStats.put("count", outputTuple.get(qInvoice.count()) != null ? outputTuple.get(qInvoice.count()) : 0L);
                outputStats.put("totalAmount", outputTuple.get(qInvoice.amount.sum().coalesce(BigDecimal.ZERO)) != null ?
                        outputTuple.get(qInvoice.amount.sum().coalesce(BigDecimal.ZERO)) : BigDecimal.ZERO);
                outputStats.put("taxExcludedAmount", outputTuple.get(qInvoice.taxAmountExcluded.sum().coalesce(BigDecimal.ZERO)) != null ?
                        outputTuple.get(qInvoice.taxAmountExcluded.sum().coalesce(BigDecimal.ZERO)) : BigDecimal.ZERO);
                outputStats.put("taxAmount", outputTuple.get(qInvoice.taxAmount.sum().coalesce(BigDecimal.ZERO)) != null ?
                        outputTuple.get(qInvoice.taxAmount.sum().coalesce(BigDecimal.ZERO)) : BigDecimal.ZERO);
            } else {
                outputStats.put("count", 0L);
                outputStats.put("totalAmount", BigDecimal.ZERO);
                outputStats.put("taxExcludedAmount", BigDecimal.ZERO);
                outputStats.put("taxAmount", BigDecimal.ZERO);
            }

            result.put("inputInvoice", inputStats);
            result.put("outputInvoice", outputStats);

            return JsonResult.successful(result);
        } catch (Exception e) {
            log.error("统计发票数量失败", e);
            return JsonResult.failure("统计发票数量失败: " + e.getMessage());
        }
    }

    /**
     * AI匹配发票业务类型（根据商品名称匹配）
     * @param accountSetId 账套ID
     * @param invoiceType 发票类型：IN-进项，OUT-销项
     * @param period 账期，格式：YYYY-MM 或 YYYY年MM期
     * @return 返回匹配结果（匹配数量、未匹配数量）
     */
    @PostMapping("/ai-match")
    public JsonResult aiMatch(@SessionAccountSet AccountSets accountSets, @RequestBody Map<String, String> params) {
        Long accountSetId = accountSets.getId();
        String invoiceType = params.get("invoiceType");
        String period = params.get("period");
        try {
            // 解析账期
            LocalDate periodDate;
            if (period.contains("年") && period.contains("期")) {
                periodDate = LocalDate.parse(period, DateTimeFormatter.ofPattern("yyyy年MM期"));
            } else if (period.contains("-")) {
                periodDate = LocalDate.parse(period + "-01");
            } else {
                return JsonResult.failure("账期格式不正确，支持格式：YYYY-MM 或 YYYY年MM期");
            }

            // 计算账期的开始和结束日期
            LocalDate periodStart = periodDate.with(TemporalAdjusters.firstDayOfMonth());
            LocalDate periodEnd = periodDate.with(TemporalAdjusters.lastDayOfMonth());

            InvoiceType type = InvoiceType.valueOf(invoiceType.toUpperCase());
            QInvoice qInvoice = QInvoice.invoice;

            // 查询该账期未匹配业务类型的发票ID列表
            List<Long> invoiceIds = bqf.selectFrom(qInvoice)
                    .select(qInvoice.id)
                    .where(qInvoice.accountSetsId.eq(accountSetId)
                            .and(qInvoice.type.eq(type))
                            .and(qInvoice.assignmentDate.between(periodStart, periodEnd))
                            .and(qInvoice.invoiceBusinessTypeId.isNull()))
                    .fetch();

            if (CollUtil.isEmpty(invoiceIds)) {
                Map<String, Object> result = new HashMap<>();
                result.put("matchedCount", 0);
                result.put("unmatchedCount", 0);
                result.put("message", "当前账期没有需要匹配的发票");
                return JsonResult.successful(result);
            }

            // 调用批量匹配服务
            JsonResult matchResult = invoiceBusinessTypeService.batchChangeBusinessType(invoiceIds, type, accountSetId);

            if (matchResult.isSuccess()) {
                @SuppressWarnings("unchecked")
                List<Long> unmatchedIds = (List<Long>) matchResult.getData();
                int matchedCount = invoiceIds.size() - (unmatchedIds != null ? unmatchedIds.size() : 0);
                int unmatchedCount = unmatchedIds != null ? unmatchedIds.size() : 0;

                Map<String, Object> result = new HashMap<>();
                result.put("matchedCount", matchedCount);
                result.put("unmatchedCount", unmatchedCount);
                result.put("totalCount", invoiceIds.size());
                result.put("message", String.format("匹配完成：成功匹配 %d 张，未匹配 %d 张", matchedCount, unmatchedCount));

                log.info("AI匹配完成 - 账套ID: {}, 发票类型: {}, 账期: {}, 匹配: {}, 未匹配: {}",
                        accountSetId, invoiceType, period, matchedCount, unmatchedCount);

                return JsonResult.successful(result);
            } else {
                return JsonResult.failure(matchResult.getMsg() != null ? matchResult.getMsg() : "匹配失败");
            }
        } catch (Exception e) {
            log.error("AI匹配发票业务类型失败", e);
            return JsonResult.failure("AI匹配失败: " + e.getMessage());
        }
    }

    /**
     * DeepSeek AI匹配发票业务类型（第二步：对未匹配的发票调用DeepSeek）
     * @param accountSetId 账套ID
     * @param invoiceType 发票类型：IN-进项，OUT-销项
     * @param period 账期，格式：YYYY-MM 或 YYYY年MM期
     * @return 返回匹配结果（匹配数量、未匹配数量）
     */
    @PostMapping("/ai-match-deepseek")
    @Transactional
    public JsonResult aiMatchDeepSeek(@SessionAccountSet AccountSets sessionAccountSets, @RequestBody Map<String, String> params) {
        Long accountSetId = sessionAccountSets.getId();
        String invoiceType = params.get("invoiceType");
        String period = params.get("period");
        try {
            // 只处理进项发票
            InvoiceType type = InvoiceType.valueOf(invoiceType.toUpperCase());
            if (type != InvoiceType.IN) {
                return JsonResult.failure("当前只支持进项发票的AI匹配");
            }

            // 解析账期
            LocalDate periodDate;
            if (period.contains("年") && period.contains("期")) {
                periodDate = LocalDate.parse(period, DateTimeFormatter.ofPattern("yyyy年MM期"));
            } else if (period.contains("-")) {
                periodDate = LocalDate.parse(period + "-01");
            } else {
                return JsonResult.failure("账期格式不正确，支持格式：YYYY-MM 或 YYYY年MM期");
            }

            // 计算账期的开始和结束日期
            LocalDate periodStart = periodDate.with(TemporalAdjusters.firstDayOfMonth());
            LocalDate periodEnd = periodDate.with(TemporalAdjusters.lastDayOfMonth());

            QInvoice qInvoice = QInvoice.invoice;

            // 查询该账期未匹配业务类型的进项发票（必须有商品明细）
            List<Invoice> unmatchedInvoices = bqf.selectFrom(qInvoice)
                    .where(qInvoice.accountSetsId.eq(accountSetId)
                            .and(qInvoice.type.eq(InvoiceType.IN))
                            .and(qInvoice.assignmentDate.between(periodStart, periodEnd))
                            .and(qInvoice.invoiceBusinessTypeId.isNull())
                            .and(qInvoice.haveDetail.isTrue())) // 必须有商品明细
                    .fetch();

            if (CollUtil.isEmpty(unmatchedInvoices)) {
                Map<String, Object> result = new HashMap<>();
                result.put("matchedCount", 0);
                result.put("unmatchedCount", 0);
                result.put("message", "当前账期没有需要匹配的进项发票");
                return JsonResult.successful(result);
            }

            // 获取账套信息（企业信息）
            AccountSets accountSets = bqf.selectFrom(qAccountSets)
                    .where(qAccountSets.id.eq(accountSetId))
                    .fetchFirst();

            if (accountSets == null) {
                return JsonResult.failure("账套信息不存在");
            }

            // 获取科目信息（用于DeepSeek选择科目）
            List<SubjectVo> subjects = subjectService.list(accountSetId);

            // 查找应付账款科目（用于自动生成应付账款-供应商）
            Subject payableSubject = bqf.selectFrom(qSubject)
                    .where(qSubject.accountSetsId.eq(accountSetId)
                            .and(qSubject.fullName.eq("应付账款")))
                    .fetchFirst();

            if (payableSubject == null) {
                return JsonResult.failure("账套中未找到应付账款科目，无法生成会计分录");
            }

            // 获取凭证字：根据账套ID查询凭证字表，优先使用默认凭证字
            QVoucherWord qVoucherWord = QVoucherWord.voucherWord;
            VoucherWord defaultVoucherWord = bqf.selectFrom(qVoucherWord)
                    .where(qVoucherWord.accountSetsId.eq(accountSetId)
                            .and(qVoucherWord.isDefault.eq(true)))
                    .fetchFirst();

            // 如果没有默认凭证字，则获取第一个凭证字（按默认排序）
            if (defaultVoucherWord == null) {
                defaultVoucherWord = bqf.selectFrom(qVoucherWord)
                        .where(qVoucherWord.accountSetsId.eq(accountSetId))
                        .orderBy(qVoucherWord.isDefault.desc(), qVoucherWord.id.asc())
                        .fetchFirst();
            }

            // 记录凭证字信息（用于调试）
            if (defaultVoucherWord != null) {
                log.info("为账套ID: {} 找到凭证字，ID: {}, 凭证字名称: {}",
                        accountSetId, defaultVoucherWord.getId(), defaultVoucherWord.getWord());
            } else {
                log.warn("账套ID: {} 没有找到任何凭证字", accountSetId);
            }

            // 构建DeepSeek请求
            RestTemplate restTemplate = new RestTemplate();
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(deepseekApiKey);

            int matchedCount = 0;
            int failedCount = 0;

            // 一张发票一个请求，逐个处理
            for (Invoice invoice : unmatchedInvoices) {
                try {
                    // 查询发票的商品明细
                    List<InvoiceItems> items = bqf.selectFrom(qInvoiceItems)
                            .where(qInvoiceItems.invoiceId.eq(invoice.getId()))
                            .fetch();

                    if (CollUtil.isEmpty(items)) {
                        log.warn("发票ID: {} 没有商品明细，跳过", invoice.getId());
                        failedCount++;
                        continue;
                    }

                    // 获取供应商名称（进项发票的customerName是供应商）
                    String supplierName = invoice.getCustomerName();
                    if (StringUtils.isBlank(supplierName)) {
                        log.warn("发票ID: {} 没有供应商名称，跳过", invoice.getId());
                        failedCount++;
                        continue;
                    }

                    // 重试机制：如果借贷不平衡，重新生成（最多重试3次）
                    int maxRetries = 3;
                    int retryCount = 0;
                    boolean success = false;
                    String lastBalanceError = null;

                    while (retryCount < maxRetries && !success) {
                        // 构建prompt（单张发票）
                        StringBuilder promptBuilder = new StringBuilder();

                        // 如果是重试，添加之前的错误信息
                        if (retryCount > 0 && StringUtils.isNotBlank(lastBalanceError)) {
                            promptBuilder.append("【重要】之前的生成结果借贷不平衡，请重新生成正确的会计分录。\n");
                            promptBuilder.append("之前的错误：").append(lastBalanceError).append("\n");
                            promptBuilder.append("请务必确保借贷平衡，严格按照要求生成会计分录。\n\n");
                        }
                    promptBuilder.append("你是一个财务专家，需要根据企业信息、供应商信息和商品明细，为进项发票匹配合适的业务类型和会计分录。\n\n");

                    // 企业信息
                    promptBuilder.append("企业信息：\n");
                    promptBuilder.append("企业名称：").append(accountSets.getCompanyName()).append("\n");
                    if (StringUtils.isNotBlank(accountSets.getCreditCode())) {
                        promptBuilder.append("税号：").append(accountSets.getCreditCode()).append("\n");
                    }
                    if (StringUtils.isNotBlank(accountSets.getProvince())) {
                        promptBuilder.append("省份：").append(accountSets.getProvince()).append("\n");
                    }
                    // 添加会计类型信息
                    if (accountSets.getVatType() != null) {
                        String vatTypeDisplay = accountSets.getVatType().display;
                        promptBuilder.append("会计类型：").append(vatTypeDisplay).append("\n");
                    }
                    promptBuilder.append("\n");

                    // 供应商信息
                    promptBuilder.append("供应商信息：\n");
                    promptBuilder.append("供应商名称：").append(supplierName).append("\n");
                    promptBuilder.append("\n");

                    // 商品明细
                    promptBuilder.append("商品明细：\n");
                    for (InvoiceItems item : items) {
                        promptBuilder.append("- 商品名称：").append(item.getGoodsName() != null ? item.getGoodsName() : "")
                                .append("，数量：").append(item.getQuantity() != null ? item.getQuantity() : "")
                                .append("，单位：").append(item.getUnit() != null ? item.getUnit() : "")
                                .append("，规格：").append(item.getSpecification() != null ? item.getSpecification() : "")
                                .append("，金额：").append(item.getAmount() != null ? item.getAmount() : "")
                                .append("，税率：").append(item.getTaxRate() != null ? item.getTaxRate() : "").append("%")
                                .append("，税额：").append(item.getTax() != null ? item.getTax() : "")
                                .append("\n");
                    }
                    promptBuilder.append("\n");

                    // 获取会计类型（需要在前面定义，因为后面会使用）
                    VatType vatType = accountSets.getVatType();

                    // 发票金额汇总（用于DeepSeek自行验证借贷平衡）
                    BigDecimal taxAmountExcluded = invoice.getTaxAmountExcluded() != null ? invoice.getTaxAmountExcluded() : BigDecimal.ZERO;
                    BigDecimal taxAmount = invoice.getTaxAmount() != null ? invoice.getTaxAmount() : BigDecimal.ZERO;
                    BigDecimal amount = invoice.getAmount() != null ? invoice.getAmount() : BigDecimal.ZERO;

                    promptBuilder.append("【【【发票金额汇总 - 必须使用这些数据进行借贷平衡验证】】】\n");
                    promptBuilder.append("不含税金额（taxAmountExcluded）：").append(taxAmountExcluded).append("\n");
                    promptBuilder.append("税额（taxAmount）：").append(taxAmount).append("\n");
                    promptBuilder.append("总金额（amount，价税合计）：").append(amount).append("\n");
                    promptBuilder.append("验证公式：总金额 = 不含税金额 + 税额，即 ").append(amount).append(" = ").append(taxAmountExcluded).append(" + ").append(taxAmount).append("\n");
                    promptBuilder.append("\n");
                    promptBuilder.append("【【【重要】】】你必须使用上述发票金额数据来验证你生成的会计分录是否借贷平衡！\n");
                    promptBuilder.append("在返回结果前，请按照以下步骤进行验证：\n");
                    if (vatType == VatType.SMALL_SCALE) {
                        promptBuilder.append("1. 计算借方总额 = 总金额（amount）= ").append(amount).append("\n");
                        promptBuilder.append("2. 计算贷方总额 = 总金额（amount）= ").append(amount).append("（系统自动生成应付账款）\n");
                        promptBuilder.append("3. 验证：借方总额 ").append(amount).append(" = 贷方总额 ").append(amount).append("，必须相等！\n");
                    } else {
                        promptBuilder.append("1. 计算借方总额 = 不含税金额（taxAmountExcluded）+ 税额（taxAmount）= ").append(taxAmountExcluded).append(" + ").append(taxAmount).append(" = ").append(amount).append("\n");
                        promptBuilder.append("2. 计算贷方总额 = 总金额（amount）= ").append(amount).append("（系统自动生成应付账款）\n");
                        promptBuilder.append("3. 验证：借方总额 ").append(amount).append(" = 贷方总额 ").append(amount).append("，必须相等！\n");
                    }
                    promptBuilder.append("4. 如果计算结果不相等，请重新生成会计分录，直到借贷平衡为止！\n");
                    promptBuilder.append("\n");

                    // 科目信息（只显示主要科目，用于DeepSeek选择）
                    promptBuilder.append("科目信息（主要科目，请从中选择）：\n");
                    subjects.stream()
                            .filter(s -> s.getLevel() != null && s.getLevel() <= 2) // 只显示一级和二级科目
                            .limit(100)
                            .forEach(s -> {
                                promptBuilder.append("科目编码：").append(s.getCode())
                                        .append("，科目名称：").append(s.getName())
                                        .append("，科目类型：").append(s.getType())
                                        .append("\n");
                            });
                    promptBuilder.append("\n");

                    // 发票类型说明
                    promptBuilder.append("【重要】这是进项发票（采购发票），企业是购买方（买方）。\n");

                    // 根据会计类型说明不同的会计分录规则（vatType已在前面定义）
                    if (vatType == VatType.SMALL_SCALE) {
                        // 小规模纳税人
                        promptBuilder.append("【会计类型：小规模纳税人】\n");
                        promptBuilder.append("小规模纳税人的进项发票会计分录规则：\n");
                        promptBuilder.append("1. 借方科目：资产类科目（如：库存商品、原材料、管理费用、销售费用、固定资产等）\n");
                        promptBuilder.append("   - 金额：总金额（含税金额，即不含税金额 + 税额）\n");
                        promptBuilder.append("   - 注意：小规模纳税人不能抵扣进项税额，所以税额应计入成本或费用\n");
                        promptBuilder.append("2. 贷方科目：应付账款（注意：应付账款-供应商由系统自动生成，你不需要返回应付账款科目）\n");
                        promptBuilder.append("【重要】小规模纳税人不需要\"应交税费-应交增值税（进项税额）\"科目，税额直接计入资产或费用科目。\n");
                    } else if (vatType == VatType.COMMONLY) {
                        // 一般纳税人
                        promptBuilder.append("【会计类型：一般纳税人】\n");
                        promptBuilder.append("一般纳税人的进项发票会计分录规则：\n");
                        promptBuilder.append("1. 借方科目：资产类科目（如：库存商品、原材料、管理费用、销售费用、固定资产等）\n");
                        promptBuilder.append("   - 金额：不含税金额\n");
                        promptBuilder.append("2. 借方科目：应交税费-应交增值税（进项税额）\n");
                        promptBuilder.append("   - 金额：税额\n");
                        promptBuilder.append("3. 贷方科目：应付账款（注意：应付账款-供应商由系统自动生成，你不需要返回应付账款科目）\n");
                        promptBuilder.append("【重要】一般纳税人可以抵扣进项税额，需要单独设置\"应交税费-应交增值税（进项税额）\"科目。\n");
                    } else {
                        // 未设置会计类型，使用默认规则（一般纳税人规则）
                        promptBuilder.append("进项发票的会计分录规则（默认按一般纳税人处理）：\n");
                        promptBuilder.append("1. 借方科目：资产类科目（如：库存商品、原材料、管理费用、销售费用、固定资产等）\n");
                        promptBuilder.append("   - 金额：不含税金额\n");
                        promptBuilder.append("2. 借方科目：应交税费-应交增值税（进项税额）\n");
                        promptBuilder.append("   - 金额：税额\n");
                        promptBuilder.append("3. 贷方科目：应付账款（注意：应付账款-供应商由系统自动生成，你不需要返回应付账款科目）\n");
                    }
                    promptBuilder.append("【严禁使用】应收账款、主营业务收入、其他业务收入等科目（这些只用于销项发票）\n");
                    promptBuilder.append("\n");

                    promptBuilder.append("请根据企业业务、供应商和商品明细，判断这张发票的业务类型，并返回会计分录模板。\n");
                    promptBuilder.append("注意：你只需要返回借方科目，不需要返回应付账款科目（系统会自动生成）。\n");
                    promptBuilder.append("\n");
                    promptBuilder.append("========== 必须严格按照以下JSON格式返回 ==========\n");
                    promptBuilder.append("{\n");
                    promptBuilder.append("  \"businessTypeName\": \"业务类型名称（如：采购原材料、办公用品采购、固定资产采购等）\",\n");
                    promptBuilder.append("  \"accountingEntries\": [\n");

                    // 根据会计类型调整格式说明
                    if (vatType == VatType.SMALL_SCALE) {
                        // 小规模纳税人：只需要资产/费用科目，使用总金额（含税）
                        promptBuilder.append("    {\n");
                        promptBuilder.append("      \"subjectCode\": \"科目编码（必须与提供的科目信息中的编码完全一致）\",\n");
                        promptBuilder.append("      \"subjectName\": \"科目名称（必须与提供的科目信息中的名称完全一致）\",\n");
                        promptBuilder.append("      \"direction\": \"借\",\n");
                        promptBuilder.append("      \"valueField\": \"amount（总金额，含税金额）\"\n");
                        promptBuilder.append("    }\n");
                    } else {
                        // 一般纳税人：需要资产/费用科目（不含税金额）+ 应交税费（税额）
                        promptBuilder.append("    {\n");
                        promptBuilder.append("      \"subjectCode\": \"科目编码（必须与提供的科目信息中的编码完全一致）\",\n");
                        promptBuilder.append("      \"subjectName\": \"科目名称（必须与提供的科目信息中的名称完全一致）\",\n");
                        promptBuilder.append("      \"direction\": \"借\",\n");
                        promptBuilder.append("      \"valueField\": \"taxAmountExcluded（不含税金额）\"\n");
                        promptBuilder.append("    },\n");
                        promptBuilder.append("    {\n");
                        promptBuilder.append("      \"subjectCode\": \"应交税费科目的编码\",\n");
                        promptBuilder.append("      \"subjectName\": \"应交税费-应交增值税（进项税额）\",\n");
                        promptBuilder.append("      \"direction\": \"借\",\n");
                        promptBuilder.append("      \"valueField\": \"taxAmount（税额）\"\n");
                        promptBuilder.append("    }\n");
                    }

                    promptBuilder.append("  ]\n");
                    promptBuilder.append("}\n");
                    promptBuilder.append("========== 格式说明结束 ==========\n");
                    promptBuilder.append("\n");
                    promptBuilder.append("【重要要求 - 请严格遵守】\n");
                    promptBuilder.append("1. 返回的JSON必须是有效的JSON格式，可以直接被解析\n");

                    if (vatType == VatType.SMALL_SCALE) {
                        promptBuilder.append("2. accountingEntries必须包含且仅包含1条分录：资产/费用科目（valueField: amount，总金额含税）\n");
                        promptBuilder.append("3. 【借贷平衡要求 - 这是会计的基本原则，必须严格遵守】\n");
                        promptBuilder.append("   - 借方总额 = 总金额（amount，含税金额）= 不含税金额 + 税额\n");
                        promptBuilder.append("   - 贷方总额 = 总金额（amount）= 不含税金额 + 税额（系统自动生成应付账款）\n");
                        promptBuilder.append("   - 因此：借方总额 = 贷方总额，必须保证借贷平衡\n");
                        promptBuilder.append("   - 请仔细检查：借方总额必须等于贷方总额，这是会计的基本原则，不能违反！\n");
                        promptBuilder.append("   - 小规模纳税人不能抵扣进项税额，税额应计入成本或费用，所以使用总金额（amount）\n");
                    } else {
                        promptBuilder.append("2. accountingEntries必须包含且仅包含2条分录：\n");
                        promptBuilder.append("   - 第一条：资产/费用科目（valueField: taxAmountExcluded，不含税金额）\n");
                        promptBuilder.append("   - 第二条：应交税费-应交增值税（进项税额）科目（valueField: taxAmount，税额）\n");
                        promptBuilder.append("3. 【借贷平衡要求 - 这是会计的基本原则，必须严格遵守】\n");
                        promptBuilder.append("   - 借方总额 = 不含税金额（taxAmountExcluded）+ 税额（taxAmount）\n");
                        promptBuilder.append("   - 贷方总额 = 总金额（amount）= 不含税金额 + 税额（系统自动生成应付账款）\n");
                        promptBuilder.append("   - 因此：借方总额 = 贷方总额，必须保证借贷平衡\n");
                        promptBuilder.append("   - 请仔细检查：借方总额必须等于贷方总额，这是会计的基本原则，不能违反！\n");
                        promptBuilder.append("   - 一般纳税人可以抵扣进项税额，所以需要单独设置应交税费科目\n");
                        promptBuilder.append("   - 必须同时包含：一条资产/费用科目（taxAmountExcluded）和一条应交税费科目（taxAmount），缺一不可\n");
                    }

                    promptBuilder.append("4. subjectCode和subjectName必须与提供的科目信息完全匹配，不能使用不存在的科目\n");
                    promptBuilder.append("5. direction字段只能是\"借\"（进项发票的借方科目）\n");
                    if (vatType == VatType.SMALL_SCALE) {
                        promptBuilder.append("6. valueField字段：资产/费用科目必须使用\"amount\"（总金额，含税）\n");
                    } else {
                        promptBuilder.append("6. valueField字段：\n");
                        promptBuilder.append("   - 资产/费用科目必须使用\"taxAmountExcluded\"（不含税金额）\n");
                        promptBuilder.append("   - 应交税费科目必须使用\"taxAmount\"（税额）\n");
                    }
                    promptBuilder.append("7. 不要返回应付账款科目，系统会自动生成应付账款-供应商（贷方，valueField: amount）\n");
                    promptBuilder.append("8. 返回的JSON中不要包含任何注释、说明文字，只返回纯JSON数据\n");
                    promptBuilder.append("9. 【【【最终检查清单 - 返回前必须完成】】】\n");
                    if (vatType == VatType.SMALL_SCALE) {
                        promptBuilder.append("   - 是否包含且仅包含1条借方分录（valueField: amount）？\n");
                    } else {
                        promptBuilder.append("   - 是否包含且仅包含2条借方分录（一条taxAmountExcluded，一条taxAmount）？\n");
                    }
                    promptBuilder.append("   - 所有科目编码和名称是否与提供的科目信息完全匹配？\n");
                    promptBuilder.append("   - 【【【最重要】】】使用发票金额数据计算验证借贷平衡：\n");
                    if (vatType == VatType.SMALL_SCALE) {
                        promptBuilder.append("     * 借方总额 = ").append(amount).append("\n");
                        promptBuilder.append("     * 贷方总额 = ").append(amount).append("\n");
                        promptBuilder.append("     * 验证：").append(amount).append(" = ").append(amount).append("，必须相等！\n");
                    } else {
                        promptBuilder.append("     * 借方总额 = ").append(taxAmountExcluded).append(" + ").append(taxAmount).append(" = ").append(amount).append("\n");
                        promptBuilder.append("     * 贷方总额 = ").append(amount).append("\n");
                        promptBuilder.append("     * 验证：").append(amount).append(" = ").append(amount).append("，必须相等！\n");
                    }
                    promptBuilder.append("   - 如果计算结果不相等，请重新生成，直到借贷平衡为止！\n");

                    // 构建DeepSeek请求体
                    Map<String, Object> requestBody = new HashMap<>();
                    requestBody.put("model", deepseekModel);
                    List<Map<String, String>> messages = new ArrayList<>();
                    Map<String, String> message = new HashMap<>();
                    message.put("role", "user");
                    message.put("content", promptBuilder.toString());
                    messages.add(message);
                    requestBody.put("messages", messages);
                    requestBody.put("temperature", 0.7);
                    requestBody.put("max_tokens", 2000);

                        HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(requestBody, headers);

                        // 调用DeepSeek API
                        ResponseEntity<Map> response = restTemplate.postForEntity(deepseekApiUrl, requestEntity, Map.class);

                        if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                        Map<String, Object> responseBody = response.getBody();
                        @SuppressWarnings("unchecked")
                        List<Map<String, Object>> choices = (List<Map<String, Object>>) responseBody.get("choices");
                        if (CollUtil.isNotEmpty(choices)) {
                            Map<String, Object> firstChoice = choices.get(0);
                            Map<String, Object> messageObj = (Map<String, Object>) firstChoice.get("message");
                            String content = (String) messageObj.get("content");

                            if (StringUtils.isNotBlank(content)) {
                                // 清理返回内容
                                String cleanedContent = content.trim();
                                if (cleanedContent.startsWith("```json")) {
                                    cleanedContent = cleanedContent.substring(7);
                                } else if (cleanedContent.startsWith("```")) {
                                    cleanedContent = cleanedContent.substring(3);
                                }
                                if (cleanedContent.endsWith("```")) {
                                    cleanedContent = cleanedContent.substring(0, cleanedContent.length() - 3);
                                }
                                cleanedContent = cleanedContent.trim();

                                try {
                                    // 解析返回的JSON
                                    JSONObject jsonResponse = JSONObject.parseObject(cleanedContent);

                                    String businessTypeName = jsonResponse.getString("businessTypeName");
                                    JSONArray accountingEntriesArray = jsonResponse.getJSONArray("accountingEntries");

                                    // 检查数据完整性，如果有问题则反馈给DeepSeek
                                    if (StringUtils.isBlank(businessTypeName) || accountingEntriesArray == null || accountingEntriesArray.isEmpty()) {
                                        retryCount++;
                                        StringBuilder errorMsg = new StringBuilder();
                                        errorMsg.append("【错误反馈】你返回的数据不完整，请重新生成。\n");
                                        if (StringUtils.isBlank(businessTypeName)) {
                                            errorMsg.append("问题：businessTypeName字段为空或缺失。\n");
                                        }
                                        if (accountingEntriesArray == null || accountingEntriesArray.isEmpty()) {
                                            errorMsg.append("问题：accountingEntries字段为空或缺失，或者数组为空。\n");
                                        }
                                        errorMsg.append("请确保返回完整的JSON数据，包含businessTypeName和accountingEntries字段。\n");

                                        log.warn("发票ID: {} DeepSeek返回的数据不完整（第{}次尝试），将反馈给DeepSeek重新生成", invoice.getId(), retryCount);

                                        if (retryCount < maxRetries) {
                                            lastBalanceError = errorMsg.toString();
                                            continue; // 继续while循环，重新生成
                                        } else {
                                            log.error("发票ID: {} 在{}次尝试后仍然数据不完整，放弃处理", invoice.getId(), maxRetries);
                                            failedCount++;
                                            success = false;
                                            break;
                                        }
                                    }

                                    // 在业务类型名称前添加【AI生成】前缀
                                    String baseName = "【AI生成】" + businessTypeName;

                                    // 检查名称是否重复，如果重复则合并已存在的业务类型
                                    InvoiceBusinessType existingBusinessType = bqf.selectFrom(qInvoiceBusinessType)
                                            .where(qInvoiceBusinessType.accountSetsId.eq(accountSetId)
                                                    .and(qInvoiceBusinessType.invoiceType.eq(InvoiceType.IN))
                                                    .and(qInvoiceBusinessType.title.eq(baseName)))
                                            .fetchFirst();

                                    InvoiceBusinessType businessType;
                                    String aiGeneratedName = baseName;

                                    if (existingBusinessType != null) {
                                        // 名称重复，合并已存在的业务类型
                                        businessType = existingBusinessType;
                                        log.info("发现重复的业务类型名称: {}，将合并商品关键字", baseName);

                                        // 合并商品关键字
                                        JSONArray existingMatchWords = businessType.getMatchWords();
                                        if (existingMatchWords == null) {
                                            existingMatchWords = new JSONArray();
                                        }

                                        // 收集当前发票的商品名称
                                        Set<String> matchWordSet = new HashSet<>();
                                        if (existingMatchWords != null) {
                                            for (int k = 0; k < existingMatchWords.size(); k++) {
                                                String word = existingMatchWords.getString(k);
                                                if (StringUtils.isNotBlank(word)) {
                                                    matchWordSet.add(word.trim());
                                                }
                                            }
                                        }

                                        // 添加当前发票的商品名称
                                        for (InvoiceItems item : items) {
                                            if (StringUtils.isNotBlank(item.getGoodsName())) {
                                                matchWordSet.add(item.getGoodsName().trim());
                                            }
                                        }

                                        // 更新匹配关键字
                                        JSONArray mergedMatchWords = new JSONArray();
                                        for (String word : matchWordSet) {
                                            mergedMatchWords.add(word);
                                        }
                                        businessType.setMatchWords(mergedMatchWords);

                                        log.info("合并后的商品关键字数量: {}", mergedMatchWords.size());
                                    } else {
                                        // 名称不重复，创建新的业务类型
                                        businessType = new InvoiceBusinessType();
                                        businessType.setAccountSetsId(accountSetId);
                                        businessType.setInvoiceType(InvoiceType.IN);
                                        businessType.setTitle(aiGeneratedName);
                                    }

                                    // 设置凭证字：根据账套ID和凭证字ID设置
                                    if (defaultVoucherWord != null) {
                                        Long voucherWordId = defaultVoucherWord.getId();
                                        businessType.setVoucherWord(voucherWordId);
                                        log.debug("为业务类型 {} 设置凭证字ID: {} (凭证字名称: {})",
                                                aiGeneratedName, voucherWordId, defaultVoucherWord.getWord());
                                    } else {
                                        log.warn("账套ID: {} 没有凭证字，业务类型 {} 的凭证字字段将为空",
                                                accountSetId, aiGeneratedName);
                                    }

                                    // 设置匹配关键字（商品名称）- 仅在新创建业务类型时设置
                                    if (existingBusinessType == null) {
                                        JSONArray matchWords = new JSONArray();
                                        for (InvoiceItems item : items) {
                                            if (StringUtils.isNotBlank(item.getGoodsName())) {
                                                matchWords.add(item.getGoodsName().trim());
                                            }
                                        }
                                        businessType.setMatchWords(matchWords);
                                    }

                                    // 设置凭证模板项（只包含借方科目）
                                    List<InvoiceBusinessType.InvoiceVoucherTemplateItems> templateItems = new ArrayList<>();
                                    List<String> validationErrors = new ArrayList<>(); // 收集验证错误

                                    for (int j = 0; j < accountingEntriesArray.size(); j++) {
                                        JSONObject entryObj = accountingEntriesArray.getJSONObject(j);
                                        String subjectCode = entryObj.getString("subjectCode");
                                        String subjectName = entryObj.getString("subjectName");
                                        String direction = entryObj.getString("direction");
                                        String valueField = entryObj.getString("valueField");

                                        // 验证必需字段
                                        if (!"借".equals(direction)) {
                                            validationErrors.add(String.format("第%d条分录的方向不是\"借\"，而是\"%s\"。进项发票的所有分录方向必须是\"借\"。", j + 1, direction));
                                            continue;
                                        }

                                        // 根据会计类型验证valueField
                                        boolean isValidValueField = false;
                                        if (vatType == VatType.SMALL_SCALE) {
                                            // 小规模纳税人：只允许使用amount（总金额，含税）
                                            isValidValueField = "amount".equals(valueField);
                                            if (!isValidValueField) {
                                                validationErrors.add(String.format("第%d条分录的valueField是\"%s\"，但小规模纳税人必须使用\"amount\"（总金额，含税）。", j + 1, valueField));
                                            }
                                        } else {
                                            // 一般纳税人：允许使用taxAmountExcluded（不含税金额）或taxAmount（税额）
                                            isValidValueField = "taxAmountExcluded".equals(valueField) || "taxAmount".equals(valueField);
                                            if (!isValidValueField) {
                                                validationErrors.add(String.format("第%d条分录的valueField是\"%s\"，但一般纳税人必须使用\"taxAmountExcluded\"（不含税金额）或\"taxAmount\"（税额）。", j + 1, valueField));
                                            }
                                        }

                                        if (StringUtils.isBlank(valueField) || !isValidValueField) {
                                            continue;
                                        }

                                        // 根据科目编码或名称查找科目
                                        Subject subject = null;
                                        if (StringUtils.isNotBlank(subjectCode)) {
                                            subject = bqf.selectFrom(qSubject)
                                                    .where(qSubject.code.eq(subjectCode)
                                                            .and(qSubject.accountSetsId.eq(accountSetId)))
                                                    .fetchFirst();
                                        }
                                        if (subject == null && StringUtils.isNotBlank(subjectName)) {
                                            subject = bqf.selectFrom(qSubject)
                                                    .where(qSubject.fullName.eq(subjectName)
                                                            .and(qSubject.accountSetsId.eq(accountSetId)))
                                                    .fetchFirst();
                                        }

                                        if (subject == null) {
                                            validationErrors.add(String.format("第%d条分录的科目未找到（编码：%s，名称：%s）。请确保科目编码或名称与提供的科目信息完全匹配。",
                                                    j + 1, subjectCode, subjectName));
                                            continue;
                                        }

                                        InvoiceBusinessType.InvoiceVoucherTemplateItems item =
                                                new InvoiceBusinessType.InvoiceVoucherTemplateItems();
                                        item.setSubjectTitle(subject.getFullName());
                                        item.setSubjectId(subject.getId().toString());
                                        item.setDirection("借");
                                        item.setValueField(valueField);
                                        templateItems.add(item);
                                    }

                                    // 如果有验证错误，反馈给DeepSeek重新生成
                                    if (!validationErrors.isEmpty()) {
                                        retryCount++;

                                        // 使用已定义的发票金额变量
                                        StringBuilder errorFeedback = new StringBuilder();
                                        errorFeedback.append("【【【错误反馈 - 请仔细阅读并重新生成】】】\n");
                                        errorFeedback.append("你返回的会计分录有以下问题，请修正后重新生成：\n\n");

                                        for (int k = 0; k < validationErrors.size(); k++) {
                                            errorFeedback.append(String.format("%d. %s\n", k + 1, validationErrors.get(k)));
                                        }

                                        errorFeedback.append("\n【发票金额数据 - 请使用这些数据进行验证】\n");
                                        errorFeedback.append("不含税金额（taxAmountExcluded）：").append(taxAmountExcluded).append("\n");
                                        errorFeedback.append("税额（taxAmount）：").append(taxAmount).append("\n");
                                        errorFeedback.append("总金额（amount，价税合计）：").append(amount).append("\n");
                                        errorFeedback.append("验证公式：总金额 = 不含税金额 + 税额，即 ").append(amount).append(" = ").append(taxAmountExcluded).append(" + ").append(taxAmount).append("\n\n");

                                        errorFeedback.append("【会计类型】").append(vatType != null ? vatType.display : "未设置").append("\n\n");

                                        if (vatType == VatType.SMALL_SCALE) {
                                            errorFeedback.append("【小规模纳税人要求】\n");
                                            errorFeedback.append("1. accountingEntries必须包含且仅包含1条分录\n");
                                            errorFeedback.append("2. 该分录必须是资产/费用科目，valueField必须是\"amount\"（总金额，含税）\n");
                                            errorFeedback.append("3. 使用发票金额验证：借方总额 = ").append(amount).append("，贷方总额 = ").append(amount).append("，必须相等\n");
                                        } else {
                                            errorFeedback.append("【一般纳税人要求】\n");
                                            errorFeedback.append("1. accountingEntries必须包含且仅包含2条分录\n");
                                            errorFeedback.append("2. 第一条：资产/费用科目，valueField必须是\"taxAmountExcluded\"（不含税金额）\n");
                                            errorFeedback.append("3. 第二条：应交税费-应交增值税（进项税额）科目，valueField必须是\"taxAmount\"（税额）\n");
                                            errorFeedback.append("4. 使用发票金额验证：借方总额 = ").append(taxAmountExcluded).append(" + ").append(taxAmount).append(" = ").append(amount).append("，贷方总额 = ").append(amount).append("，必须相等\n");
                                        }

                                        errorFeedback.append("\n请修正上述问题，使用发票金额数据验证借贷平衡，然后重新生成正确的会计分录。\n");

                                        lastBalanceError = errorFeedback.toString();

                                        log.warn("发票ID: {} 的会计分录有验证错误（第{}次尝试）：{}，将反馈给DeepSeek重新生成",
                                                invoice.getId(), retryCount, String.join("; ", validationErrors));

                                        if (retryCount < maxRetries) {
                                            continue; // 继续while循环，重新生成
                                        } else {
                                            log.error("发票ID: {} 在{}次尝试后仍然有验证错误，放弃处理", invoice.getId(), maxRetries);
                                            failedCount++;
                                            success = false;
                                            break;
                                        }
                                    }

                                    // 添加应付账款-供应商（贷方，由系统自动生成）
                                    InvoiceBusinessType.InvoiceVoucherTemplateItems payableItem =
                                            new InvoiceBusinessType.InvoiceVoucherTemplateItems();
                                    payableItem.setSubjectTitle(payableSubject.getFullName());
                                    payableItem.setSubjectId(payableSubject.getId().toString());
                                    payableItem.setDirection("贷");
                                    payableItem.setValueField("amount"); // 应付账款使用总金额
                                    templateItems.add(payableItem);

                                    log.info("发票ID: {} 的会计分录已生成（会计类型：{}，第{}次尝试）",
                                            invoice.getId(), vatType != null ? vatType.display : "未设置", retryCount + 1);

                                    // 根据会计类型检查最小分录数量
                                    int minItemsRequired = (vatType == VatType.SMALL_SCALE) ? 2 : 3; // 小规模：1借方+1贷方，一般纳税人：2借方+1贷方

                                    // 检查分录数量是否足够（包括系统自动添加的应付账款）
                                    int currentItemsCount = templateItems.size(); // 已经包含了应付账款
                                    if (CollUtil.isEmpty(templateItems) || currentItemsCount < minItemsRequired) {
                                        retryCount++;

                                        // 使用已定义的发票金额变量
                                        StringBuilder errorFeedback = new StringBuilder();
                                        errorFeedback.append("【【【错误反馈 - 请仔细阅读并重新生成】】】\n");
                                        errorFeedback.append("你返回的会计分录数量不足，请修正后重新生成。\n\n");

                                        if (vatType == VatType.SMALL_SCALE) {
                                            errorFeedback.append("【问题】当前只有").append(currentItemsCount).append("条分录，但小规模纳税人需要至少2条分录（1条借方+1条贷方应付账款）。\n");
                                            errorFeedback.append("【要求】accountingEntries必须包含且仅包含1条借方分录（valueField: amount）。\n");
                                        } else {
                                            errorFeedback.append("【问题】当前只有").append(currentItemsCount).append("条分录，但一般纳税人需要至少3条分录（2条借方+1条贷方应付账款）。\n");
                                            errorFeedback.append("【要求】accountingEntries必须包含且仅包含2条借方分录（一条taxAmountExcluded，一条taxAmount）。\n");
                                        }

                                        errorFeedback.append("\n【发票金额数据 - 请使用这些数据进行验证】\n");
                                        errorFeedback.append("不含税金额（taxAmountExcluded）：").append(taxAmountExcluded).append("\n");
                                        errorFeedback.append("税额（taxAmount）：").append(taxAmount).append("\n");
                                        errorFeedback.append("总金额（amount，价税合计）：").append(amount).append("\n");
                                        errorFeedback.append("验证公式：总金额 = 不含税金额 + 税额，即 ").append(amount).append(" = ").append(taxAmountExcluded).append(" + ").append(taxAmount).append("\n\n");

                                        errorFeedback.append("【会计类型】").append(vatType != null ? vatType.display : "未设置").append("\n\n");

                                        if (vatType == VatType.SMALL_SCALE) {
                                            errorFeedback.append("【小规模纳税人要求】\n");
                                            errorFeedback.append("1. accountingEntries必须包含且仅包含1条分录\n");
                                            errorFeedback.append("2. 该分录必须是资产/费用科目，valueField必须是\"amount\"（总金额，含税）\n");
                                            errorFeedback.append("3. 使用发票金额验证：借方总额 = ").append(amount).append("，贷方总额 = ").append(amount).append("，必须相等\n");
                                        } else {
                                            errorFeedback.append("【一般纳税人要求】\n");
                                            errorFeedback.append("1. accountingEntries必须包含且仅包含2条分录\n");
                                            errorFeedback.append("2. 第一条：资产/费用科目，valueField必须是\"taxAmountExcluded\"（不含税金额）\n");
                                            errorFeedback.append("3. 第二条：应交税费-应交增值税（进项税额）科目，valueField必须是\"taxAmount\"（税额）\n");
                                            errorFeedback.append("4. 使用发票金额验证：借方总额 = ").append(taxAmountExcluded).append(" + ").append(taxAmount).append(" = ").append(amount).append("，贷方总额 = ").append(amount).append("，必须相等\n");
                                        }

                                        errorFeedback.append("\n请修正上述问题，使用发票金额数据验证借贷平衡，然后重新生成正确的会计分录。\n");

                                        lastBalanceError = errorFeedback.toString();

                                        log.warn("发票ID: {} 的凭证模板项数量不足（当前：{}，需要：{}，第{}次尝试），将反馈给DeepSeek重新生成",
                                                invoice.getId(), currentItemsCount, minItemsRequired, retryCount);

                                        if (retryCount < maxRetries) {
                                            continue; // 继续while循环，重新生成
                                        } else {
                                            log.error("发票ID: {} 在{}次尝试后仍然分录数量不足，放弃处理", invoice.getId(), maxRetries);
                                            failedCount++;
                                            success = false;
                                            break;
                                        }
                                    }

                                    if (CollUtil.isNotEmpty(templateItems) && templateItems.size() >= minItemsRequired) {
                                        // 仅在新创建业务类型时设置凭证模板项
                                        if (existingBusinessType == null) {
                                            businessType.setItems(templateItems);
                                        } else {
                                            // 如果业务类型已存在，检查凭证模板项是否一致
                                            List<InvoiceBusinessType.InvoiceVoucherTemplateItems> existingItems = businessType.getItems();
                                            if (existingItems == null || existingItems.isEmpty()) {
                                                businessType.setItems(templateItems);
                                            } else {
                                                // 凭证模板项已存在，不更新（保持原有模板）
                                                log.debug("业务类型 {} 已存在凭证模板项，保持原有模板不变", baseName);
                                            }
                                        }

                                        // 保存业务类型
                                        InvoiceBusinessType savedType = invoiceBusinessTypeService.save(businessType);
                                        Long businessTypeId = savedType.getId();

                                        // 更新发票的业务类型
                                        jqf.update(qInvoice)
                                                .set(qInvoice.invoiceBusinessTypeId, businessTypeId)
                                                .where(qInvoice.id.eq(invoice.getId()))
                                                .execute();

                                        matchedCount++;
                                        log.info("成功为发票ID: {} 创建业务类型: {}（第{}次尝试）",
                                                invoice.getId(), aiGeneratedName, retryCount + 1);
                                        success = true; // 标记成功，退出重试循环
                                    } else {
                                        // 这种情况理论上不应该发生，因为前面已经检查过了
                                        // 但为了安全起见，仍然处理
                                        retryCount++;

                                        // 使用已定义的发票金额变量
                                        StringBuilder errorFeedback = new StringBuilder();
                                        errorFeedback.append("【【【错误反馈 - 请仔细阅读并重新生成】】】\n");
                                        errorFeedback.append("你返回的会计分录数量不足，请修正后重新生成。\n\n");
                                        errorFeedback.append("【问题】当前只有").append(templateItems.size()).append("条分录，但需要至少").append(minItemsRequired).append("条分录。\n\n");

                                        errorFeedback.append("【发票金额数据 - 请使用这些数据进行验证】\n");
                                        errorFeedback.append("不含税金额（taxAmountExcluded）：").append(taxAmountExcluded).append("\n");
                                        errorFeedback.append("税额（taxAmount）：").append(taxAmount).append("\n");
                                        errorFeedback.append("总金额（amount，价税合计）：").append(amount).append("\n");
                                        errorFeedback.append("验证公式：总金额 = 不含税金额 + 税额，即 ").append(amount).append(" = ").append(taxAmountExcluded).append(" + ").append(taxAmount).append("\n\n");

                                        errorFeedback.append("【会计类型】").append(vatType != null ? vatType.display : "未设置").append("\n\n");

                                        if (vatType == VatType.SMALL_SCALE) {
                                            errorFeedback.append("【小规模纳税人要求】\n");
                                            errorFeedback.append("1. accountingEntries必须包含且仅包含1条分录\n");
                                            errorFeedback.append("2. 该分录必须是资产/费用科目，valueField必须是\"amount\"（总金额，含税）\n");
                                        } else {
                                            errorFeedback.append("【一般纳税人要求】\n");
                                            errorFeedback.append("1. accountingEntries必须包含且仅包含2条分录\n");
                                            errorFeedback.append("2. 第一条：资产/费用科目，valueField必须是\"taxAmountExcluded\"（不含税金额）\n");
                                            errorFeedback.append("3. 第二条：应交税费-应交增值税（进项税额）科目，valueField必须是\"taxAmount\"（税额）\n");
                                        }

                                        errorFeedback.append("\n请修正上述问题，使用发票金额数据验证借贷平衡，然后重新生成正确的会计分录。\n");

                                        lastBalanceError = errorFeedback.toString();

                                        log.warn("发票ID: {} 的凭证模板项不足（当前：{}，需要：{}，第{}次尝试），将反馈给DeepSeek重新生成",
                                                invoice.getId(), templateItems.size(), minItemsRequired, retryCount);

                                        if (retryCount < maxRetries) {
                                            continue; // 继续while循环，重新生成
                                        } else {
                                            log.error("发票ID: {} 在{}次尝试后仍然分录数量不足，放弃处理", invoice.getId(), maxRetries);
                                            failedCount++;
                                            success = false;
                                            break;
                                        }
                                    }
                                } catch (Exception e) {
                                    retryCount++;

                                    // 使用已定义的发票金额变量
                                    StringBuilder errorFeedback = new StringBuilder();
                                    errorFeedback.append("【【【错误反馈 - 请仔细阅读并重新生成】】】\n");
                                    errorFeedback.append("你返回的JSON数据无法解析，请修正后重新生成。\n\n");
                                    errorFeedback.append("【错误信息】").append(e.getMessage()).append("\n\n");
                                    errorFeedback.append("【要求】\n");
                                    errorFeedback.append("1. 返回的JSON必须是有效的JSON格式，可以直接被解析\n");
                                    errorFeedback.append("2. 返回的JSON中不要包含任何注释、说明文字，只返回纯JSON数据\n");
                                    errorFeedback.append("3. 如果返回的内容包含```json或```标记，请确保格式正确\n\n");

                                    errorFeedback.append("【发票金额数据 - 请使用这些数据进行验证】\n");
                                    errorFeedback.append("不含税金额（taxAmountExcluded）：").append(taxAmountExcluded).append("\n");
                                    errorFeedback.append("税额（taxAmount）：").append(taxAmount).append("\n");
                                    errorFeedback.append("总金额（amount，价税合计）：").append(amount).append("\n");
                                    errorFeedback.append("验证公式：总金额 = 不含税金额 + 税额，即 ").append(amount).append(" = ").append(taxAmountExcluded).append(" + ").append(taxAmount).append("\n\n");

                                    errorFeedback.append("【会计类型】").append(vatType != null ? vatType.display : "未设置").append("\n\n");

                                    if (vatType == VatType.SMALL_SCALE) {
                                        errorFeedback.append("【小规模纳税人要求】\n");
                                        errorFeedback.append("1. accountingEntries必须包含且仅包含1条分录\n");
                                        errorFeedback.append("2. 该分录必须是资产/费用科目，valueField必须是\"amount\"（总金额，含税）\n");
                                    } else {
                                        errorFeedback.append("【一般纳税人要求】\n");
                                        errorFeedback.append("1. accountingEntries必须包含且仅包含2条分录\n");
                                        errorFeedback.append("2. 第一条：资产/费用科目，valueField必须是\"taxAmountExcluded\"（不含税金额）\n");
                                        errorFeedback.append("3. 第二条：应交税费-应交增值税（进项税额）科目，valueField必须是\"taxAmount\"（税额）\n");
                                    }

                                    errorFeedback.append("\n请返回有效的JSON格式数据，使用发票金额数据验证借贷平衡。\n");

                                    lastBalanceError = errorFeedback.toString();

                                    log.error("处理发票ID: {} 的DeepSeek返回失败（第{}次尝试）", invoice.getId(), retryCount, e);

                                    if (retryCount < maxRetries) {
                                        continue; // 继续while循环，重新生成
                                    } else {
                                        log.error("发票ID: {} 在{}次尝试后仍然失败，放弃处理", invoice.getId(), maxRetries);
                                        failedCount++;
                                        success = false;
                                        break;
                                    }
                                }
                            } else {
                                retryCount++;

                                // 使用已定义的发票金额变量
                                StringBuilder errorFeedback = new StringBuilder();
                                errorFeedback.append("【【【错误反馈 - 请仔细阅读并重新生成】】】\n");
                                errorFeedback.append("你返回的内容为空，请重新生成有效的JSON数据。\n\n");
                                errorFeedback.append("【要求】\n");
                                errorFeedback.append("1. 必须返回有效的JSON格式数据\n");
                                errorFeedback.append("2. JSON必须包含businessTypeName和accountingEntries字段\n");
                                errorFeedback.append("3. 返回的JSON中不要包含任何注释、说明文字，只返回纯JSON数据\n\n");

                                errorFeedback.append("【发票金额数据 - 请使用这些数据进行验证】\n");
                                errorFeedback.append("不含税金额（taxAmountExcluded）：").append(taxAmountExcluded).append("\n");
                                errorFeedback.append("税额（taxAmount）：").append(taxAmount).append("\n");
                                errorFeedback.append("总金额（amount，价税合计）：").append(amount).append("\n");
                                errorFeedback.append("验证公式：总金额 = 不含税金额 + 税额，即 ").append(amount).append(" = ").append(taxAmountExcluded).append(" + ").append(taxAmount).append("\n\n");

                                errorFeedback.append("【会计类型】").append(vatType != null ? vatType.display : "未设置").append("\n\n");

                                if (vatType == VatType.SMALL_SCALE) {
                                    errorFeedback.append("【小规模纳税人要求】\n");
                                    errorFeedback.append("1. accountingEntries必须包含且仅包含1条分录\n");
                                    errorFeedback.append("2. 该分录必须是资产/费用科目，valueField必须是\"amount\"（总金额，含税）\n");
                                } else {
                                    errorFeedback.append("【一般纳税人要求】\n");
                                    errorFeedback.append("1. accountingEntries必须包含且仅包含2条分录\n");
                                    errorFeedback.append("2. 第一条：资产/费用科目，valueField必须是\"taxAmountExcluded\"（不含税金额）\n");
                                    errorFeedback.append("3. 第二条：应交税费-应交增值税（进项税额）科目，valueField必须是\"taxAmount\"（税额）\n");
                                }

                                errorFeedback.append("\n请返回有效的JSON格式数据，使用发票金额数据验证借贷平衡。\n");

                                lastBalanceError = errorFeedback.toString();

                                log.warn("发票ID: {} DeepSeek返回的内容为空（第{}次尝试），将反馈给DeepSeek重新生成", invoice.getId(), retryCount);

                                if (retryCount < maxRetries) {
                                    continue; // 继续while循环，重新生成
                                } else {
                                    log.error("发票ID: {} 在{}次尝试后仍然返回空内容，放弃处理", invoice.getId(), maxRetries);
                                    failedCount++;
                                    success = false;
                                    break;
                                }
                            }
                        } else {
                            retryCount++;

                            // 使用已定义的发票金额变量
                            StringBuilder errorFeedback = new StringBuilder();
                            errorFeedback.append("【【【错误反馈 - 请仔细阅读并重新生成】】】\n");
                            errorFeedback.append("DeepSeek返回的choices为空，请重新生成有效的JSON数据。\n\n");
                            errorFeedback.append("【要求】\n");
                            errorFeedback.append("1. 必须返回有效的JSON格式数据\n");
                            errorFeedback.append("2. JSON必须包含businessTypeName和accountingEntries字段\n");
                            errorFeedback.append("3. 返回的JSON中不要包含任何注释、说明文字，只返回纯JSON数据\n\n");

                            errorFeedback.append("【发票金额数据 - 请使用这些数据进行验证】\n");
                            errorFeedback.append("不含税金额（taxAmountExcluded）：").append(taxAmountExcluded).append("\n");
                            errorFeedback.append("税额（taxAmount）：").append(taxAmount).append("\n");
                            errorFeedback.append("总金额（amount，价税合计）：").append(amount).append("\n");
                            errorFeedback.append("验证公式：总金额 = 不含税金额 + 税额，即 ").append(amount).append(" = ").append(taxAmountExcluded).append(" + ").append(taxAmount).append("\n\n");

                            errorFeedback.append("【会计类型】").append(vatType != null ? vatType.display : "未设置").append("\n\n");

                            if (vatType == VatType.SMALL_SCALE) {
                                errorFeedback.append("【小规模纳税人要求】\n");
                                errorFeedback.append("1. accountingEntries必须包含且仅包含1条分录\n");
                                errorFeedback.append("2. 该分录必须是资产/费用科目，valueField必须是\"amount\"（总金额，含税）\n");
                            } else {
                                errorFeedback.append("【一般纳税人要求】\n");
                                errorFeedback.append("1. accountingEntries必须包含且仅包含2条分录\n");
                                errorFeedback.append("2. 第一条：资产/费用科目，valueField必须是\"taxAmountExcluded\"（不含税金额）\n");
                                errorFeedback.append("3. 第二条：应交税费-应交增值税（进项税额）科目，valueField必须是\"taxAmount\"（税额）\n");
                            }

                            errorFeedback.append("\n请返回有效的JSON格式数据，使用发票金额数据验证借贷平衡。\n");

                            lastBalanceError = errorFeedback.toString();

                            log.warn("发票ID: {} DeepSeek返回的choices为空（第{}次尝试），将反馈给DeepSeek重新生成", invoice.getId(), retryCount);

                            if (retryCount < maxRetries) {
                                continue; // 继续while循环，重新生成
                            } else {
                                log.error("发票ID: {} 在{}次尝试后仍然返回空choices，放弃处理", invoice.getId(), maxRetries);
                                failedCount++;
                                success = false;
                                break;
                            }
                        }
                    } else {
                        retryCount++;
                        log.warn("发票ID: {} DeepSeek API调用失败（第{}次尝试），响应：{}",
                                invoice.getId(), retryCount, response.getBody());

                        if (retryCount < maxRetries) {
                            lastBalanceError = "DeepSeek API调用失败";
                            continue; // 继续while循环，重新生成
                        } else {
                            log.error("发票ID: {} 在{}次尝试后仍然API调用失败，放弃处理", invoice.getId(), maxRetries);
                            failedCount++;
                            success = false;
                            break;
                        }
                    }
                    } // while循环结束
                } catch (Exception e) {
                    log.error("处理发票ID: {} 失败", invoice.getId(), e);
                    failedCount++;
                }
            }

            Map<String, Object> result = new HashMap<>();
            result.put("matchedCount", matchedCount);
            result.put("unmatchedCount", unmatchedInvoices.size() - matchedCount);
            result.put("totalCount", unmatchedInvoices.size());
            result.put("failedCount", failedCount);
            result.put("message", String.format("DeepSeek匹配完成：成功匹配 %d 张，失败 %d 张", matchedCount, failedCount));

            log.info("DeepSeek匹配完成 - 账套ID: {}, 发票类型: {}, 账期: {}, 匹配: {}, 失败: {}",
                    accountSetId, invoiceType, period, matchedCount, failedCount);

            return JsonResult.successful(result);
        } catch (Exception e) {
            log.error("DeepSeek匹配发票业务类型失败", e);
            return JsonResult.failure("DeepSeek匹配失败: " + e.getMessage());
        }
    }

    /**
     * 查询需要生成凭证的发票（支持进项/销项）
     *
     * @param accountSetId 账套ID
     * @param yearMonth    账期
     * @param type         发票类型：IN-进项，OUT-销项
     * @return 需要生成凭证的发票列表
     */
    private List<Invoice> queryInvoicesForVoucherGeneration(Long accountSetId, YearMonth yearMonth, InvoiceType type) {
        LocalDate startDate = yearMonth.atDay(1);
        LocalDate endDate = yearMonth.atEndOfMonth();

        log.info("查询需要生成凭证的发票 - 账套ID: {}, 发票类型: {}, 账期: {}, 日期范围: {} 至 {}",
                accountSetId, type != null ? type.name() : "IN", yearMonth, startDate, endDate);

        // 查询条件：assignmentDate在账期内 OR tradeDate在账期内
        // 这样可以覆盖两种情况：有assignmentDate的发票和只有tradeDate的发票
        List<Invoice> invoices = bqf.selectFrom(qInvoice)
                .where(qInvoice.accountSetsId.eq(accountSetId)
                        .and(qInvoice.type.eq(type != null ? type : InvoiceType.IN))
                        .and(qInvoice.invoiceBusinessTypeId.isNotNull()) // 有业务类型
                        .and(qInvoice.voucherId.isNull()) // 未生成凭证
                        .and(
                            // assignmentDate在账期内 OR tradeDate在账期内
                            qInvoice.assignmentDate.between(startDate, endDate)
                                .or(qInvoice.tradeDate.between(startDate, endDate))
                        ))
                .fetch();

        log.info("查询到 {} 张需要生成凭证的发票（类型：{}）", invoices != null ? invoices.size() : 0,
                type != null ? type.name() : "IN");

        // 如果还是没有数据，输出调试信息
        if (CollUtil.isEmpty(invoices)) {
            // 查询总共有多少张当前类型的发票（用于调试）
            long totalInvoices = bqf.selectFrom(qInvoice)
                    .where(qInvoice.accountSetsId.eq(accountSetId)
                            .and(qInvoice.type.eq(type != null ? type : InvoiceType.IN)))
                    .fetchCount();

            // 查询有多少张有业务类型的发票
            long invoicesWithBusinessType = bqf.selectFrom(qInvoice)
                    .where(qInvoice.accountSetsId.eq(accountSetId)
                            .and(qInvoice.type.eq(type != null ? type : InvoiceType.IN))
                            .and(qInvoice.invoiceBusinessTypeId.isNotNull()))
                    .fetchCount();

            // 查询有多少张未生成凭证的发票
            long invoicesWithoutVoucher = bqf.selectFrom(qInvoice)
                    .where(qInvoice.accountSetsId.eq(accountSetId)
                            .and(qInvoice.type.eq(type != null ? type : InvoiceType.IN))
                            .and(qInvoice.voucherId.isNull()))
                    .fetchCount();

            log.warn("未查询到需要生成凭证的发票 - 发票类型: {}, 总发票数: {}, 有业务类型: {}, 未生成凭证: {}",
                    type != null ? type.name() : "IN", totalInvoices, invoicesWithBusinessType, invoicesWithoutVoucher);
        }

        return invoices != null ? invoices : new ArrayList<>();
    }

    /**
     * 根据发票和业务类型模板构建VoucherDto
     *
     * @param invoice           发票
     * @param businessType      业务类型
     * @param subjectMap        科目映射
     * @param localCurrency     本地币种
     * @param voucherWord       凭证字
     * @param voucherCode       凭证编号
     * @param voucherDate       凭证日期
     * @return VoucherDto，如果构建失败返回null
     */
    private VoucherDto buildVoucherDto(Invoice invoice, InvoiceBusinessType businessType,
                                      Map<Long, Subject> subjectMap, CurrencyDto localCurrency,
                                      String voucherWord, Integer voucherCode, LocalDate voucherDate) {
        try {
            List<InvoiceBusinessType.InvoiceVoucherTemplateItems> templateItems = businessType.getItems();
            if (CollUtil.isEmpty(templateItems)) {
                log.warn("发票ID: {} 的业务类型没有凭证模板项", invoice.getId());
                return null;
            }

            // 往来科目列表
            List<String> transactionAccounts = Arrays.asList(
                    "应收账款", "其他应收款", "预收账款", "应付账款", "其他应付款", "预付账款"
            );

            // 构建凭证明细
            List<VoucherDetailsDto> details = new ArrayList<>();
            BigDecimal debitAmount = BigDecimal.ZERO;
            BigDecimal creditAmount = BigDecimal.ZERO;

            // 获取发票金额
            BigDecimal taxAmountExcluded = invoice.getTaxAmountExcluded() != null ?
                    invoice.getTaxAmountExcluded() : BigDecimal.ZERO;
            BigDecimal taxAmount = invoice.getTaxAmount() != null ?
                    invoice.getTaxAmount() : BigDecimal.ZERO;
            BigDecimal amount = invoice.getAmount() != null ?
                    invoice.getAmount() : BigDecimal.ZERO;

            // 获取客户名称（用于往来科目）
            String customerName = invoice.getCustomerName();

            for (InvoiceBusinessType.InvoiceVoucherTemplateItems item : templateItems) {
                String subjectIdStr = item.getSubjectId();
                if (StringUtils.isBlank(subjectIdStr)) {
                    continue;
                }

                Long subjectId;
                try {
                    subjectId = Long.parseLong(subjectIdStr);
                } catch (NumberFormatException e) {
                    log.warn("发票ID: {} 的模板项科目ID格式错误: {}", invoice.getId(), subjectIdStr);
                    continue;
                }

                Subject subject = subjectMap.get(subjectId);
                if (subject == null) {
                    log.warn("发票ID: {} 的模板项科目ID: {} 不存在", invoice.getId(), subjectId);
                    continue;
                }

                // 获取金额
                BigDecimal itemAmount = BigDecimal.ZERO;
                String valueField = item.getValueField();
                if ("taxAmountExcluded".equals(valueField)) {
                    itemAmount = taxAmountExcluded;
                } else if ("taxAmount".equals(valueField)) {
                    itemAmount = taxAmount;
                } else if ("amount".equals(valueField)) {
                    itemAmount = amount;
                }

                if (itemAmount.compareTo(BigDecimal.ZERO) == 0) {
                    continue;
                }

                // 创建凭证明细
                VoucherDetailsDto detail = new VoucherDetailsDto();
                detail.setSubjectId(subjectId);
                detail.setSubjectName(subject.getCode() + " " + subject.getFullName());
                detail.setCurrencyId(localCurrency.getId());
                // 摘要不带【AI生成】前缀，只保留业务含义
                String summaryTitle = businessType.getTitle();
                if (StringUtils.isNotBlank(summaryTitle)) {
                    summaryTitle = summaryTitle.replaceFirst("^【AI生成】", "");
                }
                detail.setSummary(summaryTitle);

                // 判断是否为往来科目
                boolean isTransactionAccount = transactionAccounts.stream()
                        .anyMatch(ta -> subject.getFullName().startsWith(ta));

                // 对于往来科目，设置客户名称（其他应付款除外）
                if (isTransactionAccount && StringUtils.isNotBlank(customerName)) {
                    if (!subject.getFullName().startsWith("其他应付款")) {
                        detail.setCustomerName(customerName);
                    }
                }

                // 设置借贷方向和金额
                if ("借".equals(item.getDirection())) {
                    detail.setDebitAmount(itemAmount);
                    debitAmount = debitAmount.add(itemAmount);
                } else if ("贷".equals(item.getDirection())) {
                    detail.setCreditAmount(itemAmount);
                    creditAmount = creditAmount.add(itemAmount);
                }

                details.add(detail);
            }

            // 检查借贷是否平衡
            if (debitAmount.compareTo(creditAmount) != 0) {
                log.warn("发票ID: {} 生成的凭证借贷不平衡，借方: {}, 贷方: {}",
                        invoice.getId(), debitAmount, creditAmount);
                return null;
            }

            // 创建凭证DTO
            VoucherDto voucherDto = new VoucherDto();
            voucherDto.setWord(voucherWord);
            voucherDto.setCode(voucherCode);
            voucherDto.setVoucherDate(voucherDate);
            voucherDto.setDebitAmount(debitAmount);
            voucherDto.setCreditAmount(creditAmount);
            voucherDto.setInvoiceId(invoice.getId());
            voucherDto.setDetails(details);

            return voucherDto;
        } catch (Exception e) {
            log.error("为发票ID: {} 构建VoucherDto失败", invoice.getId(), e);
            return null;
        }
    }

    /**
     * 为单个发票生成凭证（独立事务）
     * 使用 REQUIRES_NEW 确保每个发票的凭证生成在独立事务中，失败不影响其他发票
     * 内部捕获所有异常，避免传播到外层事务
     *
     * @param invoice       发票
     * @param businessType  业务类型
     * @param subjectMap    科目映射
     * @param localCurrency 本地币种
     * @param voucherWord   凭证字
     * @param voucherCode   凭证编号
     * @param voucherDate   凭证日期
     * @param accountSets   账套信息
     * @param userId        用户ID
     * @return 是否成功
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public boolean generateVoucherForInvoice(Invoice invoice, InvoiceBusinessType businessType,
                                            Map<Long, Subject> subjectMap, CurrencyDto localCurrency,
                                            String voucherWord, Integer voucherCode, LocalDate voucherDate,
                                            AccountSets accountSets, Long userId) {
        try {
            // 根据发票和业务类型模板构建VoucherDto
            VoucherDto voucherDto = buildVoucherDto(invoice, businessType, subjectMap,
                    localCurrency, voucherWord, voucherCode, voucherDate);

            if (voucherDto == null) {
                log.warn("发票ID: {} 构建VoucherDto失败", invoice.getId());
                return false;
            }

            // 调用生成凭证的方法（在独立事务中，如果失败会回滚该事务）
            invoiceService.generateVoucher(
                    Collections.singletonList(voucherDto),
                    accountSets,
                    userId,
                    true // 系统自动生成
            );

            log.info("成功为发票ID: {} 生成凭证", invoice.getId());
            return true;
        } catch (Exception e) {
            // 捕获所有异常，记录日志，返回false
            // 独立事务会回滚，但异常不会传播到外层事务
            log.error("为发票ID: {} 生成凭证失败（独立事务已回滚）", invoice.getId(), e);
            return false;
        }
    }

    /**
     * 批量生成凭证（进项/销项发票）
     * 根据发票的业务类型模板生成凭证，只处理有业务类型且未生成凭证的发票
     *
     * @param accountSetId 账套ID
     * @param params       参数：period（账期，格式：YYYY-MM），invoiceType（可选：IN/OUT，默认IN）
     * @param userVo       当前用户
     * @return 生成结果
     */
    @PostMapping("/batch-generate-voucher")
    public JsonResult batchGenerateVoucher(@SessionAccountSet AccountSets sessionAccountSets,
                                           @RequestBody Map<String, String> params,
                                           @SessionUser UserVo userVo) {
        Long accountSetId = sessionAccountSets.getId();
        try {
            String period = params.get("period");
            String invoiceTypeStr = params.get("invoiceType");

            if (StringUtils.isBlank(period)) {
                return JsonResult.failure("参数不完整：需要period");
            }

            // 解析发票类型（默认IN）
            InvoiceType invoiceType = InvoiceType.IN;
            if (StringUtils.isNotBlank(invoiceTypeStr)) {
                try {
                    invoiceType = InvoiceType.valueOf(invoiceTypeStr.toUpperCase());
                } catch (Exception e) {
                    return JsonResult.failure("发票类型错误，应为IN或OUT");
                }
            }

            // 解析账期
            YearMonth yearMonth;
            try {
                yearMonth = YearMonth.parse(period);
            } catch (Exception e) {
                return JsonResult.failure("账期格式错误，应为YYYY-MM格式");
            }

            // 获取账套信息
            AccountSets accountSets = bqf.selectFrom(qAccountSets)
                    .where(qAccountSets.id.eq(accountSetId))
                    .fetchFirst();

            if (accountSets == null) {
                return JsonResult.failure("账套信息不存在");
            }

            // 查询需要生成凭证的发票（按类型）
            List<Invoice> invoices = queryInvoicesForVoucherGeneration(accountSetId, yearMonth, invoiceType);

            if (CollUtil.isEmpty(invoices)) {
                Map<String, Object> result = new HashMap<>();
                result.put("successCount", 0);
                result.put("failCount", 0);
                result.put("skipCount", 0);
                result.put("message", "当前账期没有需要生成凭证的发票");
                return JsonResult.successful(result);
            }

            // 获取本地币种
            CurrencyDto localCurrency = currencyService.fetchLocal(accountSetId);

            // 获取所有对应类型的业务类型（用于构建模板映射）
            Map<Long, InvoiceBusinessType> businessTypeMap = new HashMap<>();
            List<InvoiceBusinessType> businessTypes = bqf.selectFrom(qInvoiceBusinessType)
                    .where(qInvoiceBusinessType.accountSetsId.eq(accountSetId)
                            .and(qInvoiceBusinessType.invoiceType.eq(invoiceType)))
                    .fetch();
            for (InvoiceBusinessType bt : businessTypes) {
                businessTypeMap.put(bt.getId(), bt);
            }

            // 获取所有科目（用于构建科目映射）
            Map<Long, Subject> subjectMap = new HashMap<>();
            List<Subject> subjects = bqf.selectFrom(qSubject)
                    .where(qSubject.accountSetsId.eq(accountSetId))
                    .fetch();
            for (Subject s : subjects) {
                subjectMap.put(s.getId(), s);
            }

            // 按凭证字分组，每个凭证字维护一个凭证编号计数器
            Map<Long, Integer> voucherCodeMap = new HashMap<>();
            LocalDate voucherDate = accountSets.getCurrentAccountDate();

            int successCount = 0;
            int failCount = 0;
            int skipCount = 0;

            // 逐个处理发票
            for (Invoice invoice : invoices) {
                try {
                    Long businessTypeId = invoice.getInvoiceBusinessTypeId();
                    if (businessTypeId == null) {
                        skipCount++;
                        log.warn("发票ID: {} 没有业务类型，跳过", invoice.getId());
                        continue;
                    }

                    InvoiceBusinessType businessType = businessTypeMap.get(businessTypeId);
                    if (businessType == null) {
                        skipCount++;
                        log.warn("发票ID: {} 的业务类型ID: {} 不存在，跳过", invoice.getId(), businessTypeId);
                        continue;
                    }

                    // 获取凭证字
                    Long voucherWordId = businessType.getVoucherWord();
                    String voucherWord = "记"; // 默认凭证字

                    if (voucherWordId != null) {
                        VoucherWord vw = bqf.selectFrom(QVoucherWord.voucherWord)
                                .where(QVoucherWord.voucherWord.id.eq(voucherWordId)
                                        .and(QVoucherWord.voucherWord.accountSetsId.eq(accountSetId)))
                                .fetchFirst();
                        if (vw != null) {
                            voucherWord = vw.getWord();
                        }
                    }

                    // 获取凭证编号
                    Integer voucherCode = voucherCodeMap.get(voucherWordId);
                    if (voucherCode == null) {
                        voucherCode = voucherService.loadCode(accountSetId, voucherWord, voucherDate);
                        voucherCodeMap.put(voucherWordId, voucherCode);
                    } else {
                        voucherCode++;
                        voucherCodeMap.put(voucherWordId, voucherCode);
                    }

                    // 在独立事务中为发票生成凭证
                    boolean success = generateVoucherForInvoice(
                            invoice, businessType, subjectMap, localCurrency,
                            voucherWord, voucherCode, voucherDate,
                            accountSets, userVo.getId()
                    );

                    if (success) {
                        successCount++;
                    } else {
                        failCount++;
                    }

                } catch (Exception e) {
                    log.error("为发票ID: {} 生成凭证失败", invoice.getId(), e);
                    failCount++;
                }
            }

            // 如果有成功生成的凭证，重新计算余额
            if (successCount > 0) {
                balanceCalcService.calcSynchronization(accountSetId, yearMonth);
            }

            Map<String, Object> result = new HashMap<>();
            result.put("successCount", successCount);
            result.put("failCount", failCount);
            result.put("skipCount", skipCount);
            result.put("totalCount", invoices.size());
            result.put("message", String.format("凭证生成完成：成功 %d 张，失败 %d 张，跳过 %d 张",
                    successCount, failCount, skipCount));

            log.info("批量生成凭证完成 - 账套ID: {}, 发票类型: {}, 账期: {}, 成功: {}, 失败: {}, 跳过: {}",
                    accountSetId, invoiceType != null ? invoiceType.name() : "IN", period, successCount, failCount, skipCount);

            return JsonResult.successful(result);

        } catch (Exception e) {
            log.error("批量生成凭证失败", e);
            return JsonResult.failure("批量生成凭证失败: " + e.getMessage());
        }
    }
}

