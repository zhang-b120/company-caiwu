package cn.gson.financial.controller.openapi;

import cn.dev33.satoken.annotation.SaIgnore;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.model.entity.CustomerServiceSubscription;
import cn.gson.financial.kernel.model.repo.CustomerServiceSubscriptionRepository;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.*;

/**
 * 开放 API — 客户服务订阅（FN-NEW-03，mock）。
 * <p>
 * 给小程序首页 6 宫格使用 — 据 service_type 显示"已开通"或"未开通"badge。
 * 路径前缀 /openapi/v1/services，全部接口免鉴权（@SaIgnore）。
 * <p>
 * Phase 2 enable 接口前置支付链路；当前 mock 直接置为 active。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/openapi/v1/services")
@SaIgnore
public class OpenApiServiceSubscriptionController {

    static final Set<String> ALLOWED_TYPES = Set.of(
            CustomerServiceSubscription.TYPE_BOOKKEEPING,
            CustomerServiceSubscription.TYPE_GOV,
            CustomerServiceSubscription.TYPE_INVOICE,
            CustomerServiceSubscription.TYPE_LOAN,
            CustomerServiceSubscription.TYPE_TAX_CHECK,
            CustomerServiceSubscription.TYPE_REPORT
    );

    private final CustomerServiceSubscriptionRepository repo;

    // ═══════════════════════════════════════════════════════════════
    // GET /openapi/v1/services/{customerId} — 查客户所有订阅
    // ═══════════════════════════════════════════════════════════════

    @GetMapping("/{customerId}")
    public JsonResult list(@PathVariable Long customerId) {
        if (customerId == null) return JsonResult.failure("customerId 不能为空");
        List<CustomerServiceSubscription> all = repo.findByCustomerIdOrderByCreatedAtAsc(customerId);

        List<Map<String, Object>> rows = new ArrayList<>(all.size());
        for (CustomerServiceSubscription s : all) rows.add(toDto(s));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("customerId", customerId);
        data.put("subscriptions", rows);
        return JsonResult.successful(data);
    }

    // ═══════════════════════════════════════════════════════════════
    // POST /openapi/v1/services/{customerId}/enable
    // ═══════════════════════════════════════════════════════════════

    @PostMapping("/{customerId}/enable")
    @Transactional
    public JsonResult enable(@PathVariable Long customerId, @RequestBody EnableReq req) {
        if (customerId == null) return JsonResult.failure("customerId 不能为空");
        if (req == null) return JsonResult.failure("请求体不能为空");
        if (req.accountSetsId == null) return JsonResult.failure("accountSetsId 不能为空");
        if (req.serviceType == null || req.serviceType.isBlank()) {
            return JsonResult.failure("serviceType 不能为空");
        }
        String type = req.serviceType.trim().toLowerCase();
        if (!ALLOWED_TYPES.contains(type)) {
            return JsonResult.failure("serviceType 非法：" + req.serviceType
                    + "（合法值：" + ALLOWED_TYPES + "）");
        }
        int duration = req.durationMonths == null || req.durationMonths < 1 ? 12 : req.durationMonths;

        CustomerServiceSubscription sub = repo
                .findByCustomerIdAndServiceType(customerId, type)
                .orElseGet(CustomerServiceSubscription::new);

        sub.setCustomerId(customerId);
        sub.setAccountSetsId(req.accountSetsId);
        sub.setServiceType(type);
        sub.setPlanName(req.planName);
        sub.setStatus(CustomerServiceSubscription.STATUS_ACTIVE);
        LocalDate today = LocalDate.now();
        sub.setStartDate(today);
        sub.setEndDate(today.plusMonths(duration));

        sub = repo.save(sub);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("subscriptionId", sub.getId());
        data.put("status", sub.getStatus());
        return JsonResult.successful(data);
    }

    // ═══════════════════════════════════════════════════════════════
    // DTOs
    // ═══════════════════════════════════════════════════════════════

    @Data
    public static class EnableReq {
        public Long accountSetsId;
        public String serviceType;
        public String planName;
        public Integer durationMonths;
    }

    static Map<String, Object> toDto(CustomerServiceSubscription s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("customerId", s.getCustomerId());
        m.put("accountSetsId", s.getAccountSetsId());
        m.put("serviceType", s.getServiceType());
        m.put("status", s.getStatus());
        m.put("startDate", s.getStartDate());
        m.put("endDate", s.getEndDate());
        m.put("planName", s.getPlanName());
        m.put("createdAt", s.getCreatedAt());
        m.put("updatedAt", s.getUpdatedAt());
        return m;
    }
}
