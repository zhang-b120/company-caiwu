package cn.gson.financial.crm.controller;

import cn.dev33.satoken.annotation.SaIgnore;
import cn.gson.financial.kernel.model.vo.DownLoadReportVo;
import cn.gson.financial.kernel.service.ReportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * @Author zhb
 * @Date 2025-07-03
 * @Description
 */
@RestController
@RequestMapping("/crmReport")
@RequiredArgsConstructor
@Slf4j
public class CrmReportController {

    private final ReportService reportService;

    /**
     * 报表下载
     * @param downLoadReportVo
     */
    @PostMapping("/crmDownLoadReport")
    @SaIgnore
    public void crmDownLoadReport(@RequestBody DownLoadReportVo downLoadReportVo) {
        reportService.downLoadReport(downLoadReportVo);
    }
}