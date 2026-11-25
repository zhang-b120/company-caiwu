package cn.gson.financial.controller.openapi;

import cn.dev33.satoken.annotation.SaIgnore;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.model.entity.InvoiceCollectRequest;
import cn.gson.financial.kernel.model.repo.InvoiceCollectRequestRepository;
import cn.gson.financial.security.OpenApiSecurityGuards;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import jakarta.servlet.http.HttpServletResponse;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * FN-U（140）开票收集请求 OpenAPI —— collect <b>单表统一开票任务</b>。
 * <p>
 * 状态 3 态：{@code INCOMPLETE}（填写不完整）→ {@code PENDING_ISSUE}（待开票）→ {@code ISSUED}（开票完成）。
 * 信息填齐直接进"待开票"（<b>不再搭桥建 invoice_task</b>），BT 直接读本表领任务、开完回写发票号 + 文件路径。
 * <p>
 * <h3>红线护栏（不算状态，杜绝重复真开）</h3>
 * <ul>
 *   <li><b>领取锁</b>（{@code lock_owner}/{@code locked_at}）：BT 开票前先原子 {@code claim}，
 *       锁住的别人领不到；开完/超时自动释放。</li>
 *   <li><b>失败计数</b>（{@code fail_count}/{@code last_error}）：失败留 {@code PENDING_ISSUE}，
 *       累计到上限不再被领取（parked 转人工）。</li>
 * </ul>
 * <p>
 * 端点：
 * <ul>
 *   <li>{@code POST /invoice-collect}（存明细/带抬头）+ {@code POST /{orderNo}/header}（补抬头）→ 齐则 PENDING_ISSUE</li>
 *   <li>{@code GET /{orderNo}} 取明细/进度（含 status/发票号/文件）</li>
 *   <li>{@code GET /tasks} BT 拉可领的待开票任务</li>
 *   <li>{@code GET /failed} 失败任务列表（ISSUE_FAILED 终态，FN-FAIL-T1a/175）</li>
 *   <li>{@code POST /{orderNo}/claim}（X-Internal-Token）原子领取</li>
 *   <li>{@code POST /{orderNo}/result}（X-Internal-Token）回写结果</li>
 *   <li>{@code POST /{orderNo}/reopen}（X-Internal-Token）重开失败任务 ISSUE_FAILED→PENDING_ISSUE（FN-FAIL-T1b/175）</li>
 * </ul>
 * {@code @SaIgnore} 无 sa-token 鉴权；claim/result 用 {@code X-Internal-Token} 内部保护。
 */
@Slf4j
@RestController
@RequestMapping("/openapi/v1/invoice-collect")
@SaIgnore
public class OpenApiInvoiceCollectController {

    private final InvoiceCollectRequestRepository repo;

    public OpenApiInvoiceCollectController(InvoiceCollectRequestRepository repo) {
        this.repo = repo;
    }

    /**
     * FN-SEC-T1（167）安全门面：按账套限流 + collect 审计。
     * {@code required=false} —— 纯 JUnit 单测无 Spring 上下文时为 null，控制器走原路径不受影响。
     */
    @Autowired(required = false)
    private OpenApiSecurityGuards securityGuards;

    /**
     * 268：存证截图 URL 透出——列表行只读判 {@code <orderNo>/evidence.png} 是否存在。
     * {@code required=false} —— 纯 JUnit 单测无 Spring 上下文时为 null，buildListRow 守 null 不透 evidenceUrl。
     */
    @Autowired(required = false)
    private cn.gson.financial.service.InvoiceFileStorageService storageService;

    /** FN-U-T3：失败上限；到此值不再被 tasks 列表选中（parked 转人工）。 */
    @Value("${financial.scan-direct.max-fail:5}")
    int maxFail;
    /** FN-U-T3：领取锁超时（分钟）；超过即可被再次 claim（防 BT 崩了卡死）。 */
    @Value("${financial.scan-direct.lock-timeout-min:10}")
    int lockTimeoutMin;
    /** FN-U-T3/T4：claim/result 的内部 token；配置非空时必须匹配，否则 403。 */
    @Value("${financial.scan-direct.internal-token:}")
    String internalToken;

    /** PM 规范回执号：R + yyyyMMddHHmmss + 6 位随机 */
    private static final DateTimeFormatter RECEIPT_TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";

    // ════════════════════════════════════════════════════════════════
    // 1) POST /invoice-collect ——
    //    带 header → 一次性信息齐 → PENDING_ISSUE
    //    不带 header → upsert by orderNo + INCOMPLETE（两阶段·存明细）
    // ════════════════════════════════════════════════════════════════
    @PostMapping
    @Transactional
    public JsonResult collect(@RequestBody CollectReq req) {
        String err = validateBase(req);
        if (err != null) return JsonResult.failure(err).setData(Map.of("errorType", "VALIDATION"));

        // FN-SEC-T1（167）按 accountSetsId 30/min（拦截器已挡 IP 10/min；账套维度需 body，故放控制器）。
        if (securityGuards != null && req.getAccountSetsId() != null
                && !securityGuards.allowCollectByAccount(req.getAccountSetsId())) {
            securityGuards.auditCollect(req.getAccountSetsId(), req.getOrderNo(), "RATE_LIMITED");
            return securityGuards.tooFrequent();
        }

        JsonResult result = doCollect(req);

        // FN-SEC-T1（167）SECURITY_AUDIT 提交审计：IP + accountSetsId + orderNo + 结果。
        if (securityGuards != null) {
            securityGuards.auditCollect(req.getAccountSetsId(), req.getOrderNo(),
                    result.isSuccess() ? "OK" : "REJECTED");
        }
        return result;
    }

    /** collect 业务主体（被 {@link #collect} 包裹限流/审计后调用）。 */
    private JsonResult doCollect(CollectReq req) {
        boolean hasHeader = req.getHeader() != null && hasAnyHeaderField(req.getHeader());
        if (hasHeader) {
            // 一次性路径：header 必须齐 4 项 + 扫码直开必须带 accountSetsId
            String herr = validateHeaderComplete(req.getHeader(), req.getInvoiceType());  // 255-B：专票税号必填
            if (herr != null) return JsonResult.failure(herr).setData(Map.of("errorType", "VALIDATION"));
            if (req.getAccountSetsId() == null) {
                return JsonResult.failure("accountSetsId 必填（扫码直开需带商家账套）").setData(
                        Map.of("errorType", "VALIDATION"));
            }
            return upsertWithHeader(req);
        }
        // 两阶段·存明细：upsert，status=INCOMPLETE，receipt_no=null
        // accountSetsId 仍要求带，否则补抬头时无省份、又得回头让客户再扫一次
        if (req.getAccountSetsId() == null) {
            return JsonResult.failure("accountSetsId 必填（扫码直开需带商家账套）").setData(
                    Map.of("errorType", "VALIDATION"));
        }
        return upsertIncomplete(req);
    }

    private JsonResult upsertWithHeader(CollectReq req) {
        String orderNo = req.getOrderNo().trim();
        Optional<InvoiceCollectRequest> existing = repo.findByOrderNo(orderNo);
        InvoiceCollectRequest e = existing.orElseGet(InvoiceCollectRequest::new);
        boolean isUpdate = existing.isPresent();
        // FN2-GUARD-T1（161）已 ISSUED 硬挡：同抬头无害重试仍幂等返已存；想改抬头/重发起 → ALREADY_ISSUED、库不变
        if (isUpdate && InvoiceCollectRequest.STATUS_ISSUED.equals(e.getStatus())) {
            if (sameHeader(e, req.getHeader())) {
                log.info("[FN2-GUARD-T1] orderNo={} 已 ISSUED，同抬头幂等返原回执 receiptNo={}", orderNo, e.getReceiptNo());
                return JsonResult.successful(buildDto(e, true, "header")).setMsg("已开票，返回原回执");
            }
            log.warn("[FN2-GUARD-T1] orderNo={} 已 ISSUED，拒绝改抬头/重复申请", orderNo);
            return alreadyIssued(e);
        }
        // 已 PENDING_ISSUE 的同 orderNo → 幂等返已存，不重置
        if (isUpdate && InvoiceCollectRequest.STATUS_PENDING_ISSUE.equals(e.getStatus())) {
            log.info("[FN-U-T2] orderNo={} 已 PENDING_ISSUE，幂等返原回执 receiptNo={}", orderNo, e.getReceiptNo());
            return JsonResult.successful(buildDto(e, true, "header")).setMsg("已存在，返回原回执");
        }
        // 新建 or INCOMPLETE → PENDING_ISSUE：明细 + 抬头 + 账套 + 金额 + 回执号
        e.setOrderNo(orderNo);
        e.setItemsJson(JSON.toJSONString(req.getItems()));
        applyHeader(e, req.getHeader());
        e.setAccountSetsId(req.getAccountSetsId());
        e.setTaxInclusion(normalizeTaxInclusion(req.getTaxInclusion()));  // FN-TAX-T1：null/非法→'2' 含税
        // FN-INV-T1（173）票种区 4 字段归一落库
        e.setInvoiceType(normalizeInvoiceType(req.getInvoiceType()));
        e.setInvoiceCarrier(normalizeInvoiceCarrier(req.getInvoiceCarrier()));
        e.setSpecialBusiness(normalizeSpecialBusiness(req.getSpecialBusiness()));
        e.setDifferentialTax(normalizeDifferentialTax(req.getDifferentialTax()));
        promoteToPendingIssue(e);
        if (e.getReceiptNo() == null) e.setReceiptNo(generateReceiptNo());
        if (e.getSource() == null) e.setSource(req.getSource() == null ? "scan-h5" : req.getSource().trim());
        try {
            e = repo.save(e);
        } catch (DataIntegrityViolationException dup) {
            log.warn("[FN-U-T2] orderNo={} 并发插入冲突，回退查已存", orderNo);
            InvoiceCollectRequest other = repo.findByOrderNo(orderNo)
                    .orElseThrow(() -> new IllegalStateException("DataIntegrityViolation 但 findByOrderNo 也空"));
            return JsonResult.successful(buildDto(other, true, "header")).setMsg("已存在，返回原回执");
        }
        log.info("[FN-U-T2] {} → PENDING_ISSUE：receiptNo={}, orderNo={}, accountSetsId={}",
                isUpdate ? "INCOMPLETE→PENDING_ISSUE 补抬头" : "新增", e.getReceiptNo(), e.getOrderNo(),
                e.getAccountSetsId());
        return JsonResult.successful(buildDto(e, false, "header"));
    }

    private JsonResult upsertIncomplete(CollectReq req) {
        String orderNo = req.getOrderNo().trim();
        Optional<InvoiceCollectRequest> existing = repo.findByOrderNo(orderNo);
        InvoiceCollectRequest e = existing.orElseGet(InvoiceCollectRequest::new);
        boolean isUpdate = existing.isPresent();
        // FN2-GUARD-T1（161）已 ISSUED 硬挡：仅存明细重提（绕 UI 直连）→ ALREADY_ISSUED、库不变
        if (isUpdate && InvoiceCollectRequest.STATUS_ISSUED.equals(e.getStatus())) {
            log.warn("[FN2-GUARD-T1] orderNo={} 已 ISSUED，拒绝仅存明细覆盖", orderNo);
            return alreadyIssued(e);
        }
        // 已 PENDING_ISSUE 的不允许被存明细 step 覆盖（防止抹掉已发的回执）
        if (isUpdate && InvoiceCollectRequest.STATUS_PENDING_ISSUE.equals(e.getStatus())) {
            log.warn("[FN-U-T2] orderNo={} 已 PENDING_ISSUE，不允许仅存明细覆盖", orderNo);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("errorType", "ALREADY_COLLECTED");
            data.put("orderNo", orderNo);
            data.put("status", e.getStatus());
            data.put("receiptNo", e.getReceiptNo());
            return JsonResult.failure("订单 " + orderNo + " 已 " + e.getStatus() + " 不可仅存明细覆盖").setData(data);
        }
        // 新建 or INCOMPLETE 覆盖：只覆盖明细 + 账套 + source
        e.setOrderNo(orderNo);
        e.setItemsJson(JSON.toJSONString(req.getItems()));
        e.setAccountSetsId(req.getAccountSetsId());
        e.setTaxInclusion(normalizeTaxInclusion(req.getTaxInclusion()));  // FN-TAX-T1：随明细带入；null/非法→'2'
        // FN-INV-T1（173）票种区 4 字段归一落库（两阶段路径：明细阶段即存，补抬头后即可被 BT 领取）
        e.setInvoiceType(normalizeInvoiceType(req.getInvoiceType()));
        e.setInvoiceCarrier(normalizeInvoiceCarrier(req.getInvoiceCarrier()));
        e.setSpecialBusiness(normalizeSpecialBusiness(req.getSpecialBusiness()));
        e.setDifferentialTax(normalizeDifferentialTax(req.getDifferentialTax()));
        e.setStatus(InvoiceCollectRequest.STATUS_INCOMPLETE);
        if (req.getSource() != null) e.setSource(req.getSource().trim());
        else if (e.getSource() == null) e.setSource("scan-h5");
        try {
            e = repo.save(e);
        } catch (DataIntegrityViolationException dup) {
            log.warn("[FN-U-T2] orderNo={} 并发插入冲突，回退查已存", orderNo);
            InvoiceCollectRequest other = repo.findByOrderNo(orderNo)
                    .orElseThrow(() -> new IllegalStateException("DataIntegrityViolation 但 findByOrderNo 也空"));
            return JsonResult.successful(buildDto(other, true, "pending"));
        }
        log.info("[FN-U-T2] {} INCOMPLETE：orderNo={}", isUpdate ? "覆盖明细" : "新增", e.getOrderNo());
        return JsonResult.successful(buildDto(e, isUpdate, "pending"));
    }

    // ════════════════════════════════════════════════════════════════
    // 2) GET /invoice-collect/{orderNo} —— 取明细/进度（扫码页 + 收银端 142 用）
    // ════════════════════════════════════════════════════════════════
    @GetMapping("/{orderNo}")
    public JsonResult getByOrderNo(@PathVariable String orderNo) {
        if (orderNo == null || orderNo.isBlank()) {
            return JsonResult.failure("orderNo 必填").setData(Map.of("errorType", "VALIDATION"));
        }
        Optional<InvoiceCollectRequest> opt = repo.findByOrderNo(orderNo.trim());
        if (opt.isEmpty()) {
            return JsonResult.failure("订单 " + orderNo + " 未存明细").setData(Map.of("errorType", "NOT_FOUND"));
        }
        InvoiceCollectRequest e = opt.get();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("orderNo", e.getOrderNo());
        data.put("status", e.getStatus());
        data.put("items", JSON.parseArray(e.getItemsJson()));
        data.put("source", e.getSource());
        BigDecimal[] sums = computeAmounts(e.getItemsJson(), e.getTaxInclusion());
        data.put("requestAmount", sums[0]);
        data.put("requestTaxAmount", sums[1]);
        data.put("createdAt", e.getCreatedAt());
        data.put("receiptNo", e.getReceiptNo());     // INCOMPLETE 时为 null
        // FN-U-T4：收银端（142）查进度展示发票号 + 文件
        data.put("invoiceCode", e.getInvoiceCode());
        data.put("invoiceNumber", e.getInvoiceNumber());
        data.put("invoiceFiles", parseFiles(e.getInvoiceFiles()));
        data.put("issuedAt", e.getIssuedAt());
        // FN-QTA-T3（163）额度透出（账套最新额度=该账套最近 issued_at 的 ISSUED 行 quota_after_issue）；
        // issuedBrief 仅单条详情回原样快照（解析回 JSON 对象，消费端拿结构化中文键名），列表行不背 LONGTEXT。
        data.put("quotaAfterIssue", e.getQuotaAfterIssue());
        data.put("issuedBrief", parseBriefForOutput(e.getIssuedBriefJson()));
        // header 已填的也透传（PENDING_ISSUE / ISSUED 时）
        if (isPendingOrIssued(e.getStatus())) {
            Map<String, Object> h = new LinkedHashMap<>();
            h.put("companyName", e.getBuyerCompany());
            h.put("taxNo", e.getBuyerTaxNo());
            h.put("phone", e.getBuyerPhone());
            h.put("email", e.getBuyerEmail());
            h.put("address", e.getBuyerAddress());
            h.put("bank", e.getBuyerBank());
            h.put("bankAccount", e.getBuyerBankAccount());
            data.put("header", h);
        }
        return JsonResult.successful(data);
    }

    // ════════════════════════════════════════════════════════════════
    // 3) POST /invoice-collect/{orderNo}/header —— 补抬头 → PENDING_ISSUE
    // ════════════════════════════════════════════════════════════════
    @PostMapping("/{orderNo}/header")
    @Transactional
    public JsonResult addHeader(@PathVariable String orderNo, @RequestBody AddHeaderReq req) {
        if (orderNo == null || orderNo.isBlank()) {
            return JsonResult.failure("orderNo 必填").setData(Map.of("errorType", "VALIDATION"));
        }
        if (req == null || req.getHeader() == null) {
            return JsonResult.failure("header 必填").setData(Map.of("errorType", "VALIDATION"));
        }

        Optional<InvoiceCollectRequest> opt = repo.findByOrderNo(orderNo.trim());
        if (opt.isEmpty()) {
            return JsonResult.failure("订单 " + orderNo + " 未存明细，无法补抬头").setData(
                    Map.of("errorType", "NOT_FOUND"));
        }
        InvoiceCollectRequest e = opt.get();
        // 255-B：专票税号必填——票种在任务 e 上（存明细阶段已落），故先取 e 再校验
        String herr = validateHeaderComplete(req.getHeader(), e.getInvoiceType());
        if (herr != null) return JsonResult.failure(herr).setData(Map.of("errorType", "VALIDATION"));
        // FN2-GUARD-T1（161）已 ISSUED 硬挡：同抬头无害重试仍幂等返已存；想改抬头/重发起 → ALREADY_ISSUED、库不变
        if (InvoiceCollectRequest.STATUS_ISSUED.equals(e.getStatus())) {
            if (sameHeader(e, req.getHeader())) {
                log.info("[FN2-GUARD-T1] orderNo={} 已 ISSUED，同抬头幂等返原回执 receiptNo={}", orderNo, e.getReceiptNo());
                return JsonResult.successful(buildDto(e, true, "header")).setMsg("已开票，返回原回执");
            }
            log.warn("[FN2-GUARD-T1] orderNo={} 已 ISSUED，拒绝改抬头/重复申请", orderNo);
            return alreadyIssued(e);
        }
        // 已 PENDING_ISSUE → 幂等返已存
        if (InvoiceCollectRequest.STATUS_PENDING_ISSUE.equals(e.getStatus())) {
            log.info("[FN-U-T2] orderNo={} 已 PENDING_ISSUE，幂等返原回执 receiptNo={}", orderNo, e.getReceiptNo());
            return JsonResult.successful(buildDto(e, true, "header")).setMsg("已存在，返回原回执");
        }
        // INCOMPLETE → PENDING_ISSUE：填抬头 + 金额 + 生成 receiptNo
        applyHeader(e, req.getHeader());
        promoteToPendingIssue(e);
        e.setReceiptNo(generateReceiptNo());
        e = repo.save(e);
        log.info("[FN-U-T2] 补抬头 → PENDING_ISSUE：orderNo={}, receiptNo={}, accountSetsId={}",
                e.getOrderNo(), e.getReceiptNo(), e.getAccountSetsId());
        return JsonResult.successful(buildDto(e, false, "header"));
    }

    // ════════════════════════════════════════════════════════════════
    // 3b) GET /invoice-collect/by-account/{accountSetsId} —— 按账套列列表（FN-LIST-T1）
    // ⚠️ 安全：当前 @SaIgnore 裸放行，雪花 id 难猜可接受（仅收银端用）；
    //    若对外暴露需加 X-Internal-Token 或登录校验，管理者定是否收紧。
    // ════════════════════════════════════════════════════════════════
    @GetMapping("/by-account/{accountSetsId}")
    public JsonResult byAccount(
            @PathVariable Long accountSetsId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        if (page < 1) page = 1;
        if (size < 1) size = 20;
        if (size > 100) size = 100;
        PageRequest pr = PageRequest.of(page - 1, size, Sort.by("createdAt").descending());
        Page<InvoiceCollectRequest> result;
        if (status != null && !status.isBlank()) {
            result = repo.findByAccountSetsIdAndStatusOrderByCreatedAtDesc(accountSetsId, status.trim(), pr);
        } else {
            result = repo.findByAccountSetsIdOrderByCreatedAtDesc(accountSetsId, pr);
        }
        List<Map<String, Object>> rows = new ArrayList<>((int) Math.min(result.getNumberOfElements(), size));
        for (InvoiceCollectRequest e : result.getContent()) {
            rows.add(buildListRow(e));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("rows", rows);
        data.put("total", result.getTotalElements());
        data.put("page", page);
        data.put("size", size);
        return JsonResult.successful(data);
    }

    // ════════════════════════════════════════════════════════════════
    // 3c) POST /invoice-collect/status-by-orders —— 按一批订单号批量查状态（FN-SYNC-T1，159）
    //    给 invoice-print 收银端订单列表 overlay 用：正好对齐"当前页的 orderNo"，与账套分页解耦。
    //    ⚠️ 安全（159 待确认 4）：加 X-Internal-Token 校验（与 claim/result 同款）；
    //       按一批 orderNo 可探状态，故缺失或不匹配 → HTTP 403。
    // ════════════════════════════════════════════════════════════════
    @PostMapping("/status-by-orders")
    public JsonResult statusByOrders(
            @RequestHeader(value = INTERNAL_TOKEN_HEADER, required = false) String token,
            @RequestBody(required = false) StatusByOrdersReq req,
            HttpServletResponse response) {
        if (!internalTokenOk(token)) return forbidden(response);
        List<String> orderNos = req == null ? null : req.getOrderNos();
        Map<String, Object> data = new LinkedHashMap<>();
        // 空数组 / null → 返 {rows:[]}，不报错、不打库
        if (orderNos == null || orderNos.isEmpty()) {
            data.put("rows", List.of());
            return JsonResult.successful(data);
        }
        // 去空白/去重，杜绝脏 orderNo 干扰 IN 查询
        List<String> cleaned = orderNos.stream()
                .filter(s -> s != null && !s.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
        if (cleaned.isEmpty()) {
            data.put("rows", List.of());
            return JsonResult.successful(data);
        }
        List<InvoiceCollectRequest> found = repo.findByOrderNoIn(cleaned);
        List<Map<String, Object>> rows = new ArrayList<>(found.size());
        for (InvoiceCollectRequest e : found) {
            rows.add(buildListRow(e));     // 复用现成行 DTO（含 orderNo/status/statusLabel/发票号/文件/issuedAt）
        }
        data.put("rows", rows);
        return JsonResult.successful(data);
    }

    // ════════════════════════════════════════════════════════════════
    // 4) GET /invoice-collect/tasks —— BT 拉可领的待开票任务（FN-U-T3）
    //    ?status=PENDING_ISSUE&size=200（status 仅作 API 兼容；只返可领的 PENDING_ISSUE）
    //    FN-Q（223）?accountSetsId=x 可选按销方账套过滤；不传 → 全局（行为不变）
    // ════════════════════════════════════════════════════════════════
    @GetMapping("/tasks")
    public JsonResult tasks(@RequestParam(required = false, defaultValue = "PENDING_ISSUE") String status,
                            @RequestParam(required = false, defaultValue = "0") Integer page,
                            @RequestParam(required = false, defaultValue = "200") Integer size,
                            @RequestParam(required = false) Long accountSetsId) {
        if (size == null || size < 1) size = 200;
        if (size > 500) size = 500;
        // FN-FIX-INVOICE-COLLECT-TASKS-PAGING：page 现在生效（之前固定 0 页 → 超单页第二页永远领不到 = 静默漏开）。
        // page 为 0-based（与本工单一致；注意同控制器 /by-account 是 1-based，BT 对 /tasks 需传 0-based，见完成情况）。
        if (page == null || page < 0) page = 0;
        LocalDateTime lockExpireBefore = LocalDateTime.now().minusMinutes(lockTimeoutMin);
        // FN-Q（223）accountSetsId 可选过滤 + 分页一起生效（合并两处改动；之前工作树误合并留下重复声明致编译失败）。
        List<InvoiceCollectRequest> rows = repo.findClaimableTasks(maxFail, lockExpireBefore, accountSetsId, PageRequest.of(page, size));
        long total = repo.countClaimableTasks(maxFail, lockExpireBefore);
        List<Map<String, Object>> taskRows = new ArrayList<>(rows.size());
        for (InvoiceCollectRequest e : rows) {
            taskRows.add(buildTaskData(e));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("rows", taskRows);
        data.put("total", (int) total);   // (int) 保持 number（避开 Long→String 全局序列化器）
        data.put("page", page);
        data.put("size", size);
        data.put("count", taskRows.size());   // 本页条数（保留兼容旧调用方）
        return JsonResult.successful(data);
    }

    // ════════════════════════════════════════════════════════════════
    // 4b) GET /invoice-collect/failed —— 失败任务列表（FN-FAIL-T1a，175）
    //    BT（176）失败队列展示用：只返 ISSUE_FAILED 终态，按 lastAttemptAt 倒序。
    //    行 DTO 复用 buildListRow（含 failCount/lastError 摘要/金额/orderNo/statusLabel）。
    //    ⚠️ 安全：@SaIgnore 裸放行；lastError 在 buildListRow 已收敛为友好摘要（不裸暴露栈/路径，见风险 3）。
    // ════════════════════════════════════════════════════════════════
    @GetMapping("/failed")
    public JsonResult failed(@RequestParam(required = false, defaultValue = "100") Integer size) {
        if (size == null || size < 1) size = 100;
        if (size > 500) size = 500;
        // 按 lastAttemptAt 倒序（最近失败的在前）；NULL 自然排末尾
        PageRequest pr = PageRequest.of(0, size, Sort.by("lastAttemptAt").descending());
        List<InvoiceCollectRequest> rows = repo.findByStatus(InvoiceCollectRequest.STATUS_ISSUE_FAILED, pr);
        List<Map<String, Object>> listRows = new ArrayList<>(rows.size());
        for (InvoiceCollectRequest e : rows) {
            listRows.add(buildListRow(e));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("rows", listRows);
        data.put("size", size);
        data.put("count", listRows.size());
        return JsonResult.successful(data);
    }

    // ════════════════════════════════════════════════════════════════
    // 4c) GET /invoice-collect/issued —— 已开发票列表（FN-ISSUED-T1，183）
    //    BT 板块3「已开发票」数据源：只返 status=ISSUED，按 issuedAt 倒序（最近开的在前；NULL 末尾）。
    //    入参 size(默认100,≤500) + 可选 accountSetsId + 可选 issuedFrom/issuedTo(yyyy-MM-dd，按 issued_at 闭区间)。
    //    无过滤 → 退化 findByStatus；带账套/日期 → findIssuedFiltered 动态拼。行 DTO 复用 buildListRow。
    //    @SaIgnore 裸放行——只读非敏感列表，与 /failed /tasks 同口径，不挂 X-Internal-Token。
    // ════════════════════════════════════════════════════════════════
    @GetMapping("/issued")
    public JsonResult issued(@RequestParam(required = false, defaultValue = "100") Integer size,
                             @RequestParam(required = false) Long accountSetsId,
                             @RequestParam(required = false) String issuedFrom,
                             @RequestParam(required = false) String issuedTo) {
        if (size == null || size < 1) size = 100;
        if (size > 500) size = 500;
        // 按 issuedAt 倒序（最近开的在前）；NULL 自然排末尾，走 idx_status_issued
        PageRequest pr = PageRequest.of(0, size, Sort.by("issuedAt").descending());

        LocalDateTime from = parseDateStart(issuedFrom);   // yyyy-MM-dd → 当天 00:00:00
        LocalDateTime to = parseDateEnd(issuedTo);          // yyyy-MM-dd → 当天 23:59:59.999999999（闭区间）

        List<InvoiceCollectRequest> rows;
        if (accountSetsId == null && from == null && to == null) {
            rows = repo.findByStatus(InvoiceCollectRequest.STATUS_ISSUED, pr);     // 无过滤退化
        } else {
            rows = repo.findIssuedFiltered(InvoiceCollectRequest.STATUS_ISSUED, accountSetsId, from, to, pr);
        }
        List<Map<String, Object>> listRows = new ArrayList<>(rows.size());
        for (InvoiceCollectRequest e : rows) {
            listRows.add(buildListRow(e));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("rows", listRows);
        data.put("size", size);
        data.put("count", listRows.size());
        return JsonResult.successful(data);
    }

    /** issuedFrom（yyyy-MM-dd）→ 当天起始时刻；空/非法 → null（该端不过滤）。 */
    private static LocalDateTime parseDateStart(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return LocalDate.parse(s.trim()).atStartOfDay();
        } catch (Exception e) {
            return null;
        }
    }

    /** issuedTo（yyyy-MM-dd）→ 当天结束时刻（闭区间）；空/非法 → null（该端不过滤）。 */
    private static LocalDateTime parseDateEnd(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return LocalDate.parse(s.trim()).atTime(LocalTime.MAX);
        } catch (Exception e) {
            return null;
        }
    }

    // ════════════════════════════════════════════════════════════════
    // 5) POST /invoice-collect/{orderNo}/claim —— 原子领取（FN-U-T3）
    //    防两端重复真开的关键；带 X-Internal-Token
    // ════════════════════════════════════════════════════════════════
    @PostMapping("/{orderNo}/claim")
    @Transactional
    public JsonResult claim(@PathVariable String orderNo,
                            @RequestHeader(value = INTERNAL_TOKEN_HEADER, required = false) String token,
                            @RequestBody(required = false) ClaimReq req,
                            HttpServletResponse response) {
        if (!internalTokenOk(token)) return forbidden(response);
        if (orderNo == null || orderNo.isBlank()) {
            return JsonResult.failure("orderNo 必填").setData(Map.of("errorType", "VALIDATION"));
        }
        String owner = (req != null && req.getOwner() != null && !req.getOwner().isBlank())
                ? req.getOwner().trim() : "bt";
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime lockExpireBefore = now.minusMinutes(lockTimeoutMin);
        int affected = repo.claimTask(orderNo.trim(), owner, now, lockExpireBefore);
        Map<String, Object> data = new LinkedHashMap<>();
        if (affected == 1) {
            InvoiceCollectRequest e = repo.findByOrderNo(orderNo.trim())
                    .orElseThrow(() -> new IllegalStateException("claim 成功但 findByOrderNo 空，orderNo=" + orderNo));
            log.info("[FN-U-T3] claim ok：orderNo={}, owner={}", orderNo, owner);
            data.put("claimed", true);
            data.put("task", buildTaskData(e));
        } else {
            log.info("[FN-U-T3] claim miss（别人已领/已不是待开票）：orderNo={}, owner={}", orderNo, owner);
            data.put("claimed", false);
            data.put("orderNo", orderNo);
        }
        return JsonResult.successful(data);
    }

    // ════════════════════════════════════════════════════════════════
    // 6) POST /invoice-collect/{orderNo}/result —— 回写结果（FN-U-T4）
    //    成功 → ISSUED + 发票号 + 文件 + 释放锁；失败 → fail_count++ + last_error + 释放锁
    //    带 X-Internal-Token
    // ════════════════════════════════════════════════════════════════
    @PostMapping("/{orderNo}/result")
    @Transactional
    public JsonResult result(@PathVariable String orderNo,
                             @RequestHeader(value = INTERNAL_TOKEN_HEADER, required = false) String token,
                             @RequestBody ResultReq req,
                             HttpServletResponse response) {
        if (!internalTokenOk(token)) return forbidden(response);
        if (orderNo == null || orderNo.isBlank()) {
            return JsonResult.failure("orderNo 必填").setData(Map.of("errorType", "VALIDATION"));
        }
        if (req == null || req.getSuccess() == null) {
            return JsonResult.failure("success 必填").setData(Map.of("errorType", "VALIDATION"));
        }
        Optional<InvoiceCollectRequest> opt = repo.findByOrderNo(orderNo.trim());
        if (opt.isEmpty()) {
            return JsonResult.failure("订单 " + orderNo + " 不存在").setData(Map.of("errorType", "NOT_FOUND"));
        }
        InvoiceCollectRequest e = opt.get();

        if (Boolean.TRUE.equals(req.getSuccess())) {
            // 幂等：已 ISSUED 再回写 → 返已存、不覆盖（防重复回写）
            if (InvoiceCollectRequest.STATUS_ISSUED.equals(e.getStatus())) {
                log.info("[FN-U-T4] orderNo={} 已 ISSUED，幂等不覆盖", orderNo);
                return JsonResult.successful(buildResultDto(e, true)).setMsg("已开票完成，幂等返已存");
            }
            e.setStatus(InvoiceCollectRequest.STATUS_ISSUED);
            e.setInvoiceCode(req.getInvoiceCode());
            e.setInvoiceNumber(req.getInvoiceNumber());
            e.setInvoiceFiles(req.getFiles() == null ? null : JSON.toJSONString(req.getFiles()));
            // FN-DLV-T1（182）电税官方下载链接（qrUrl）存库，作客户交付备用；可空，老 BT 不带 → NULL。
            if (req.getQrUrl() != null && !req.getQrUrl().isBlank()) {
                e.setQrUrl(truncate(req.getQrUrl(), 1024));
            }
            e.setIssuedAt(LocalDateTime.now());
            // FN-QTA-T2（163）额度 + 简要信息入库。
            // 红线：解析/序列化失败一律 warn + NULL，绝不让整个回写失败（回写失败会撞 162 "交付失败≠开票失败"）。
            // 缺省兼容：旧 BT 不带这两字段 → 全 NULL，照常成功。
            e.setQuotaAfterIssue(parseQuotaOrWarn(req.getQuotaAfterIssue(), e.getOrderNo()));
            e.setIssuedBriefJson(serializeBriefOrNull(req.getIssuedBrief(), e.getOrderNo()));
            releaseLock(e);
            e = repo.save(e);
            log.info("[FN-U-T4] 回写成功 → ISSUED：orderNo={}, invoiceNumber={}, files={}, quotaAfterIssue={}",
                    e.getOrderNo(), e.getInvoiceNumber(),
                    req.getFiles() == null ? 0 : req.getFiles().size(), e.getQuotaAfterIssue());
            return JsonResult.successful(buildResultDto(e, false));
        }

        // FN-1S 开票只跑一次：失败即置终态 ISSUE_FAILED，不再留 PENDING_ISSUE 等重试（杜绝多开）。
        // 已 ISSUED 的不覆盖（防把已开成功的票冲成失败）。
        if (InvoiceCollectRequest.STATUS_ISSUED.equals(e.getStatus())) {
            log.info("[FN-U-T4] orderNo={} 已 ISSUED，失败回写忽略（幂等不覆盖）", orderNo);
            return JsonResult.successful(buildResultDto(e, true)).setMsg("已开票完成，忽略失败回写");
        }
        e.setStatus(InvoiceCollectRequest.STATUS_ISSUE_FAILED);
        e.setFailCount((e.getFailCount() == null ? 0 : e.getFailCount()) + 1);   // 仅作审计/展示，不再驱动重试
        e.setLastError(truncate(req.getError(), 512));
        releaseLock(e);
        e = repo.save(e);
        log.warn("[FN-U-T4] 回写失败 → ISSUE_FAILED（终态，不重试）：orderNo={}, lastError={}",
                e.getOrderNo(), e.getLastError());
        Map<String, Object> data = buildResultDto(e, false);
        data.put("parked", true);   // 终态，永不再被领
        return JsonResult.successful(data);
    }

    // ════════════════════════════════════════════════════════════════
    // 7) POST /invoice-collect/{orderNo}/reopen —— 重开失败任务（FN-FAIL-T1b，175）
    //    ISSUE_FAILED → PENDING_ISSUE，failCount 归零 + 清锁；带 X-Internal-Token、@Transactional。
    //    🟥 红线：reopen 反转 FN-1S「失败即终态」防重复设计——
    //       已 ISSUED 硬挡（ALREADY_ISSUED、库不变）防把开成功的票冲回重开 = 双开；
    //       挡不住"票已在税局、financial 记 FAILED"的错位，真正防双开靠前端 176 人工二次确认（见风险 1）。
    // ════════════════════════════════════════════════════════════════
    @PostMapping("/{orderNo}/reopen")
    @Transactional
    public JsonResult reopen(@PathVariable String orderNo,
                             @RequestHeader(value = INTERNAL_TOKEN_HEADER, required = false) String token,
                             HttpServletResponse response) {
        if (!internalTokenOk(token)) return forbidden(response);
        if (orderNo == null || orderNo.isBlank()) {
            return JsonResult.failure("orderNo 必填").setData(Map.of("errorType", "VALIDATION"));
        }
        Optional<InvoiceCollectRequest> opt = repo.findByOrderNo(orderNo.trim());
        if (opt.isEmpty()) {
            return JsonResult.failure("订单 " + orderNo + " 不存在").setData(Map.of("errorType", "NOT_FOUND"));
        }
        InvoiceCollectRequest e = opt.get();
        // 已 ISSUED 硬挡：库不变，防把开成功的票冲回重开（双开）
        if (InvoiceCollectRequest.STATUS_ISSUED.equals(e.getStatus())) {
            log.warn("[FN-FAIL-T1b] orderNo={} 已 ISSUED，拒绝 reopen（防双开）", orderNo);
            return alreadyIssued(e);
        }
        // 非 ISSUE_FAILED（INCOMPLETE / PENDING_ISSUE）→ 幂等返当前态、不动库
        if (!InvoiceCollectRequest.STATUS_ISSUE_FAILED.equals(e.getStatus())) {
            log.info("[FN-FAIL-T1b] orderNo={} 当前 {} 非失败态，reopen 幂等返当前、不动", orderNo, e.getStatus());
            return JsonResult.successful(buildListRow(e)).setMsg("当前非失败态，幂等返当前");
        }
        // ISSUE_FAILED → PENDING_ISSUE：failCount 归零（否则 findClaimableTasks 的 maxFail 挡住领不出来）、
        // 清锁；238：lastError 一并清空——退回待开 = 干净状态，不留上次失败原因（原"保留便于追溯"改为清除）。
        e.setStatus(InvoiceCollectRequest.STATUS_PENDING_ISSUE);
        e.setFailCount(0);
        e.setLastError(null);   // 238：退回待开清掉失败记录
        releaseLock(e);
        e = repo.save(e);
        log.info("[FN-FAIL-T1b] reopen ok：orderNo={} ISSUE_FAILED → PENDING_ISSUE，failCount 归零、lastError 清空、锁已清", orderNo);
        return JsonResult.successful(buildListRow(e)).setMsg("已重开，回到待开票");
    }

    // ════════════════════════════════════════════════════════════════
    // 8) POST /invoice-collect/{orderNo}/cancel —— 软取消待开任务（FN-Q，225）
    //    待开队列删除按钮的后端：软取消留痕，不物理删。带 X-Internal-Token、@Transactional。
    //    ISSUED → 拒绝（已开票不可取消）；CANCELED → 幂等成功；
    //    PENDING_ISSUE/INCOMPLETE/ISSUE_FAILED → CANCELED + 清锁 + SECURITY_AUDIT 留痕。
    //    CANCELED 天然不在 findClaimableTasks(status='PENDING_ISSUE') → 自动移出待开队列（不改队列查询）。
    // ════════════════════════════════════════════════════════════════
    @PostMapping("/{orderNo}/cancel")
    @Transactional
    public JsonResult cancel(@PathVariable String orderNo,
                             @RequestHeader(value = INTERNAL_TOKEN_HEADER, required = false) String token,
                             HttpServletResponse response) {
        if (!internalTokenOk(token)) return forbidden(response);
        if (orderNo == null || orderNo.isBlank()) {
            return JsonResult.failure("orderNo 必填").setData(Map.of("errorType", "VALIDATION"));
        }
        Optional<InvoiceCollectRequest> opt = repo.findByOrderNo(orderNo.trim());
        if (opt.isEmpty()) {
            return JsonResult.failure("订单 " + orderNo + " 不存在").setData(Map.of("errorType", "NOT_FOUND"));
        }
        InvoiceCollectRequest e = opt.get();
        // 已 ISSUED 硬挡：开票完成不可取消（票据已生成、留痕保全）
        if (InvoiceCollectRequest.STATUS_ISSUED.equals(e.getStatus())) {
            log.warn("[FN-Q-CANCEL] orderNo={} 已 ISSUED，拒绝取消", orderNo);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("errorType", "ALREADY_ISSUED");
            data.put("orderNo", e.getOrderNo());
            data.put("status", e.getStatus());
            data.put("invoiceNumber", e.getInvoiceNumber());
            return JsonResult.failure("该订单已开票，不可取消").setData(data);
        }
        // 已 CANCELED → 幂等返当前、不动库
        if (InvoiceCollectRequest.STATUS_CANCELED.equals(e.getStatus())) {
            log.info("[FN-Q-CANCEL] orderNo={} 已 CANCELED，幂等返当前", orderNo);
            return JsonResult.successful(buildListRow(e)).setMsg("已取消，幂等返当前");
        }
        // PENDING_ISSUE / INCOMPLETE / ISSUE_FAILED → CANCELED：清锁（移出待开队列）+ 留痕
        String from = e.getStatus();
        e.setStatus(InvoiceCollectRequest.STATUS_CANCELED);
        releaseLock(e);
        e = repo.save(e);
        // SECURITY_AUDIT 留痕（securityGuards 为空＝纯单测无 Spring 上下文，跳过不影响主流程）
        if (securityGuards != null) {
            securityGuards.auditCollect(e.getAccountSetsId(), orderNo, "CANCELED");
        }
        log.info("[FN-Q-CANCEL] cancel ok：orderNo={} {} → CANCELED，锁已清（移出待开队列）", orderNo, from);
        return JsonResult.successful(buildListRow(e)).setMsg("已取消");
    }

    // ════════════════════════════════════════════════════════════════
    // 9) POST /invoice-collect/{orderNo}/face-auth —— 置待扫脸 + 绑二维码 URL（248 Part B，机器端点）
    //    服务器 headless 开票遇扫脸弹窗 → 截二维码图（Part A 经现有文件服务上传得 URL）→ 调本端点存 URL +
    //    置 FACE_AUTH_PENDING，等桌面端扫码。带 X-Internal-Token。261：去 @Transactional（单条 save 自管事务）+ save
    //    包 try/catch 返具体错——规避 catch 后 return 的 rollback-only(UnexpectedRollbackException) 陷阱。
    //    🟥 不 releaseLock（服务器会话仍挂着等扫码）；FACE_AUTH_PENDING 天然不在 findClaimableTasks(PENDING_ISSUE) 被领。
    //    可重复调 = 刷新二维码（覆盖 faceAuthQrUrl）；扫完出票走现有 /result→ISSUED；卡住走 cancel→CANCELED 清理。
    // ════════════════════════════════════════════════════════════════
    @PostMapping("/{orderNo}/face-auth")
    public JsonResult faceAuth(@PathVariable String orderNo,
                               @RequestHeader(value = INTERNAL_TOKEN_HEADER, required = false) String token,
                               @RequestBody(required = false) FaceAuthReq req,
                               HttpServletResponse response) {
        if (!internalTokenOk(token)) return forbidden(response);
        if (orderNo == null || orderNo.isBlank()) {
            return JsonResult.failure("orderNo 必填").setData(Map.of("errorType", "VALIDATION"));
        }
        Optional<InvoiceCollectRequest> opt = repo.findByOrderNo(orderNo.trim());
        if (opt.isEmpty()) {
            return JsonResult.failure("订单 " + orderNo + " 不存在").setData(Map.of("errorType", "NOT_FOUND"));
        }
        InvoiceCollectRequest e = opt.get();
        // 已 ISSUED 硬挡：开票完成不再置待扫脸
        if (InvoiceCollectRequest.STATUS_ISSUED.equals(e.getStatus())) {
            log.warn("[FN-FACE-QR-B] orderNo={} 已 ISSUED，拒绝置待扫脸", orderNo);
            return alreadyIssued(e);
        }
        // 任意非 ISSUED 前态 → FACE_AUTH_PENDING + 绑（刷新）二维码 URL + 记录时刻；不 releaseLock（会话仍在跑）
        try {
            e.setStatus(InvoiceCollectRequest.STATUS_FACE_AUTH_PENDING);
            e.setFaceAuthQrUrl(req == null ? null : truncate(req.getFaceAuthQrUrl(), 1024));
            e.setFaceAuthAt(LocalDateTime.now());
            e = repo.save(e);
            log.info("[FN-FACE-QR-B] face-auth ok：orderNo={} → FACE_AUTH_PENDING，绑二维码（锁保留）", orderNo);
            return JsonResult.successful(buildListRow(e)).setMsg("已置待扫脸");
        } catch (Exception ex) {
            log.error("[FN-FACE-QR-B] face-auth 置待扫脸失败 orderNo={}", orderNo, ex);
            return JsonResult.failure("置待扫脸失败：" + ex.getMessage(), 500)
                    .setData(Map.of("errorType", "STORAGE"));
        }
    }

    // ════════════════════════════════════════════════════════════════
    // 9b) GET /invoice-collect/face-auth-pending —— 桌面端查待扫脸列表（248 Part B）
    //    访问口径同 /failed：@SaIgnore 只读列表（不挂 X-Internal-Token），行含 faceAuthQrUrl + statusLabel「待扫脸」。
    // ════════════════════════════════════════════════════════════════
    @GetMapping("/face-auth-pending")
    public JsonResult faceAuthPending(@RequestParam(required = false, defaultValue = "100") Integer size) {
        if (size == null || size < 1) size = 100;
        if (size > 500) size = 500;
        // 按进入待扫脸时刻倒序（最近的在前）；NULL 自然排末尾
        PageRequest pr = PageRequest.of(0, size, Sort.by("faceAuthAt").descending());
        List<InvoiceCollectRequest> rows = repo.findByStatus(InvoiceCollectRequest.STATUS_FACE_AUTH_PENDING, pr);
        List<Map<String, Object>> listRows = new ArrayList<>(rows.size());
        for (InvoiceCollectRequest e : rows) {
            listRows.add(buildListRow(e));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("rows", listRows);
        data.put("size", size);
        data.put("count", listRows.size());
        return JsonResult.successful(data);
    }

    // ─── promote / 金额 / 省份 helpers ─────────────────────────────

    /** INCOMPLETE → PENDING_ISSUE：校验 items 格式后置状态（金额不落库，读时现算）。 */
    private void promoteToPendingIssue(InvoiceCollectRequest e) {
        parseAndValidateItems(e.getItemsJson()); // 格式校验，抛异常即拦截
        e.setStatus(InvoiceCollectRequest.STATUS_PENDING_ISSUE);
    }

    /** 读时现算金额；items_json 异常则返 [0,0]，不影响主流程。 */
    private static BigDecimal[] computeAmounts(String itemsJson, String taxInclusion) {
        try { return sumItems(JSON.parseArray(itemsJson), taxInclusion); }
        catch (Exception ex) { return new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO}; }
    }

    /**
     * FN-TAX-T1（171）items 合计 → [价税合计, 税额]，按整单含税标志双算法（直接定开票金额）。
     * <ul>
     *   <li>{@code '2'} 含税（默认）：价税合计 = Σ amount；税额 = Σ amount×rate/(1+rate)</li>
     *   <li>{@code '1'} 不含税：不含税额 = Σ amount；税额 = Σ amount×rate；价税合计 = 不含税额 + 税额</li>
     * </ul>
     * taxRate 形如 0.13；逐条税额四舍五入到分后再累加（与电税逐行计税口径一致）。
     * <p>包级可见：供单测精确到分直接断言两种算法（{@code OpenApiInvoiceCollectControllerTest}）。
     */
    static BigDecimal[] sumItems(JSONArray items, String taxInclusion) {
        boolean exclusive = InvoiceCollectRequest.TAX_EXCLUSIVE.equals(normalizeTaxInclusion(taxInclusion));
        BigDecimal base = BigDecimal.ZERO, tax = BigDecimal.ZERO;
        for (int i = 0; i < items.size(); i++) {
            var it = items.getJSONObject(i);
            BigDecimal a = new BigDecimal(it.getString("amount"));
            BigDecimal rate = new BigDecimal(it.getString("taxRate"));
            base = base.add(a);
            if (exclusive) {
                // 不含税：税额 = 不含税额 × 税率
                tax = tax.add(a.multiply(rate).setScale(2, RoundingMode.HALF_UP));
            } else {
                // 含税：税额 = 价税合计 × 税率 /(1+税率)
                tax = tax.add(a.multiply(rate).divide(BigDecimal.ONE.add(rate), 2, RoundingMode.HALF_UP));
            }
        }
        base = base.setScale(2, RoundingMode.HALF_UP);
        tax = tax.setScale(2, RoundingMode.HALF_UP);
        // 价税合计：含税时 base 本就是价税合计；不含税时 = 不含税额 + 税额
        BigDecimal gross = exclusive ? base.add(tax) : base;
        return new BigDecimal[]{gross, tax};
    }

    /** FN-TAX-T1（171）含税标志归一：只认 '1'/'2'，null/非法 → 默认含税 '2'（向后兼容）。 */
    static String normalizeTaxInclusion(String v) {
        return InvoiceCollectRequest.TAX_EXCLUSIVE.equals(v)
                ? InvoiceCollectRequest.TAX_EXCLUSIVE : InvoiceCollectRequest.TAX_INCLUSIVE;
    }

    /** FN-INV-T1（173）票种归一：只认 GENERAL/SPECIAL，null/非法 → 默认 GENERAL（向后兼容）。 */
    static String normalizeInvoiceType(String v) {
        return InvoiceCollectRequest.INVOICE_TYPE_SPECIAL.equals(v)
                ? InvoiceCollectRequest.INVOICE_TYPE_SPECIAL : InvoiceCollectRequest.INVOICE_TYPE_GENERAL;
    }

    /** FN-INV-T1（173）发票载体归一：只认 '01'/'02'，null/非法 → 默认 '01' 电子（向后兼容）。 */
    static String normalizeInvoiceCarrier(String v) {
        return InvoiceCollectRequest.CARRIER_PAPER.equals(v)
                ? InvoiceCollectRequest.CARRIER_PAPER : InvoiceCollectRequest.CARRIER_ELECTRONIC;
    }

    /** FN-INV-T1（173）特定业务归一：不在 8 枚举内 → null（空 = 不下发 BT）。 */
    static String normalizeSpecialBusiness(String v) {
        return v != null && SPECIAL_BUSINESS_ENUM.contains(v) ? v : null;
    }

    /** FN-INV-T1（173）差额征税归一：不在 2 枚举内 → null（仅透传标志，空 = 不下发 BT）。 */
    static String normalizeDifferentialTax(String v) {
        return v != null && DIFFERENTIAL_TAX_ENUM.contains(v) ? v : null;
    }

    private static final java.util.Set<String> SPECIAL_BUSINESS_ENUM = java.util.Set.of(
            "白酒", "客运场站服务", "建筑服务", "货物运输服务",
            "不动产销售", "拖拉机和联合收割机", "不动产经营租赁服务", "旅客运输服务");

    private static final java.util.Set<String> DIFFERENTIAL_TAX_ENUM = java.util.Set.of(
            "差额征税-全额开票", "差额征税-差额开票");

    private static JSONArray parseAndValidateItems(String itemsJson) {
        JSONArray arr;
        try { arr = JSON.parseArray(itemsJson); }
        catch (Exception ex) { throw new IllegalArgumentException("items_json 解析失败: " + ex.getMessage()); }
        if (arr == null || arr.isEmpty()) throw new IllegalArgumentException("items 至少 1 条");
        for (int i = 0; i < arr.size(); i++) {
            var it = arr.getJSONObject(i);
            // qty / price 选填（与收银端一致：服务类/按金额开票本就允许不填数量单价；合计/税额只用 amount+taxRate）
            if (isBlank(it.getString("name")))    throw new IllegalArgumentException("items[" + i + "].name 必填");
            if (isBlank(it.getString("amount")))  throw new IllegalArgumentException("items[" + i + "].amount 必填");
            if (isBlank(it.getString("taxRate"))) throw new IllegalArgumentException("items[" + i + "].taxRate 必填");
        }
        return arr;
    }

    // ─── token / 403 ──────────────────────────────────────────────

    /** 内部 token 校验：配置非空且 header 匹配才放行；配置空也拒（内部端点不裸奔）。 */
    private boolean internalTokenOk(String token) {
        if (internalToken == null || internalToken.isBlank()) {
            log.error("[FN-U-T3/T4] financial.scan-direct.internal-token 未配置，claim/result 一律 403");
            return false;
        }
        return internalToken.equals(token);
    }

    private static JsonResult forbidden(HttpServletResponse response) {
        if (response != null) response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        return JsonResult.failure("X-Internal-Token 缺失或不匹配").setData(Map.of("errorType", "FORBIDDEN"));
    }

    // ─── 其它 helpers ─────────────────────────────────────────────

    private static boolean isPendingOrIssued(String status) {
        return InvoiceCollectRequest.STATUS_PENDING_ISSUE.equals(status)
                || InvoiceCollectRequest.STATUS_ISSUED.equals(status);
    }

    /**
     * FN2-GUARD-T1（161）已开票护栏返回：库不变，明确业务错误码 {@code ALREADY_ISSUED}。
     * 160 收银端（IP）透传此码 → 前端 toast "已开票，不可重复申请"。
     */
    private static JsonResult alreadyIssued(InvoiceCollectRequest e) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("errorType", "ALREADY_ISSUED");
        data.put("orderNo", e.getOrderNo());
        data.put("status", e.getStatus());
        data.put("receiptNo", e.getReceiptNo());
        data.put("invoiceNumber", e.getInvoiceNumber());
        return JsonResult.failure("该订单已开票，不可重复申请").setData(data);
    }

    /**
     * FN2-GUARD-T1（161）传入抬头是否与已存抬头完全一致（trim 归一，null/空等价）。
     * 用于已 ISSUED 时区分"无害网络重试（同抬头）→ 幂等放行"与"想改抬头/重发起 → 拒绝"。
     */
    private static boolean sameHeader(InvoiceCollectRequest e, Header h) {
        if (h == null) return false;
        return eqNorm(e.getBuyerCompany(), h.getCompanyName())
                && eqNorm(e.getBuyerTaxNo(), h.getTaxNo())
                && eqNorm(e.getBuyerPhone(), h.getPhone())
                && eqNorm(e.getBuyerEmail(), h.getEmail())
                && eqNorm(e.getBuyerAddress(), h.getAddress())
                && eqNorm(e.getBuyerBank(), h.getBank())
                && eqNorm(e.getBuyerBankAccount(), h.getBankAccount());
    }

    private static boolean eqNorm(String a, String b) {
        return norm(a).equals(norm(b));
    }

    private static String norm(String s) {
        return s == null ? "" : s.trim();
    }

    private static void releaseLock(InvoiceCollectRequest e) {
        e.setLockOwner(null);
        e.setLockedAt(null);
    }

    private static List<Object> parseFiles(String filesJson) {
        if (filesJson == null || filesJson.isBlank()) return List.of();
        try { return JSON.parseArray(filesJson); }
        catch (Exception ex) { return List.of(); }
    }

    /**
     * FN-QTA-T2（163）剩余额度容错解析：去千分位逗号（中/英）/ 货币符 / 空白后转 BigDecimal。
     * <p><b>红线</b>：缺省（null/空）→ NULL；解析失败 → log.warn + NULL。<b>绝不抛异常</b>——
     * 额度解析失败不能拖垮整个 /result 回写（否则撞 162 "交付失败≠开票失败"，引发重复开票风险）。
     */
    private static BigDecimal parseQuotaOrWarn(String raw, String orderNo) {
        if (raw == null || raw.isBlank()) return null;
        String cleaned = raw.replace(",", "").replace("，", "")
                .replace("￥", "").replace("¥", "").replace("$", "")
                .replaceAll("\\s", "");
        if (cleaned.isEmpty()) return null;
        try {
            return new BigDecimal(cleaned);
        } catch (NumberFormatException ex) {
            log.warn("[FN-QTA-T2] orderNo={} 剩余额度解析失败，存 NULL（不影响回写）：quotaAfterIssue='{}'", orderNo, raw);
            return null;
        }
    }

    /**
     * FN-QTA-T2（163）简要信息卡原样存档：序列化为 JSON 字符串。
     * <p>同红线：缺省 → NULL；序列化异常 → log.warn + NULL，绝不让回写失败。
     */
    private static String serializeBriefOrNull(Object brief, String orderNo) {
        if (brief == null) return null;
        try {
            return JSON.toJSONString(brief);
        } catch (Exception ex) {
            log.warn("[FN-QTA-T2] orderNo={} issuedBrief 序列化失败，存 NULL（不影响回写）", orderNo, ex);
            return null;
        }
    }

    /**
     * FN-QTA-T3（163）单条详情回吐 issuedBrief：把存档 JSON 字符串解析回结构化对象（中文键名原样）。
     * 解析失败兜底返原始字符串（快照不丢），空 → null。
     */
    private static Object parseBriefForOutput(String briefJson) {
        if (briefJson == null || briefJson.isBlank()) return null;
        try { return JSON.parse(briefJson); }
        catch (Exception ex) { return briefJson; }
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    /**
     * FN-FAIL-T1a（175）失败原因对外收敛：只取首行（剥离多行栈）+ 截断 200，杜绝把内部栈/文件路径
     * 裸暴露到失败列表端点（@SaIgnore 公网可达，风险 3）。null/空 → null。
     */
    private static String summarizeError(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String firstLine = raw.trim().split("\\R", 2)[0].trim();
        return truncate(firstLine, 200);
    }

    /** 基础校验：orderNo + items 必填 */
    static String validateBase(CollectReq req) {
        if (req == null) return "请求体不能为空";
        if (isBlank(req.getOrderNo())) return "orderNo 必填";
        if (req.getItems() == null || req.getItems().isEmpty()) return "items 至少 1 条";
        return null;
    }

    /**
     * 抬头校验（用于"带 header 一次性"+"/header 补抬头"）。
     * 254-B：公司名必填、税号选填（自然人/个人购方无税号、普票合法）。
     * 255-B：<b>专用发票</b>购方税号<b>仍必填</b>（专票法定需购方税号）；普票（GENERAL/null）税号可空。
     */
    static String validateHeaderComplete(Header h, String invoiceType) {
        if (h == null) return "header 必填";
        if (isBlank(h.getCompanyName())) return "header.companyName 必填";
        // 255-B：专票购方税号必填；普票可空（254-B 放开零回归）。phone/email 选填。
        if (InvoiceCollectRequest.INVOICE_TYPE_SPECIAL.equals(normalizeInvoiceType(invoiceType))
                && isBlank(h.getTaxNo())) {
            return "专用发票购方税号必填";
        }
        return null;
    }

    /** header 任一字段非空 → 视为"带 header"路径 */
    static boolean hasAnyHeaderField(Header h) {
        return !isBlank(h.getCompanyName()) || !isBlank(h.getTaxNo())
                || !isBlank(h.getPhone()) || !isBlank(h.getEmail());
    }

    private static boolean isBlank(String s) { return s == null || s.trim().isEmpty(); }

    static String generateReceiptNo() {
        return "R" + java.time.LocalDateTime.now().format(RECEIPT_TS)
                + String.format("%06d", ThreadLocalRandom.current().nextInt(1_000_000));
    }

    private static void applyHeader(InvoiceCollectRequest e, Header h) {
        e.setBuyerCompany(h.getCompanyName().trim());
        // 254-B：taxNo 选填——空则落 null（仿 phone/email 兜底），不再裸 .trim() 防 NPE
        e.setBuyerTaxNo(isBlank(h.getTaxNo()) ? null : h.getTaxNo().trim());
        // FN-HDR-T1：phone/email 选填，可能为 null → null 兜底防 NPE
        e.setBuyerPhone(h.getPhone() == null ? null : h.getPhone().trim());
        e.setBuyerEmail(h.getEmail() == null ? null : h.getEmail().trim());
        e.setBuyerAddress(h.getAddress() == null ? null : h.getAddress().trim());
        e.setBuyerBank(h.getBank() == null ? null : h.getBank().trim());
        e.setBuyerBankAccount(h.getBankAccount() == null ? null : h.getBankAccount().trim());
    }

    /** 存/补抬头返回的精简 DTO。 */
    private static Map<String, Object> buildDto(InvoiceCollectRequest e, boolean idempotent, String mode) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("receiptNo", e.getReceiptNo());          // INCOMPLETE 时为 null
        m.put("orderNo", e.getOrderNo());
        m.put("status", e.getStatus());
        m.put("source", e.getSource());
        BigDecimal[] sums = computeAmounts(e.getItemsJson(), e.getTaxInclusion());
        m.put("requestAmount", sums[0]);
        m.put("requestTaxAmount", sums[1]);
        m.put("createdAt", e.getCreatedAt());
        m.put("idempotent", idempotent);
        m.put("mode", mode);                            // "header" | "pending"
        return m;
    }

    /** FN-U-T3：BT 直接开票需要的完整任务数据。 */
    private static Map<String, Object> buildTaskData(InvoiceCollectRequest e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("orderNo", e.getOrderNo());
        m.put("items", JSON.parseArray(e.getItemsJson()));
        Map<String, Object> h = new LinkedHashMap<>();
        h.put("companyName", e.getBuyerCompany());
        h.put("taxNo", e.getBuyerTaxNo());
        h.put("phone", e.getBuyerPhone());
        h.put("email", e.getBuyerEmail());
        h.put("address", e.getBuyerAddress());
        h.put("bank", e.getBuyerBank());
        h.put("bankAccount", e.getBuyerBankAccount());
        m.put("header", h);
        // FN-ID-T2：BT 是 JS 端，雪花 id 必须字符串化防精度丢失
        m.put("accountSetsId", e.getAccountSetsId() == null ? null : String.valueOf(e.getAccountSetsId()));
        // FN-TAX-T1（171）含税标志下发 BT（顶层字段同名，'1'/'2' 透传；存量/历史 null 兜底为 '2' 含税）
        m.put("taxInclusion", normalizeTaxInclusion(e.getTaxInclusion()));
        // FN-INV-T1（173）票种区 4 字段下发 BT（键名与 BT DTO 对齐；null 下发 null = 不使用）
        m.put("invoiceType", normalizeInvoiceType(e.getInvoiceType()));
        m.put("invoiceCarrier", normalizeInvoiceCarrier(e.getInvoiceCarrier()));
        m.put("specialBusiness", e.getSpecialBusiness());
        m.put("differentialTax", e.getDifferentialTax());
        m.put("source", e.getSource());                  // 采集渠道，仅展示
        BigDecimal[] sums = computeAmounts(e.getItemsJson(), e.getTaxInclusion());
        m.put("requestAmount", sums[0]);
        m.put("requestTaxAmount", sums[1]);
        m.put("failCount", e.getFailCount());
        m.put("receiptNo", e.getReceiptNo());
        return m;
    }

    /** FN-LIST-T1：账套列表行 DTO（金额现算，雪花字段不出现在本 DTO 中）。 */
    private Map<String, Object> buildListRow(InvoiceCollectRequest e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("orderNo", e.getOrderNo());
        m.put("status", e.getStatus());
        m.put("statusLabel", statusLabel(e.getStatus()));
        m.put("buyerCompany", e.getBuyerCompany());
        m.put("buyerTaxNo", e.getBuyerTaxNo());
        // 234：销方账套 id（雪花 19 位字符串化防 JS 精度丢失，口径同 buildTaskData）；
        // 供 brower-tax 已开/失败/回执行按 accountSetsId 关联账套表查销方公司名（修「销方显—」根因）。
        m.put("accountSetsId", e.getAccountSetsId() == null ? null : String.valueOf(e.getAccountSetsId()));
        BigDecimal[] sums = computeAmounts(e.getItemsJson(), e.getTaxInclusion());
        m.put("requestAmount", sums[0]);
        m.put("requestTaxAmount", sums[1]);
        m.put("receiptNo", e.getReceiptNo());
        m.put("invoiceCode", e.getInvoiceCode());
        m.put("invoiceNumber", e.getInvoiceNumber());
        m.put("invoiceType", e.getInvoiceType());     // FN-ISSUED-T1（183）票种 GENERAL/SPECIAL（板块3 票种筛选）
        m.put("invoiceFiles", parseFiles(e.getInvoiceFiles()));
        m.put("qrUrl", e.getQrUrl());     // FN-DLV-T1（182）电税官方下载链接（客户交付备用）
        m.put("faceAuthQrUrl", e.getFaceAuthQrUrl());   // 248 Part B：扫脸认证二维码 URL（≠qrUrl 交付 QR）
        // 268/270：存证截图 URL（258/266 截图）。270 起优先读列 evidence_url（上传成功回写）；
        // 列空（270 之前/回写失败/老票）退回 stat 兜底——平滑过渡不丢老票，前端 null 不显。
        String evidenceUrl = e.getEvidenceUrl();
        if (evidenceUrl == null && storageService != null) {
            evidenceUrl = storageService.evidenceUrlIfPresent(e.getOrderNo());
        }
        m.put("evidenceUrl", evidenceUrl);
        m.put("createdAt", e.getCreatedAt());
        m.put("issuedAt", e.getIssuedAt());
        // FN-FAIL-T1a（175）失败展示：失败计数 + 失败原因摘要（非失败行 lastError 通常为 null）。
        // lastError 经 summarizeError 收敛——只取首行、截断 200，杜绝把内部栈/路径裸暴露公网（风险 3）。
        m.put("failCount", e.getFailCount());
        m.put("lastError", summarizeError(e.getLastError()));
        // FN-QTA-T3（163）列表行透出额度（账套最新额度=该账套最近 issued_at 的 ISSUED 行本值）；issuedBrief 不进列表（LONGTEXT）
        m.put("quotaAfterIssue", e.getQuotaAfterIssue());
        Map<String, Object> h = new LinkedHashMap<>();
        h.put("address", e.getBuyerAddress());
        h.put("bank", e.getBuyerBank());
        h.put("bankAccount", e.getBuyerBankAccount());
        m.put("header", h);
        return m;
    }

    private static String statusLabel(String status) {
        if (status == null) return "";
        return switch (status) {
            case InvoiceCollectRequest.STATUS_INCOMPLETE    -> "填写不完整";
            case InvoiceCollectRequest.STATUS_PENDING_ISSUE -> "待开票";
            case InvoiceCollectRequest.STATUS_ISSUED        -> "开票完成";
            case InvoiceCollectRequest.STATUS_ISSUE_FAILED  -> "开票失败";
            case InvoiceCollectRequest.STATUS_CANCELED      -> "已取消";
            case InvoiceCollectRequest.STATUS_FACE_AUTH_PENDING -> "待扫脸";
            default -> status;
        };
    }

    private static Map<String, Object> buildResultDto(InvoiceCollectRequest e, boolean idempotent) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("orderNo", e.getOrderNo());
        m.put("status", e.getStatus());
        m.put("invoiceCode", e.getInvoiceCode());
        m.put("invoiceNumber", e.getInvoiceNumber());
        m.put("invoiceFiles", parseFiles(e.getInvoiceFiles()));
        m.put("qrUrl", e.getQrUrl());     // FN-DLV-T1（182）电税官方下载链接（客户交付备用）
        m.put("issuedAt", e.getIssuedAt());
        m.put("failCount", e.getFailCount());
        m.put("lastError", e.getLastError());
        // FN-QTA-T3（163）回写响应回带已落库的额度（BT 据此确认额度入库成功）
        m.put("quotaAfterIssue", e.getQuotaAfterIssue());
        m.put("idempotent", idempotent);
        return m;
    }

    // ════════════════════════════════════════════════════════════════
    // 请求 DTO
    // ════════════════════════════════════════════════════════════════

    @Data
    public static class CollectReq {
        private Integer v;
        private String orderNo;
        private String createdAt;
        private List<Map<String, Object>> items;
        private Header header;
        private String source;
        /** 商家账套 id；扫码直开必填 */
        private Long accountSetsId;
        /**
         * FN-TAX-T1（171）整单级含税标志 '2' 含税(默认)/'1' 不含税。
         * null/非法值落库一律按 '2'（向后兼容老客户端不传）；显式 '1' 才走不含税。
         */
        private String taxInclusion;
        /**
         * FN-INV-T1（173）票种 GENERAL 普票(默认)/SPECIAL 专票。
         * null/非法值落库一律按 'GENERAL'（向后兼容老客户端不传）。
         */
        private String invoiceType;
        /**
         * FN-INV-T1（173）发票载体 '01' 电子(默认)/'02' 纸质。
         * null/非法值落库一律按 '01'（向后兼容老客户端不传）。
         */
        private String invoiceCarrier;
        /**
         * FN-INV-T1（173）特定业务（BT 8 项中文枚举）；不在枚举 → null（空 = 不下发）。
         */
        private String specialBusiness;
        /**
         * FN-INV-T1（173）差额征税（差额征税-全额开票/差额征税-差额开票）；不在枚举 → null。
         * 仅透传标志，sumItems 不改。
         */
        private String differentialTax;
    }

    @Data
    public static class Header {
        private String companyName;
        private String taxNo;
        private String phone;
        private String email;
        private String address;
        private String bank;
        private String bankAccount;
    }

    /** 补抬头专用：只接 header */
    @Data
    public static class AddHeaderReq {
        private Header header;
    }

    /** FN-SYNC-T1（159）：批量按订单号查状态 body，单次最多约 50 个，空数组合法。 */
    @Data
    public static class StatusByOrdersReq {
        private List<String> orderNos;
    }

    /** FN-U-T3 claim body：owner = BT 实例标识（可空，默认 "bt"） */
    @Data
    public static class ClaimReq {
        private String owner;
    }

    /** 248 Part B face-auth body：扫脸二维码图 URL（Part A 经现有文件服务上传后得到）。 */
    @Data
    public static class FaceAuthReq {
        private String faceAuthQrUrl;
    }

    /** FN-U-T4 result body */
    @Data
    public static class ResultReq {
        private Boolean success;
        private String invoiceCode;
        private String invoiceNumber;
        /** FN-DLV-T1（182）一组文件 URL（首个=主发票 PDF，含 OFD/XML/二维码等；现为中心存储可访问 URL） */
        private List<String> files;
        /** FN-DLV-T1（182）电税官方下载链接（qrUrl）；可空，作客户交付备用 */
        private String qrUrl;
        private String error;
        /**
         * FN-QTA-T2（163）本票开完后剩余可开票额度（162 成功页抓取）。
         * String 兼容数值/带千分位/带货币符；落库时去符号解析 BigDecimal，解析失败 warn+NULL（不影响回写）。
         */
        private String quotaAfterIssue;
        /**
         * FN-QTA-T2（163）开票成功页 发票简要信息+额度信息 原样快照（中文键名）。
         * Object/Map，原样 JSON.toJSONString 存档，消费端自行解析。
         */
        private Object issuedBrief;
    }
}
