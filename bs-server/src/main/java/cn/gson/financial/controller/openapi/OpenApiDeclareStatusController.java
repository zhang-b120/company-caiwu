package cn.gson.financial.controller.openapi;

import cn.dev33.satoken.annotation.SaIgnore;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.model.entity.DeclareTask;
import cn.gson.financial.kernel.model.entity.QDeclareTask;
import cn.gson.financial.kernel.model.hibernate.BlazeJPAQueryFactory;
import cn.hutool.core.util.StrUtil;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.web.bind.annotation.*;

import java.time.YearMonth;
import java.util.*;

/**
 * FN-REVERSE-CHECKOUT-AND-DECLARE-STATUS 改造 2.2 —— 本期申报状态查询 OpenAPI。
 * 路径 {@code GET /openapi/v1/account-sets/{accountSetId}/declare-status?period=yyyy-MM}。
 *
 * <p>复用 {@code declare_task} 口径（与 {@code AccountSetsProgressService} 同源），包成 openapi 让 BT
 * <b>硬判"已报税"</b>（替代 BT-REDO 一期"已关账近似 + 人工确认"）。
 *
 * <p>返回各税种 {@code declared / undeclared / failed} + {@code anyDeclared} + 每税种 {@code declaredAt}。
 * 映射（保守，仅 SUCCESS 视为已报）：{@code SUCCESS→declared}、{@code FAILED→failed}、
 * 其余（PENDING/PREPARING/EXECUTING/MANUAL/无任务）→{@code undeclared}。不改业务逻辑。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/openapi/v1/account-sets")
@SaIgnore
public class OpenApiDeclareStatusController {

    /** 税种分组：openapi 键 → declare_task.tax_type（大写）取值集合（与 progress 大 SQL 口径一致）。 */
    private static final Map<String, List<String>> TAX_TYPE_GROUPS = Map.of(
            "vat", List.of("VAT"),
            "surcharge", List.of("SURCHARGE"),
            "iit", List.of("IIT", "PIT")
    );

    @Resource
    @Lazy
    private BlazeJPAQueryFactory bqf;

    @GetMapping("/{accountSetId}/declare-status")
    public JsonResult declareStatus(@PathVariable Long accountSetId,
                                    @RequestParam String period) {
        if (accountSetId == null) return JsonResult.failure("accountSetId 不能为空");
        if (StrUtil.isBlank(period)) return JsonResult.failure("period 不能为空（yyyy-MM）");
        YearMonth ym;
        try { ym = YearMonth.parse(period.trim()); }
        catch (Exception e) { return JsonResult.failure("period 格式非法（应为 yyyy-MM）：" + period); }
        int year = ym.getYear(), month = ym.getMonthValue();

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("accountSetsId", accountSetId);
        resp.put("period", ym.toString());

        Map<String, Object> details = new LinkedHashMap<>();
        boolean anyDeclared = false;
        for (String key : List.of("vat", "surcharge", "iit")) {
            DeclareTask latest = latestTask(accountSetId, year, month, TAX_TYPE_GROUPS.get(key));
            String status = mapStatus(latest);
            resp.put(key, status);
            if ("declared".equals(status)) anyDeclared = true;

            Map<String, Object> d = new LinkedHashMap<>();
            d.put("status", status);
            d.put("declareTaskId", latest != null ? latest.getId() : null);
            d.put("rawStatus", latest != null && latest.getStatus() != null ? latest.getStatus().name() : null);
            d.put("declaredAt", declaredAt(latest, status));
            details.put(key, d);
        }
        resp.put("anyDeclared", anyDeclared);
        resp.put("details", details);
        return JsonResult.successful(resp);
    }

    /** 取该账套 + 账期 + 某税种组的最新一条 declare_task（id desc）。package-private 便于单测覆写。 */
    DeclareTask latestTask(Long accountSetId, int year, int month, List<String> upperTaxTypes) {
        QDeclareTask q = QDeclareTask.declareTask;
        return bqf.selectFrom(q)
                .where(q.accountSetsId.eq(accountSetId)
                        .and(q.periodYear.eq(year))
                        .and(q.periodMonth.eq(month))
                        .and(q.taxType.upper().in(upperTaxTypes)))
                .orderBy(q.id.desc())
                .fetchFirst();
    }

    /** SUCCESS→declared / FAILED→failed / 其余/无→undeclared（保守，仅 SUCCESS 算已报）。 */
    private static String mapStatus(DeclareTask t) {
        if (t == null || t.getStatus() == null) return "undeclared";
        return switch (t.getStatus()) {
            case SUCCESS -> "declared";
            case FAILED -> "failed";
            default -> "undeclared";   // PENDING / PREPARING / EXECUTING / MANUAL
        };
    }

    private static String declaredAt(DeclareTask t, String status) {
        if (t == null || !"declared".equals(status)) return null;
        var at = t.getSubmittedAt() != null ? t.getSubmittedAt() : t.getUpdatedAt();
        return at != null ? at.toString() : null;
    }
}
