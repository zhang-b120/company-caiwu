package cn.gson.financial.kernel.service;

import cn.gson.financial.kernel.common.CopierCopyUtils;
import cn.gson.financial.kernel.model.dto.TaxDto;
import cn.gson.financial.kernel.model.entity.*;
import cn.gson.financial.kernel.model.ua.dto.TaxTableResultDTO;
import cn.gson.financial.kernel.model.ua.request.GetCommonReq;
import cn.gson.financial.kernel.model.ua.resp.GetTaxTableResultResp;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.json.JSONUtil;
import com.querydsl.core.types.dsl.Expressions;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 一键报税
 */
@Service
public class TaxService extends AbsService{

    private QAccountSets qAccountSets = QAccountSets.accountSets;
    private QTaskExecuteList qTaskExecuteList = QTaskExecuteList.taskExecuteList;
    private QTaxTable qTaxTable = QTaxTable.taxTable;
    private QTaxReportResult qTaxReportResult = QTaxReportResult.taxReportResult;

    @Value("${static-url.base-url}")
    private String baseUrl;

    @Value("${static-url.img}")
    private String img;

    public void saveTaxItem(TaxDto taxItem) {
        taxItem.setTaxId(snowflakeId.generate());
        lazyDao.executeSql("insertTaxItem",taxItem);
    }
    public List<TaxDto> selectTaxItem(TaxDto taxItem) {
        if(taxItem == null) {
            return null;
        }
        Map<String, Object> params = taxItem.toMap();
        List<TaxDto> taxDtoList = lazyDao.findBySql("selectTaxItem", params, TaxDto.class);
        if (CollUtil.isNotEmpty(taxDtoList)) {

            taxDtoList.forEach(item -> {
                if(StringUtils.isNotEmpty(item.getImageUrl())) {
                    item.setImageUrl(baseUrl + img + item.getImageUrl());
                }
                if(StringUtils.isNotEmpty(item.getTotalTaxes())) {
                    item.setTotalTaxes(item.getTotalTaxes().replace(" ", ""));
                }
                if(StringUtils.isNotEmpty(item.getTaxImageUrl())){
                    String taxImageUrl = item.getTaxImageUrl();
                    item.setTaxImageUrl(baseUrl + img + taxImageUrl);
                    item.setTaxImageUrlList(Arrays.asList(taxImageUrl.split("#")).stream().map(taxImage -> baseUrl + img + taxImage).toList());
                }
                if(StringUtils.isNotEmpty(item.getPaymentUrl())){
                    String paymentUrl = item.getPaymentUrl();
                    item.setPaymentUrl(baseUrl + img + paymentUrl);
                    item.setTaxImageUrlList(Arrays.asList(paymentUrl.split("#")).stream().map(paymentImage -> baseUrl + img + paymentImage).toList());
                }
            });
        }


        return taxDtoList;
    }
    public void deleteTaxItem(Map<String,Object> map) {
        lazyDao.executeSql("deleteTaxItem",map);
    }
    //判断是否存在特殊发票
    public Long invoiceCount (Map<String,String> map) {
        //将map变为map<String,Object>模式的
        Map<String, Object> objectMap = new HashMap<>(map);
        Long count = lazyDao.getCount("invoiceCount",objectMap);
        return count;
    }
    public void saveTaxTaskLog(TaxDto tax) {
            //将list中的数据封装到map中
            Map<String, Object> map = tax.toMap();
            map.put("id", snowflakeId.generate());
            lazyDao.executeSql("insertTaxTaskLog", map);
    }
    @Transactional
    public void saveTaskExecuteList(TaskExecuteList taskExecute){
        jqf.insert(qTaskExecuteList)
                .columns(qTaskExecuteList.sponsor,
                        qTaskExecuteList.taskType,
                        qTaskExecuteList.taskStatus,
                        qTaskExecuteList.taskExecutionTime,
                        qTaskExecuteList.taskResult,
                        qTaskExecuteList.province,
                        qTaskExecuteList.taskData,
                        qTaskExecuteList.sort)
                .values(taskExecute.getSponsor(),
                        taskExecute.getTaskType(),
                        taskExecute.getTaskStatus(),
                        taskExecute.getTaskExecutionTime(),
                        taskExecute.getTaskResult(),
                        taskExecute.getProvince(),
                        taskExecute.getTaskData(),
                        taskExecute.getSort())
                .execute();
    }
    public String selectElectricityTaxPhone(Map<String, Object> companyMap) {
        String electricityTaxPhone =  bqf.selectFrom(qAccountSets).select(qAccountSets.electricityTaxPhone).where(qAccountSets.creditCode.eq(companyMap.get("creditCode").toString())).fetchOne();
        return electricityTaxPhone;
    }
    public TaskExecuteList selectTaskExecuteList(Map<String, String> map) {
        TaskExecuteList taskExecuteList =  bqf.selectFrom(qTaskExecuteList).where(qTaskExecuteList.sponsor.eq(map.get("sponsor").toString()).and(qTaskExecuteList.taskType.eq(2)).and(qTaskExecuteList.taskStatus.eq("0"))).orderBy(qTaskExecuteList.sort.asc()).fetchFirst();
        return taskExecuteList;
    }
    @Transactional(rollbackFor = Exception.class)
    public int updateTaskExecuteList(Map<String,String> result){

        jqf.update(qTaskExecuteList).set(qTaskExecuteList.taskStatus,"1").set(qTaskExecuteList.taskResult, JSONUtil.toJsonStr(result)).where(qTaskExecuteList.id.eq(Long.valueOf(result.get("taskListId")))).execute();
        if ("success".equals(result.get("status"))){
            jqf.update(qTaxTable).set(qTaxTable.taxState,"已申报").where(qTaxTable.taxId.eq(Long.valueOf(result.get("taxId")))).execute();
        }
        return 1;
    }
    /**
     * 根据任务类型、公司、添加任务时间判断任务是否执行成功
     * @param taxTask
     * @param companyName
     * @param taskDate
     * @return
     */
    public boolean getTaskStatus(String taxTask, String companyName, LocalDate taskDate){
        TaskExecuteList task = jqf.selectFrom(qTaskExecuteList)
                .where(qTaskExecuteList.taskData.like("%"+taxTask+"%")
                        .and(qTaskExecuteList.taskData.like("%"+companyName+"%"))
                        .and(Expressions.dateTemplate(LocalDate.class,
                                "date_format({0}, '%Y-%m-%d')",
                                qTaskExecuteList.taskExecutionTime).eq(taskDate))).fetchFirst();
        String result = task.getTaskResult();
        if ("未执行".equals(result)){
            return true;
        }else{
            Map<String,Object> map = JSONUtil.toBean(result,HashMap.class);
            String status = String.valueOf(map.get("status"));
            return "error".equals(status) ? false : true;
        }

    }

    @Transactional
    public void insertTaxReportResult(TaxReportResult result){
        result.setId(snowflakeId.generate());
        lazyDao.executeSql("insertTaxReportResult", result);
    }

    /**
     * 获取报税结果
     * req
     * @param req
     * @return
     */
    public GetTaxTableResultResp getTaxTableResult(GetCommonReq req) {
        GetTaxTableResultResp resp = new GetTaxTableResultResp();
        if(ObjectUtils.isEmpty(req.getAccountSetsId()) || ObjectUtils.isEmpty(req.getPeriod())){
            return resp;
        }
        //当前账期数据
        resp.setCurrent(getTaxTableResultDTO(req.getAccountSetsId(), req.getPeriod()));
        //上账期数据
        resp.setLast(getTaxTableResultDTO(req.getAccountSetsId(), req.getPeriod().minusMonths(1L)));
        return resp;
    }

    private TaxTableResultDTO getTaxTableResultDTO(Long accountSetId, LocalDate period){
        TaxTableResultDTO dto = new TaxTableResultDTO();
        TaxReportResult current = bqf.selectFrom(qTaxReportResult)
                .where(qTaxReportResult.accountSetId.eq(accountSetId)
                        .and(qTaxReportResult.taxTime.eq(period))
                )
                .limit(1L)
                .fetchFirst();
        if(ObjectUtils.isNotEmpty(current)){
            dto = CopierCopyUtils.convertTo(current, TaxTableResultDTO.class);
            if(StringUtils.isNotEmpty(current.getImgPath())) {
                dto.setImgPath(baseUrl + img + current.getImgPath());
            }
        }
        return dto;
    }
}
