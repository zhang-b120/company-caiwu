package cn.gson.financial.controller.openapi;

import cn.dev33.satoken.annotation.SaIgnore;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.model.entity.BankStatement;
import cn.gson.financial.kernel.model.entity.BankStatementDetail;
import cn.gson.financial.kernel.model.entity.FileInfo;
import cn.gson.financial.kernel.model.entity.QBankStatementDetail;
import cn.gson.financial.kernel.model.hibernate.BlazeJPAQueryFactory;
import cn.gson.financial.kernel.model.hibernate.PrimaryKey;
import cn.gson.financial.kernel.model.repo.BankStatementDetailRepository;
import cn.gson.financial.kernel.model.repo.BankStatementRepository;
import cn.gson.financial.kernel.model.repo.FileInfoRepository;
import cn.gson.financial.kernel.service.VoucherService;
import cn.hutool.core.util.StrUtil;
import jakarta.annotation.Resource;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 开放 API — 银行流水批量结构化写入（xiaowei AI 解析回写专用）
 * <p>
 * POST /openapi/v1/bank/statements/batch
 * xiaowei 用 LLM 解析 xlsx/PDF/CSV 后，把结构化的交易记录批量推到这里。
 * <p>
 * 业务约束：
 *   - 整批事务：任一笔字段校验失败则全部回滚
 *   - 去重：同 (accountSetId, accountNumber, serialNo) 视为重复，已存在的不重插
 *   - serialNo 为空时按 (accountSetId, accountNumber, tradeDate, tradeAmount, summary) 兜底去重
 *   - 已关账期间不允许写入
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/openapi/v1/bank/statements")
@SaIgnore
public class OpenApiBankBatchController {

    private final VoucherService voucherService;
    private final FileInfoRepository fileInfoRepository;
    private final BankStatementRepository bankStatementRepository;
    private final BankStatementDetailRepository bankStatementDetailRepository;

    @Resource
    @Lazy
    private BlazeJPAQueryFactory bqf;

    private static final QBankStatementDetail qBsd = QBankStatementDetail.bankStatementDetail;
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ISO_LOCAL_DATE;

    @PostMapping("/batch")
    @Transactional(rollbackFor = Exception.class)
    public JsonResult batch(@RequestBody BankStatementBatchReq req) {
        // ── 1. 顶层必填校验 ─────────────────────────────────────────
        if (req == null) return failure("VALIDATION_FAILED", "请求体不能为空", null);
        if (req.getAccountSetsId() == null) return failure("VALIDATION_FAILED", "accountSetsId 不能为空", null);
        if (req.getFileId() == null) return failure("VALIDATION_FAILED", "fileId 不能为空", null);
        if (StrUtil.isBlank(req.getBankName())) return failure("VALIDATION_FAILED", "bankName 不能为空", null);
        if (StrUtil.isBlank(req.getAccountNumber())) return failure("VALIDATION_FAILED", "accountNumber 不能为空", null);
        if (StrUtil.isBlank(req.getPeriod())) return failure("VALIDATION_FAILED", "period 不能为空", null);
        if (req.getTransactions() == null || req.getTransactions().isEmpty()) {
            return failure("VALIDATION_FAILED", "transactions 不能为空", null);
        }

        // ── 2. period 格式校验 ─────────────────────────────────────
        YearMonth ym;
        try {
            ym = YearMonth.parse(req.getPeriod());
        } catch (Exception e) {
            return failure("VALIDATION_FAILED", "period 格式错误（应为 YYYY-MM）：" + req.getPeriod(), null);
        }

        // ── 3. fileId 校验：必须存在且属于该账套 ───────────────────
        FileInfo file = fileInfoRepository
                .findById(new PrimaryKey(req.getFileId(), req.getAccountSetsId()))
                .orElse(null);
        if (file == null || Integer.valueOf(1).equals(file.getIsDelete())) {
            return failure("FILE_NOT_FOUND",
                    "文件不存在或不属于该账套：" + req.getFileId(), null);
        }

        // ── 4. 关账期间检查 ────────────────────────────────────────
        try {
            voucherService.checkCheckOut(req.getAccountSetsId(), ym.getYear(), ym.getMonthValue());
        } catch (Exception e) {
            return failure("PERIOD_CLOSED",
                    "期间已关账，禁止写入：" + req.getPeriod(), null);
        }

        // ── 5. 逐笔字段校验（先全部校验再开始入库） ────────────────
        List<Map<String, Object>> errors = new ArrayList<>();
        List<TransactionInput> validated = new ArrayList<>(req.getTransactions().size());
        for (int i = 0; i < req.getTransactions().size(); i++) {
            BankTransactionReq t = req.getTransactions().get(i);
            TransactionInput v = validateTransaction(i, t, errors);
            if (v != null) validated.add(v);
        }
        if (!errors.isEmpty()) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("errorType", "VALIDATION_FAILED");
            data.put("errors", errors);
            return JsonResult.failure("交易记录字段校验失败，共 " + errors.size() + " 条").setData(data);
        }

        // ── 6. 创建 BankStatement 父记录（本批次的对账单头） ────────
        BankStatement header = new BankStatement();
        header.setAccountSetsId(req.getAccountSetsId());
        header.setPeriod(req.getPeriod());
        header.setCreateTime(LocalDateTime.now());
        header.setIsDelete(0);
        BankStatement savedHeader = bankStatementRepository.save(header);

        // ── 7. 逐笔去重 + 入库 ─────────────────────────────────────
        BigDecimal totalCredit = BigDecimal.ZERO;
        BigDecimal totalDebit  = BigDecimal.ZERO;
        List<Long> outIds = new ArrayList<>(validated.size());
        int inserted = 0;
        int duplicated = 0;

        for (TransactionInput v : validated) {
            BankStatementDetail dup = findExisting(req.getAccountSetsId(), req.getAccountNumber(), v);
            if (dup != null) {
                outIds.add(dup.getId());
                duplicated++;
            } else {
                BankStatementDetail row = buildDetail(req, savedHeader.getId(), v);
                BankStatementDetail saved = bankStatementDetailRepository.save(row);
                outIds.add(saved.getId());
                inserted++;
            }
            if ("credit".equals(v.direction)) {
                totalCredit = totalCredit.add(v.amount);
            } else {
                totalDebit = totalDebit.add(v.amount);
            }
        }

        // ── 8. 响应 ───────────────────────────────────────────────
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("inserted", inserted);
        data.put("duplicated", duplicated);
        data.put("ids", outIds);
        data.put("totalCredit", totalCredit);
        data.put("totalDebit", totalDebit);
        data.put("bankStatementId", savedHeader.getId());
        return JsonResult.successful(data);
    }

    // ═══════════════════════════════════════════════════════════════
    // 辅助
    // ═══════════════════════════════════════════════════════════════

    /**
     * 校验一笔交易，并把校验通过的字段解析成 TransactionInput。
     * 校验失败把错误塞 errors 列表，返回 null。
     */
    private TransactionInput validateTransaction(int index, BankTransactionReq t, List<Map<String, Object>> errors) {
        if (t == null) {
            errors.add(errorEntry(index, null, "交易记录不能为空"));
            return null;
        }
        // tradeDate
        if (StrUtil.isBlank(t.getTradeDate())) {
            errors.add(errorEntry(index, "tradeDate", "tradeDate 不能为空"));
            return null;
        }
        LocalDate date;
        try {
            date = LocalDate.parse(t.getTradeDate().trim(), DATE_FMT);
        } catch (Exception e) {
            errors.add(errorEntry(index, "tradeDate", "tradeDate 格式错误（应为 YYYY-MM-DD）：" + t.getTradeDate()));
            return null;
        }
        // direction
        String dir = t.getDirection() != null ? t.getDirection().trim().toLowerCase() : "";
        if (!"credit".equals(dir) && !"debit".equals(dir)) {
            errors.add(errorEntry(index, "direction", "direction 只支持 credit/debit：" + t.getDirection()));
            return null;
        }
        // amount > 0
        if (t.getAmount() == null) {
            errors.add(errorEntry(index, "amount", "amount 不能为空"));
            return null;
        }
        if (t.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            errors.add(errorEntry(index, "amount", "金额必须 > 0"));
            return null;
        }

        TransactionInput v = new TransactionInput();
        v.index = index;
        v.tradeDate = date;
        v.direction = dir;
        v.amount = t.getAmount();
        v.balance = t.getBalance();
        v.serialNo = StrUtil.trimToNull(t.getSerialNo());
        v.counterName = StrUtil.trimToNull(t.getCounterName());
        v.counterAccount = StrUtil.trimToNull(t.getCounterAccount());
        v.summary = StrUtil.trimToNull(t.getSummary());
        v.note = StrUtil.trimToNull(t.getNote());
        v.refNo = StrUtil.trimToNull(t.getRefNo());
        return v;
    }

    /**
     * 按 serialNo 去重；serialNo 为空时按 (tradeDate, amount, summary) 兜底。
     */
    private BankStatementDetail findExisting(Long accountSetsId, String accountNumber, TransactionInput v) {
        if (v.serialNo != null) {
            return findExistingForTest(accountSetsId, accountNumber, v.serialNo);
        }
        // fallback：同账号、同日、同金额、同摘要视为重复
        LocalDateTime start = v.tradeDate.atStartOfDay();
        LocalDateTime end   = v.tradeDate.atTime(23, 59, 59);
        return bqf.selectFrom(qBsd)
                .where(qBsd.accountSetId.eq(accountSetsId)
                        .and(qBsd.accountNumber.eq(accountNumber))
                        .and(qBsd.tradeDate.between(start, end))
                        .and(qBsd.tradeAmount.eq(v.amount))
                        .and(v.summary != null ? qBsd.remarks.eq(v.summary) : qBsd.remarks.isNull())
                        .and(qBsd.isDelete.eq(0).or(qBsd.isDelete.isNull())))
                .fetchFirst();
    }

    /**
     * package-private hook：单测通过子类覆写此方法替换 BlazeJPAQuery 查询，
     * 避开 BlazeJPAQuery 难直接 mock 的问题。
     */
    BankStatementDetail findExistingForTest(Long accountSetsId, String accountNumber, String serialNo) {
        return bqf.selectFrom(qBsd)
                .where(qBsd.accountSetId.eq(accountSetsId)
                        .and(qBsd.accountNumber.eq(accountNumber))
                        .and(qBsd.serialNo.eq(serialNo))
                        .and(qBsd.isDelete.eq(0).or(qBsd.isDelete.isNull())))
                .fetchFirst();
    }

    /**
     * 把请求中的字段映射到 BankStatementDetail：
     *   direction=credit（收入）：我方在 receive 一侧，对方在 pay 一侧
     *   direction=debit （支出）：我方在 pay 一侧，对方在 receive 一侧
     */
    private BankStatementDetail buildDetail(BankStatementBatchReq req, Long bankStatementId, TransactionInput v) {
        BankStatementDetail d = new BankStatementDetail();
        d.setBankStatementId(bankStatementId);
        d.setAccountSetId(req.getAccountSetsId());
        d.setBankName(req.getBankName());
        d.setAccountNumber(req.getAccountNumber());
        d.setSerialNo(v.serialNo);
        d.setTradeDate(v.tradeDate.atStartOfDay());
        d.setTradeAmount(v.amount);
        d.setBalance(v.balance);
        d.setRemarks(v.summary);
        d.setBusinessType(v.refNo);
        d.setIsDelete(0);
        if ("credit".equals(v.direction)) {
            // 我方收款
            d.setReceiveAccountNum(req.getAccountNumber());
            d.setReceiveName(req.getAccountHolder());
            d.setReceiveBankName(req.getBankName());
            d.setPayAccountNum(v.counterAccount);
            d.setPayName(v.counterName);
        } else {
            // 我方付款
            d.setPayAccountNum(req.getAccountNumber());
            d.setPayName(req.getAccountHolder());
            d.setPayBankName(req.getBankName());
            d.setReceiveAccountNum(v.counterAccount);
            d.setReceiveName(v.counterName);
        }
        return d;
    }

    private static Map<String, Object> errorEntry(int index, String field, String message) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("index", index);
        e.put("field", field);
        e.put("message", message);
        return e;
    }

    private static JsonResult failure(String errorType, String msg, List<Map<String, Object>> errors) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("errorType", errorType);
        if (errors != null && !errors.isEmpty()) data.put("errors", errors);
        return JsonResult.failure(msg).setData(data);
    }

    /** 已校验通过的交易记录（中间数据结构） */
    private static class TransactionInput {
        int index;
        LocalDate tradeDate;
        String direction;
        BigDecimal amount;
        BigDecimal balance;
        String serialNo;
        String counterName;
        String counterAccount;
        String summary;
        String note;
        String refNo;
    }

    // ═══════════════════════════════════════════════════════════════
    // 请求 DTO
    // ═══════════════════════════════════════════════════════════════

    @Data
    public static class BankStatementBatchReq {
        private Long accountSetsId;
        private Long fileId;
        private String bankName;
        private String accountNumber;
        private String accountHolder;
        private String period;
        private BigDecimal openingBalance;
        private BigDecimal closingBalance;
        private String parserUsed;
        private List<BankTransactionReq> transactions;
    }

    @Data
    public static class BankTransactionReq {
        private String serialNo;
        private String tradeDate;       // YYYY-MM-DD
        private String direction;       // credit / debit
        private BigDecimal amount;
        private BigDecimal balance;
        private String counterName;
        private String counterAccount;
        private String summary;
        private String note;
        private String refNo;
    }
}
