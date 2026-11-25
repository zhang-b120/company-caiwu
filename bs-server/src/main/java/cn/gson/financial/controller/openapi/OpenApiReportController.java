package cn.gson.financial.controller.openapi;

import cn.dev33.satoken.annotation.SaIgnore;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.model.dto.CurrencyDto;
import cn.gson.financial.kernel.model.dto.ReportItemsDto;
import cn.gson.financial.kernel.model.entity.AccountSets;
import cn.gson.financial.kernel.model.entity.QAccountSets;
import cn.gson.financial.kernel.model.hibernate.BlazeJPAQueryFactory;
import cn.gson.financial.kernel.service.CurrencyService;
import cn.gson.financial.kernel.service.ReportService;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;

/**
 * 开放 API — 报表数据
 * 路径前缀 /openapi/v1/report
 *
 * 当前支持 3 类报表（财务系统模板键）：
 *   BALANCE_SHEET → zcfz   资产负债表
 *   PROFIT_LOSS   → lr     利润表
 *   CASH_FLOW     → xjll   现金流量表
 *
 * VAT_TAX（增值税申报底稿）当前未实现，返回 UNSUPPORTED_TYPE。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/openapi/v1/report")
@SaIgnore
public class OpenApiReportController {

    private final ReportService reportService;
    private final CurrencyService currencyService;

    @Resource
    @Lazy
    private BlazeJPAQueryFactory bqf;

    private static final QAccountSets qAccountSets = QAccountSets.accountSets;

    private static final Map<String, String> TYPE_TO_TEMPLATE_KEY = Map.of(
            "BALANCE_SHEET", "zcfz",
            "PROFIT_LOSS", "lr",
            "CASH_FLOW", "xjll"
    );

    /**
     * P1-1 报表数据
     * GET /openapi/v1/report?accountSetsId=88&type=PROFIT_LOSS&year=2025&month=12
     */
    @GetMapping
    public JsonResult report(@RequestParam Long accountSetsId,
                             @RequestParam String type,
                             @RequestParam Integer year,
                             @RequestParam Integer month) {
        if (accountSetsId == null) {
            return JsonResult.failure("accountSetsId 不能为空");
        }
        if (year == null || month == null) {
            return JsonResult.failure("year/month 不能为空");
        }
        String templateKey = TYPE_TO_TEMPLATE_KEY.get(type == null ? "" : type.trim().toUpperCase());
        if (templateKey == null) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("errorType", "UNSUPPORTED_TYPE");
            err.put("supported", TYPE_TO_TEMPLATE_KEY.keySet());
            return JsonResult.failure("type 非法或未实现：" + type
                    + "（合法值：BALANCE_SHEET / PROFIT_LOSS / CASH_FLOW）").setData(err);
        }

        AccountSets accountSets = bqf.selectFrom(qAccountSets)
                .where(qAccountSets.id.eq(accountSetsId))
                .fetchFirst();
        if (accountSets == null) {
            return JsonResult.failure("账套不存在：" + accountSetsId);
        }

        // 取本币
        CurrencyDto local = currencyService.fetchLocal(accountSetsId);
        if (local == null) {
            return JsonResult.failure("账套未配置本币");
        }

        // 报表用月初日期作为基准
        LocalDate voucherDate = YearMonth.of(year, month).atDay(1);

        try {
            List<ReportItemsDto> items = reportService.items(templateKey, accountSetsId, false);
            List<ReportItemsDto> data = reportService.reportData(
                    items, accountSets, templateKey, local.getId(), voucherDate, "");

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("accountSetsId", accountSetsId);
            result.put("type", type.trim().toUpperCase());
            result.put("templateKey", templateKey);
            result.put("year", year);
            result.put("month", month);
            result.put("period", String.format("%04d-%02d", year, month));
            result.put("items", flattenItems(data));
            return JsonResult.successful(result);
        } catch (Exception e) {
            log.error("openapi 报表查询失败 type={}, accountSetsId={}, year={}, month={}",
                    type, accountSetsId, year, month, e);
            return JsonResult.failure("报表计算失败：" + e.getMessage());
        }
    }

    /**
     * 把 ReportItemsDto 扁平化成对外 JSON 友好的结构。
     * values 是 Map<String, BigDecimal>，里面 key 通常是列名（如 "C"=本期金额、"M"=年初金额 等）。
     * 我们直接保留原始 values，加上 title/level/pos 等基本字段。
     */
    private List<Map<String, Object>> flattenItems(List<ReportItemsDto> items) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (items == null) return out;
        for (ReportItemsDto i : items) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", i.getId());
            m.put("title", i.getTitle());
            m.put("level", i.getLevel());
            m.put("pos", i.getPos());
            m.put("rowNum", i.getRowNum());
            m.put("parentId", i.getParentId());
            m.put("isBolder", Boolean.TRUE.equals(i.getIsBolder()));
            m.put("isFolding", Boolean.TRUE.equals(i.getIsFolding()));
            m.put("values", i.getValues() != null ? i.getValues() : new HashMap<String, BigDecimal>());
            out.add(m);
        }
        return out;
    }
}
