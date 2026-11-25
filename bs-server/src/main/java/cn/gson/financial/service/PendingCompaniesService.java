package cn.gson.financial.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;

/**
 * FN-WB-02 ⭐ 未完成企业查询 — 给 BT AI 秘书答"这个月还有哪些企业没做账报税"。
 *
 * <p>判定口径（简化版，Phase 2 接真实结账标志）：
 * <ul>
 *   <li>bookkeeping 完成 = 该 period 至少有 1 张凭证（voucher.voucherDate in period）</li>
 *   <li>vat_declare 完成 = declare_task 表有 tax_type='vat' + period 匹配 + status='SUCCESS'</li>
 *   <li>iit_declare 完成 = declare_task 表有 tax_type='iit' + period 匹配 + status='SUCCESS'</li>
 * </ul>
 *
 * <p>查询设计为单条 LEFT JOIN + GROUP BY，避免 N+1 — 中小账套（&lt; 10k）秒级返回。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PendingCompaniesService {

    public static final String PT_BOOKKEEPING            = "bookkeeping";
    public static final String PT_DECLARE                = "declare";
    public static final String PT_BOOKKEEPING_AND_DECLARE = "bookkeeping_and_declare";
    static final Set<String> ALLOWED_PT = Set.of(
            PT_BOOKKEEPING, PT_DECLARE, PT_BOOKKEEPING_AND_DECLARE);

    private final JdbcTemplate jdbcTemplate;

    public Result query(String period, String pendingType) {
        if (period == null || period.isBlank()) throw new IllegalArgumentException("period 不能为空");
        YearMonth ym;
        try {
            ym = YearMonth.parse(period);
        } catch (Exception e) {
            throw new IllegalArgumentException("period 格式非法（应为 yyyy-MM）：" + period);
        }
        String pt = pendingType == null || pendingType.isBlank()
                ? PT_BOOKKEEPING_AND_DECLARE
                : pendingType.trim().toLowerCase();
        if (!ALLOWED_PT.contains(pt)) {
            throw new IllegalArgumentException(
                    "pending_type 非法：" + pendingType + "（合法值：" + ALLOWED_PT + "）");
        }

        LocalDate start = ym.atDay(1);
        LocalDate end = ym.atEndOfMonth();

        // 一次性 JOIN + 聚合，避免 N+1
        String sql = """
                SELECT
                    a.id AS account_sets_id,
                    a.company_name,
                    a.province,
                    (SELECT COUNT(1) FROM fxy_financial_voucher v
                     WHERE v.account_sets_id = a.id
                       AND v.voucher_date BETWEEN ? AND ?) AS voucher_count,
                    (SELECT COUNT(1) FROM fxy_financial_declare_task d
                     WHERE d.account_sets_id = a.id
                       AND d.tax_type = 'vat'
                       AND d.period_year = ?
                       AND d.period_month = ?
                       AND d.status = 'SUCCESS') AS vat_done,
                    (SELECT COUNT(1) FROM fxy_financial_declare_task d
                     WHERE d.account_sets_id = a.id
                       AND d.tax_type = 'iit'
                       AND d.period_year = ?
                       AND d.period_month = ?
                       AND d.status = 'SUCCESS') AS iit_done,
                    (SELECT MAX(v.voucher_date) FROM fxy_financial_voucher v
                     WHERE v.account_sets_id = a.id) AS last_voucher_date
                FROM fxy_financial_account_sets a
                WHERE a.deactivated = 0
                """;

        List<Map<String, Object>> rows;
        try {
            rows = jdbcTemplate.queryForList(sql,
                    start, end,
                    ym.getYear(), ym.getMonthValue(),
                    ym.getYear(), ym.getMonthValue());
        } catch (Exception e) {
            log.error("[FN-WB-02] query failed period={} pt={}", period, pt, e);
            throw new RuntimeException("查询未完成企业失败：" + e.getMessage(), e);
        }

        List<Map<String, Object>> records = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            long voucherCount = asLong(row.get("voucher_count"));
            long vatDone = asLong(row.get("vat_done"));
            long iitDone = asLong(row.get("iit_done"));
            boolean bookkeepingDone = voucherCount > 0;
            boolean vatDeclareDone = vatDone > 0;
            boolean iitDeclareDone = iitDone > 0;

            List<String> pendingSteps = new ArrayList<>(3);
            if (!bookkeepingDone) pendingSteps.add("bookkeeping");
            if (!vatDeclareDone)  pendingSteps.add("vat_declare");
            if (!iitDeclareDone)  pendingSteps.add("iit_declare");

            // 按 pending_type 过滤
            boolean keep = switch (pt) {
                case PT_BOOKKEEPING -> !bookkeepingDone;
                case PT_DECLARE -> !vatDeclareDone || !iitDeclareDone;
                default -> !bookkeepingDone || !vatDeclareDone || !iitDeclareDone;
            };
            if (!keep) continue;

            Map<String, Object> rec = new LinkedHashMap<>();
            rec.put("accountSetsId", row.get("account_sets_id"));
            rec.put("companyName", row.get("company_name"));
            rec.put("province", row.get("province"));
            rec.put("pendingSteps", pendingSteps);
            // data_completeness 留给 BT 端单独调 FN-COMPLETENESS-01 取详情（性能考虑）
            Map<String, Object> dc = new LinkedHashMap<>();
            dc.put("checked", false);
            dc.put("hint", "调 GET /openapi/v1/account-sets/{id}/completeness?period= 取详情");
            rec.put("dataCompleteness", dc);
            rec.put("lastAction", row.get("last_voucher_date"));
            records.add(rec);
        }

        Result r = new Result();
        r.total = records.size();
        r.records = records;
        return r;
    }

    static long asLong(Object o) {
        if (o == null) return 0L;
        if (o instanceof Number n) return n.longValue();
        try { return Long.parseLong(o.toString()); } catch (Exception e) { return 0L; }
    }

    public static class Result {
        public int total;
        public List<Map<String, Object>> records;
    }
}
