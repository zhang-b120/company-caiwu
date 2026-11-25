package cn.gson.financial.controller.openapi;

import cn.dev33.satoken.annotation.SaIgnore;
import cn.gson.financial.kernel.controller.JsonResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * FN-INVOICE-02 — 客户库 OpenAPI（开票专用）。
 *
 * <p>GET /openapi/v1/customer/{id}         — 客户详情（含开票字段 + 开票历史）
 * <p>GET /openapi/v1/customer/search       — 关键字搜索客户列表
 *
 * <p>与 /openapi/customer（FN-02）不同：本控制器路径含 v1，面向开票场景，
 * 返回 bank/bankAccount/taxpayerType/defaultInvoiceType 等开票必要字段。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/openapi/v1/customer")
@SaIgnore
public class OpenApiInvoiceCustomerController {

    private final JdbcTemplate jdbcTemplate;

    private static final String CUSTOMER_SELECT =
            "SELECT c.id, c.account_sets_id, c.name, c.code, c.credit_code, " +
            "c.address, c.tel_phone, c.contact, c.email, " +
            "c.bank, c.bank_account, c.taxpayer_type, c.default_invoice_type, c.industry, " +
            "c.wechat_external_userid " +
            "FROM fxy_financial_customer c";

    // ─── GET /{id} ───────────────────────────────────────────────────

    @GetMapping("/{id}")
    public JsonResult getCustomer(@PathVariable Long id,
                                  @RequestParam(required = false) Long accountSetsId) {
        if (id == null) return JsonResult.failure("id 不能为空");
        Map<String, Object> row = loadCustomerRow(id, accountSetsId);
        if (row == null) return JsonResult.failure("客户不存在：" + id);

        Map<String, Object> history = loadInvoiceHistory((Long) row.get("id"));

        Map<String, Object> data = buildCustomerDto(row);
        data.put("invoiceHistory", history);
        return JsonResult.successful(data);
    }

    // ─── GET /search ─────────────────────────────────────────────────

    @GetMapping("/search")
    public JsonResult search(@RequestParam(required = false) String keyword,
                             @RequestParam(required = false) String taxNumber,
                             @RequestParam(required = false) Long accountSetId,
                             @RequestParam(defaultValue = "20") Integer limit) {
        if (limit == null || limit < 1) limit = 20;
        if (limit > 100) limit = 100;

        List<Map<String, Object>> rows = searchCustomers(keyword, taxNumber, accountSetId, limit);
        List<Map<String, Object>> list = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) list.add(buildCustomerDto(row));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("rows", list);
        data.put("total", list.size());
        data.put("limit", limit);
        data.put("records", list);   // 兼容旧调用方
        return JsonResult.successful(data);
    }

    // ─── helpers (public for spy-based tests) ────────────────────────

    public Map<String, Object> loadCustomerRow(Long id, Long accountSetsId) {
        StringBuilder sql = new StringBuilder(CUSTOMER_SELECT).append(" WHERE c.id = ?");
        List<Object> params = new ArrayList<>();
        params.add(id);
        if (accountSetsId != null) {
            sql.append(" AND c.account_sets_id = ?");
            params.add(accountSetsId);
        }
        sql.append(" LIMIT 1");
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql.toString(), params.toArray());
            return rows.isEmpty() ? null : rows.get(0);
        } catch (Exception e) {
            log.warn("[FN-INVOICE-02] loadCustomerRow id={} error: {}", id, e.getMessage());
            return null;
        }
    }

    public List<Map<String, Object>> searchCustomers(String keyword, Long accountSetId, int limit) {
        return searchCustomers(keyword, null, accountSetId, limit);
    }

    public List<Map<String, Object>> searchCustomers(String keyword, String taxNumber, Long accountSetId, int limit) {
        StringBuilder sql = new StringBuilder(CUSTOMER_SELECT).append(" WHERE 1=1");
        List<Object> params = new ArrayList<>();
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND (c.name LIKE ? OR c.credit_code LIKE ? OR c.contact LIKE ?)");
            String like = "%" + keyword.trim() + "%";
            params.add(like); params.add(like); params.add(like);
        }
        // taxNumber 单独精确匹配 credit_code（与 keyword 是 AND 关系）
        if (taxNumber != null && !taxNumber.isBlank()) {
            sql.append(" AND c.credit_code = ?");
            params.add(taxNumber.trim());
        }
        if (accountSetId != null) {
            sql.append(" AND c.account_sets_id = ?");
            params.add(accountSetId);
        }
        sql.append(" LIMIT ?");
        params.add(limit);
        return jdbcTemplate.queryForList(sql.toString(), params.toArray());
    }

    public Map<String, Object> loadInvoiceHistory(Long customerId) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            Map<String, Object> stats = jdbcTemplate.queryForMap(
                    "SELECT COUNT(*) AS cnt, MAX(issued_at) AS last_at " +
                    "FROM fxy_financial_invoice_task " +
                    "WHERE customer_id = ? AND status = 'SUCCESS'",
                    customerId);
            result.put("count", stats.get("cnt"));
            result.put("lastInvoiceAt", stats.get("last_at"));
        } catch (Exception e) {
            result.put("count", 0L);
            result.put("lastInvoiceAt", null);
        }
        return result;
    }

    private static Map<String, Object> buildCustomerDto(Map<String, Object> row) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", row.get("id"));
        m.put("accountSetsId", row.get("account_sets_id"));
        m.put("name", row.get("name"));
        m.put("code", row.get("code"));
        m.put("taxNumber", row.get("credit_code"));
        m.put("address", row.get("address"));
        m.put("phone", row.get("tel_phone"));
        m.put("contact", row.get("contact"));
        m.put("email", row.get("email"));
        m.put("bank", row.get("bank"));
        m.put("bankName", row.get("bank"));        // xiaowei 期望的字段名
        m.put("bankAccount", row.get("bank_account"));
        m.put("taxpayerType", row.get("taxpayer_type"));
        m.put("defaultInvoiceType", row.get("default_invoice_type"));
        m.put("industry", row.get("industry"));
        m.put("wechatExternalUserid", row.get("wechat_external_userid"));
        return m;
    }
}
