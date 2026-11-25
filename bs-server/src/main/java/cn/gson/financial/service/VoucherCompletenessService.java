package cn.gson.financial.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * FN-NEW-COMPLETENESS-REPORT — 凭证完整度报告。
 *
 * <p>对比各原始数据源与已生成凭证，输出：
 * <ul>
 *   <li>sources: invoiceOut / invoiceIn / bankFlow / salary / fixedAsset</li>
 *   <li>monthEnd: vatTransfer / surcharge / profitLossTransfer / periodClose</li>
 *   <li>totalIssues</li>
 * </ul>
 *
 * <p>实现策略：纯 JdbcTemplate，每类 1-2 条 SQL，目标 &lt;500 ms/request。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VoucherCompletenessService {

    private final JdbcTemplate jdbcTemplate;

    static final int MAX_MISSING = 20;

    // ─── public API ───

    public Report report(Long accountSetsId, String period) {
        if (accountSetsId == null) throw new IllegalArgumentException("accountSetsId 不能为空");
        YearMonth ym;
        try {
            ym = YearMonth.parse(period);
        } catch (Exception e) {
            throw new IllegalArgumentException("period 格式非法（应为 yyyy-MM）：" + period);
        }
        int y = ym.getYear(), m = ym.getMonthValue();

        Report r = new Report();
        r.sources = new Sources();
        r.sources.invoiceOut  = invoiceStat(accountSetsId, 0, y, m);
        r.sources.invoiceIn   = invoiceStat(accountSetsId, 1, y, m);
        r.sources.bankFlow    = bankFlowStat(accountSetsId, y, m);
        r.sources.salary      = salaryStat(accountSetsId, y, m);
        r.sources.fixedAsset  = fixedAssetStat(accountSetsId, y, m);
        r.monthEnd = monthEndStat(accountSetsId, y, m);
        r.totalIssues = computeTotalIssues(r);
        return r;
    }

    // ─── source stats ───

    /**
     * @param typeOrd InvoiceType ordinal: 0=OUT, 1=IN; state=0 (正常)
     */
    public SourceStat invoiceStat(Long accountSetsId, int typeOrd, int year, int month) {
        Map<String, Object> agg = jdbcTemplate.queryForMap(
                "SELECT COUNT(*) AS cnt, " +
                "  COALESCE(SUM(amount), 0) AS total_amount, " +
                "  COUNT(voucher_id) AS vc " +
                "FROM fxy_financial_invoice " +
                "WHERE account_sets_id = ? AND state = 0 AND type = ? " +
                "  AND YEAR(COALESCE(assignment_date, trade_date)) = ? " +
                "  AND MONTH(COALESCE(assignment_date, trade_date)) = ?",
                accountSetsId, typeOrd, year, month);

        SourceStat s = new SourceStat();
        s.count             = toLong(agg.get("cnt"));
        s.totalAmount       = toBd(agg.get("total_amount"));
        s.vouchersGenerated = toLong(agg.get("vc"));
        if (s.count > s.vouchersGenerated) {
            s.missing = jdbcTemplate.queryForList(
                    "SELECT id FROM fxy_financial_invoice " +
                    "WHERE account_sets_id = ? AND state = 0 AND type = ? AND voucher_id IS NULL " +
                    "  AND YEAR(COALESCE(assignment_date, trade_date)) = ? " +
                    "  AND MONTH(COALESCE(assignment_date, trade_date)) = ? " +
                    "LIMIT " + MAX_MISSING,
                    Long.class, accountSetsId, typeOrd, year, month);
        }
        return s;
    }

    public SourceStat bankFlowStat(Long accountSetsId, int year, int month) {
        Map<String, Object> agg = jdbcTemplate.queryForMap(
                "SELECT COUNT(*) AS cnt, " +
                "  COALESCE(SUM(trade_amount), 0) AS total_amount, " +
                "  COUNT(cashier_journal_id) AS vc " +
                "FROM fxy_financial_bank_statement_detail " +
                "WHERE account_set_id = ? AND is_delete = 0 " +
                "  AND YEAR(trade_date) = ? AND MONTH(trade_date) = ?",
                accountSetsId, year, month);

        SourceStat s = new SourceStat();
        s.count             = toLong(agg.get("cnt"));
        s.totalAmount       = toBd(agg.get("total_amount"));
        s.vouchersGenerated = toLong(agg.get("vc"));
        if (s.count > s.vouchersGenerated) {
            s.missing = jdbcTemplate.queryForList(
                    "SELECT id FROM fxy_financial_bank_statement_detail " +
                    "WHERE account_set_id = ? AND is_delete = 0 AND cashier_journal_id IS NULL " +
                    "  AND YEAR(trade_date) = ? AND MONTH(trade_date) = ? " +
                    "LIMIT " + MAX_MISSING,
                    Long.class, accountSetsId, year, month);
        }
        return s;
    }

    public SourceStat salaryStat(Long accountSetsId, int year, int month) {
        Long salCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fxy_financial_salary WHERE account_sets_id = ? AND year = ? AND month = ?",
                Long.class, accountSetsId, year, month);

        Map<String, Object> svAgg = jdbcTemplate.queryForMap(
                "SELECT COUNT(voucher_id) AS vc, COALESCE(SUM(amount), 0) AS amt " +
                "FROM fxy_financial_salary_voucher " +
                "WHERE account_sets_id = ? AND year = ? AND month = ?",
                accountSetsId, year, month);

        SourceStat s = new SourceStat();
        s.count             = salCount == null ? 0L : salCount;
        s.vouchersGenerated = toLong(svAgg.get("vc"));
        s.totalAmount       = toBd(svAgg.get("amt"));
        if (s.count > 0 && s.vouchersGenerated == 0) {
            s.missing = jdbcTemplate.queryForList(
                    "SELECT id FROM fxy_financial_salary " +
                    "WHERE account_sets_id = ? AND year = ? AND month = ? LIMIT " + MAX_MISSING,
                    Long.class, accountSetsId, year, month);
        }
        return s;
    }

    public SourceStat fixedAssetStat(Long accountSetsId, int year, int month) {
        Map<String, Object> agg = jdbcTemplate.queryForMap(
                "SELECT COUNT(ad.id) AS cnt, " +
                "  COUNT(ad.voucher_id) AS vc, " +
                "  COALESCE(SUM(v.debit_amount), 0) AS amt " +
                "FROM fxy_financial_assets_depreciation ad " +
                "LEFT JOIN fxy_financial_voucher v ON v.id = ad.voucher_id " +
                "WHERE ad.account_sets_id = ? AND ad.check_year = ? AND ad.check_month = ?",
                accountSetsId, year, month);

        SourceStat s = new SourceStat();
        s.count             = toLong(agg.get("cnt"));
        s.vouchersGenerated = toLong(agg.get("vc"));
        s.totalAmount       = toBd(agg.get("amt"));
        if (s.count > s.vouchersGenerated) {
            s.missing = jdbcTemplate.queryForList(
                    "SELECT id FROM fxy_financial_assets_depreciation " +
                    "WHERE account_sets_id = ? AND check_year = ? AND check_month = ? " +
                    "  AND voucher_id IS NULL LIMIT " + MAX_MISSING,
                    Long.class, accountSetsId, year, month);
        }
        return s;
    }

    // ─── month-end ───

    public MonthEnd monthEndStat(Long accountSetsId, int year, int month) {
        MonthEnd me = new MonthEnd();
        me.vatTransfer        = new MonthEndItem();
        me.surcharge          = new MonthEndItem();
        me.profitLossTransfer = new MonthEndItem();
        me.periodClose        = new MonthEndItem();

        // Checkout status: 0=neither, 1=P/L transferred, 2=fully closed
        List<Integer> statuses = jdbcTemplate.queryForList(
                "SELECT status FROM fxy_financial_checkout " +
                "WHERE account_sets_id = ? AND check_year = ? AND check_month = ? LIMIT 1",
                Integer.class, accountSetsId, year, month);
        Integer cs = statuses.isEmpty() ? null : statuses.get(0);
        if (cs != null) {
            me.profitLossTransfer.generated = cs >= 1;
            me.periodClose.generated        = cs >= 2;
        }

        // Carry-forward vouchers joined with their checkout templates
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT v.id AS vid, v.debit_amount AS amt, ct.title AS tpl " +
                    "FROM fxy_financial_voucher v " +
                    "LEFT JOIN fxy_financial_checkout_template ct ON ct.id = v.check_tpl_id " +
                    "WHERE v.account_sets_id = ? AND v.year = ? AND v.month = ? AND v.carry_forward = 1",
                    accountSetsId, year, month);

            for (Map<String, Object> row : rows) {
                String tpl = (String) row.get("tpl");
                if (tpl == null) continue;
                Long vid = toLongObj(row.get("vid"));
                BigDecimal amt = toBd(row.get("amt"));
                if (tpl.contains("增值税")) {
                    me.vatTransfer.generated = true;
                    me.vatTransfer.voucherId = vid;
                    me.vatTransfer.amount    = amt;
                }
                if (tpl.contains("附加")) {
                    me.surcharge.generated = true;
                    me.surcharge.voucherId = vid;
                    me.surcharge.amount    = amt;
                }
                if (tpl.contains("损益")) {
                    me.profitLossTransfer.generated = true;
                    me.profitLossTransfer.voucherId = vid;
                    me.profitLossTransfer.amount    = amt;
                }
            }
        } catch (Exception e) {
            log.warn("[FN-NEW-COMPLETENESS-REPORT] carry-forward query failed acc={} y={} m={}", accountSetsId, year, month, e);
        }

        return me;
    }

    // ─── total issues ───

    int computeTotalIssues(Report r) {
        Sources s = r.sources;
        MonthEnd me = r.monthEnd;
        long n = 0;
        n += s.invoiceOut.count  - s.invoiceOut.vouchersGenerated;
        n += s.invoiceIn.count   - s.invoiceIn.vouchersGenerated;
        n += s.bankFlow.count    - s.bankFlow.vouchersGenerated;
        if (s.salary.count > 0 && s.salary.vouchersGenerated == 0) n++;
        n += s.fixedAsset.count  - s.fixedAsset.vouchersGenerated;
        if (!me.vatTransfer.generated)        n++;
        if (!me.surcharge.generated)          n++;
        if (!me.profitLossTransfer.generated) n++;
        if (!me.periodClose.generated)        n++;
        return (int) Math.max(0, n);
    }

    // ─── helpers ───

    static long toLong(Object o) {
        if (o == null) return 0L;
        if (o instanceof Long l) return l;
        if (o instanceof Number n) return n.longValue();
        return Long.parseLong(o.toString());
    }

    static Long toLongObj(Object o) {
        if (o == null) return null;
        if (o instanceof Long l) return l;
        if (o instanceof Number n) return n.longValue();
        try { return Long.parseLong(o.toString()); } catch (Exception e) { return null; }
    }

    static BigDecimal toBd(Object o) {
        if (o == null) return BigDecimal.ZERO;
        if (o instanceof BigDecimal bd) return bd;
        if (o instanceof Number n) return new BigDecimal(n.toString());
        try { return new BigDecimal(o.toString()); } catch (Exception e) { return BigDecimal.ZERO; }
    }

    // ─── DTOs ───

    public static class Report {
        public Sources sources;
        public MonthEnd monthEnd;
        public int totalIssues;
    }

    public static class Sources {
        public SourceStat invoiceOut;
        public SourceStat invoiceIn;
        public SourceStat bankFlow;
        public SourceStat salary;
        public SourceStat fixedAsset;
    }

    public static class SourceStat {
        public long count;
        public BigDecimal totalAmount = BigDecimal.ZERO;
        public long vouchersGenerated;
        public List<Long> missing = new ArrayList<>();
        public List<Long> orphan  = new ArrayList<>();
    }

    public static class MonthEnd {
        public MonthEndItem vatTransfer;
        public MonthEndItem surcharge;
        public MonthEndItem profitLossTransfer;
        public MonthEndItem periodClose;
    }

    public static class MonthEndItem {
        public boolean    generated = false;
        public BigDecimal amount    = BigDecimal.ZERO;
        public Long       voucherId = null;
    }
}
