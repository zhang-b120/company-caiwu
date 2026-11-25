package cn.gson.financial.controller.openapi;

import cn.dev33.satoken.annotation.SaIgnore;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.model.entity.Invoice;
import cn.gson.financial.kernel.model.entity.InvoiceItems;
import cn.gson.financial.kernel.model.entity.QInvoice;
import cn.gson.financial.kernel.model.entity.QInvoiceItems;
import cn.gson.financial.kernel.model.entity.QVoucher;
import cn.gson.financial.kernel.model.query.InvoicePeriodPredicate;
import cn.gson.financial.kernel.model.enums.InvoiceState;
import cn.gson.financial.kernel.model.enums.InvoiceType;
import cn.gson.financial.kernel.model.hibernate.BlazeJPAQueryFactory;
import cn.hutool.core.util.StrUtil;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.jpa.JPAExpressions;
import jakarta.annotation.Resource;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/**
 * 开放 API — 发票
 * 路径前缀 /openapi/v1/invoices
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/openapi/v1/invoices")
@SaIgnore
public class OpenApiInvoiceController {

    @Resource
    @Lazy
    private BlazeJPAQueryFactory bqf;

    private static final QInvoice qInvoice = QInvoice.invoice;
    private static final QInvoiceItems qInvoiceItems = QInvoiceItems.invoiceItems;
    private static final QVoucher qVoucher = QVoucher.voucher;

    /**
     * §2.5 发票列表
     * POST /openapi/v1/invoices/list
     * type: SALES（销项=Invoice.OUT）/ PURCHASE（进项=Invoice.IN）
     */
    @PostMapping("/list")
    public JsonResult list(@RequestBody InvoiceQueryReq req) {
        if (req.getAccountSetsId() == null) {
            return JsonResult.failure("accountSetsId 不能为空");
        }
        Integer page = req.getPage() != null ? req.getPage() : 1;
        Integer size = req.getSize() != null ? req.getSize() : 100;
        if (page < 1) page = 1;
        if (size < 1) size = 100;
        if (size > 500) size = 500;

        BooleanBuilder where = new BooleanBuilder();
        where.and(qInvoice.accountSetsId.eq(req.getAccountSetsId()));

        // type 映射：SALES → OUT（开具/销项）；PURCHASE → IN（取得/进项）
        if (StrUtil.isNotBlank(req.getType())) {
            String t = req.getType().trim().toUpperCase();
            InvoiceType invType;
            switch (t) {
                case "SALES":
                case "OUT":
                    invType = InvoiceType.OUT;
                    break;
                case "PURCHASE":
                case "IN":
                    invType = InvoiceType.IN;
                    break;
                default:
                    return JsonResult.failure("type 只支持 SALES / PURCHASE");
            }
            where.and(qInvoice.type.eq(invType));
        }

        // state 映射（入参支持英文枚举名 / 中文，统一转 InvoiceState）
        if (StrUtil.isNotBlank(req.getState())) {
            InvoiceState st = parseState(req.getState());
            if (st == null) {
                return JsonResult.failure("state 非法：" + req.getState()
                        + "（合法值：NORMAL/INVALID/FULL_RED_OFFSET/PARTIAL_RED_OFFSET）");
            }
            where.and(qInvoice.state.eq(st));
        } else {
            // 默认只返回正常发票，排除作废和红冲
            where.and(qInvoice.state.eq(InvoiceState.正常));
        }

        // 日期范围
        // FN-FIX-OPENAPI-INVOICE-PERIOD-ASSIGNMENT-DATE：期间过滤统一用「assignmentDate 优先 + tradeDate 兜底」
        // （与 FN 内部做账口径一致），纳入被指定到本期但开票在别月的发票。入参 tradeDateStart/End 仍作期间区间，
        // 应用到「有效期间 = COALESCE(assignmentDate, tradeDate)」，不新增字段、不破坏契约。
        var periodCond = InvoicePeriodPredicate.byPeriod(qInvoice, req.getTradeDateStart(), req.getTradeDateEnd());
        if (periodCond != null) {
            where.and(periodCond);
        }

        // FN-FIX-INVOICE-STALE-VOUCHER：是否只返回未做账。
        // 仅 isNull 不够 —— 历史 bulk delete 漏触发 voucherProcessor 时，invoice.voucher_id
        // 仍悬空指向已删 voucher。这里 JOIN voucher 表实时检查存在性，悬空数据视同未做账。
        if (Boolean.TRUE.equals(req.getVoucherIdIsNull())) {
            where.and(qInvoice.voucherId.isNull().or(
                    qInvoice.voucherId.notIn(
                            JPAExpressions.select(qVoucher.id).from(qVoucher)
                                    .where(qVoucher.accountSetsId.eq(req.getAccountSetsId()))
                    )
            ));
        }

        long total = countInvoicesForTest(where);
        List<Invoice> invoices = fetchInvoicesForTest(where, (long) (page - 1) * size, size);

        // 批量加载明细
        List<Long> invoiceIds = new ArrayList<>();
        for (Invoice i : invoices) invoiceIds.add(i.getId());
        Map<Long, List<InvoiceItems>> itemsByInvoice = fetchItemsByInvoiceForTest(req.getAccountSetsId(), invoiceIds);

        // FN-FIX-INVOICE-STALE-VOUCHER：批量预查本页 invoices 引用的 voucherId 中哪些还真存在，
        // toDto 据此自愈返回（悬空的 voucherId 字段返 null，避免 BT UI 误判已做账）。
        // 用 IN 命中主键索引，单次查询完成；N+1 不会发生。
        List<Long> candidates = invoices.stream()
                .map(Invoice::getVoucherId).filter(Objects::nonNull).distinct().toList();
        Set<Long> stillExistingVoucherIds = resolveExistingVoucherIdsForTest(req.getAccountSetsId(), candidates);

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Invoice i : invoices) {
            rows.add(toDto(i, itemsByInvoice.getOrDefault(i.getId(), Collections.emptyList()),
                    stillExistingVoucherIds));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rows", rows);
        // 用 int 包装绕过全局 Long→String 序列化器（WebMvcSupport.java:183），保证 total 是 number
        result.put("total", (int) total);
        result.put("page", page);
        result.put("size", size);
        return JsonResult.successful(result);
    }

    /** package-private hook：单测覆写以避开 BlazeJPAQuery */
    Map<Long, List<InvoiceItems>> fetchItemsByInvoiceForTest(Long accountSetsId, List<Long> invoiceIds) {
        Map<Long, List<InvoiceItems>> itemsByInvoice = new HashMap<>();
        if (invoiceIds == null || invoiceIds.isEmpty()) return itemsByInvoice;
        List<InvoiceItems> itemList = bqf.selectFrom(qInvoiceItems)
                .where(qInvoiceItems.accountSetsId.eq(accountSetsId)
                        .and(qInvoiceItems.invoiceId.in(invoiceIds)))
                .fetch();
        for (InvoiceItems it : itemList) {
            itemsByInvoice.computeIfAbsent(it.getInvoiceId(), k -> new ArrayList<>()).add(it);
        }
        return itemsByInvoice;
    }

    /** package-private hook：批量查 candidates 中真存在于该账套 voucher 表的 ID 子集（单测覆写） */
    Set<Long> resolveExistingVoucherIdsForTest(Long accountSetsId, List<Long> candidates) {
        if (candidates == null || candidates.isEmpty()) return Collections.emptySet();
        return new HashSet<>(bqf.selectFrom(qVoucher).select(qVoucher.id)
                .where(qVoucher.id.in(candidates).and(qVoucher.accountSetsId.eq(accountSetsId)))
                .fetch());
    }

    /** package-private hook：单测覆写以避开 BlazeJPAQuery */
    long countInvoicesForTest(BooleanBuilder where) {
        return bqf.selectFrom(qInvoice).where(where).fetchCount();
    }

    /** package-private hook：单测覆写以避开 BlazeJPAQuery */
    List<Invoice> fetchInvoicesForTest(BooleanBuilder where, long offset, int limit) {
        return bqf.selectFrom(qInvoice)
                .where(where)
                .orderBy(qInvoice.tradeDate.asc(), qInvoice.id.asc())
                .offset(offset)
                .limit(limit)
                .fetch();
    }

    /**
     * @param stillExistingVoucherIds 本页 invoices 引用的 voucherId 中真实存在于 voucher 表的子集。
     *                                自愈：i.voucherId 若不在此集合中（悬空引用），返 null；存在则原样返。
     */
    Map<String, Object> toDto(Invoice i, List<InvoiceItems> items, Set<Long> stillExistingVoucherIds) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", i.getId());
        m.put("accountSetsId", i.getAccountSetsId());
        // 反向映射：OUT → SALES, IN → PURCHASE
        m.put("type", i.getType() == InvoiceType.OUT ? "SALES"
                : (i.getType() == InvoiceType.IN ? "PURCHASE" : null));
        m.put("newCategory", i.getNewCategory());
        // state 中文枚举转英文枚举名（NORMAL/INVALID/FULL_RED_OFFSET/PARTIAL_RED_OFFSET）
        m.put("state", stateToEnglish(i.getState()));
        m.put("tradeDate", i.getTradeDate());
        m.put("checkDate", i.getCheckDate());
        m.put("assignmentDate", i.getAssignmentDate());
        m.put("invoiceCode", i.getInvoiceCode());
        m.put("invoiceNumber", i.getInvoiceNumber());
        m.put("customerName", i.getCustomerName());
        m.put("creditCode", i.getCreditCode());
        m.put("amountTotal", i.getAmount());           // 价税合计
        m.put("amountTax", i.getTaxAmount());          // 税额
        m.put("amountExcluded", i.getTaxAmountExcluded()); // 不含税
        // FN-FIX-INVOICE-STALE-VOUCHER：悬空 voucher_id 返 null 自愈
        Long vid = i.getVoucherId();
        m.put("voucherId", (vid != null && stillExistingVoucherIds.contains(vid)) ? vid : null);
        m.put("haveDetail", Boolean.TRUE.equals(i.getHaveDetail()));
        m.put("remark", i.getRemark());

        List<Map<String, Object>> itemList = new ArrayList<>();
        for (InvoiceItems it : items) {
            Map<String, Object> ri = new LinkedHashMap<>();
            ri.put("id", it.getId());
            ri.put("goodsName", it.getGoodsName());
            ri.put("specification", it.getSpecification());
            ri.put("unit", it.getUnit());
            ri.put("quantity", it.getQuantity());
            // InvoiceItems.amount 实体里是 String，对外统一成 BigDecimal number
            ri.put("amountExcluded", parseAmount(it.getAmount()));
            ri.put("taxRate", it.getTaxRate());
            ri.put("tax", it.getTax());
            ri.put("taxedItems", it.getTaxedItems());
            itemList.add(ri);
        }
        m.put("items", itemList);
        return m;
    }

    /** state 入参：英文枚举名 / 中文，统一映射到 InvoiceState */
    private static InvoiceState parseState(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.isEmpty()) return null;
        return switch (t.toUpperCase()) {
            case "NORMAL" -> InvoiceState.正常;
            case "INVALID" -> InvoiceState.作废;
            case "FULL_RED_OFFSET" -> InvoiceState.全额红冲;
            case "PARTIAL_RED_OFFSET" -> InvoiceState.部分红冲;
            default -> {
                // 兜底：直接尝试中文匹配
                if ("正常".equals(t)) yield InvoiceState.正常;
                if ("作废".equals(t)) yield InvoiceState.作废;
                if ("全额红冲".equals(t)) yield InvoiceState.全额红冲;
                if ("部分红冲".equals(t)) yield InvoiceState.部分红冲;
                yield null;
            }
        };
    }

    /** state 出参：InvoiceState 中文枚举 → 英文枚举名 */
    private static String stateToEnglish(InvoiceState s) {
        if (s == null) return null;
        return switch (s) {
            case 正常 -> "NORMAL";
            case 作废 -> "INVALID";
            case 全额红冲 -> "FULL_RED_OFFSET";
            case 部分红冲 -> "PARTIAL_RED_OFFSET";
        };
    }

    /** InvoiceItems.amount 是 String 类型，安全转 BigDecimal */
    private static BigDecimal parseAmount(String s) {
        if (StrUtil.isBlank(s)) return null;
        try {
            return new BigDecimal(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Data
    public static class InvoiceQueryReq {
        private Long accountSetsId;
        private String type;             // SALES / PURCHASE
        private LocalDate tradeDateStart;
        private LocalDate tradeDateEnd;
        private String state;            // NORMAL / INVALID
        private Boolean voucherIdIsNull;
        private Integer page;
        private Integer size;
    }
}
