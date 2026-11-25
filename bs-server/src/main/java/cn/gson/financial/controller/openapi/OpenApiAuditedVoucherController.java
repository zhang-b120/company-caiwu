package cn.gson.financial.controller.openapi;

import cn.dev33.satoken.annotation.SaIgnore;
import cn.gson.financial.kernel.controller.JsonResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 开放 API — 账套<b>历史凭证明细</b>（ACCOUNT-PROFILE-V2 改造③，供 XW 主动学习反推「摘要/品类→科目」映射）。
 * 路径 {@code GET /openapi/v1/account-sets/{accountSetId}/audited-vouchers?months=12&maxVouchers=500}。
 *
 * <p>拉近 N 月（按 voucher_date，默认 12）凭证及其分录明细（科目 code/name + 摘要 + 借贷额）+ 关联发票（品类维度）。
 * XW 据 (摘要/品类,科目) 高频一致反推画像规则（降级链②主动学习）。
 *
 * <p><b>不过滤审核</b>（jianye 2026-06-10）：xiaowei push 的凭证默认无审核人（{@code audit_member_id} 为 null），
 * 原 {@code AND audit_member_id IS NOT NULL} 会把它们全滤掉、学不到数据，故去掉——本接口取<b>历史全量凭证</b>（不再限"已审"）。
 * 限 cap 张 + voucher_date DESC 取最近 N 张兜底（不裸拉全部）。路径名暂留 audited-vouchers，语义已变，后续可改 historical-vouchers。
 *
 * <p>纯读、@SaIgnore（沿用既定对外暂不鉴权）。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/openapi/v1/account-sets")
@SaIgnore
public class OpenApiAuditedVoucherController {

    static final int DEFAULT_MONTHS = 12;   // jianye 2026-06-10：窗口放宽到 12 个月
    static final int MAX_MONTHS = 24;
    static final int DEFAULT_MAX_VOUCHERS = 500;
    static final int CAP_MAX_VOUCHERS = 2000;

    private final JdbcTemplate jdbcTemplate;

    // PROFILE-V2-LEARN-AI-HYBRID：语义已变为"历史凭证"（不再只已审）→ 新增 historical-vouchers 路径名，audited-vouchers 暂留兼容
    @GetMapping({"/{accountSetId}/historical-vouchers", "/{accountSetId}/audited-vouchers"})
    public JsonResult auditedVouchers(@PathVariable Long accountSetId,
                                      @RequestParam(required = false, defaultValue = "12") Integer months,
                                      @RequestParam(required = false, defaultValue = "500") Integer maxVouchers) {
        if (accountSetId == null) return JsonResult.failure("accountSetId 不能为空");
        int m = (months == null || months < 1) ? DEFAULT_MONTHS : Math.min(months, MAX_MONTHS);
        int cap = (maxVouchers == null || maxVouchers < 1) ? DEFAULT_MAX_VOUCHERS : Math.min(maxVouchers, CAP_MAX_VOUCHERS);
        LocalDate fromDate = LocalDate.now().minusMonths(m);
        try {
            // 1) 历史凭证（不过滤审核——xiaowei push 的凭证无审核人；按 voucher_date 倒序，限 cap 张兜底）
            List<Map<String, Object>> vrows = jdbcTemplate.queryForList(
                    "SELECT id, voucher_date, code FROM fxy_financial_voucher " +
                            "WHERE account_sets_id = ? AND voucher_date >= ? " +
                            "ORDER BY voucher_date DESC, id DESC LIMIT ?",
                    accountSetId, fromDate, cap);

            List<Map<String, Object>> vouchers = new ArrayList<>(vrows.size());
            Map<Long, List<Map<String, Object>>> entriesByVoucher = new LinkedHashMap<>();
            Map<Long, List<Map<String, Object>>> invoicesByVoucher = new LinkedHashMap<>();
            Map<Long, Map<String, Object>> voucherById = new LinkedHashMap<>();
            List<Long> ids = new ArrayList<>(vrows.size());
            for (Map<String, Object> v : vrows) {
                Long vid = asLong(v.get("id"));
                ids.add(vid);
                Map<String, Object> vm = new LinkedHashMap<>();
                vm.put("voucherId", vid);
                vm.put("voucherDate", v.get("voucher_date") == null ? null : String.valueOf(v.get("voucher_date")));
                vm.put("code", v.get("code"));
                vm.put("summary", null);   // 凭证级摘要：取首条分录摘要（Voucher 无独立摘要列），下方填
                List<Map<String, Object>> el = new ArrayList<>();
                vm.put("entries", el);     // 分录：科目/摘要/借贷
                entriesByVoucher.put(vid, el);
                List<Map<String, Object>> il = new ArrayList<>();
                vm.put("invoices", il);    // 关联发票：商品名/品类/对手方/金额
                invoicesByVoucher.put(vid, il);
                voucherById.put(vid, vm);
                vouchers.add(vm);
            }

            int entryCount = 0;
            int invoiceCount = 0;
            if (!ids.isEmpty()) {
                String in = ids.stream().map(x -> "?").collect(Collectors.joining(","));

                // 2) 这些凭证的分录（科目 code/name + 摘要 + 借贷）
                List<Object> dargs = new ArrayList<>();
                dargs.add(accountSetId);   // subject 账套隔离 join
                dargs.addAll(ids);
                List<Map<String, Object>> drows = jdbcTemplate.queryForList(
                        "SELECT vd.voucher_id, vd.summary, s.code AS subject_code, s.name AS subject_name, " +
                                "vd.debit_amount, vd.credit_amount " +
                                "FROM fxy_financial_voucher_details vd " +
                                "LEFT JOIN fxy_financial_subject s ON s.id = vd.subject_id AND s.account_sets_id = ? " +
                                "WHERE vd.voucher_id IN (" + in + ") ORDER BY vd.voucher_id, vd.id",
                        dargs.toArray());
                for (Map<String, Object> d : drows) {
                    Long vid = asLong(d.get("voucher_id"));
                    List<Map<String, Object>> el = entriesByVoucher.get(vid);
                    if (el == null) continue;
                    Map<String, Object> em = new LinkedHashMap<>();
                    em.put("subjectCode", d.get("subject_code"));
                    em.put("subjectName", d.get("subject_name"));
                    em.put("summary", d.get("summary"));
                    em.put("debit", d.get("debit_amount"));
                    em.put("credit", d.get("credit_amount"));
                    el.add(em);
                    entryCount++;
                    // 凭证级摘要补首条分录摘要
                    Map<String, Object> vm = voucherById.get(vid);
                    if (vm != null && vm.get("summary") == null && d.get("summary") != null) {
                        vm.put("summary", d.get("summary"));
                    }
                }

                // 3) 这些凭证关联的发票（invoice.voucher_id→凭证），带 品类(业务类型)/专普票/对手方/金额。
                List<Object> iargs = new ArrayList<>();
                iargs.add(accountSetId);   // business_type 账套隔离 join
                iargs.add(accountSetId);   // invoice 过滤
                iargs.addAll(ids);
                List<Map<String, Object>> irows = jdbcTemplate.queryForList(
                        "SELECT i.id, i.voucher_id, i.type, i.category, i.new_category, i.customer_name, i.note, " +
                                "i.amount, i.tax_amount, i.tax_amount_excluded, bt.title AS business_type_name " +
                                "FROM fxy_financial_invoice i " +
                                "LEFT JOIN fxy_financial_invoice_business_type bt " +
                                "  ON bt.id = i.invoice_business_type_id AND bt.account_sets_id = ? " +
                                "WHERE i.account_sets_id = ? AND i.voucher_id IN (" + in + ") " +
                                "ORDER BY i.voucher_id, i.id",
                        iargs.toArray());
                Map<Long, List<String>> goodsByInvoice = new LinkedHashMap<>();   // invoice id → goodsNames
                List<Long> invoiceIds = new ArrayList<>(irows.size());
                for (Map<String, Object> i : irows) {
                    List<Map<String, Object>> il = invoicesByVoucher.get(asLong(i.get("voucher_id")));
                    if (il == null) continue;
                    Long invId = asLong(i.get("id"));
                    invoiceIds.add(invId);
                    List<String> goodsNames = new ArrayList<>();
                    goodsByInvoice.put(invId, goodsNames);
                    Map<String, Object> im = new LinkedHashMap<>();
                    im.put("invoiceId", invId);
                    im.put("direction", enumName(cn.gson.financial.kernel.model.enums.InvoiceType.class, i.get("type")));   // IN/OUT
                    im.put("category", enumName(cn.gson.financial.kernel.model.enums.InvoiceCategory.class, i.get("category"))); // 专/普票
                    im.put("newCategory", i.get("new_category"));
                    im.put("businessTypeName", i.get("business_type_name"));  // 业务类型
                    im.put("customerName", i.get("customer_name"));
                    im.put("note", i.get("note"));
                    im.put("amount", i.get("amount"));
                    im.put("taxAmount", i.get("tax_amount"));
                    im.put("amountExcluded", i.get("tax_amount_excluded"));
                    im.put("goodsNames", goodsNames);   // 4) join InvoiceItems 填（AI 看"卖的是什么"）
                    il.add(im);
                    invoiceCount++;
                }

                // 4) PROFILE-V2-LEARN-AI-HYBRID：发票商品名（InvoiceItems.goods_name，一票多商品 → goodsNames[]）
                if (!invoiceIds.isEmpty()) {
                    String inv = invoiceIds.stream().map(x -> "?").collect(Collectors.joining(","));
                    List<Object> gargs = new ArrayList<>();
                    gargs.add(accountSetId);   // items 账套隔离
                    gargs.addAll(invoiceIds);
                    List<Map<String, Object>> grows = jdbcTemplate.queryForList(
                            "SELECT invoice_id, goods_name FROM fxy_financial_invoice_items " +
                                    "WHERE account_sets_id = ? AND invoice_id IN (" + inv + ") ORDER BY invoice_id, id",
                            gargs.toArray());
                    for (Map<String, Object> g : grows) {
                        List<String> gn = goodsByInvoice.get(asLong(g.get("invoice_id")));
                        Object name = g.get("goods_name");
                        if (gn != null && name != null && !String.valueOf(name).isBlank()) {
                            gn.add(String.valueOf(name));
                        }
                    }
                }
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("accountSetsId", accountSetId);
            data.put("months", m);
            data.put("fromDate", fromDate.toString());
            data.put("voucherCount", vouchers.size());
            data.put("entryCount", entryCount);
            data.put("invoiceCount", invoiceCount);
            data.put("truncated", vrows.size() >= cap);   // 命中张数上限，可能未取全
            data.put("vouchers", vouchers);
            return JsonResult.successful(data);
        } catch (Exception e) {
            log.error("[ACCOUNT-PROFILE-V2] audited-vouchers failed acc={} months={}", accountSetId, m, e);
            return JsonResult.failure("查询历史已审凭证失败：" + e.getMessage());
        }
    }

    private static Long asLong(Object o) {
        if (o instanceof Number n) return n.longValue();
        return o == null ? null : Long.parseLong(o.toString());
    }

    /** DB 存的是 @Enumerated 序号（ordinal）→ 枚举名；越界/空返 null（容错，不抛）。 */
    private static <E extends Enum<E>> String enumName(Class<E> type, Object ordinal) {
        if (!(ordinal instanceof Number)) return ordinal == null ? null : String.valueOf(ordinal);
        int idx = ((Number) ordinal).intValue();
        E[] vals = type.getEnumConstants();
        return (idx >= 0 && idx < vals.length) ? vals[idx].name() : String.valueOf(idx);
    }
}

