package cn.gson.financial.controller.openapi;

import cn.dev33.satoken.annotation.SaIgnore;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.model.dto.BalanceInitialDto;
import cn.gson.financial.kernel.model.dto.TrialBalanceDto;
import cn.gson.financial.kernel.model.entity.AccountSets;
import cn.gson.financial.kernel.model.entity.Balance;
import cn.gson.financial.kernel.model.entity.BalanceInitial;
import cn.gson.financial.kernel.model.entity.QAccountSets;
import cn.gson.financial.kernel.model.entity.QBalance;
import cn.gson.financial.kernel.model.entity.QBalanceInitial;
import cn.gson.financial.kernel.model.entity.QSubject;
import cn.gson.financial.kernel.model.entity.QVoucher;
import cn.gson.financial.kernel.model.entity.Subject;
import cn.gson.financial.kernel.model.hibernate.BlazeJPAQueryFactory;
import cn.gson.financial.kernel.service.BalanceInitialService;
import cn.gson.financial.kernel.service.CurrencyService;
import cn.gson.financial.kernel.service.SubjectService;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.Tuple;
import jakarta.annotation.Resource;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * 开放 API — 余额（期初余额 + 当期余额 + 试算平衡）
 * 路径前缀 /openapi/v1/balance
 *  GET  /                  当期科目余额（P0-2）
 *  GET  /check             试算平衡校验（P0-3）
 *  GET  /initial           期初余额查询
 *  POST /initial           导入期初余额
 *  GET  /initial/check     期初试算
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/openapi/v1/balance")
@SaIgnore
public class OpenApiBalanceController {

    private final BalanceInitialService balanceInitialService;
    private final SubjectService subjectService;
    private final CurrencyService currencyService;

    @Resource
    @Lazy
    private BlazeJPAQueryFactory bqf;

    private static final QAccountSets qAccountSets = QAccountSets.accountSets;
    private static final QBalanceInitial qBalanceInitial = QBalanceInitial.balanceInitial;
    private static final QBalance qBalance = QBalance.balance;
    private static final QSubject qSubject = QSubject.subject;
    private static final QVoucher qVoucher = QVoucher.voucher;

    // ═══════════════════════════════════════════════════════════════
    // §2.3 期初余额查询
    // ═══════════════════════════════════════════════════════════════

    /**
     * GET /openapi/v1/balance/initial?accountSetsId=88
     */
    @GetMapping("/initial")
    public JsonResult list(@RequestParam Long accountSetsId) {
        if (accountSetsId == null) {
            return JsonResult.failure("accountSetsId 不能为空");
        }

        List<Tuple> rows = bqf.selectFrom(qBalanceInitial)
                .select(qBalanceInitial, qSubject.code)
                .leftJoin(qSubject).on(qSubject.id.eq(qBalanceInitial.subjectId)
                        .and(qSubject.accountSetsId.eq(qBalanceInitial.accountSetsId)))
                .where(qBalanceInitial.accountSetsId.eq(accountSetsId))
                .orderBy(qSubject.code.asc())
                .fetch();

        List<Map<String, Object>> result = new ArrayList<>();
        for (Tuple t : rows) {
            BalanceInitial b = t.get(qBalanceInitial);
            String subjectCode = t.get(qSubject.code);
            if (b == null) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("subjectId", b.getSubjectId());
            m.put("subjectCode", subjectCode);
            m.put("auxiliaryDetailId", b.getAuxiliaryDetailId());
            m.put("currencyId", b.getCurrencyId());
            m.put("beginBalance", b.getBeginBalance());
            m.put("yearTotalDebit", b.getYearTotalDebit());
            m.put("yearTotalCredit", b.getYearTotalCredit());
            m.put("yearTotal", b.getYearTotal());
            m.put("beginQuantity", b.getBeginQuantity());
            result.add(m);
        }
        return JsonResult.successful(result);
    }

    // ═══════════════════════════════════════════════════════════════
    // §3.2 导入期初余额
    // ═══════════════════════════════════════════════════════════════

    /**
     * POST /openapi/v1/balance/initial
     * mode: replace=覆盖（先清空再写）；merge=按 (subjectId+aux+currency) 合并
     */
    @PostMapping("/initial")
    @Transactional
    public JsonResult importBalance(@RequestBody ImportBalanceReq req) {
        if (req.getAccountSetsId() == null) {
            return JsonResult.failure("accountSetsId 不能为空");
        }
        if (req.getEntries() == null || req.getEntries().isEmpty()) {
            return JsonResult.failure("entries 不能为空");
        }

        AccountSets accountSets = bqf.selectFrom(qAccountSets)
                .where(qAccountSets.id.eq(req.getAccountSetsId()))
                .fetchFirst();
        if (accountSets == null) {
            return JsonResult.failure("账套不存在：" + req.getAccountSetsId());
        }

        List<BalanceInitialDto> dtoList = new ArrayList<>();
        // 用于补充 balanceDirection（updateInitBalance 内部需要）
        Map<Long, Subject> subjectMap = new HashMap<>();
        for (Subject s : subjectService.selectData(req.getAccountSetsId())) {
            subjectMap.put(s.getId(), s);
        }

        for (BalanceEntryDto e : req.getEntries()) {
            if (e.getSubjectId() == null) continue;
            Subject sub = subjectMap.get(e.getSubjectId());
            if (sub == null) {
                return JsonResult.failure("科目不存在：subjectId=" + e.getSubjectId());
            }
            BalanceInitialDto d = new BalanceInitialDto();
            d.setAccountSetsId(req.getAccountSetsId());
            d.setSubjectId(e.getSubjectId());
            d.setSubjectCode(sub.getCode());
            d.setBalanceDirection(sub.getBalanceDirection());
            d.setAuxiliaryDetailId(e.getAuxiliaryDetailId() != null ? e.getAuxiliaryDetailId() : 0L);
            d.setCurrencyId(e.getCurrencyId() != null ? e.getCurrencyId() : 0L);
            d.setBeginBalance(orZero(e.getBeginBalance()));
            d.setBeginBalanceFor(orZero(e.getBeginBalanceFor()));
            d.setYearTotalDebit(orZero(e.getYearTotalDebit()));
            d.setYearTotalCredit(orZero(e.getYearTotalCredit()));
            d.setYearTotalDebitFor(orZero(e.getYearTotalDebitFor()));
            d.setYearTotalCreditFor(orZero(e.getYearTotalCreditFor()));
            d.setBeginQuantity(orZero(e.getBeginQuantity()));
            d.setLeaf(Boolean.TRUE.equals(sub.getLeaf()));
            d.setLevel(sub.getLevel());
            d.calculateYearTotal();
            dtoList.add(d);
        }

        try {
            String mode = req.getMode() != null ? req.getMode() : "replace";
            if ("replace".equalsIgnoreCase(mode)) {
                balanceInitialService.updateInitBalance(dtoList, accountSets);
            } else {
                balanceInitialService.saveInitBalance(dtoList, accountSets);
            }
        } catch (Exception ex) {
            log.error("openapi 期初余额写入失败", ex);
            return JsonResult.failure("写入失败：" + ex.getMessage());
        }

        // 返回试算结果
        TrialBalanceDto trial = balanceInitialService.trialBalance(req.getAccountSetsId());
        BigDecimal debit = orZero(trial.getInitalDebit());
        BigDecimal credit = orZero(trial.getInitalCredit());
        boolean balanced = debit.subtract(credit).abs().compareTo(new BigDecimal("0.01")) <= 0;

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("rowsImported", dtoList.size());
        Map<String, Object> tb = new LinkedHashMap<>();
        tb.put("debitTotal", debit);
        tb.put("creditTotal", credit);
        tb.put("balanced", balanced);
        data.put("trialBalance", tb);
        return JsonResult.successful(data);
    }

    // ═══════════════════════════════════════════════════════════════
    // §3.3 试算平衡校验
    // ═══════════════════════════════════════════════════════════════

    @GetMapping("/initial/check")
    public JsonResult check(@RequestParam Long accountSetsId) {
        if (accountSetsId == null) {
            return JsonResult.failure("accountSetsId 不能为空");
        }
        TrialBalanceDto trial = balanceInitialService.trialBalance(accountSetsId);
        BigDecimal debit = orZero(trial.getInitalDebit());
        BigDecimal credit = orZero(trial.getInitalCredit());
        BigDecimal diff = debit.subtract(credit).setScale(2, RoundingMode.HALF_UP);
        boolean balanced = diff.abs().compareTo(new BigDecimal("0.01")) <= 0;

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("balanced", balanced);
        m.put("debitTotal", debit);
        m.put("creditTotal", credit);
        m.put("diff", diff);
        return JsonResult.successful(m);
    }

    private static BigDecimal orZero(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }

    // ═══════════════════════════════════════════════════════════════
    // P0-2 当期科目余额
    // GET /openapi/v1/balance?accountSetsId=88&year=2025&month=12
    // ═══════════════════════════════════════════════════════════════

    @GetMapping
    public JsonResult currentBalance(@RequestParam Long accountSetsId,
                                     @RequestParam Integer year,
                                     @RequestParam Integer month,
                                     @RequestParam(required = false) String subjectIds,
                                     @RequestParam(required = false, defaultValue = "false") Boolean nonZeroOnly,
                                     @RequestParam(required = false, defaultValue = "false") Boolean sumAllAuxiliary) {
        if (accountSetsId == null || year == null || month == null) {
            return JsonResult.failure("accountSetsId/year/month 不能为空");
        }

        List<Long> idFilter = null;
        if (subjectIds != null && !subjectIds.isBlank()) {
            idFilter = new ArrayList<>();
            for (String s : subjectIds.split(",")) {
                String t = s.trim();
                if (!t.isEmpty()) {
                    try {
                        idFilter.add(Long.parseLong(t));
                    } catch (NumberFormatException ignored) {
                        return JsonResult.failure("subjectIds 含非法值：" + t);
                    }
                }
            }
            if (idFilter.isEmpty()) idFilter = null;
        }

        // BUG B-8：必须限定本位币 ID，否则同一科目会返回多条（不同币种），且 currency_id=0 那条数据不准
        Long localCurrencyId = currencyService.fetchLocal(accountSetsId).getId();

        // FN-FIX-BALANCE-AUX-AGGREGATE
        // - sumAllAuxiliary=false（默认）：保留原行为，aux=0 的汇总行（与 SubjectBalance 页面一致）
        // - sumAllAuxiliary=true：放开 aux 限制，按 subject_id GROUP BY SUM 各金额字段
        //   —— 解决章丘 5602xx 工资科目挂员工辅助核算时，aux=0 汇总行余额=0、aux≠0 真实余额拿不到的问题
        List<BalanceRow> rows = Boolean.TRUE.equals(sumAllAuxiliary)
                ? fetchAggregatedRows(accountSetsId, year, month, idFilter, localCurrencyId)
                : fetchDetailedRows(accountSetsId, year, month, idFilter, localCurrencyId);

        List<Map<String, Object>> result = new ArrayList<>();
        for (BalanceRow r : rows) {
            BigDecimal bb = orZero(r.beginBalance);
            BigDecimal eb = orZero(r.endBalance);
            BigDecimal cmd = orZero(r.currentMonthDebit);
            BigDecimal cmc = orZero(r.currentMonthCredit);
            BigDecimal ytd = orZero(r.yearTotalDebit);
            BigDecimal ytc = orZero(r.yearTotalCredit);

            if (Boolean.TRUE.equals(nonZeroOnly)
                    && bb.signum() == 0 && eb.signum() == 0
                    && cmd.signum() == 0 && cmc.signum() == 0) {
                continue;
            }

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("subjectId", r.subjectId);
            m.put("subjectCode", r.subjectCode);
            m.put("subjectName", r.subjectName);
            m.put("balanceDirection", r.balanceDirection);
            m.put("auxiliaryDetailId", r.auxiliaryDetailId);
            m.put("currencyId", r.currencyId);
            m.put("year", year);
            m.put("month", month);
            m.put("beginBalance", bb);
            m.put("currentMonthDebit", cmd);
            m.put("currentMonthCredit", cmc);
            m.put("endBalance", eb);
            m.put("yearTotalDebit", ytd);
            m.put("yearTotalCredit", ytc);
            // FN-NEW-BALANCE-FULL-FIELDS：暴露期初/期末借贷拆分字段，给 XW 结转凭证科目余额驱动用
            //   opening_debit/credit = 期初借/贷 = beginDebitBalance / beginCreditBalance
            //   ending_debit/credit  = 期末借/贷 = endDebitBalance  / endCreditBalance
            //   period_debit/credit  = 本期发生 = currentMonthDebit / currentMonthCredit（已有）
            m.put("beginDebitBalance",  orZero(r.beginDebitBalance));
            m.put("beginCreditBalance", orZero(r.beginCreditBalance));
            m.put("endDebitBalance",    orZero(r.endDebitBalance));
            m.put("endCreditBalance",   orZero(r.endCreditBalance));
            result.add(m);
        }
        return JsonResult.successful(result);
    }

    /** 原行为：aux=0 + 本位币，每条 balance 一行 */
    List<BalanceRow> fetchDetailedRows(Long accountSetsId, Integer year, Integer month,
                                       List<Long> subjectIdFilter, Long localCurrencyId) {
        BooleanBuilder where = new BooleanBuilder();
        where.and(qBalance.accountSetsId.eq(accountSetsId));
        where.and(qBalance.year.eq(year));
        where.and(qBalance.period.eq(month));
        where.and(qBalance.auxiliaryDetailId.eq(0L));
        where.and(qBalance.currencyId.eq(localCurrencyId));
        if (subjectIdFilter != null && !subjectIdFilter.isEmpty()) {
            where.and(qBalance.subjectId.in(subjectIdFilter));
        }

        List<Tuple> tuples = bqf.selectFrom(qBalance)
                .select(qBalance, qSubject.code, qSubject.name, qSubject.balanceDirection)
                .leftJoin(qSubject).on(qSubject.id.eq(qBalance.subjectId)
                        .and(qSubject.accountSetsId.eq(qBalance.accountSetsId)))
                .where(where)
                .orderBy(qSubject.code.asc())
                .fetch();

        List<BalanceRow> rows = new ArrayList<>(tuples.size());
        for (Tuple t : tuples) {
            Balance b = t.get(qBalance);
            if (b == null) continue;
            BalanceRow r = new BalanceRow();
            r.subjectId = b.getSubjectId();
            r.subjectCode = t.get(qSubject.code);
            r.subjectName = t.get(qSubject.name);
            r.balanceDirection = t.get(qSubject.balanceDirection);
            r.auxiliaryDetailId = b.getAuxiliaryDetailId();
            r.currencyId = b.getCurrencyId();
            r.beginBalance = b.getBeginBalance();
            r.endBalance = b.getEndBalance();
            r.currentMonthDebit = b.getDebit();
            r.currentMonthCredit = b.getCredit();
            r.yearTotalDebit = b.getYearTotalDebit();
            r.yearTotalCredit = b.getYearTotalCredit();
            // FN-NEW-BALANCE-FULL-FIELDS：期初/期末借贷拆分字段
            r.beginDebitBalance  = b.getBeginDebitBalance();
            r.beginCreditBalance = b.getBeginCreditBalance();
            r.endDebitBalance    = b.getEndDebitBalance();
            r.endCreditBalance   = b.getEndCreditBalance();
            rows.add(r);
        }
        return rows;
    }

    /**
     * FN-FIX-BALANCE-AUX-AGGREGATE：放开 aux 限制，按 subject_id GROUP BY SUM 各金额字段。
     * 返回行的 auxiliaryDetailId=null（语义：聚合行，跨所有 aux 维度合计）。
     */
    List<BalanceRow> fetchAggregatedRows(Long accountSetsId, Integer year, Integer month,
                                         List<Long> subjectIdFilter, Long localCurrencyId) {
        BooleanBuilder where = buildAggregatedWhere(accountSetsId, year, month, subjectIdFilter, localCurrencyId);

        List<Tuple> tuples = bqf.selectFrom(qBalance)
                .select(qBalance.subjectId,
                        qBalance.beginBalance.sum(),
                        qBalance.endBalance.sum(),
                        qBalance.debit.sum(),
                        qBalance.credit.sum(),
                        qBalance.yearTotalDebit.sum(),
                        qBalance.yearTotalCredit.sum(),
                        // FN-NEW-BALANCE-FULL-FIELDS：聚合路径也要 SUM 借贷拆分字段
                        qBalance.beginDebitBalance.sum(),
                        qBalance.beginCreditBalance.sum(),
                        qBalance.endDebitBalance.sum(),
                        qBalance.endCreditBalance.sum(),
                        qSubject.code, qSubject.name, qSubject.balanceDirection)
                .leftJoin(qSubject).on(qSubject.id.eq(qBalance.subjectId)
                        .and(qSubject.accountSetsId.eq(qBalance.accountSetsId)))
                .where(where)
                .groupBy(qBalance.subjectId, qSubject.code, qSubject.name, qSubject.balanceDirection)
                .orderBy(qSubject.code.asc())
                .fetch();

        List<BalanceRow> rows = new ArrayList<>(tuples.size());
        for (Tuple t : tuples) {
            Long sid = t.get(qBalance.subjectId);
            if (sid == null) continue;
            BalanceRow r = new BalanceRow();
            r.subjectId = sid;
            r.subjectCode = t.get(qSubject.code);
            r.subjectName = t.get(qSubject.name);
            r.balanceDirection = t.get(qSubject.balanceDirection);
            r.auxiliaryDetailId = null;   // 聚合行，无 aux 维度
            r.currencyId = localCurrencyId;
            r.beginBalance = t.get(qBalance.beginBalance.sum());
            r.endBalance = t.get(qBalance.endBalance.sum());
            r.currentMonthDebit = t.get(qBalance.debit.sum());
            r.currentMonthCredit = t.get(qBalance.credit.sum());
            r.yearTotalDebit = t.get(qBalance.yearTotalDebit.sum());
            r.yearTotalCredit = t.get(qBalance.yearTotalCredit.sum());
            r.beginDebitBalance  = t.get(qBalance.beginDebitBalance.sum());
            r.beginCreditBalance = t.get(qBalance.beginCreditBalance.sum());
            r.endDebitBalance    = t.get(qBalance.endDebitBalance.sum());
            r.endCreditBalance   = t.get(qBalance.endCreditBalance.sum());
            rows.add(r);
        }
        return rows;
    }

    /**
     * 构造 aggregated 路径的 where。提为独立方法是为了让单测能直接断言"必须包含
     * `subject.leaf = true`"以防 FN-FIX-BALANCE-AUX-LEAF-FILTER 回归。
     * <p>核心约束：
     *   - 限定本账套 / 期间 / 本位币
     *   - 必须只返末级科目（leaf=true）—— 否则 5602 父级 + 560215 子级都会被 SUM 出来
     *     XW pl_carry 把父级带进凭证 → voucherService.save 抛"不是末级科目"
     *   - 不限 auxiliary_detail_id —— 这正是 sumAllAuxiliary=true 的语义
     */
    BooleanBuilder buildAggregatedWhere(Long accountSetsId, Integer year, Integer month,
                                        List<Long> subjectIdFilter, Long localCurrencyId) {
        BooleanBuilder where = new BooleanBuilder();
        where.and(qBalance.accountSetsId.eq(accountSetsId));
        where.and(qBalance.year.eq(year));
        where.and(qBalance.period.eq(month));
        where.and(qBalance.currencyId.eq(localCurrencyId));
        if (subjectIdFilter != null && !subjectIdFilter.isEmpty()) {
            where.and(qBalance.subjectId.in(subjectIdFilter));
        }
        // FN-FIX-BALANCE-AUX-LEAF-FILTER：必须放在这里，防回归
        where.and(qSubject.leaf.eq(true));
        return where;
    }

    /** 内部行结构，供 detailed / aggregated 两条路径共享 + 给单测构造 */
    static class BalanceRow {
        Long subjectId;
        String subjectCode;
        String subjectName;
        String balanceDirection;
        Long auxiliaryDetailId;   // 详细行=实际 aux ID（含 0）；聚合行=null
        Long currencyId;
        BigDecimal beginBalance;
        BigDecimal endBalance;
        BigDecimal currentMonthDebit;
        BigDecimal currentMonthCredit;
        BigDecimal yearTotalDebit;
        BigDecimal yearTotalCredit;
        // FN-NEW-BALANCE-FULL-FIELDS：期初/期末借贷拆分字段（XW 结转科目余额驱动用）
        BigDecimal beginDebitBalance;
        BigDecimal beginCreditBalance;
        BigDecimal endDebitBalance;
        BigDecimal endCreditBalance;
    }

    // ═══════════════════════════════════════════════════════════════
    // P0-3 试算平衡校验（当期）
    // GET /openapi/v1/balance/check?accountSetsId=88&year=2025&month=12
    // ═══════════════════════════════════════════════════════════════

    @GetMapping("/check")
    public JsonResult checkCurrent(@RequestParam Long accountSetsId,
                                   @RequestParam Integer year,
                                   @RequestParam Integer month) {
        if (accountSetsId == null || year == null || month == null) {
            return JsonResult.failure("accountSetsId/year/month 不能为空");
        }

        // 1) 全期间凭证级借贷合计（直接从凭证主表汇总）
        Tuple totals = bqf.selectFrom(qVoucher)
                .select(qVoucher.debitAmount.sum(), qVoucher.creditAmount.sum(), qVoucher.id.count())
                .where(qVoucher.accountSetsId.eq(accountSetsId)
                        .and(qVoucher.year.eq(year))
                        .and(qVoucher.month.eq(month)))
                .fetchFirst();

        BigDecimal totalDebit = totals != null && totals.get(qVoucher.debitAmount.sum()) != null
                ? totals.get(qVoucher.debitAmount.sum())
                : BigDecimal.ZERO;
        BigDecimal totalCredit = totals != null && totals.get(qVoucher.creditAmount.sum()) != null
                ? totals.get(qVoucher.creditAmount.sum())
                : BigDecimal.ZERO;
        long voucherCount = totals != null && totals.get(qVoucher.id.count()) != null
                ? totals.get(qVoucher.id.count())
                : 0L;

        BigDecimal diff = totalDebit.subtract(totalCredit).setScale(2, RoundingMode.HALF_UP);
        boolean balanced = diff.abs().compareTo(new BigDecimal("0.01")) <= 0;

        // 2) 单凭证内借贷不平的列表（理论上不应该有，因为 save 时会校验，但留作宏观体检）
        List<Tuple> mismatches = bqf.selectFrom(qVoucher)
                .select(qVoucher.id, qVoucher.word, qVoucher.code, qVoucher.voucherDate,
                        qVoucher.debitAmount, qVoucher.creditAmount)
                .where(qVoucher.accountSetsId.eq(accountSetsId)
                        .and(qVoucher.year.eq(year))
                        .and(qVoucher.month.eq(month))
                        .and(qVoucher.debitAmount.ne(qVoucher.creditAmount)))
                .fetch();

        List<Map<String, Object>> mismatchList = new ArrayList<>();
        for (Tuple t : mismatches) {
            Map<String, Object> mm = new LinkedHashMap<>();
            mm.put("voucherId", t.get(qVoucher.id));
            mm.put("word", t.get(qVoucher.word));
            mm.put("code", t.get(qVoucher.code));
            mm.put("voucherDate", t.get(qVoucher.voucherDate));
            mm.put("debitAmount", t.get(qVoucher.debitAmount));
            mm.put("creditAmount", t.get(qVoucher.creditAmount));
            mismatchList.add(mm);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("balanced", balanced && mismatchList.isEmpty());
        data.put("totalDebit", totalDebit);
        data.put("totalCredit", totalCredit);
        data.put("diff", diff);
        data.put("voucherCount", (int) voucherCount);
        data.put("voucherWithBalanceMismatch", mismatchList);
        return JsonResult.successful(data);
    }

    // ═══════════════════════════════════════════════════════════════
    // 请求 DTO
    // ═══════════════════════════════════════════════════════════════

    @Data
    public static class ImportBalanceReq {
        private Long accountSetsId;
        private String mode;            // replace / merge
        private List<BalanceEntryDto> entries;
    }

    @Data
    public static class BalanceEntryDto {
        private Long subjectId;
        private Long auxiliaryDetailId;
        private Long currencyId;
        private BigDecimal beginBalance;
        private BigDecimal beginBalanceFor;
        private BigDecimal yearTotalDebit;
        private BigDecimal yearTotalCredit;
        private BigDecimal yearTotalDebitFor;
        private BigDecimal yearTotalCreditFor;
        private BigDecimal beginQuantity;
    }
}
