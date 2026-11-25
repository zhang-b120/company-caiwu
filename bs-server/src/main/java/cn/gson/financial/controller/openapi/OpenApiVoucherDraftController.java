package cn.gson.financial.controller.openapi;

import cn.dev33.satoken.annotation.SaIgnore;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.model.entity.AccountSets;
import cn.gson.financial.kernel.model.entity.QAccountSets;
import cn.gson.financial.kernel.model.entity.QSubject;
import cn.gson.financial.kernel.model.hibernate.BlazeJPAQueryFactory;
import cn.hutool.core.util.StrUtil;
import com.querydsl.core.Tuple;
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
 * FN-FIX-08：批量推 VoucherDraft 接口（配合 XW-FIX-01 push_drafts 链路）
 * <p>
 * <b>动机</b>：xiaowei skill 端生成的 VoucherDraft 用的是 subjectCode（"1001"/"1122"），
 * 而 FN 端现有 POST /openapi/v1/voucher/batch 要求 subjectId（雪花 long）。
 * 让 XW 在客户端做 code→id 映射既要维护一份本地 subject 缓存又容易在科目变动时漂移。
 * <p>
 * 本接口 POST /openapi/v1/voucher/draft-batch 接受 subjectCode，**服务端一次性 batch
 * 解析 code→id**（仅末级科目，QSubject.leaf=true），然后委托给现有 voucher/batch 主流程。
 * <p>
 * 解析失败的 draft 行（subjectCode 为空 / 不存在 / 非末级）作为 errorType=SUBJECT_NOT_FOUND
 * 直接返回，不进入 save。其他行正常处理（独立事务，部分失败继续）。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/openapi/v1/voucher")
@SaIgnore
public class OpenApiVoucherDraftController {

    /** 复用现有 voucher/batch 主控制器的事务级写入（saveSingleInNewTransaction） */
    private final OpenApiVoucherController voucherController;

    @Resource
    @Lazy
    private BlazeJPAQueryFactory bqf;

    private static final QAccountSets qAccountSets = QAccountSets.accountSets;
    private static final QSubject qSubject = QSubject.subject;

    // ═══════════════════════════════════════════════════════════════
    // POST /openapi/v1/voucher/draft-batch
    // ═══════════════════════════════════════════════════════════════

    @PostMapping("/draft-batch")
    public JsonResult draftBatch(@RequestBody VoucherDraftBatchReq req) {
        if (req.getAccountSetsId() == null) {
            return JsonResult.failure("accountSetsId 不能为空");
        }
        if (req.getVouchers() == null || req.getVouchers().isEmpty()) {
            return JsonResult.failure("vouchers 不能为空");
        }

        AccountSets accountSets = bqf.selectFrom(qAccountSets)
                .where(qAccountSets.id.eq(req.getAccountSetsId()))
                .fetchFirst();
        if (accountSets == null) {
            return JsonResult.failure("账套不存在：" + req.getAccountSetsId());
        }

        // 1) 收集所有 subjectCode，一次性 batch 查（避免 N+1）
        Set<String> codes = new HashSet<>();
        for (VoucherDraftReq v : req.getVouchers()) {
            if (v.getDetails() != null) {
                for (VoucherDraftDetailReq d : v.getDetails()) {
                    if (StrUtil.isNotBlank(d.getSubjectCode())) {
                        codes.add(d.getSubjectCode().trim());
                    }
                }
            }
        }
        Map<String, Long> codeToId = new HashMap<>();
        Set<String> nonLeaf = new HashSet<>();
        if (!codes.isEmpty()) {
            List<Tuple> rows = bqf.selectFrom(qSubject)
                    .select(qSubject.code, qSubject.id, qSubject.leaf)
                    .where(qSubject.accountSetsId.eq(req.getAccountSetsId())
                            .and(qSubject.code.in(codes)))
                    .fetch();
            for (Tuple r : rows) {
                String code = r.get(qSubject.code);
                Boolean leaf = r.get(qSubject.leaf);
                if (Boolean.TRUE.equals(leaf)) {
                    codeToId.put(code, r.get(qSubject.id));
                } else {
                    nonLeaf.add(code);
                }
            }
        }

        // 2) 逐条转换 + 写入（独立事务，部分失败不影响其他）
        List<Map<String, Object>> results = new ArrayList<>(req.getVouchers().size());
        int succeeded = 0;
        int failed = 0;

        for (int i = 0; i < req.getVouchers().size(); i++) {
            VoucherDraftReq draft = req.getVouchers().get(i);
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("index", i);

            // 2a) 解析 subjectCode → subjectId
            String resolveErr = null;
            String firstBadCode = null;
            List<OpenApiVoucherController.VoucherDetailReq> normalized = new ArrayList<>();
            if (draft.getDetails() == null || draft.getDetails().isEmpty()) {
                resolveErr = "details 不能为空";
            } else {
                for (VoucherDraftDetailReq d : draft.getDetails()) {
                    if (StrUtil.isBlank(d.getSubjectCode())) {
                        resolveErr = "subjectCode 不能为空";
                        break;
                    }
                    String code = d.getSubjectCode().trim();
                    Long subjectId = codeToId.get(code);
                    if (subjectId == null) {
                        if (nonLeaf.contains(code)) {
                            resolveErr = "subjectCode 非末级科目";
                        } else {
                            resolveErr = "subjectCode 不存在";
                        }
                        firstBadCode = code;
                        break;
                    }
                    OpenApiVoucherController.VoucherDetailReq vd =
                            new OpenApiVoucherController.VoucherDetailReq();
                    vd.setSubjectId(subjectId);
                    vd.setSummary(d.getSummary());
                    vd.setDebitAmount(d.getDebitAmount());
                    vd.setCreditAmount(d.getCreditAmount());
                    vd.setAuxiliaryDetailId(d.getAuxiliaryDetailId());
                    vd.setCurrencyId(d.getCurrencyId());
                    vd.setNum(d.getNum());
                    vd.setPrice(d.getPrice());
                    vd.setCashFlow(d.getCashFlow());
                    vd.setCarryForward(d.getCarryForward());
                    normalized.add(vd);
                }
            }

            if (resolveErr != null) {
                r.put("status", "error");
                r.put("errorType", resolveErr.contains("非末级") ? "NON_LEAF_SUBJECT" : "SUBJECT_NOT_FOUND");
                r.put("message", firstBadCode != null
                        ? resolveErr + "：" + firstBadCode
                        : resolveErr);
                failed++;
                results.add(r);
                continue;
            }

            // 2b) 构造 VoucherCreateReq 调主 controller 的 single-transaction save
            OpenApiVoucherController.VoucherCreateReq vc = new OpenApiVoucherController.VoucherCreateReq();
            vc.setAccountSetsId(req.getAccountSetsId());
            vc.setWord(draft.getWord());
            vc.setVoucherDate(draft.getVoucherDate());
            vc.setRemark(draft.getRemark());
            vc.setReceiptNum(draft.getReceiptNum());
            vc.setCarryForward(draft.getCarryForward());
            vc.setDetails(normalized);
            vc.setSourceInvoiceIds(draft.getSourceInvoiceIds());

            try {
                var saved = voucherController.saveSingleInNewTransaction(vc, accountSets);
                r.put("status", "ok");
                r.put("voucherId", saved.getId());
                succeeded++;
            } catch (Exception e) {
                r.put("status", "error");
                r.put("errorType", "INTERNAL_ERROR");
                r.put("message", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
                failed++;
                log.warn("openapi draft-batch 单条失败 index={}: {}", i, e.getMessage());
            }
            results.add(r);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("totalSubmitted", req.getVouchers().size());
        data.put("succeeded", succeeded);
        data.put("failed", failed);
        data.put("results", results);
        return JsonResult.successful(data);
    }

    // ═══════════════════════════════════════════════════════════════
    // 请求 DTO — 与 VoucherBatchReq 几乎一致，但 details 用 subjectCode 不是 subjectId
    // ═══════════════════════════════════════════════════════════════

    @Data
    public static class VoucherDraftBatchReq {
        private Long accountSetsId;
        private List<VoucherDraftReq> vouchers;
    }

    @Data
    public static class VoucherDraftReq {
        private String word;
        private LocalDate voucherDate;
        private String remark;
        private Integer receiptNum;
        private Boolean carryForward;
        private List<VoucherDraftDetailReq> details;
        /** 可选：要回写 voucher_id 的发票 id 列表（与 voucher/batch 同语义） */
        private List<Long> sourceInvoiceIds;
    }

    @Data
    public static class VoucherDraftDetailReq {
        /** 关键差异：用 subjectCode（"1001"）而不是 subjectId */
        private String subjectCode;
        private String summary;
        private BigDecimal debitAmount;
        private BigDecimal creditAmount;
        private Long auxiliaryDetailId;
        private Long currencyId;
        private BigDecimal num;
        private BigDecimal price;
        private Boolean cashFlow;
        private Boolean carryForward;
    }
}
