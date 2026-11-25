package cn.gson.financial.service;

import cn.gson.financial.kernel.model.entity.QInvoice;
import cn.gson.financial.kernel.model.enums.InvoiceState;
import cn.gson.financial.kernel.model.enums.InvoiceType;
import cn.gson.financial.kernel.model.hibernate.BlazeJPAQueryFactory;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;

/**
 * FN-COMPLETENESS-01 账套数据完整性检查。
 *
 * <p>检查 4 项必填资料是否齐全（按 period 范围内）：
 * <ul>
 *   <li>银行流水（fxy_financial_bank_statement_detail）</li>
 *   <li>工资表（fxy_financial_salary，按 year+month）</li>
 *   <li>销项发票（fxy_financial_invoice type=OUT, state=NORMAL）</li>
 *   <li>进项发票（fxy_financial_invoice type=IN, state=NORMAL）</li>
 * </ul>
 *
 * <p>状态机：
 * <ul>
 *   <li>ready — count > 0</li>
 *   <li>missing — count = 0</li>
 *   <li>partial — 进项发票特殊：count > 0 但部分未认证（开票 &gt; 30 天未认证），其他场景不出现</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountCompletenessService {

    public static final String STATUS_READY   = "ready";
    public static final String STATUS_MISSING = "missing";
    public static final String STATUS_PARTIAL = "partial";

    @Resource
    @Lazy
    private BlazeJPAQueryFactory bqf;

    private final JdbcTemplate jdbcTemplate;

    /**
     * 检查指定账套 + 期间的数据完整性。
     *
     * @param accountSetsId 账套
     * @param period yyyy-MM
     * @return Result（含 ready / required[] / missingCount）
     */
    public Result check(Long accountSetsId, String period) {
        if (accountSetsId == null) throw new IllegalArgumentException("accountSetsId 不能为空");
        if (period == null || period.isBlank()) throw new IllegalArgumentException("period 不能为空");

        YearMonth ym;
        try {
            ym = YearMonth.parse(period);
        } catch (Exception e) {
            throw new IllegalArgumentException("period 格式非法（应为 yyyy-MM）：" + period);
        }
        LocalDate start = ym.atDay(1);
        LocalDate end = ym.atEndOfMonth();

        Result r = new Result();
        r.required = new ArrayList<>(4);

        // 1. 银行流水
        long bankCount = countBankStatement(accountSetsId, start, end);
        r.required.add(item("银行流水", bankCount > 0 ? STATUS_READY : STATUS_MISSING));

        // 2. 工资表（按 year+month）
        long salaryCount = countSalary(accountSetsId, ym.getYear(), ym.getMonthValue());
        r.required.add(item("工资表", salaryCount > 0 ? STATUS_READY : STATUS_MISSING));

        // 3. 销项发票
        long outCount = countInvoice(accountSetsId, InvoiceType.OUT, start, end);
        r.required.add(item("销项发票", outCount > 0 ? STATUS_READY : STATUS_MISSING));

        // 4. 进项发票
        long inCount = countInvoice(accountSetsId, InvoiceType.IN, start, end);
        long inUncertified = countInvoiceUncertifiedOver30Days(accountSetsId, start, end);
        String inStatus;
        if (inCount == 0) inStatus = STATUS_MISSING;
        else if (inUncertified > 0) inStatus = STATUS_PARTIAL;
        else inStatus = STATUS_READY;
        r.required.add(item("进项发票", inStatus));

        int missing = (int) r.required.stream()
                .filter(m -> !STATUS_READY.equals(m.get("status"))).count();
        r.missingCount = missing;
        r.ready = missing == 0;
        return r;
    }

    public long countBankStatement(Long accountSetsId, LocalDate start, LocalDate end) {
        // BankStatementDetail.account_set_id 历史命名是单 s（与本表 entity 一致），不要改
        try {
            Long c = jdbcTemplate.queryForObject(
                    "select count(1) from fxy_financial_bank_statement_detail " +
                    "where account_set_id = ? and is_delete = 0 and trade_date between ? and ?",
                    Long.class, accountSetsId, start, end);
            return c == null ? 0L : c;
        } catch (Exception e) {
            log.warn("[FN-COMPLETENESS-01] count bank statement failed acc={}", accountSetsId, e);
            return 0L;
        }
    }

    public long countSalary(Long accountSetsId, int year, int month) {
        try {
            Long c = jdbcTemplate.queryForObject(
                    "select count(1) from fxy_financial_salary where account_sets_id = ? and year = ? and month = ?",
                    Long.class, accountSetsId, year, month);
            return c == null ? 0L : c;
        } catch (Exception e) {
            log.warn("[FN-COMPLETENESS-01] count salary failed acc={}", accountSetsId, e);
            return 0L;
        }
    }

    public long countInvoice(Long accountSetsId, InvoiceType type, LocalDate start, LocalDate end) {
        QInvoice q = QInvoice.invoice;
        Long c = bqf.selectFrom(q).select(q.count())
                .where(q.accountSetsId.eq(accountSetsId)
                        .and(q.type.eq(type))
                        .and(q.state.eq(InvoiceState.正常))
                        .and(q.tradeDate.between(start, end)))
                .fetchFirst();
        return c == null ? 0L : c;
    }

    /** 进项发票里"开票超 30 天但还没有 checkDate"（未认证）的数量 */
    public long countInvoiceUncertifiedOver30Days(Long accountSetsId, LocalDate start, LocalDate end) {
        QInvoice q = QInvoice.invoice;
        Long c = bqf.selectFrom(q).select(q.count())
                .where(q.accountSetsId.eq(accountSetsId)
                        .and(q.type.eq(InvoiceType.IN))
                        .and(q.state.eq(InvoiceState.正常))
                        .and(q.tradeDate.between(start, end))
                        .and(q.checkDate.isNull())
                        .and(q.tradeDate.before(LocalDate.now().minusDays(30))))
                .fetchFirst();
        return c == null ? 0L : c;
    }

    static Map<String, Object> item(String name, String status) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("status", status);
        return m;
    }

    public static class Result {
        public boolean ready;
        public List<Map<String, Object>> required;
        public int missingCount;
    }
}
