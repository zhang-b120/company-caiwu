package cn.gson.financial.controller.openapi;

import cn.dev33.satoken.annotation.SaIgnore;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.model.entity.Customer;
import cn.gson.financial.kernel.model.entity.QInvoice;
import cn.gson.financial.kernel.model.enums.InvoiceState;
import cn.gson.financial.kernel.model.enums.InvoiceType;
import cn.gson.financial.kernel.model.hibernate.BlazeJPAQueryFactory;
import cn.gson.financial.kernel.model.hibernate.PrimaryKey;
import cn.gson.financial.kernel.model.repo.CustomerRepository;
import cn.hutool.core.util.StrUtil;
import com.querydsl.core.BooleanBuilder;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Year;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 开放 API — 开票额度（FN-FIX-06）
 * <p>
 * 给 WC 客服服务回答"今年开了多少票/还能开多少"用。
 * 路径前缀 /openapi/v1/invoice/quota，全部接口免鉴权（@SaIgnore）。
 * <p>
 * 业务约束：
 * <ul>
 *     <li>invoiced 仅累计 state=正常 + type=OUT（销项）的发票金额（amount = 价税合计）</li>
 *     <li>quota 取自 customer 表上的额度字段；本期 Customer 实体没有 quota 字段，
 *         所以暂用一个固定默认值 DEFAULT_ANNUAL_QUOTA = 1000000.00（CNY/年）。
 *         后续业务侧补 customer.quota 列后再切换。</li>
 *     <li>customer 主键是复合 PK (id, accountSetsId)，所以查询必须同时带 accountSetsId</li>
 *     <li>已停用客户 (enable=false) 视为不存在，返 NOT_FOUND</li>
 * </ul>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/openapi/v1/invoice/quota")
@SaIgnore
public class OpenApiQuotaController {

    /**
     * 客户开票额度默认值（CNY / 年）。
     * 待业务侧在 Customer entity 上补 quota 字段后再替换为按客户读取。
     */
    public static final BigDecimal DEFAULT_ANNUAL_QUOTA = new BigDecimal("1000000.00");

    private final CustomerRepository customerRepository;

    @Resource
    @Lazy
    private BlazeJPAQueryFactory bqf;

    private static final QInvoice qInvoice = QInvoice.invoice;

    // ═══════════════════════════════════════════════════════════════
    // GET /openapi/v1/invoice/quota/{customerId}?accountSetsId=X&year=Y
    // ═══════════════════════════════════════════════════════════════

    @GetMapping("/{customerId}")
    public JsonResult quota(@PathVariable("customerId") Long customerId,
                            @RequestParam(value = "accountSetsId", required = false) Long accountSetsId,
                            @RequestParam(value = "year", required = false) Integer year) {
        // 入参校验
        if (customerId == null) {
            return failureWithType("customerId 不能为空", -1, "INVALID_PARAM");
        }
        if (accountSetsId == null) {
            return failureWithType("accountSetsId 不能为空", -1, "INVALID_PARAM");
        }
        int yr = year != null ? year : Year.now().getValue();

        // 1. 查客户：必须存在且 enable!=false
        Optional<Customer> opt = customerRepository.findById(new PrimaryKey(customerId, accountSetsId));
        if (opt.isEmpty()) {
            return failureWithType("客户不存在：customerId=" + customerId
                    + ", accountSetsId=" + accountSetsId, -1, "NOT_FOUND");
        }
        Customer customer = opt.get();
        if (Boolean.FALSE.equals(customer.getEnable())) {
            return failureWithType("客户已停用：customerId=" + customerId
                    + ", accountSetsId=" + accountSetsId, -1, "NOT_FOUND");
        }

        // 2. 计算 invoiced（销项发票本年累计含税合计）
        BigDecimal invoiced = sumInvoicedAmount(customer, accountSetsId, yr);
        if (invoiced == null) {
            invoiced = BigDecimal.ZERO;
        }

        // 3. quota：暂用固定默认值
        BigDecimal quota = DEFAULT_ANNUAL_QUOTA;
        BigDecimal remaining = quota.subtract(invoiced);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("customerId", customerId);
        data.put("accountSetsId", accountSetsId);
        data.put("period", String.valueOf(yr));
        data.put("invoiced", invoiced);
        data.put("quota", quota);
        data.put("remaining", remaining);
        // 标记 quota 数据源，方便联调时确认是否已迁到真实字段
        data.put("quotaSource", "DEFAULT");
        return JsonResult.successful(data);
    }

    // ═══════════════════════════════════════════════════════════════
    // 辅助
    // ═══════════════════════════════════════════════════════════════

    /**
     * 按 (customer, accountSetsId, 年) 累加销项正常发票的含税金额。
     * 抽成 protected 方法，是为了让单元测试能直接 stub 出固定值，
     * 不必去 mock BlazeJPAQuery 链（BlazeJPAQuery 是 final 类，
     * 在当前 mockito 配置下 mock 不动）。
     */
    protected BigDecimal sumInvoicedAmount(Customer customer, Long accountSetsId, int year) {
        LocalDate periodStart = LocalDate.of(year, 1, 1);
        LocalDate periodEnd = LocalDate.of(year, 12, 31);

        BooleanBuilder where = new BooleanBuilder();
        where.and(qInvoice.accountSetsId.eq(accountSetsId));
        where.and(qInvoice.type.eq(InvoiceType.OUT));
        where.and(qInvoice.state.eq(InvoiceState.正常));
        where.and(qInvoice.tradeDate.between(periodStart, periodEnd));
        where.and(customerMatchPredicate(customer));

        return bqf.selectFrom(qInvoice)
                .select(qInvoice.amount.sum().coalesce(BigDecimal.ZERO))
                .where(where)
                .fetchFirst();
    }

    /**
     * 根据 customer 的 creditCode / name 构造发票匹配条件。
     * Invoice 表没有 customer_id 列，只能按这两个字段反查。
     */
    static com.querydsl.core.types.dsl.BooleanExpression customerMatchPredicate(Customer customer) {
        String credit = StrUtil.trimToNull(customer.getCreditCode());
        String name = StrUtil.trimToNull(customer.getName());
        if (credit != null && name != null) {
            // 二选一命中即可：客户改过名 / 历史发票字段不齐都能兜底
            return qInvoice.creditCode.eq(credit).or(qInvoice.customerName.eq(name));
        }
        if (credit != null) {
            return qInvoice.creditCode.eq(credit);
        }
        if (name != null) {
            return qInvoice.customerName.eq(name);
        }
        // 客户连名字都没有 — 不可能匹配到任何发票
        return qInvoice.id.isNull();
    }

    private static JsonResult failureWithType(String msg, int code, String errorType) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("errorType", errorType);
        return JsonResult.failure(msg, code).setData(data);
    }
}
