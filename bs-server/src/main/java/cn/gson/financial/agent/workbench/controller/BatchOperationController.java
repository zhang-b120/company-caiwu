package cn.gson.financial.agent.workbench.controller;

import cn.gson.financial.agent.workbench.service.BatchOperationService;
import cn.gson.financial.kernel.controller.JsonResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 批量操作控制器
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/workbench/batch")
public class BatchOperationController {
    
    private final BatchOperationService batchOperationService;
    
    /**
     * 批量下载发票
     */
    @PostMapping("/invoice")
    public JsonResult batchDownloadInvoices(@RequestBody Map<String, Object> request) {
        try {
            @SuppressWarnings("unchecked")
            List<Long> accountSetsIds = (List<Long>) request.get("accountSetsIds");
            String periodStr = (String) request.get("period");
            LocalDate period = periodStr != null ? LocalDate.parse(periodStr + "-01") : LocalDate.now().withDayOfMonth(1);
            
            if (accountSetsIds == null || accountSetsIds.isEmpty()) {
                return JsonResult.failure("请选择至少一个账套");
            }
            
            Map<String, Object> result = batchOperationService.batchDownloadInvoices(accountSetsIds, period);
            return JsonResult.successful(result);
        } catch (Exception e) {
            log.error("批量下载发票失败", e);
            return JsonResult.failure("批量下载发票失败：" + e.getMessage());
        }
    }
    
    /**
     * 批量制作凭证
     */
    @PostMapping("/voucher")
    public JsonResult batchMakeVouchers(@RequestBody Map<String, Object> request) {
        try {
            @SuppressWarnings("unchecked")
            List<Long> accountSetsIds = (List<Long>) request.get("accountSetsIds");
            String periodStr = (String) request.get("period");
            LocalDate period = periodStr != null ? LocalDate.parse(periodStr + "-01") : LocalDate.now().withDayOfMonth(1);
            
            if (accountSetsIds == null || accountSetsIds.isEmpty()) {
                return JsonResult.failure("请选择至少一个账套");
            }
            
            Map<String, Object> result = batchOperationService.batchMakeVouchers(accountSetsIds, period);
            return JsonResult.successful(result);
        } catch (Exception e) {
            log.error("批量制作凭证失败", e);
            return JsonResult.failure("批量制作凭证失败：" + e.getMessage());
        }
    }
    
    /**
     * 批量申报税务
     */
    @PostMapping("/tax")
    public JsonResult batchDeclareTax(@RequestBody Map<String, Object> request) {
        try {
            @SuppressWarnings("unchecked")
            List<Long> accountSetsIds = (List<Long>) request.get("accountSetsIds");
            String periodStr = (String) request.get("period");
            LocalDate period = periodStr != null ? LocalDate.parse(periodStr + "-01") : LocalDate.now().withDayOfMonth(1);
            
            if (accountSetsIds == null || accountSetsIds.isEmpty()) {
                return JsonResult.failure("请选择至少一个账套");
            }
            
            Map<String, Object> result = batchOperationService.batchDeclareTax(accountSetsIds, period);
            return JsonResult.successful(result);
        } catch (Exception e) {
            log.error("批量申报税务失败", e);
            return JsonResult.failure("批量申报税务失败：" + e.getMessage());
        }
    }
    
    /**
     * 查询批量任务状态
     */
    @GetMapping("/status/{taskId}")
    public JsonResult getBatchTaskStatus(@PathVariable String taskId) {
        try {
            BatchOperationService.BatchTaskStatus status = batchOperationService.getBatchTaskStatus(taskId);
            return JsonResult.successful(status);
        } catch (Exception e) {
            log.error("查询批量任务状态失败", e);
            return JsonResult.failure("查询任务状态失败：" + e.getMessage());
        }
    }
}
