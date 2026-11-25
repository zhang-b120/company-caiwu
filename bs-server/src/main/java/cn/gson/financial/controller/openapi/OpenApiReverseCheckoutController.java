package cn.gson.financial.controller.openapi;

import cn.dev33.satoken.annotation.SaIgnore;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.model.entity.AccountSets;
import cn.gson.financial.kernel.model.entity.Checkout;
import cn.gson.financial.kernel.model.repo.AccountSetsRepository;
import cn.gson.financial.kernel.model.repo.CheckoutRepository;
import cn.gson.financial.kernel.model.repo.TaskPeriodRepository;
import cn.gson.financial.kernel.service.CheckoutService;
import cn.hutool.core.util.StrUtil;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;
import java.util.stream.Collectors;

/**
 * FN-REVERSE-CHECKOUT-AND-DECLARE-STATUS 改造 2.1 —— 反结账 OpenAPI（安全包装 {@code CheckoutService.unCheck}）。
 * 路径 {@code POST /openapi/v1/account-sets/{accountSetId}/uncheck-period}。
 *
 * <p><b>数据高危 → 默认只反"最后一个已结账期"</b>：若目标期之后还有已结账月（{@code checkout.status=2}）
 * → 拒绝（{@code PERIOD_HAS_LATER_CLOSED} + 列出后续已结账月），<b>不静默连带反</b>；{@code force=true} 才连带
 * （BT 端须二次确认"将同时反结 X、Y、Z 月"）。period 未关账 → 幂等返成功。
 *
 * <p>反结成功后把受影响各期 {@code step_carry} 回退 {@code pending}（联动 STAGE-STATUS-PERSIST，同事务原子）。
 * 不改 {@code unCheck} 内部逻辑。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/openapi/v1/account-sets")
@SaIgnore
public class OpenApiReverseCheckoutController {

    /** 目标期之后仍有已结账月、且未 force → 拒绝码。 */
    static final int CODE_PERIOD_HAS_LATER_CLOSED = 4480;

    private final AccountSetsRepository accountSetsRepository;
    private final CheckoutRepository checkoutRepository;
    private final CheckoutService checkoutService;
    private final TaskPeriodRepository taskPeriodRepository;

    @PostMapping("/{accountSetId}/uncheck-period")
    @Transactional
    public JsonResult uncheckPeriod(@PathVariable Long accountSetId, @RequestBody UncheckReq req) {
        if (accountSetId == null) return JsonResult.failure("accountSetId 不能为空");
        if (req == null || StrUtil.isBlank(req.getPeriod())) {
            return JsonResult.failure("period 不能为空（yyyy-MM）");
        }
        YearMonth ym;
        try { ym = YearMonth.parse(req.getPeriod().trim()); }
        catch (Exception e) { return JsonResult.failure("period 格式非法（应为 yyyy-MM）：" + req.getPeriod()); }
        int year = ym.getYear(), month = ym.getMonthValue();
        LocalDate targetDate = ym.atDay(1);

        AccountSets acc = accountSetsRepository.findById(accountSetId).orElse(null);
        if (acc == null) return JsonResult.failure("账套不存在：" + accountSetId);

        boolean force = Boolean.TRUE.equals(req.getForce());
        String operatedBy = StrUtil.isBlank(req.getOperatedBy()) ? "system" : req.getOperatedBy().trim();
        String targetPeriod = periodOf(year, month);

        // period 未关账（status≠2）→ 幂等返成功（已是未结账态）
        boolean targetClosed = checkoutRepository
                .existsByAccountSetsIdAndCheckYearAndCheckMonthAndStatus(accountSetId, year, month, 2);
        if (!targetClosed) {
            log.info("[FN-REVERSE-CHECKOUT] 幂等：acc={} period={} 未关账，无需反结 operatedBy={}",
                    accountSetId, targetPeriod, operatedBy);
            return JsonResult.successful(result(accountSetId, targetPeriod, false, true, force, operatedBy, List.of()));
        }

        // 目标期之后仍有已结账月（checkout.status=2 且 checkDate > 目标）
        List<String> laterClosed = checkoutService.list(accountSetId).stream()
                .filter(c -> c.getStatus() != null && c.getStatus() == 2 && c.getCheckDate() != null)
                .filter(c -> c.getCheckDate().isAfter(targetDate))
                .sorted(Comparator.comparing(Checkout::getCheckDate))
                .map(this::periodOf)
                .collect(Collectors.toList());

        if (!laterClosed.isEmpty() && !force) {
            // 默认拒绝跨期连带反结（数据高危），列出后续已结账月供 BT 二次确认
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("errorType", "PERIOD_HAS_LATER_CLOSED");
            data.put("period", targetPeriod);
            data.put("laterClosedPeriods", laterClosed);
            data.put("hint", "该期之后还有 " + laterClosed.size() + " 个已结账月，反结将连带反掉它们；"
                    + "如确需连带请传 force=true 并先二次确认将影响的月份");
            JsonResult r = JsonResult.failure("该期之后仍有已结账月（" + String.join("、", laterClosed)
                    + "），默认不跨期反结；如需连带请 force=true", CODE_PERIOD_HAS_LATER_CLOSED);
            r.setData(data);
            return r;
        }

        // 受影响月份 = 目标期 + 其后已结账月（unCheck 内部会反结 [目标 .. 最后已结账]）
        List<String> reversedPeriods = new ArrayList<>();
        reversedPeriods.add(targetPeriod);
        reversedPeriods.addAll(laterClosed);

        // 包 unCheck（不改其内部）
        checkoutService.unCheck(acc, year, month);

        // 反结成功 → step_carry 回退 pending（同事务原子；无 TaskPeriod 行则默认即 pending，跳过）
        for (String p : reversedPeriods) {
            taskPeriodRepository.findByAccountSetIdAndPeriod(accountSetId, p).ifPresent(tp -> {
                tp.setStepCarry("pending");
                taskPeriodRepository.save(tp);
            });
        }

        log.info("[FN-REVERSE-CHECKOUT] acc={} period={} force={} operatedBy={} reversed={}",
                accountSetId, targetPeriod, force, operatedBy, reversedPeriods);
        return JsonResult.successful(result(accountSetId, targetPeriod, true, false, force, operatedBy, reversedPeriods));
    }

    private Map<String, Object> result(Long accId, String period, boolean unchecked, boolean alreadyOpen,
                                       boolean force, String operatedBy, List<String> reversedPeriods) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("accountSetsId", accId);
        m.put("period", period);
        m.put("unchecked", unchecked);
        m.put("alreadyOpen", alreadyOpen);
        m.put("force", force);
        m.put("operatedBy", operatedBy);
        m.put("reversedPeriods", reversedPeriods);
        return m;
    }

    private String periodOf(Checkout c) { return periodOf(c.getCheckYear(), c.getCheckMonth()); }

    private String periodOf(int year, int month) { return String.format("%04d-%02d", year, month); }

    @Data
    public static class UncheckReq {
        private String period;       // yyyy-MM
        private Boolean force;       // true = 连带反结后续已结账月（须 BT 二次确认）
        private String operatedBy;   // 审计：操作人（BT 传入，空回退 system）
    }
}
