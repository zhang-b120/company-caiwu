package cn.gson.financial.ua.controller;

import cn.dev33.satoken.annotation.SaIgnore;
import cn.gson.financial.kernel.controller.JsonResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.*;

/**
 * 小程序端 — 月度报表 KPI 汇总（FN-AL-01 BL-DAY3-03）
 * <p>
 * 路径前缀：/ua/report（小程序专属域，区别于 BT/XW 桌面端的 /openapi/v1/report）
 * <p>
 * 对应 wx 端 financial-mock.getMonthlyReport，返回三大报表入口 + 关键指标卡。
 * 字段对齐 wx/utils/financial-mock.js#128-147。
 * <p>
 * 数据来源（TODO 后续切真实）：
 * <ul>
 *   <li>indicators.taxPayable/taxPaid/taxRetained → 接 TaxFilingStatus / TaxService</li>
 *   <li>indicators.revenue/expense/profit → 接 ReportService 的利润表数据</li>
 *   <li>reports[*].available → 三大报表表头是否生成（ReportService.list）</li>
 * </ul>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/ua/report")
@SaIgnore
public class UaReportController {

    /**
     * 月度报表汇总：3 张报表入口 + 6 项关键指标
     * <p>
     * GET /ua/report/uaMonthly?accountSetId=123&period=2026-05
     * <p>
     * BL-DAY3-06 字段对齐：query 参数统一用 accountSetId（与 accountSetsId 同含义，控制器内归一）
     */
    @GetMapping("/uaMonthly")
    public JsonResult uaMonthly(@RequestParam(name = "accountSetId", required = false) Long accountSetId,
                                @RequestParam(name = "accountSetsId", required = false) Long accountSetsId,
                                @RequestParam String period) {
        Long asId = accountSetId != null ? accountSetId : accountSetsId;
        if (asId == null) return JsonResult.failure("accountSetId 不能为空");
        if (period == null || period.isBlank()) return JsonResult.failure("period 不能为空（YYYY-MM）");

        // 校验 period 格式
        try {
            LocalDate.parse(period + "-01");
        } catch (Exception e) {
            return JsonResult.failure("period 格式错误，应为 YYYY-MM");
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("accountSetId", asId);
        data.put("period", period);

        // 关键指标（TODO 接真实 TaxService + ReportService 聚合）
        Map<String, Object> indicators = new LinkedHashMap<>();
        indicators.put("taxPayable", 12580.50);
        indicators.put("taxPaid", 9800.00);
        indicators.put("taxRetained", 2780.50);
        indicators.put("revenue", 458000);
        indicators.put("expense", 312000);
        indicators.put("profit", 146000);
        data.put("indicators", indicators);

        // 三大报表入口（TODO 接 ReportService.list 判断 available）
        String updateTime = period + "-11 09:00";
        List<Map<String, Object>> reports = new ArrayList<>();
        reports.add(reportItem("zcfz", "资产负债表", updateTime, true));
        reports.add(reportItem("lr",   "利润表",     updateTime, true));
        reports.add(reportItem("xjll", "现金流量表", updateTime, true));
        data.put("reports", reports);

        return JsonResult.successful(data);
    }

    /**
     * 全量报表（含 VAT_TAX 增值税申报底稿）—— FN-AL-02 占位
     * <p>
     * GET /ua/report/uaReport?type=BALANCE_SHEET|PROFIT_LOSS|CASH_FLOW|VAT_TAX&accountSetId=&period=
     * <p>
     * 当前 VAT_TAX 未实现，与原 OpenApiReportController 行为一致返回 UNSUPPORTED_TYPE，
     * 等 FN-AL-02 落地。
     */
    @GetMapping("/uaReport")
    public JsonResult uaReport(@RequestParam String type,
                               @RequestParam(name = "accountSetId", required = false) Long accountSetId,
                               @RequestParam(name = "accountSetsId", required = false) Long accountSetsId,
                               @RequestParam String period) {
        Long asId = accountSetId != null ? accountSetId : accountSetsId;
        if (asId == null) return JsonResult.failure("accountSetId 不能为空");
        if ("VAT_TAX".equalsIgnoreCase(type)) {
            // FN-AL-02 待实现
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("code", "UNSUPPORTED_TYPE");
            err.put("message", "VAT_TAX 增值税申报底稿尚未实现（FN-AL-02）");
            return JsonResult.successful(err);
        }
        // 其它类型留给后续逐步对接现有 ReportController（/report/data/{templateKey}）
        Map<String, Object> placeholder = new LinkedHashMap<>();
        placeholder.put("type", type);
        placeholder.put("accountSetId", asId);
        placeholder.put("period", period);
        placeholder.put("note", "请走 /report/data/{templateKey} 获取明细，本端点为聚合占位");
        return JsonResult.successful(placeholder);
    }

    private Map<String, Object> reportItem(String templateKey, String name, String updateTime, boolean available) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("templateKey", templateKey);
        m.put("name", name);
        m.put("updateTime", updateTime);
        m.put("available", available);
        return m;
    }
}
