package cn.gson.financial.openapi.controller;

import cn.dev33.satoken.annotation.SaIgnore;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.model.entity.ImagePayment;
import cn.gson.financial.kernel.model.entity.QImagePayment;
import cn.gson.financial.kernel.model.entity.QTaxReportResult;
import cn.gson.financial.kernel.model.entity.TaxFilingStatus;
import cn.gson.financial.kernel.model.entity.TaxReportResult;
import cn.gson.financial.kernel.model.hibernate.BlazeJPAQueryFactory;
import cn.gson.financial.kernel.model.repo.TaxFilingStatusRepository;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.YearMonth;
import java.util.*;

/**
 * 开放 API — 申报状态汇总（FN-AL-01 BL-DAY3-04）
 * <p>
 * 路径：/openapi/v1/tax
 * <p>
 * 对应 wx 端 financial-mock.getTaxDeclareStatus，按账期返回 4 税种状态汇总。
 * 字段对齐 wx/utils/financial-mock.js#150-162。
 * <p>
 * 数据来源（TODO 后续切真实）：
 * 现有 TaxFilingStatusController 已有 tax-filing-status 数据模型，但维度是"账套+账期"
 * 的横向表（增值税/个税/印花税在一行的不同列），需要在 service 层转成纵向 items[]。
 * 等 FN-AL-01 收尾时把现有 TaxFilingStatusService.taxDetail / iitDetail 串过来。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/openapi/v1/tax")
@SaIgnore
public class OpenApiTaxDeclareController {

    private final TaxFilingStatusRepository taxFilingStatusRepository;

    @Resource
    @Lazy
    private BlazeJPAQueryFactory bqf;

    private static final QTaxReportResult qTaxReportResult = QTaxReportResult.taxReportResult;
    private static final QImagePayment qImagePayment = QImagePayment.imagePayment;

    /**
     * 工单 123 · TAX-EXPOSE · 税局第 5 源只读暴露（**纯读已落库 3 张表，绝不触发 RPA 登录/任务**）。
     * <p>
     * 严格按 TAX-VERIFY-FIND 对照表分块返回，<b>每块带 {@code caliber}（口径）标注</b>，
     * 红线：底稿/人工口径绝不冒充申报值。
     * <ul>
     *   <li>{@code declareProgress} —— ① 申报口径（税局确认）：{@code tax_report_result} 申报进度 + 截图</li>
     *   <li>{@code filingDraft} —— ③ 底稿/人工口径：{@code tax_filing_status} 自由文本（非申报值）</li>
     *   <li>{@code payableScreenshot} —— ② 仅截图：{@code image_payment}（无结构化税额数字）</li>
     *   <li>{@code creditLevel} —— ④ financial 未存储，返 null + dataIssue</li>
     * </ul>
     * 申报表预填数据（底稿口径）走既有 {@code GET /openapi/v1/declare-data/{accountSetId}?type=&period=}。
     * <p>
     * GET /openapi/v1/tax/source5?accountSetId=123&period=2026-05
     */
    @GetMapping("/source5")
    public JsonResult source5(@RequestParam(name = "accountSetId", required = false) Long accountSetId,
                             @RequestParam(name = "accountSetsId", required = false) Long accountSetsId,
                             @RequestParam String period) {
        Long asId = accountSetId != null ? accountSetId : accountSetsId;
        if (asId == null) return JsonResult.failure("accountSetId 不能为空");
        if (period == null || !period.matches("^\\d{4}-\\d{2}$")) return JsonResult.failure("period 必须为 YYYY-MM");
        YearMonth ym = YearMonth.parse(period);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("accountSetId", asId);
        data.put("period", period);
        data.put("note", "本接口只读已落库税局数据，绝不触发任何 RPA 登录/任务；各块 caliber 为口径标注，底稿口径不得当申报值");
        List<Map<String, Object>> dataIssues = new ArrayList<>();

        // ① 申报口径（税局确认）：tax_report_result（RPA 抓税局申报页落库），按账期匹配 taxTime
        TaxReportResult trr = bqf.selectFrom(qTaxReportResult)
                .where(qTaxReportResult.accountSetId.eq(asId)
                        .and(qTaxReportResult.taxTime.goe(ym.atDay(1)))
                        .and(qTaxReportResult.taxTime.loe(ym.atEndOfMonth())))
                .orderBy(qTaxReportResult.taxTime.desc())
                .fetchFirst();
        Map<String, Object> declareProgress = new LinkedHashMap<>();
        declareProgress.put("caliber", "申报口径（税局确认·RPA 抓取申报进度）");
        if (trr != null) {
            declareProgress.put("totalItems", trr.getAllCount());
            declareProgress.put("declaredItems", trr.getSuccessCount());
            declareProgress.put("pendingItems", trr.getAllCount() - trr.getSuccessCount());
            declareProgress.put("taxTime", trr.getTaxTime());
            declareProgress.put("screenshotUrl", trr.getImgPath());
        } else {
            declareProgress.put("totalItems", null);
            dataIssues.add(issue("declareProgress", "NO_DECLARE_PROGRESS",
                    "该账期无申报进度记录（tax_report_result），疑未抓取/未申报"));
        }
        data.put("declareProgress", declareProgress);

        // ③ 底稿/人工口径：tax_filing_status（自由文本，非申报值）
        TaxFilingStatus tfs = taxFilingStatusRepository.findByAccountSetIdAndTaxPeriod(asId, period).orElse(null);
        Map<String, Object> filingDraft = new LinkedHashMap<>();
        filingDraft.put("caliber", "底稿/人工填写口径（自由文本，★非税局申报值，不得当申报口径用）");
        if (tfs != null) {
            filingDraft.put("salaryWages", tfs.getSalaryWages());
            filingDraft.put("salaryWagesTax", tfs.getSalaryWagesTax());
            filingDraft.put("businessIncome", tfs.getBusinessIncome());
            filingDraft.put("businessIncomeTax", tfs.getBusinessIncomeTax());
            filingDraft.put("stateTax", tfs.getStateTax());
            filingDraft.put("stateTaxAmount", tfs.getStateTaxAmount());
        } else {
            dataIssues.add(issue("filingDraft", "NO_FILING_DRAFT",
                    "该账期无报税情况底稿（tax_filing_status）"));
        }
        data.put("filingDraft", filingDraft);

        // ② 待缴税款截图：image_payment（无账期维度，取该账套最近一张；★仅截图无结构化税额）
        ImagePayment ip = bqf.selectFrom(qImagePayment)
                .where(qImagePayment.accountSetsId.eq(asId))
                .orderBy(qImagePayment.id.desc())
                .fetchFirst();
        Map<String, Object> payableScreenshot = new LinkedHashMap<>();
        payableScreenshot.put("caliber", "税局待缴税款页截图（★仅图像 URL，无结构化税额数值；无账期维度，为该账套最近一次）");
        payableScreenshot.put("imageUrl", ip != null ? ip.getImageUrl() : null);
        if (ip == null) {
            dataIssues.add(issue("payableScreenshot", "NO_PAYABLE_SCREENSHOT", "该账套无待缴税款截图（image_payment）"));
        }
        data.put("payableScreenshot", payableScreenshot);

        // ④ 纳税信用等级：financial 未存储
        data.put("creditLevel", null);
        dataIssues.add(issue("creditLevel", "NOT_STORED_IN_FINANCIAL",
                "纳税信用等级 financial 未存储，需补外部源（非本系统口径）"));

        // 申报表预填（底稿口径）走既有端点
        data.put("declareDataHint", "申报表预填数据（财务底稿口径）走 GET /openapi/v1/declare-data/{accountSetId}?type=&period=");

        data.put("dataIssues", dataIssues);
        log.info("[source5] accountSetId={} period={} hasProgress={} hasDraft={} hasShot={} issues={}",
                asId, period, trr != null, tfs != null, ip != null, dataIssues.size());
        return JsonResult.successful(data);
    }

    private Map<String, Object> issue(String block, String code, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("block", block);
        m.put("code", code);
        m.put("message", message);
        return m;
    }

    /**
     * 按账期返回 4 税种状态
     * <p>
     * GET /openapi/v1/tax/declare-status?accountSetId=123&period=2026-05
     */
    @GetMapping("/declare-status")
    public JsonResult declareStatus(@RequestParam(name = "accountSetId", required = false) Long accountSetId,
                                    @RequestParam(name = "accountSetsId", required = false) Long accountSetsId,
                                    @RequestParam String period) {
        Long asId = accountSetId != null ? accountSetId : accountSetsId;
        if (asId == null) return JsonResult.failure("accountSetId 不能为空");
        if (period == null || period.isBlank()) return JsonResult.failure("period 不能为空（YYYY-MM）");

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("accountSetId", asId);
        data.put("period", period);

        List<Map<String, Object>> items = new ArrayList<>();
        // TODO 接 TaxFilingStatusService.taxDetail(asId, period) 还原真实 4 税种状态
        items.add(item("VAT",      "增值税及附加", "success",   period + "-10 14:20", 4520.00, 4520.00, 4520.00, null));
        items.add(item("INCOME",   "企业所得税",   "pending",   null,                  6800.00, 6800.00, 0,       null));
        items.add(item("PERSONAL", "个人所得税",   "success",   period + "-09 10:00", 1260.00, 1260.00, 1260.00, null));
        items.add(item("STAMP",    "印花税",       "need_more", null,                  0,       0,       0,       "请补合同明细"));
        data.put("items", items);

        return JsonResult.successful(data);
    }

    /**
     * 单税种状态详情（TODO，供 tax-progress 进度页用）
     * <p>
     * GET /openapi/v1/tax/declare-status/{taxCode}?accountSetId=&period=
     */
    @GetMapping("/declare-status/{taxCode}")
    public JsonResult declareStatusDetail(@org.springframework.web.bind.annotation.PathVariable String taxCode,
                                          @RequestParam(name = "accountSetId", required = false) Long accountSetId,
                                          @RequestParam(name = "accountSetsId", required = false) Long accountSetsId,
                                          @RequestParam String period) {
        Long asId = accountSetId != null ? accountSetId : accountSetsId;
        if (asId == null) return JsonResult.failure("accountSetId 不能为空");

        // TODO 接 TaxFilingStatusService 的 taxDetail / iitDetail
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("taxCode", taxCode);
        data.put("accountSetId", asId);
        data.put("period", period);
        data.put("status", "success");
        data.put("declaredAt", period + "-10 14:20");
        data.put("receiptUrl", "https://mock.qingyicloud.com/tax-receipt-mock.pdf");
        data.put("timeline", List.of(
                Map.of("title", "受理", "time", period + "-10 09:00", "done", true),
                Map.of("title", "申报", "time", period + "-10 14:20", "done", true),
                Map.of("title", "扣款", "time", period + "-10 16:00", "done", true)
        ));
        return JsonResult.successful(data);
    }

    private Map<String, Object> item(String taxCode, String taxName, String status, String declaredAt,
                                     double amount, double payable, double paid, String missing) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("taxCode", taxCode);
        m.put("taxName", taxName);
        m.put("status", status);              // success | pending | failed | need_more
        m.put("declaredAt", declaredAt);
        m.put("amount", amount);
        m.put("payable", payable);
        m.put("paid", paid);
        if (missing != null) m.put("missing", missing);
        return m;
    }
}
