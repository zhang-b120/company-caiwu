package cn.gson.financial.controller.openapi;

import cn.dev33.satoken.annotation.SaIgnore;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.model.entity.AccountSets;
import cn.gson.financial.kernel.model.entity.Checkout;
import cn.gson.financial.kernel.model.entity.QVoucher;
import cn.gson.financial.kernel.model.repo.AccountSetsRepository;
import cn.gson.financial.kernel.model.repo.CheckoutRepository;
import cn.gson.financial.task.workbench.service.TaskWorkbenchService;
import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.annotation.Resource;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * FN-NEW-CLOSE-PERIOD — 月末关账 API。
 *
 * <p>POST /openapi/v1/account-sets/{id}/close-period
 *
 * <p>校验：无 PENDING 凭证 + 借贷平衡 + 未重复关账，然后：
 * <ul>
 *   <li>创建/更新 Checkout → status=2（已结转损益 + 已结账）</li>
 *   <li>AccountSets.currentAccountDate 推进到下月</li>
 *   <li>本期所有凭证标 locked=true</li>
 * </ul>
 *
 * <p>异常码：
 * <ul>
 *   <li>4401 — 有 PENDING 未审凭证</li>
 *   <li>4402 — 借贷不平衡</li>
 *   <li>4403 — 已关账（重复关账）</li>
 * </ul>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/openapi/v1/account-sets")
@SaIgnore
public class OpenApiClosePeriodController {

    private final AccountSetsRepository accountSetsRepository;
    private final CheckoutRepository checkoutRepository;
    private final JdbcTemplate jdbcTemplate;
    // STAGE-STATUS-PERSIST：关账成功/失败内联写 task_period.step_carry（单一真相源）
    private final TaskWorkbenchService taskWorkbenchService;

    @Resource
    private JPAQueryFactory jqf;

    private static final QVoucher qVoucher = QVoucher.voucher;

    // ─── close-period ───

    @PostMapping("/{id}/close-period")
    @Transactional
    public JsonResult closePeriod(@PathVariable Long id,
                                  @RequestBody ClosePeriodReq req) {
        if (id == null) return JsonResult.failure("id 不能为空");
        if (req == null || req.getPeriod() == null || req.getPeriod().isBlank()) {
            return JsonResult.failure("period 不能为空");
        }

        YearMonth ym;
        try {
            ym = YearMonth.parse(req.getPeriod());
        } catch (Exception e) {
            return JsonResult.failure("period 格式非法（应为 yyyy-MM）：" + req.getPeriod());
        }
        int year = ym.getYear(), month = ym.getMonthValue();

        // 1. 账套必须存在
        Optional<AccountSets> accOpt = accountSetsRepository.findById(id);
        if (accOpt.isEmpty()) return JsonResult.failure("账套不存在：" + id);
        AccountSets acc = accOpt.get();

        // 2. 重复关账检查 → 4403
        if (checkoutRepository.existsByAccountSetsIdAndCheckYearAndCheckMonthAndStatus(id, year, month, 2)) {
            // STAGE-STATUS-PERSIST：该期间确已关账 → step_carry 的真实状态就是 done（自愈/幂等修正，
            // 而非 error——把已关账期标 error 反而制造工单要消除的"漂移"，详见完成情况设计说明）
            writeStepCarrySafe(id, req.getPeriod(), "done");
            return JsonResult.failure("该期间已关账，不可重复操作", 4403);
        }

        // 3. PENDING 凭证检查 — 已撤销（FN-FIX-OPENAPI-NO-AUDIT 2026-05-18）。
        // 业务实际不走人工审核流程：OpenAPI 创建的凭证默认就是"未审"（audit_member_id=null），
        // 关账无前置审核要求。force 参数保留以兼容老调用方，但已无副作用。

        // 4. 借贷平衡检查 → 4402
        BigDecimal[] sums = sumDebitCredit(id, year, month);
        BigDecimal totalDebit  = sums[0];
        BigDecimal totalCredit = sums[1];
        if (totalDebit.compareTo(totalCredit) != 0) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("totalDebit", totalDebit);
            data.put("totalCredit", totalCredit);
            data.put("diff", totalDebit.subtract(totalCredit).abs());
            // STAGE-STATUS-PERSIST：结转/关账失败 → step_carry=error 落库（页面显错有据）
            writeStepCarrySafe(id, req.getPeriod(), "error");
            return errorWithData("本期凭证借贷不平衡（借方合计=" + totalDebit + "，贷方合计=" + totalCredit + "）", 4402, data);
        }

        // 5. 执行关账（事务内）
        int voucherCount = doClosePeriod(acc, year, month, ym);

        // STAGE-STATUS-PERSIST §8（纯落库展示，可靠性）：关账成功 → step_carry=done，
        // 走 writeStepStatusTx 与关账<b>同事务原子</b>——关账提交 ⟺ step_carry=done 已落库，无漂移窗口；
        // 状态写失败则整个关账一并回滚（漏写=显错，调用方重试），不再 best-effort 吞异常。
        // 注意：step_archive 不在此写——已确认语义 step_archive=报税后归档，关账早于报税，此处写 done 会制造漂移。
        taskWorkbenchService.writeStepStatusTx(id, req.getPeriod(), "stepCarry", "done");

        YearMonth nextYm = ym.plusMonths(1);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("accountSetsId", id);
        result.put("period", req.getPeriod());
        result.put("previousCurrentAccountDate", acc.getCurrentAccountDate());
        result.put("newCurrentAccountDate", nextYm.atDay(1));
        result.put("newCurrentPeriod", nextYm.toString());  // yyyy-MM, per spec
        result.put("lockedVoucherCount", voucherCount);
        result.put("status", "CLOSED");
        log.info("[FN-NEW-CLOSE-PERIOD] closed acc={} period={} locked={} vouchers", id, req.getPeriod(), voucherCount);
        return JsonResult.successful(result);
    }

    // ─── helpers ───

    public long countPendingVouchers(Long accountSetsId, int year, int month) {
        Long c = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fxy_financial_voucher " +
                "WHERE account_sets_id = ? AND year = ? AND month = ? AND audit_member_id IS NULL",
                Long.class, accountSetsId, year, month);
        return c == null ? 0L : c;
    }

    public BigDecimal[] sumDebitCredit(Long accountSetsId, int year, int month) {
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT COALESCE(SUM(debit_amount), 0) AS d, COALESCE(SUM(credit_amount), 0) AS c " +
                "FROM fxy_financial_voucher WHERE account_sets_id = ? AND year = ? AND month = ?",
                accountSetsId, year, month);
        BigDecimal d = toBd(row.get("d"));
        BigDecimal c = toBd(row.get("c"));
        return new BigDecimal[]{d, c};
    }

    int doClosePeriod(AccountSets acc, int year, int month, YearMonth ym) {
        Long accountSetsId = acc.getId();

        // 5a. Upsert Checkout → status=2
        Optional<Checkout> existingCo = checkoutRepository.findByAccountSetsIdAndCheckYearAndCheckMonth(
                accountSetsId, year, month);
        Checkout checkout = existingCo.orElseGet(Checkout::new);
        checkout.setAccountSetsId(accountSetsId);
        checkout.setCheckYear(year);
        checkout.setCheckMonth(month);
        checkout.setCheckDate(LocalDate.of(year, month, 1));
        checkout.setStatus(2);
        checkoutRepository.save(checkout);

        // 5b. Advance currentAccountDate to next month
        acc.setCurrentAccountDate(ym.plusMonths(1).atDay(1));
        accountSetsRepository.save(acc);

        // 5c. Lock all period vouchers
        long locked = jqf.update(qVoucher)
                .set(qVoucher.locked, true)
                .where(qVoucher.accountSetsId.eq(accountSetsId)
                        .and(qVoucher.year.eq(year))
                        .and(qVoucher.month.eq(month)))
                .execute();
        return (int) locked;
    }

    /**
     * STAGE-STATUS-PERSIST：内联写 task_period.step_carry，best-effort——
     * 状态落库失败不得拖垮关账主流程（§2.5 漂移由对账兜底），故吞异常仅告警。
     */
    private void writeStepCarrySafe(Long accountSetId, String period, String status) {
        try {
            taskWorkbenchService.writeStepStatusDirect(accountSetId, period, "stepCarry", status);
        } catch (Exception e) {
            log.warn("[STAGE-STATUS-PERSIST] 写 step_carry={} 失败 acc={} period={}（不影响关账主流程）",
                    status, accountSetId, period, e);
        }
    }

    private static JsonResult errorWithData(String msg, int code, Map<String, Object> data) {
        JsonResult r = JsonResult.failure(msg, code);
        r.setData(data);
        return r;
    }

    private static BigDecimal toBd(Object o) {
        if (o == null) return BigDecimal.ZERO;
        if (o instanceof BigDecimal bd) return bd;
        if (o instanceof Number n) return new BigDecimal(n.toString());
        try { return new BigDecimal(o.toString()); } catch (Exception e) { return BigDecimal.ZERO; }
    }

    // ─── DTOs ───

    @Data
    public static class ClosePeriodReq {
        private String period;  // yyyy-MM
        private Boolean force;  // true = skip PENDING check
    }
}
