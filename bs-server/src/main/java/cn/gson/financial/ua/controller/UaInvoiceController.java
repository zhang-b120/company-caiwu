package cn.gson.financial.ua.controller;/* Copyright © 2020 Yuech and/or its affiliates. All rights reserved. */

import cn.dev33.satoken.annotation.SaIgnore;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.controller.PageResults;
import cn.gson.financial.kernel.model.dto.AllTaskDto;
import cn.gson.financial.kernel.model.dto.InvoiceDto;
import cn.gson.financial.kernel.model.dto.InvoiceExportItemsDto;
import cn.gson.financial.kernel.model.entity.AccountSets;
import cn.gson.financial.kernel.model.enums.InvoiceType;
import cn.gson.financial.kernel.service.*;
import cn.gson.financial.kernel.tool.ImportVoUtil;
import cn.gson.financial.kernel.model.ua.request.GetCommonReq;
import cn.gson.financial.kernel.model.ua.request.GetInvoiceListReq;
import cn.gson.financial.rpa.TaxBrowserManager;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.poi.excel.ExcelUtil;
import cn.hutool.poi.excel.ExcelWriter;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.DataFormat;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * @Author zhb
 * @Date 2025-05-19
 * @Description
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/ua/invoice")
public class UaInvoiceController {

    private final TaxBrowserManager taxBrowserManager;

    private final AllTaskService allTaskService;

    private final UserService userService;
    private final AccountSetsService accountSetsService;
    private final InvoiceService invoiceService;

    /**
     * 获取发票信息
     * @param req
     * @param type
     * @param response
     * @return
     * @throws IOException
     */
    @SaIgnore
    @PostMapping("/uaGetInvoiceList/{type}")
    public JsonResult uaGetInvoiceList(@RequestBody GetInvoiceListReq req, @PathVariable InvoiceType type, HttpServletResponse response) throws IOException {
        PageResults<InvoiceDto> list = invoiceService.list(type, req.getAccountSetsId(), req.getPage(), req.getQuery());

        if (req.getQuery().download) {
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
            InvoiceDto summary = invoiceService.summary(type, req.getAccountSetsId(), req.getQuery());
            return JsonResult.successful()
                    .data("dataList", list)
                    .data("summary", summary);
        }
    }

    /**
     * 获取一键取票数据
     * @param req
     * @return
     */
    @SaIgnore
    @PostMapping("/uaGetAccountSet")
    public JsonResult uaGetAccountSet(@RequestBody GetCommonReq req){
        return JsonResult.successful(this.accountSetsService.getAccountSetsByAccountSetsId(req.getAccountSetsId()));
    }

    /**
     * 一键取票
     * @param map
     * @return
     */
    @SaIgnore
    @PostMapping("/uaDownInvoiceAll")
    public JsonResult uaDownInvoiceAll(@RequestBody Map<String,Object> map){
        List<String> invoceTypeList = (List<String>)map.get("invoiceType");
        String list = map.get("list").toString();
        List<AccountSets> accountSetsList = JSONArray.parseArray(list,AccountSets.class);
        //获取用户手机号
        String mobile = userService.uaGetUserInfo(accountSetsList.get(0).getId()).getMobile();
        AllTaskDto allTaskDto = new AllTaskDto();
        allTaskDto.setTaskTime(
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        );
        allTaskDto.setTaskType("一键取票");
        allTaskDto.setTaskAllCount(accountSetsList.size()*invoceTypeList.size());
        allTaskDto.setTaskCreaterName(mobile);
        Long taskId = allTaskService.insert(allTaskDto);
        int successCount = 0;
        int failCount = 0;

        for(AccountSets accountSets:accountSetsList){
            for(String invoiceType:invoceTypeList){
                Map<String,String> taskItemParams = new HashMap<>();
                taskItemParams.put("invoiceType",invoiceType);
                taskItemParams.put("phoneNumber",mobile);//登录手机号
                taskItemParams.put("creditCode",accountSets.getCreditCode());//税号
                taskItemParams.put("password",accountSets.getElectricityTaxCode());//点税密码
                taskItemParams.put("province",accountSets.getProvince());//省份
                taskItemParams.put("accountSetsId",accountSets.getId().toString());//账套id
                taskItemParams.put("startDate",map.get("startDate").toString());
                taskItemParams.put("endDate",map.get("endDate").toString());
                taskItemParams.put("invoiceType",invoiceType);
                taskItemParams.put("taskId",taskId.toString());
                taskItemParams.put("vatType",accountSets.getVatType().name());
                //将taskItemParams转为JSON字符串的数据存放在TaskParams里
                String taskItemParamsJson = JSONObject.toJSONString(taskItemParams);
                allTaskDto.setTaskParams(taskItemParamsJson);
                // 获取任务执行标记
                allTaskDto.setAccountSetId(accountSets.getId());
                allTaskDto.setCompanyName(accountSets.getCompanyName());
                Map<String,String> params = new HashMap<>();
                params.put("phoneNumber",mobile);//登录手机号
                params.put("creditCode",accountSets.getCreditCode());//税号
                params.put("password",accountSets.getElectricityTaxCode());//点税密码
                params.put("province",accountSets.getProvince());//省份
                params.put("accountSetsId",accountSets.getId().toString());//账套id
                params.put("startDate",map.get("startDate").toString());
                params.put("endDate",map.get("endDate").toString());
                params.put("invoiceType",invoiceType);//修改-----------------
                Map<String,String> result = new HashMap<>();
                if (invoiceType.equals("已勾选")&&accountSets.getVatType().name().equals("COMMONLY")){// invoiceType ["已勾选", "未勾选", "开具发票","取得发票"]
                    result = taxBrowserManager.downgouxuanInvoice(params,accountSets);
                }else if(invoiceType.equals("开具发票")||invoiceType.equals("取得发票")){
                    result = taxBrowserManager.downInvoice(params,accountSets);
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
                allTaskService.insertItem(allTaskDto);

            }
        }
        return null;
    }

    /**
     * 获取发票汇总信息
     * @param req
     * @return
     */
    @SaIgnore
    @PostMapping("/uaGetInvoiceSumInfo")
    public JsonResult uaGetInvoiceSumInfo(@RequestBody GetCommonReq req){
        return JsonResult.successful(invoiceService.uaGetInvoiceSumInfo(req.getAccountSetsId(), req.getPeriod()));
    }
}