package cn.gson.financial.controller.openapi;

import cn.dev33.satoken.annotation.SaIgnore;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.model.entity.Customer;
import cn.gson.financial.kernel.model.entity.QCustomer;
import cn.gson.financial.kernel.model.hibernate.BlazeJPAQueryFactory;
import cn.gson.financial.kernel.model.hibernate.PrimaryKey;
import cn.gson.financial.kernel.model.repo.CustomerRepository;
import cn.hutool.core.util.StrUtil;
import jakarta.annotation.Resource;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 开放 API — 客户（FN-02）
 * <p>
 * 提供给企业微信集成 / 外部 CRM 使用，全部接口免鉴权（@SaIgnore）。
 * 仅暴露 wechatExternalUserid 字段相关的两个最小操作：
 * <ul>
 *     <li>GET  /openapi/customer/by-wechat-userid/{uid} — 按企微 ID 反查客户</li>
 *     <li>PUT  /openapi/customer/{id}/wechat-userid    — 设置/更新某客户的企微 ID</li>
 * </ul>
 * 注意：客户表 PK 是复合 (id, accountSetsId)，PUT 接口需要传 accountSetsId 才能定位行。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/openapi/customer")
@SaIgnore
public class OpenApiCustomerController {

    private final CustomerRepository customerRepository;

    @Resource
    @Lazy
    private BlazeJPAQueryFactory bqf;

    private static final QCustomer qCustomer = QCustomer.customer;

    // ═══════════════════════════════════════════════════════════════
    // GET /openapi/customer/by-wechat-userid/{uid}
    //   按企微 external_userid 查客户。
    //   由于同一个 external_userid 在不同账套下可能各自绑定不同客户，
    //   这里返回 list（绝大多数情况下 0 或 1 条）。
    // ═══════════════════════════════════════════════════════════════

    @GetMapping("/by-wechat-userid/{uid}")
    public JsonResult byWechatUserid(@PathVariable("uid") String uid) {
        if (StrUtil.isBlank(uid)) {
            return JsonResult.failure("uid 不能为空");
        }

        List<Customer> rows = bqf.selectFrom(qCustomer)
                .where(qCustomer.wechatExternalUserid.eq(uid.trim()))
                .orderBy(qCustomer.accountSetsId.asc(), qCustomer.id.asc())
                .fetch();

        List<Map<String, Object>> result = new ArrayList<>(rows.size());
        for (Customer c : rows) {
            result.add(toDto(c));
        }
        return JsonResult.successful(result);
    }

    // ═══════════════════════════════════════════════════════════════
    // PUT /openapi/customer/{id}/wechat-userid
    //   设置/更新客户的企微 ID。请求体：
    //   { "accountSetsId": 12345, "wechatExternalUserid": "wmxxx..." }
    //   传空字符串或 null 视为"解绑"。
    // ═══════════════════════════════════════════════════════════════

    @PutMapping("/{id}/wechat-userid")
    @Transactional
    public JsonResult setWechatUserid(@PathVariable("id") Long id,
                                      @RequestBody UpdateWechatUseridReq req) {
        if (id == null) {
            return JsonResult.failure("id 不能为空");
        }
        if (req == null || req.getAccountSetsId() == null) {
            return JsonResult.failure("accountSetsId 不能为空");
        }

        Optional<Customer> opt = customerRepository.findById(new PrimaryKey(id, req.getAccountSetsId()));
        if (opt.isEmpty()) {
            return JsonResult.failure("客户不存在：id=" + id + ", accountSetsId=" + req.getAccountSetsId());
        }

        Customer customer = opt.get();
        String newUid = StrUtil.isBlank(req.getWechatExternalUserid())
                ? null
                : req.getWechatExternalUserid().trim();
        customer.setWechatExternalUserid(newUid);
        Customer saved = customerRepository.save(customer);

        return JsonResult.successful(toDto(saved));
    }

    // ═══════════════════════════════════════════════════════════════
    // 辅助
    // ═══════════════════════════════════════════════════════════════

    private Map<String, Object> toDto(Customer c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("accountSetsId", c.getAccountSetsId());
        m.put("categoryId", c.getCategoryId());
        m.put("code", c.getCode());
        m.put("name", c.getName());
        m.put("creditCode", c.getCreditCode());
        m.put("contact", c.getContact());
        m.put("telPhone", c.getTelPhone());
        m.put("wechatExternalUserid", c.getWechatExternalUserid());
        return m;
    }

    @Data
    public static class UpdateWechatUseridReq {
        private Long accountSetsId;
        private String wechatExternalUserid;
    }
}
