package cn.gson.financial.controller.openapi;

import cn.dev33.satoken.annotation.SaIgnore;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.model.entity.Checkout;
import cn.gson.financial.kernel.model.entity.Customer;
import cn.gson.financial.kernel.model.entity.QCheckout;
import cn.gson.financial.kernel.model.entity.QVoucher;
import cn.gson.financial.kernel.model.hibernate.BlazeJPAQueryFactory;
import cn.gson.financial.kernel.model.hibernate.PrimaryKey;
import cn.gson.financial.kernel.model.repo.CustomerRepository;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 开放 API — 做账状态（FN-FIX-06）
 * <p>
 * 给 WC 客服服务回答"X 客户 N 月做账完成了吗"用。
 * 路径前缀 /openapi/v1/bookkeeping，全部接口免鉴权（@SaIgnore）。
 * <p>
 * 注意：当前 Voucher / Checkout 都是"按账套整体"组织的，并没有"按客户分账期"的概念，
 *      所以 status 反映的是 <b>整个账套</b> 在该账期的做账状态，customerId 主要用来
 *      校验"客户是否存在 + 是否属于这个账套"。如果业务后续要求按客户分别核算，需要
 *      额外维护一张 customer_bookkeeping 表 — 见返回体里的"待业务扩展"说明。
 * <p>
 * status 判定逻辑：
 * <ul>
 *     <li>查 Checkout (fxy_financial_checkout, 按 accountSetsId/checkYear/checkMonth)</li>
 *     <li>Checkout.status=2（已结账）→ COMPLETED, completedAt = checkout.checkDate（当月最后一天）</li>
 *     <li>Checkout.status=1（已结转损益、未结账）→ IN_PROGRESS, completedAt=null</li>
 *     <li>Checkout.status=0 或不存在 + 当期凭证数 &gt; 0 → IN_PROGRESS</li>
 *     <li>Checkout.status=0 或不存在 + 当期凭证数 == 0 → NOT_STARTED</li>
 * </ul>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/openapi/v1/bookkeeping")
@SaIgnore
public class OpenApiBookkeepingController {

    public static final String STATUS_NOT_STARTED = "NOT_STARTED";
    public static final String STATUS_IN_PROGRESS = "IN_PROGRESS";
    public static final String STATUS_COMPLETED = "COMPLETED";

    private final CustomerRepository customerRepository;

    @Resource
    @Lazy
    private BlazeJPAQueryFactory bqf;

    private static final QCheckout qCheckout = QCheckout.checkout;
    private static final QVoucher qVoucher = QVoucher.voucher;

    // ═══════════════════════════════════════════════════════════════
    // GET /openapi/v1/bookkeeping/{customerId}?accountSetsId=X&period=YYYY-MM
    // ═══════════════════════════════════════════════════════════════

    @GetMapping("/{customerId}")
    public JsonResult bookkeeping(@PathVariable("customerId") Long customerId,
                                  @RequestParam(value = "accountSetsId", required = false) Long accountSetsId,
                                  @RequestParam(value = "period", required = false) String period) {
        if (customerId == null) {
            return failureWithType("customerId 不能为空", "INVALID_PARAM");
        }
        if (accountSetsId == null) {
            return failureWithType("accountSetsId 不能为空", "INVALID_PARAM");
        }
        if (period == null || period.isBlank()) {
            return failureWithType("period 不能为空，格式 YYYY-MM", "INVALID_PARAM");
        }

        YearMonth ym;
        try {
            ym = YearMonth.parse(period.trim());
        } catch (DateTimeParseException e) {
            return failureWithType("period 格式非法（期望 YYYY-MM）：" + period, "INVALID_PARAM");
        }

        // 1. 校验客户存在（带账套，因为 PK 是复合的）
        Optional<Customer> opt = customerRepository.findById(new PrimaryKey(customerId, accountSetsId));
        if (opt.isEmpty()) {
            return failureWithType("客户不存在：customerId=" + customerId
                    + ", accountSetsId=" + accountSetsId, "NOT_FOUND");
        }
        // 注：客户停用不影响做账状态查询（历史月份可能仍有账要看）

        int year = ym.getYear();
        int month = ym.getMonthValue();

        // 2. 当期凭证数
        long voucherCount = countVouchers(accountSetsId, year, month);

        // 3. 最大凭证日期（凭证数为 0 时跳过查询）
        LocalDate lastVoucherDate = voucherCount > 0 ? findLastVoucherDate(accountSetsId, year, month) : null;

        // 4. 查 Checkout
        Checkout checkout = findCheckout(accountSetsId, year, month);

        String status;
        LocalDate completedAt = null;
        if (checkout != null && checkout.getStatus() != null && checkout.getStatus() == 2) {
            status = STATUS_COMPLETED;
            completedAt = checkout.getCheckDate();
        } else if (checkout != null && checkout.getStatus() != null && checkout.getStatus() == 1) {
            status = STATUS_IN_PROGRESS;
        } else {
            // checkout.status==0 或 checkout 记录不存在
            status = voucherCount > 0 ? STATUS_IN_PROGRESS : STATUS_NOT_STARTED;
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("customerId", customerId);
        data.put("accountSetsId", accountSetsId);
        data.put("period", ym.toString());
        data.put("status", status);
        data.put("completedAt", completedAt);   // LocalDate (序列化为 ISO yyyy-MM-dd 或 null)
        // 用 (int) 包装绕过全局 Long→String 序列化器，保证 voucherCount 是 number
        data.put("voucherCount", (int) voucherCount);
        data.put("lastVoucherDate", lastVoucherDate);
        return JsonResult.successful(data);
    }

    // ═══════════════════════════════════════════════════════════════
    // 抽成 protected 方法以便单测覆盖：BlazeJPAQuery 是 final 类，
    // 在当前 mockito 配置下不可直接 mock 链式调用。
    // ═══════════════════════════════════════════════════════════════

    protected long countVouchers(Long accountSetsId, int year, int month) {
        return bqf.selectFrom(qVoucher)
                .where(qVoucher.accountSetsId.eq(accountSetsId)
                        .and(qVoucher.year.eq(year))
                        .and(qVoucher.month.eq(month)))
                .fetchCount();
    }

    protected LocalDate findLastVoucherDate(Long accountSetsId, int year, int month) {
        return bqf.selectFrom(qVoucher)
                .select(qVoucher.voucherDate.max())
                .where(qVoucher.accountSetsId.eq(accountSetsId)
                        .and(qVoucher.year.eq(year))
                        .and(qVoucher.month.eq(month)))
                .fetchFirst();
    }

    protected Checkout findCheckout(Long accountSetsId, int year, int month) {
        return bqf.selectFrom(qCheckout)
                .where(qCheckout.accountSetsId.eq(accountSetsId)
                        .and(qCheckout.checkYear.eq(year))
                        .and(qCheckout.checkMonth.eq(month)))
                .fetchFirst();
    }

    private static JsonResult failureWithType(String msg, String errorType) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("errorType", errorType);
        return JsonResult.failure(msg).setData(data);
    }
}
