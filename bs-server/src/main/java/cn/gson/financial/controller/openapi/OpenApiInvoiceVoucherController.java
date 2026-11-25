package cn.gson.financial.controller.openapi;

import cn.dev33.satoken.annotation.SaIgnore;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.model.dto.VoucherDetailsDto;
import cn.gson.financial.kernel.model.dto.VoucherDto;
import cn.gson.financial.kernel.model.entity.*;
import cn.gson.financial.kernel.model.hibernate.BlazeJPAQueryFactory;
import cn.gson.financial.kernel.service.VoucherService;
import cn.hutool.core.util.StrUtil;
import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.annotation.Resource;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;

/**
 * 开放 API — 开票凭证（BT 开票成功后回调）
 * <p>
 * POST /openapi/v1/invoice/voucher
 * BT 完成发票开具后调用，在账套中自动生成对应的销售开票凭证：
 *   Dr  应收账款(1122*)  = 不含税 + 税额
 *   Cr  主营业务收入(6001*) = 不含税
 *   Cr  应交税费-销项税额(2221*) = 税额（有税时）
 * 若账套中缺少上述科目，返回 failure 并说明缺失的科目名称。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/openapi/v1/invoice")
@SaIgnore
public class OpenApiInvoiceVoucherController {

    private final VoucherService voucherService;

    @Resource
    @Lazy
    private BlazeJPAQueryFactory bqf;

    @Resource
    private JPAQueryFactory jqf;

    private static final QAccountSets qAccountSets = QAccountSets.accountSets;
    private static final QInvoice qInvoice = QInvoice.invoice;
    private static final QSubject qSubject = QSubject.subject;

    @PostMapping("/voucher")
    @Transactional(rollbackFor = Exception.class)
    public JsonResult postInvoiceVoucher(@RequestBody InvoiceVoucherReq req) {
        // ── 1. 必填校验 ──────────────────────────────────────────────
        if (req.getAccountSetId() == null) return JsonResult.failure("accountSetId 不能为空");
        if (StrUtil.isBlank(req.getPeriod())) return JsonResult.failure("period 不能为空");

        BigDecimal amount    = req.getAmount()    != null ? req.getAmount()    : BigDecimal.ZERO;
        BigDecimal taxAmount = req.getTaxAmount()  != null ? req.getTaxAmount() : BigDecimal.ZERO;
        BigDecimal total     = amount.add(taxAmount);
        if (total.compareTo(BigDecimal.ZERO) == 0) {
            return JsonResult.failure("amount 和 taxAmount 不能同时为零");
        }

        // ── 2. 解析期间，凭证日期取月末 ──────────────────────────────
        YearMonth ym;
        try {
            ym = YearMonth.parse(req.getPeriod());
        } catch (Exception e) {
            return JsonResult.failure("period 格式错误（应为 YYYY-MM）：" + req.getPeriod());
        }
        LocalDate voucherDate = ym.atEndOfMonth();

        // ── 3. 检查账套 ───────────────────────────────────────────────
        AccountSets accountSets = bqf.selectFrom(qAccountSets)
                .where(qAccountSets.id.eq(req.getAccountSetId()))
                .fetchFirst();
        if (accountSets == null) return JsonResult.failure("账套不存在：" + req.getAccountSetId());

        // ── 4. 幂等：发票已有凭证则直接返回 ──────────────────────────
        if (StrUtil.isNotBlank(req.getInvoiceNumber())) {
            Invoice existing = bqf.selectFrom(qInvoice)
                    .where(qInvoice.accountSetsId.eq(req.getAccountSetId())
                            .and(qInvoice.invoiceNumber.eq(req.getInvoiceNumber())))
                    .fetchFirst();
            if (existing != null && existing.getVoucherId() != null) {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("voucherId", existing.getVoucherId());
                data.put("status", "POSTED");
                return JsonResult.successful(data);
            }
        }

        // ── 5. 查末级启用科目 ─────────────────────────────────────────
        Long receivableId = findLeafSubject(req.getAccountSetId(), "1122");
        Long revenueId    = findLeafSubject(req.getAccountSetId(), "6001");
        Long vatId        = taxAmount.compareTo(BigDecimal.ZERO) > 0
                ? findLeafSubject(req.getAccountSetId(), "2221") : null;

        boolean needVat = taxAmount.compareTo(BigDecimal.ZERO) > 0;
        boolean canPost = receivableId != null && revenueId != null && (!needVat || vatId != null);

        if (!canPost) {
            List<String> missing = new ArrayList<>();
            if (receivableId == null) missing.add("应收账款(1122)");
            if (revenueId == null)    missing.add("主营业务收入(6001)");
            if (needVat && vatId == null) missing.add("应交税费-销项税额(2221)");
            return JsonResult.failure("账套缺少以下末级科目，无法自动生成凭证：" + String.join("、", missing));
        }

        // ── 6. 构造凭证 ───────────────────────────────────────────────
        String invoiceRef = StrUtil.isNotBlank(req.getInvoiceNumber()) ? " #" + req.getInvoiceNumber() : "";
        VoucherDto dto = new VoucherDto();
        dto.setAccountSetsId(req.getAccountSetId());
        dto.setWord("记");
        dto.setVoucherDate(voucherDate);
        dto.setRemark("开票凭证" + invoiceRef);
        dto.setReceiptNum(1);
        dto.setCode(voucherService.loadCode(req.getAccountSetId(), "记", voucherDate));

        List<VoucherDetailsDto> details = new ArrayList<>();
        details.add(detail(req.getAccountSetId(), receivableId, "开票应收", total, BigDecimal.ZERO));
        details.add(detail(req.getAccountSetId(), revenueId,    "开票收入", BigDecimal.ZERO, amount));
        if (vatId != null) {
            details.add(detail(req.getAccountSetId(), vatId, "开票销项税", BigDecimal.ZERO, taxAmount));
        }
        dto.setDetails(details);

        // ── 7. 保存 ───────────────────────────────────────────────────
        VoucherDto saved;
        try {
            saved = voucherService.save(dto, accountSets, true);
        } catch (Exception e) {
            log.error("openapi invoice/voucher 凭证保存失败 accountSetId={}", req.getAccountSetId(), e);
            return JsonResult.failure("凭证保存失败：" + e.getMessage());
        }

        // ── 8. 回写发票 voucherId ─────────────────────────────────────
        if (StrUtil.isNotBlank(req.getInvoiceNumber())) {
            jqf.update(qInvoice)
                    .set(qInvoice.voucherId, saved.getId())
                    .where(qInvoice.accountSetsId.eq(req.getAccountSetId())
                            .and(qInvoice.invoiceNumber.eq(req.getInvoiceNumber())))
                    .execute();
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("voucherId", saved.getId());
        data.put("status", "POSTED");
        return JsonResult.successful(data);
    }

    /** 按编码前缀找末级启用科目，编码更长的优先（更具体）。 */
    private Long findLeafSubject(Long accountSetsId, String prefix) {
        Subject s = bqf.selectFrom(qSubject)
                .where(qSubject.accountSetsId.eq(accountSetsId)
                        .and(qSubject.code.startsWith(prefix))
                        .and(qSubject.leaf.isTrue())
                        .and(qSubject.status.isTrue()))
                .orderBy(qSubject.code.asc())
                .fetchFirst();
        return s != null ? s.getId() : null;
    }

    private static VoucherDetailsDto detail(Long accountSetsId, Long subjectId,
                                            String summary, BigDecimal debit, BigDecimal credit) {
        VoucherDetailsDto d = new VoucherDetailsDto();
        d.setAccountSetsId(accountSetsId);
        d.setSubjectId(subjectId);
        d.setSummary(summary);
        d.setDebitAmount(debit);
        d.setCreditAmount(credit);
        d.setAuxiliaryDetailId(0L);
        d.setCurrencyId(0L);
        d.setCashFlow(false);
        return d;
    }

    @Data
    public static class InvoiceVoucherReq {
        private String invoiceNumber;   // 发票号码
        private String invoiceCode;     // 发票代码（纸质票有）
        private Long accountSetId;      // 账套 ID
        private String period;          // "2025-01"
        private BigDecimal amount;      // 不含税金额
        private BigDecimal taxAmount;   // 税额
        private BigDecimal taxRate;     // 税率（记录备用，凭证生成不需要）
    }
}
