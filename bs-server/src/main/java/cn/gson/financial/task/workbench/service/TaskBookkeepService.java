package cn.gson.financial.task.workbench.service;

import cn.gson.financial.kernel.model.dto.VoucherDetailsDto;
import cn.gson.financial.kernel.model.dto.VoucherDto;
import cn.gson.financial.kernel.model.entity.*;
import cn.gson.financial.kernel.model.enums.InvoiceType;
import cn.gson.financial.kernel.model.hibernate.BlazeJPAQueryFactory;
import cn.gson.financial.kernel.model.hibernate.PrimaryKey;
import com.querydsl.jpa.impl.JPAQueryFactory;
import cn.gson.financial.kernel.model.repo.AccountSetsRepository;
import cn.gson.financial.kernel.model.repo.CashierIoTypeVoucherTemplateRepository;
import cn.gson.financial.kernel.model.repo.VoucherWordRepository;
import cn.gson.financial.kernel.service.*;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 任务工作台 - 做账内部任务
 *
 * <p><b>⚠️ FN-FIX-DISABLE-NATIVE-BOOKKEEPING（2026-05-29）：FN 原生做账已统一停用</b>
 * <p>做账（含结转损益）已彻底交给 xiaowei。本服务的 {@link #execute(Long, String, String, boolean)}
 * 入口直接拒绝并返 {@code status=disabled}；下层私有方法（generateAssetDepreciationVouchers /
 * generateBankVouchers / executeCarryForwardInNewTx 等）保留为死代码（不删，便于 fallback /
 * 审计追溯）但不可达。
 * <p>背景：FN-FIX-PL-CARRY-VERIFY-ONLY 只切了 OpenAPI {@code /profit-carry-auto} 这条；本工单补
 * 原生任务工作台这条，根除"FN 也建结转损益→2 张"的隐患。详见
 * {@code 项目工单/工单_FN-FIX-DISABLE-NATIVE-BOOKKEEPING.md}。
 *
 * <p>历史功能（已停用，仅供追溯）：
 * <ol>
 *   <li>固定资产折旧凭证 — AssetsDepreciationService.generateVoucher</li>
 *   <li>发票凭证 — InvoiceAutoVoucherService：关键词匹配业务类型 → 按模板生成凭证</li>
 *   <li>工资凭证 — SalaryVoucherTemplateService.autoGenerateVouchers（需已复核工资+模板）</li>
 *   <li>银行流水凭证 — 查找当期无凭证的日记账，按收支类别模板生成凭证</li>
 *   <li>结转损益凭证 — TaskCarryForwardService：结转损益 + 12月额外结转本年利润</li>
 * </ol>
 *
 * <p>读取/查询方法保留：{@code queryInvoiceDetail / queryBankDetail / querySalaryDetail /
 * queryAssetDetail}（会计仍能在 FN 查看数据，只是不再"FN 自己写凭证"）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskBookkeepService {

    /**
     * FN-FIX-DISABLE-NATIVE-BOOKKEEPING：拒绝时返给上游的标准消息。
     * 上游（{@code TaskWorkbenchService.executeBookkeepAfterDispatch} → Controller → Vue UI）原样透传。
     */
    public static final String DISABLED_MSG =
            "做账已统一走 xiaowei/BT，FN 任务工作台做账已停用";

    @Resource
    @Lazy
    private BlazeJPAQueryFactory bqf;

    @Resource
    private JPAQueryFactory jqf;

    private final AssetsDepreciationService assetsDepreciationService;
    private final InvoiceAutoVoucherService invoiceAutoVoucherService;
    private final SalaryVoucherTemplateService salaryVoucherTemplateService;
    private final TaskCarryForwardService taskCarryForwardService;
    private final BalanceCalcService balanceCalcService;
    private final CashierJournalService cashierJournalService;
    private final VoucherService voucherService;
    private final CheckoutService checkoutService;
    private final AccountSetsRepository accountSetsRepository;
    private final CashierIoTypeVoucherTemplateRepository voucherTemplateRepository;
    private final VoucherWordRepository voucherWordRepository;
    private final SubjectService subjectService;
    private final cn.gson.financial.kernel.model.repo.TaskVatPreRepository taskVatPreRepository;
    private final cn.gson.financial.kernel.model.repo.TaskPeriodRepository taskPeriodRepository;

    @Resource
    @Lazy
    private TaskBookkeepService self; // 自注入，确保 @Transactional(REQUIRES_NEW) 走代理

    private static final DateTimeFormatter PERIOD_FMT = DateTimeFormatter.ofPattern("yyyy-MM");

    /**
     * 执行做账任务（全部子任务）。
     */
    public Map<String, Object> execute(Long accountSetId, String period) {
        return execute(accountSetId, period, null, false);
    }

    public Map<String, Object> execute(Long accountSetId, String period, String subType) {
        return execute(accountSetId, period, subType, false);
    }

    /**
     * 执行做账任务。
     *
     * <p><b>⚠️ FN-FIX-DISABLE-NATIVE-BOOKKEEPING（2026-05-29）：本方法已停用</b>
     * <p>所有调用直接返 {@code {status: "disabled", msg: <DISABLED_MSG>, detail: []}}，不再触发任何
     * 凭证生成（含结转损益 / 工资 / 银行 / 发票 / 折旧 / 代缴税）。
     * <p>方法体保留是为：①让旧调用方（{@code TaskWorkbenchService.executeBookkeepAfterDispatch}）能继续
     * 拿到 result 结构、②审计追溯历史业务规则、③允许 fallback（极端情况运维改回）。本方法体下面的逻辑
     * 不可达，工单 {@code 工单_FN-FIX-DISABLE-NATIVE-BOOKKEEPING.md} 已说明。
     *
     * @param accountSetId 账套 ID
     * @param period       账期 yyyy-MM
     * @param subType      子任务类型（null=全部，invoice/asset/salary/bank/carry_forward）
     * @param force        true 时先删除已有凭证再重新生成
     * @return 结果 map：status / msg / detail（停用后恒为 status=disabled）
     */
    public Map<String, Object> execute(Long accountSetId, String period, String subType, boolean force) {
        // FN-FIX-DISABLE-NATIVE-BOOKKEEPING 顶部拒绝：彻底阻断 FN 原生做账触发链。
        // 一旦这条返了，下面整段做账流程（含 self.executeCarryForwardInNewTx →
        // taskCarryForwardService.execute → buildCarryForwardVoucher）都不可达。
        log.warn("[FN-FIX-DISABLE-NATIVE-BOOKKEEPING] 拒绝 FN 原生做账：accountSetId={} period={} subType={} force={}",
                accountSetId, period, subType, force);
        Map<String, Object> disabled = new LinkedHashMap<>();
        disabled.put("status", "disabled");
        disabled.put("msg", DISABLED_MSG);
        disabled.put("detail", new ArrayList<Map<String, Object>>());
        return disabled;

        /*
         * ====== 旧实现，保留作死代码标记（已停用，不可达；见类 Javadoc）======
         */
        /*
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> detail = new ArrayList<>();
        boolean hasError = false;
        boolean hasWarn = false;

        YearMonth ym = YearMonth.parse(period, PERIOD_FMT);
        int year = ym.getYear();
        int month = ym.getMonthValue();

        boolean all = (subType == null || subType.isEmpty());

        AccountSets accountSets = accountSetsRepository.findById(accountSetId).orElse(null);
        if (accountSets == null) {
            result.put("status", "error");
            result.put("msg", "账套不存在：" + accountSetId);
            return result;
        }

        // force=true 时先删除已有凭证，让后续步骤从零重新生成
        // 必须通过 self（REQUIRES_NEW）独立提交，确保行锁在子任务执行前释放
        if (force) {
            self.deleteVouchersForSubTaskInNewTx(accountSets, ym, subType);
        }

        // ── 1. 固定资产折旧凭证 ──────────────────────────────────────
        if (all || "asset".equals(subType)) {
            Map<String, Object> assetResult = self.generateAssetDepreciationVouchers(accountSetId, year, month);
            detail.add(assetResult);
            String s = (String) assetResult.get("status");
            if ("error".equals(s)) hasError = true;
            if ("warn".equals(s)) hasWarn = true;
        }

        // ── 2. 发票凭证 ─────────────────────────────────────────────
        if (all || "invoice".equals(subType)) {
            Map<String, Object> invoiceResult = invoiceAutoVoucherService.autoMatchAndGenerateVouchers(accountSetId, ym);
            detail.add(invoiceResult);
            String s = (String) invoiceResult.get("status");
            if ("error".equals(s)) hasError = true;
            if ("warn".equals(s)) hasWarn = true;
        }

        // ── 3. 工资凭证 ─────────────────────────────────────────────
        if (all || "salary".equals(subType)) {
            Map<String, Object> salaryResult = salaryVoucherTemplateService.autoGenerateVouchers(accountSetId, ym.atDay(1));
            detail.add(salaryResult);
            String s = (String) salaryResult.get("status");
            if ("error".equals(s)) hasError = true;
            if ("warn".equals(s) || "skipped".equals(s)) hasWarn = true;
        }

        // ── 4. 银行流水凭证 ─────────────────────────────────────────
        if (all || "bank".equals(subType)) {
            Map<String, Object> bankResult = self.generateBankVouchers(accountSetId, year, month);
            detail.add(bankResult);
            String s = (String) bankResult.get("status");
            if ("error".equals(s)) hasError = true;
            if ("warn".equals(s) || "skipped".equals(s)) hasWarn = true;
        }

        // ── 5. 代缴税费凭证（从 vat_declare_pre 回调数据生成）────────
        if (all || "tax".equals(subType)) {
            Map<String, Object> taxResult = generateTaxFeeVouchers(accountSetId, year, month);
            detail.add(taxResult);
            String s = (String) taxResult.get("status");
            if ("error".equals(s)) hasError = true;
            if ("warn".equals(s) || "skipped".equals(s)) hasWarn = true;
        }

        // ── 6. 结转损益凭证（+12月结转本年利润）────────────────────
        if (all || "carry_forward".equals(subType)) {
            Map<String, Object> cfResult;
            try {
                cfResult = self.executeCarryForwardInNewTx(accountSetId, year, month);
            } catch (Exception e) {
                log.error("结转损益执行异常 accountSetId={} 账期={}", accountSetId, period, e);
                cfResult = new java.util.LinkedHashMap<>();
                cfResult.put("type", "carry_forward");
                cfResult.put("status", "error");
                cfResult.put("msg", "结转损益执行失败：" + e.getMessage());
            }
            detail.add(cfResult);
            String s = (String) cfResult.get("status");
            if ("error".equals(s)) hasError = true;
            if ("warn".equals(s) || "skipped".equals(s)) hasWarn = true;
        }

        // ── 整体状态 ─────────────────────────────────────────────────
        String overallStatus = hasError ? "error" : (hasWarn ? "warn" : "done");
        result.put("status", overallStatus);
        result.put("msg", subType != null ? detail.stream().map(d -> (String)d.get("msg")).findFirst().orElse("完成") : buildOverallMsg(overallStatus));
        result.put("detail", detail);
        return result;
        */ // end of FN-FIX-DISABLE-NATIVE-BOOKKEEPING dead-code block
    }

    /**
     * 重新执行前清除当期已生成的凭证，以便从零重新生成。
     * invoice: 清除发票的 voucherId + invoiceBusinessTypeId（让 AI 重新匹配业务类型）
     * asset:   清除折旧记录的 voucherId
     * salary:  删除 SalaryVoucher 关联记录
     * bank:    清除日记账的 voucherId
     * carry_forward: 删除结转凭证，重置结账状态
     * null/all: 执行以上全部
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void deleteVouchersForSubTaskInNewTx(AccountSets accountSets, YearMonth ym, String subType) {
        Long accountSetId = accountSets.getId();
        boolean all = (subType == null || subType.isEmpty());
        LocalDate periodStart = ym.atDay(1);
        LocalDate periodEnd = ym.atEndOfMonth();

        // ── 发票凭证 ─────────────────────────────────────────────────
        if (all || "invoice".equals(subType)) {
            QInvoice qInv = QInvoice.invoice;
            var periodCond = qInv.assignmentDate.between(periodStart, periodEnd)
                    .or(qInv.assignmentDate.isNull()
                            .and(qInv.tradeDate.between(periodStart, periodEnd)));
            List<Long> invoiceVoucherIds = bqf.selectFrom(qInv)
                    .where(qInv.accountSetsId.eq(accountSetId)
                            .and(periodCond)
                            .and(qInv.voucherId.isNotNull()))
                    .fetch().stream().map(Invoice::getVoucherId).filter(Objects::nonNull).collect(Collectors.toList());
            if (!invoiceVoucherIds.isEmpty()) {
                voucherService.batchDelete(accountSets, invoiceVoucherIds);
            }
            // 清除发票的凭证关联和业务类型（让 AI 重新匹配）
            jqf.update(qInv)
                    .setNull(qInv.voucherId)
                    .setNull(qInv.invoiceBusinessTypeId)
                    .where(qInv.accountSetsId.eq(accountSetId)
                            .and(periodCond))
                    .execute();
            log.info("重新执行-删除发票凭证: accountSetId={} 账期={} 凭证数={}", accountSetId, ym, invoiceVoucherIds.size());
        }

        // ── 固定资产折旧凭证 ─────────────────────────────────────────
        if (all || "asset".equals(subType)) {
            QAssetsDepreciation qDep = QAssetsDepreciation.assetsDepreciation;
            List<Long> depVoucherIds = bqf.selectFrom(qDep)
                    .where(qDep.accountSetsId.eq(accountSetId)
                            .and(qDep.checkYear.eq(ym.getYear()))
                            .and(qDep.checkMonth.eq(ym.getMonthValue()))
                            .and(qDep.voucherId.isNotNull()))
                    .fetch().stream().map(AssetsDepreciation::getVoucherId).filter(Objects::nonNull).collect(Collectors.toList());
            if (!depVoucherIds.isEmpty()) {
                voucherService.batchDelete(accountSets, depVoucherIds);
            }
            jqf.update(qDep)
                    .setNull(qDep.voucherId)
                    .where(qDep.accountSetsId.eq(accountSetId)
                            .and(qDep.checkYear.eq(ym.getYear()))
                            .and(qDep.checkMonth.eq(ym.getMonthValue())))
                    .execute();
            log.info("重新执行-删除折旧凭证: accountSetId={} 账期={} 凭证数={}", accountSetId, ym, depVoucherIds.size());
        }

        // ── 工资凭证 ─────────────────────────────────────────────────
        if (all || "salary".equals(subType)) {
            QSalaryVoucher qSv = QSalaryVoucher.salaryVoucher;
            List<Long> salaryVoucherIds = bqf.selectFrom(qSv)
                    .where(qSv.accountSetsId.eq(accountSetId)
                            .and(qSv.year.eq(ym.getYear()))
                            .and(qSv.month.eq(ym.getMonthValue()))
                            .and(qSv.voucherId.isNotNull()))
                    .fetch().stream().map(SalaryVoucher::getVoucherId).filter(Objects::nonNull).collect(Collectors.toList());
            if (!salaryVoucherIds.isEmpty()) {
                voucherService.batchDelete(accountSets, salaryVoucherIds);
            }
            jqf.delete(qSv)
                    .where(qSv.accountSetsId.eq(accountSetId)
                            .and(qSv.year.eq(ym.getYear()))
                            .and(qSv.month.eq(ym.getMonthValue())))
                    .execute();
            log.info("重新执行-删除工资凭证: accountSetId={} 账期={} 凭证数={}", accountSetId, ym, salaryVoucherIds.size());
        }

        // ── 银行流水凭证 ─────────────────────────────────────────────
        if (all || "bank".equals(subType)) {
            QCashierJournal qJournal = QCashierJournal.cashierJournal;
            List<Long> bankVoucherIds = bqf.selectFrom(qJournal)
                    .where(qJournal.accountSetsId.eq(accountSetId)
                            .and(qJournal.billDate.between(periodStart, periodEnd))
                            .and(qJournal.voucherId.isNotNull()))
                    .fetch().stream().map(CashierJournal::getVoucherId).filter(Objects::nonNull).collect(Collectors.toList());
            if (!bankVoucherIds.isEmpty()) {
                voucherService.batchDelete(accountSets, bankVoucherIds);
            }
            jqf.update(qJournal)
                    .setNull(qJournal.voucherId)
                    .where(qJournal.accountSetsId.eq(accountSetId)
                            .and(qJournal.billDate.between(periodStart, periodEnd)))
                    .execute();
            log.info("重新执行-删除银行凭证: accountSetId={} 账期={} 凭证数={}", accountSetId, ym, bankVoucherIds.size());
        }

        // ── 结转损益凭证 ─────────────────────────────────────────────
        if (all || "carry_forward".equals(subType)) {
            QVoucher qVoucher = QVoucher.voucher;
            List<Long> cfVoucherIds = bqf.selectFrom(qVoucher)
                    .where(qVoucher.accountSetsId.eq(accountSetId)
                            .and(qVoucher.carryForward.isTrue())
                            .and(qVoucher.voucherDate.between(periodStart, periodEnd)))
                    .fetch().stream().map(Voucher::getId).filter(Objects::nonNull).collect(Collectors.toList());
            if (!cfVoucherIds.isEmpty()) {
                voucherService.batchDelete(accountSets, cfVoucherIds);
            }
            // 重置结账状态，让结转服务可以重新执行
            checkoutService.updateStatus(accountSetId, ym.getYear(), ym.getMonthValue(), 0);
            log.info("重新执行-删除结转凭证: accountSetId={} 账期={} 凭证数={}", accountSetId, ym, cfVoucherIds.size());
        }

        // 删除完后重算余额
        balanceCalcService.calc(accountSetId, ym);
    }

    /**
     * 自动生成固定资产折旧凭证。
     * 查询当期已计提但未生成凭证的条目，逐条独立事务生成，最后统一重算余额。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Map<String, Object> generateAssetDepreciationVouchers(Long accountSetId, int year, int month) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("type", "asset_depreciation");
        try {
            List<AssetsDepreciation> records = assetsDepreciationService.listByPeriodWithoutVoucher(accountSetId, year, month);
            int generated = 0;
            for (AssetsDepreciation d : records) {
                try {
                    self.createAssetDepreciationVoucherInNewTx(d.getId(), accountSetId);
                    generated++;
                } catch (Exception e) {
                    log.warn("固定资产折旧凭证生成失败: depreciationId={}, accountSetId={}", d.getId(), accountSetId, e);
                }
            }
            if (generated > 0) {
                balanceCalcService.calc(accountSetId, YearMonth.of(year, month));
            }
            r.put("status", "done");
            r.put("msg", String.format("固定资产折旧凭证：新生成 %d 条", generated));
            r.put("generated", generated);
        } catch (Exception e) {
            log.error("固定资产折旧凭证处理失败: accountSetId={}", accountSetId, e);
            r.put("status", "error");
            r.put("msg", "固定资产折旧凭证失败：" + e.getMessage());
        }
        return r;
    }

    /**
     * 自动生成银行流水凭证。
     * 每条银行流水（CashierJournal）单独生成一张凭证，严格 1 借 1 贷。
     *   收入（来账）：借 银行账户科目，贷 对方科目（journal.subjectId）
     *   支出（往账）：借 对方科目（journal.subjectId），贷 银行账户科目
     * 摘要前缀 MM-DD 来自 journal.tradeDate（原始交易日期），便于在凭证列表中区分（所有凭证 voucherDate 都是账期第一天）。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Map<String, Object> generateBankVouchers(Long accountSetId, int year, int month) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("type", "bank");
        try {
            QCashierJournal qJournal = QCashierJournal.cashierJournal;
            LocalDate periodStart = LocalDate.of(year, month, 1);
            LocalDate periodEnd = YearMonth.of(year, month).atEndOfMonth();

            List<CashierJournal> journals = bqf.selectFrom(qJournal)
                    .where(qJournal.accountSetsId.eq(accountSetId)
                            .and(qJournal.billDate.between(periodStart, periodEnd))
                            .and(qJournal.subjectId.isNotNull())
                            .and(qJournal.voucherId.isNull()))
                    .orderBy(qJournal.tradeDate.asc(), qJournal.id.asc())
                    .fetch();

            log.info("银行流水凭证: accountSetId={}, 账期={}-{}, 待生成日记账数={}",
                    accountSetId, year, month, journals.size());

            if (journals.isEmpty()) {
                r.put("status", "done");
                r.put("msg", "银行流水凭证：当期无待生成的日记账");
                return r;
            }

            AccountSets accountSets = accountSetsRepository.findById(accountSetId).orElse(null);
            if (accountSets == null) {
                r.put("status", "error");
                r.put("msg", "银行流水凭证：账套不存在");
                return r;
            }

            // 缓存银行账户 → 关联科目ID
            QCashierAccount qAccount = QCashierAccount.cashierAccount;
            Map<Long, Long> accountSubjectMap = new HashMap<>();
            bqf.selectFrom(qAccount)
                    .where(qAccount.accountSetsId.eq(accountSetId)
                            .and(qAccount.subjectId.isNotNull()))
                    .fetch()
                    .forEach(a -> accountSubjectMap.put(a.getId(), a.getSubjectId()));

            String word = "记";
            VoucherWord defaultVW = bqf.selectFrom(QVoucherWord.voucherWord)
                    .where(QVoucherWord.voucherWord.accountSetsId.eq(accountSetId))
                    .orderBy(QVoucherWord.voucherWord.isDefault.desc())
                    .fetchFirst();
            if (defaultVW != null) word = defaultVW.getWord();

            DateTimeFormatter mmddFmt = DateTimeFormatter.ofPattern("MM-dd");
            int voucherCount = 0;
            int failCount = 0;
            int skipCount = 0;
            Map<String, Integer> codeMap = new HashMap<>();

            // 每条银行流水单独生成一张凭证（严格 1 借 1 贷）
            for (CashierJournal journal : journals) {
                if (journal.getSubjectId() == null) { skipCount++; continue; }
                Long bankSubjectId = accountSubjectMap.get(journal.getAccountId());
                if (bankSubjectId == null) {
                    log.warn("银行凭证跳过: journalId={}, 银行账户(id={})未设置关联科目",
                            journal.getId(), journal.getAccountId());
                    skipCount++;
                    continue;
                }

                try {
                    BigDecimal amount = journal.getDebit() != null && journal.getDebit().compareTo(BigDecimal.ZERO) > 0
                            ? journal.getDebit() : journal.getCredit();
                    boolean isIncome = journal.getDebit() != null && journal.getDebit().compareTo(BigDecimal.ZERO) > 0;

                    // 摘要 MM-DD 前缀来自原始交易日期；交易日期缺失时回退到 billDate
                    LocalDate prefixDate = journal.getTradeDate() != null
                            ? journal.getTradeDate() : journal.getBillDate();
                    String prefix = prefixDate != null ? prefixDate.format(mmddFmt) + " " : "";
                    String digest = StrUtil.nullToEmpty(journal.getDigest());
                    String summary = prefix + digest;

                    VoucherDetailsDto debit = new VoucherDetailsDto();
                    debit.setAccountSetsId(accountSetId);
                    debit.setSubjectId(isIncome ? bankSubjectId : journal.getSubjectId());
                    debit.setSummary(summary);
                    debit.setDebitAmount(amount);

                    VoucherDetailsDto credit = new VoucherDetailsDto();
                    credit.setAccountSetsId(accountSetId);
                    credit.setSubjectId(isIncome ? journal.getSubjectId() : bankSubjectId);
                    credit.setSummary(summary);
                    credit.setCreditAmount(amount);

                    List<VoucherDetailsDto> details = new ArrayList<>(2);
                    details.add(debit);
                    details.add(credit);

                    LocalDate voucherDate = journal.getBillDate();

                    Integer code = codeMap.get(word);
                    if (code == null) {
                        code = voucherService.loadCode(accountSetId, word, voucherDate);
                    } else {
                        code++;
                    }
                    codeMap.put(word, code);

                    VoucherDto voucherDto = new VoucherDto();
                    voucherDto.setAccountSetsId(accountSetId);
                    voucherDto.setWord(word);
                    voucherDto.setCode(code);
                    voucherDto.setVoucherDate(voucherDate);
                    voucherDto.setDetails(details);
                    voucherDto.setJournalIds(Collections.singletonList(journal.getId()));

                    self.generateBankVoucherInNewTx(voucherDto, accountSets);
                    voucherCount++;

                } catch (Exception e) {
                    failCount++;
                    log.warn("银行凭证生成失败: journalId={}, error={}", journal.getId(),
                            e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
                }
            }

            if (voucherCount > 0) {
                balanceCalcService.calc(accountSetId, YearMonth.of(year, month));
            }

            String status = failCount > 0 ? "warn" : "done";
            r.put("status", status);
            r.put("generated", voucherCount);
            r.put("msg", String.format("银行流水凭证：%d 笔流水生成 %d 张凭证（一借一贷），跳过 %d 条，失败 %d 笔",
                    journals.size(), voucherCount, skipCount, failCount));
            log.info("银行流水凭证完成: accountSetId={}, 流水={}, 凭证={}, 跳过={}, 失败={}",
                    accountSetId, journals.size(), voucherCount, skipCount, failCount);
        } catch (Exception e) {
            log.error("银行流水凭证处理失败: accountSetId={}", accountSetId, e);
            r.put("status", "error");
            r.put("msg", "银行流水凭证失败：" + e.getMessage());
        }
        return r;
    }

    /**
     * 在独立事务中生成银行凭证，失败不影响外层事务。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void generateBankVoucherInNewTx(VoucherDto voucherDto, AccountSets accountSets) {
        cashierJournalService.generateVoucher(voucherDto, accountSets);
    }

    /** 在独立事务中执行结转损益，失败不影响外层事务。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public Map<String, Object> executeCarryForwardInNewTx(Long accountSetId, int year, int month) {
        return taskCarryForwardService.execute(accountSetId, year, month);
    }

    /** 在独立事务中生成固定资产折旧凭证，失败不影响外层事务。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void createAssetDepreciationVoucherInNewTx(Long depreciationId, Long accountSetId) {
        assetsDepreciationService.autoCreateVoucher(depreciationId, accountSetId);
    }

    // ══════════════════════════════════════════════════════════════
    // 做账子任务详情查询（供前端弹窗展示明细表格）
    // ══════════════════════════════════════════════════════════════

    /**
     * 查询发票凭证详情：当期所有发票 + 凭证状态
     */
    public List<Map<String, Object>> queryInvoiceDetail(Long accountSetId, YearMonth ym) {
        QInvoice qi = QInvoice.invoice;
        LocalDate start = ym.atDay(1);
        LocalDate end = ym.atEndOfMonth();

        List<Invoice> invoices = bqf.selectFrom(qi)
                .where(qi.accountSetsId.eq(accountSetId)
                        .and(qi.assignmentDate.between(start, end)
                                .or(qi.assignmentDate.isNull()
                                        .and(qi.tradeDate.between(start, end)))))
                .orderBy(qi.type.asc(), qi.tradeDate.asc())
                .fetch();

        // 批量查业务类型名
        Map<Long, String> btNames = new HashMap<>();
        invoices.stream().map(Invoice::getInvoiceBusinessTypeId).filter(Objects::nonNull).distinct().forEach(btId -> {
            bqf.selectFrom(QInvoiceBusinessType.invoiceBusinessType)
                    .where(QInvoiceBusinessType.invoiceBusinessType.id.eq(btId))
                    .fetchFirst();
        });
        QInvoiceBusinessType qbt = QInvoiceBusinessType.invoiceBusinessType;
        bqf.selectFrom(qbt).where(qbt.accountSetsId.eq(accountSetId)).fetch()
                .forEach(bt -> btNames.put(bt.getId(), bt.getTitle()));

        // 批量查凭证号
        Map<Long, String> voucherCodes = new HashMap<>();
        invoices.stream().map(Invoice::getVoucherId).filter(Objects::nonNull).distinct().forEach(vid -> {
            Voucher v = bqf.selectFrom(QVoucher.voucher)
                    .where(QVoucher.voucher.accountSetsId.eq(accountSetId)
                            .and(QVoucher.voucher.id.eq(vid)))
                    .fetchFirst();
            if (v != null) voucherCodes.put(vid, v.getWord() + "-" + v.getCode());
        });

        // 批量查商品明细（invoiceId → items）
        Map<Long, List<Map<String, Object>>> itemsMap = new HashMap<>();
        if (!invoices.isEmpty()) {
            List<Long> invoiceIds = invoices.stream().map(Invoice::getId).toList();
            QInvoiceItems qii = QInvoiceItems.invoiceItems;
            bqf.selectFrom(qii).where(qii.invoiceId.in(invoiceIds)).fetch().forEach(item -> {
                Map<String, Object> im = new LinkedHashMap<>();
                im.put("goodsName", item.getGoodsName());
                im.put("specification", item.getSpecification());
                im.put("unit", item.getUnit());
                im.put("quantity", item.getQuantity());
                im.put("amount", item.getAmount());
                im.put("taxRate", item.getTaxRate());
                im.put("tax", item.getTax());
                itemsMap.computeIfAbsent(item.getInvoiceId(), k -> new ArrayList<>()).add(im);
            });
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Invoice inv : invoices) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", inv.getId());
            row.put("type", inv.getType() != null ? inv.getType().name() : "");
            row.put("typeLabel", inv.getType() == InvoiceType.IN ? "进项" : "销项");
            row.put("invoiceCode", inv.getInvoiceCode());
            row.put("invoiceNumber", inv.getInvoiceNumber());
            row.put("customerName", inv.getCustomerName());
            row.put("amount", inv.getAmount());
            row.put("taxAmount", inv.getTaxAmount());
            row.put("tradeDate", inv.getTradeDate());
            row.put("businessType", inv.getInvoiceBusinessTypeId() != null
                    ? btNames.getOrDefault(inv.getInvoiceBusinessTypeId(), "未知") : null);
            row.put("voucherId", inv.getVoucherId());
            row.put("voucherCode", inv.getVoucherId() != null
                    ? voucherCodes.getOrDefault(inv.getVoucherId(), "") : null);
            row.put("items", itemsMap.getOrDefault(inv.getId(), List.of()));
            // 状态判定
            String status;
            if (inv.getVoucherId() != null) status = "done";
            else if (inv.getInvoiceBusinessTypeId() != null) status = "matched";
            else status = "unmatched";
            row.put("status", status);
            rows.add(row);
        }
        return rows;
    }

    /**
     * 查询银行凭证详情：当期日记账 + 凭证状态
     */
    public List<Map<String, Object>> queryBankDetail(Long accountSetId, YearMonth ym) {
        QCashierJournal qj = QCashierJournal.cashierJournal;
        LocalDate start = ym.atDay(1);
        LocalDate end = ym.atEndOfMonth();

        List<CashierJournal> journals = bqf.selectFrom(qj)
                .where(qj.accountSetsId.eq(accountSetId)
                        .and(qj.billDate.between(start, end)))
                .orderBy(qj.billDate.asc())
                .fetch();

        // 批量查科目名
        Map<Long, String> subjectNames = new HashMap<>();
        QSubject qs = QSubject.subject;
        bqf.selectFrom(qs).where(qs.accountSetsId.eq(accountSetId)).fetch()
                .forEach(s -> subjectNames.put(s.getId(), s.getFullName() != null ? s.getFullName() : s.getName()));

        // 批量查凭证号
        Map<Long, String> voucherCodes = new HashMap<>();
        journals.stream().map(CashierJournal::getVoucherId).filter(Objects::nonNull).distinct().forEach(vid -> {
            Voucher v = bqf.selectFrom(QVoucher.voucher)
                    .where(QVoucher.voucher.accountSetsId.eq(accountSetId)
                            .and(QVoucher.voucher.id.eq(vid)))
                    .fetchFirst();
            if (v != null) voucherCodes.put(vid, v.getWord() + "-" + v.getCode());
        });

        List<Map<String, Object>> rows = new ArrayList<>();
        for (CashierJournal j : journals) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", j.getId());
            row.put("billDate", j.getBillDate());
            row.put("digest", j.getDigest());
            row.put("oppositeAccountName", j.getOppositeAccountName());
            row.put("debit", j.getDebit());
            row.put("credit", j.getCredit());
            row.put("subjectName", j.getSubjectId() != null
                    ? subjectNames.getOrDefault(j.getSubjectId(), "") : null);
            row.put("voucherId", j.getVoucherId());
            row.put("voucherCode", j.getVoucherId() != null
                    ? voucherCodes.getOrDefault(j.getVoucherId(), "") : null);
            // 状态
            String status;
            if (j.getVoucherId() != null) status = "done";
            else if (j.getSubjectId() != null) status = "ready";
            else status = "no_template";
            row.put("status", status);
            rows.add(row);
        }
        return rows;
    }

    /**
     * 查询工资凭证详情
     */
    public List<Map<String, Object>> querySalaryDetail(Long accountSetId, YearMonth ym) {
        List<Map<String, Object>> rows = new ArrayList<>();
        try {
            var templates = salaryVoucherTemplateService.list(accountSetId);
            if (templates.isEmpty()) return rows;

            // 查询本期已生成的工资凭证记录（templateId → SalaryVoucher）
            QSalaryVoucher qsv = QSalaryVoucher.salaryVoucher;
            List<Long> tplIds = templates.stream().map(t -> t.getId()).toList();
            Map<Long, SalaryVoucher> svMap = bqf.selectFrom(qsv)
                    .where(qsv.accountSetsId.eq(accountSetId)
                            .and(qsv.templateId.in(tplIds))
                            .and(qsv.year.eq(ym.getYear()))
                            .and(qsv.month.eq(ym.getMonthValue())))
                    .fetch().stream()
                    .collect(java.util.stream.Collectors.toMap(
                            SalaryVoucher::getTemplateId, sv -> sv,
                            (a, b) -> a)); // 同一模板同期理论上只有一条，取先出现的

            // 批量查凭证号
            Set<Long> voucherIds = svMap.values().stream()
                    .map(SalaryVoucher::getVoucherId).filter(Objects::nonNull)
                    .collect(java.util.stream.Collectors.toSet());
            Map<Long, String> voucherCodes = new HashMap<>();
            voucherIds.forEach(vid -> {
                Voucher v = bqf.selectFrom(QVoucher.voucher)
                        .where(QVoucher.voucher.accountSetsId.eq(accountSetId)
                                .and(QVoucher.voucher.id.eq(vid)))
                        .fetchFirst();
                if (v != null) voucherCodes.put(vid, v.getWord() + "-" + v.getCode());
            });

            for (var tpl : templates) {
                SalaryVoucher sv = svMap.get(tpl.getId());
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", tpl.getId());
                row.put("title", tpl.getTitle());
                row.put("isAccrual", Boolean.TRUE.equals(tpl.getIsAccrual()) ? "计提" : "发放");
                if (sv != null && sv.getVoucherId() != null) {
                    row.put("status", "done");
                    row.put("voucherId", sv.getVoucherId());
                    row.put("voucherCode", voucherCodes.getOrDefault(sv.getVoucherId(), ""));
                    row.put("amount", sv.getAmount());
                } else {
                    row.put("status", "pending");
                    row.put("voucherId", null);
                    row.put("voucherCode", null);
                    row.put("amount", null);
                }
                rows.add(row);
            }
        } catch (Exception e) {
            log.warn("查询工资凭证详情失败: {}", e.getMessage());
        }
        return rows;
    }

    /**
     * 查询折旧凭证详情
     */
    public List<Map<String, Object>> queryAssetDetail(Long accountSetId, YearMonth ym) {
        List<Map<String, Object>> rows = new ArrayList<>();
        try {
            int year = ym.getYear();
            int month = ym.getMonthValue();
            QAssetsDepreciation qd = QAssetsDepreciation.assetsDepreciation;
            List<AssetsDepreciation> records = bqf.selectFrom(qd)
                    .where(qd.accountSetsId.eq(accountSetId)
                            .and(qd.checkYear.eq(year))
                            .and(qd.checkMonth.eq(month)))
                    .fetch();

            // 查凭证号
            Map<Long, String> voucherCodes = new HashMap<>();
            records.stream().map(AssetsDepreciation::getVoucherId).filter(Objects::nonNull).distinct().forEach(vid -> {
                Voucher v = bqf.selectFrom(QVoucher.voucher)
                        .where(QVoucher.voucher.accountSetsId.eq(accountSetId)
                                .and(QVoucher.voucher.id.eq(vid)))
                        .fetchFirst();
                if (v != null) voucherCodes.put(vid, v.getWord() + "-" + v.getCode());
            });

            for (AssetsDepreciation d : records) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", d.getId());
                row.put("checkDate", d.getCheckDate());
                row.put("status", d.getVoucherId() != null ? "done" : "pending");
                row.put("voucherId", d.getVoucherId());
                row.put("voucherCode", d.getVoucherId() != null
                        ? voucherCodes.getOrDefault(d.getVoucherId(), "") : null);
                rows.add(row);
            }
        } catch (Exception e) {
            log.warn("查询折旧凭证详情失败: {}", e.getMessage());
        }
        return rows;
    }

    private String buildOverallMsg(String status) {
        return switch (status) {
            case "done" -> "做账完成";
            case "warn" -> "部分凭证生成有跳过项或失败，请查看详情";
            case "error" -> "做账部分失败，请查看详情";
            default -> "做账执行完毕";
        };
    }

    // ═══════════════════════════════════════════════════════════════════
    // 代缴税费凭证（读取 vat_declare_pre 回调数据）
    // ═══════════════════════════════════════════════════════════════════

    /** 税种名称 → 借方科目 fullName 关键词 */
    private static final Map<String, String> TAX_DEBIT_SUBJECT_MAP = Map.of(
            "增值税",       "应交税费-应交增值税",
            "城市维护建设税", "应交税费-应交城市维护建设税",
            "城建税",       "应交税费-应交城市维护建设税",
            "教育费附加",    "应交税费-应交教育费附加",
            "地方教育附加",  "应交税费-应交地方教育附加",
            "地方教育费附加", "应交税费-应交地方教育附加"
    );

    private static final String TAX_CREDIT_SUBJECT_KEYWORD = "银行存款";

    /**
     * 根据 vat_declare_pre 回调保存的税费数据生成代缴税费凭证。
     * 每个税种一张凭证：借 应交税费-XX  贷 银行存款
     */
    private Map<String, Object> generateTaxFeeVouchers(Long accountSetId, int year, int month) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("type", "tax");

        YearMonth ym = YearMonth.of(year, month);
        // 查找该账期的 task_vat_pre 数据
        var tpOpt = taskPeriodRepository.findByAccountSetIdAndPeriod(accountSetId, ym.format(PERIOD_FMT));
        if (tpOpt.isEmpty()) {
            r.put("status", "skipped");
            r.put("msg", "代缴税费：无任务周期记录");
            return r;
        }
        List<cn.gson.financial.kernel.model.entity.TaskVatPre> vatPreItems =
                taskVatPreRepository.findByTaskPeriodIdAndStatus(tpOpt.get().getId(), "done");
        if (vatPreItems.isEmpty()) {
            r.put("status", "skipped");
            r.put("msg", "代缴税费：无做账前申报数据，跳过");
            return r;
        }

        // 过滤金额为 0 的，以及税目包含"税务部门罚没收入"的
        vatPreItems = vatPreItems.stream()
                .filter(v -> v.getTaxPayable() != null && v.getTaxPayable().compareTo(java.math.BigDecimal.ZERO) > 0)
                .filter(v -> v.getTaxItem() == null || !v.getTaxItem().contains("税务部门罚没收入"))
                .collect(Collectors.toList());
        if (vatPreItems.isEmpty()) {
            r.put("status", "done");
            r.put("msg", "代缴税费：各税种金额均为0，无需生成凭证");
            r.put("generated", 0);
            return r;
        }

        AccountSets accountSets = accountSetsRepository.findById(accountSetId).orElse(null);
        if (accountSets == null) {
            r.put("status", "error");
            r.put("msg", "账套不存在");
            return r;
        }

        // 查找贷方科目（银行存款）
        Long creditSubjectId = findSubjectByKeyword(accountSetId, TAX_CREDIT_SUBJECT_KEYWORD);
        if (creditSubjectId == null) {
            r.put("status", "error");
            r.put("msg", "代缴税费：未找到[银行存款]科目");
            return r;
        }

        int generated = 0;
        int failed = 0;
        LocalDate voucherDate = ym.atEndOfMonth();

        for (var vatPre : vatPreItems) {
            // 按税种名称匹配借方科目
            String debitKeyword = null;
            for (var entry : TAX_DEBIT_SUBJECT_MAP.entrySet()) {
                if (vatPre.getTaxName().contains(entry.getKey())) {
                    debitKeyword = entry.getValue();
                    break;
                }
            }
            if (debitKeyword == null) {
                log.warn("代缴税费：未匹配到借方科目, taxName={}", vatPre.getTaxName());
                failed++;
                continue;
            }
            Long debitSubjectId = findSubjectByKeyword(accountSetId, debitKeyword);
            if (debitSubjectId == null) {
                log.warn("代缴税费：借方科目未找到, keyword={}, taxName={}", debitKeyword, vatPre.getTaxName());
                failed++;
                continue;
            }

            try {
                VoucherDetailsDto debit = new VoucherDetailsDto();
                debit.setAccountSetsId(accountSetId);
                debit.setSubjectId(debitSubjectId);
                debit.setSummary("缴纳" + vatPre.getTaxName());
                debit.setDebitAmount(vatPre.getTaxPayable());

                VoucherDetailsDto credit = new VoucherDetailsDto();
                credit.setAccountSetsId(accountSetId);
                credit.setSubjectId(creditSubjectId);
                credit.setSummary("缴纳" + vatPre.getTaxName());
                credit.setCreditAmount(vatPre.getTaxPayable());

                VoucherDto voucherDto = new VoucherDto();
                voucherDto.setAccountSetsId(accountSetId);
                voucherDto.setVoucherDate(voucherDate);
                voucherDto.setDetails(List.of(debit, credit));

                self.generateTaxVoucherInNewTx(voucherDto, accountSets);
                generated++;
            } catch (Exception e) {
                log.error("代缴税费凭证生成失败: taxName={}, accountSetId={}", vatPre.getTaxName(), accountSetId, e);
                failed++;
            }
        }

        String status = failed > 0 ? (generated > 0 ? "warn" : "error") : "done";
        r.put("status", status);
        r.put("generated", generated);
        r.put("msg", String.format("代缴税费凭证：共 %d 个税种，生成 %d 张凭证，失败 %d 张", vatPreItems.size(), generated, failed));
        log.info("代缴税费凭证: accountSetId={}, 账期={}-{}, generated={}, failed={}", accountSetId, year, month, generated, failed);
        return r;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void generateTaxVoucherInNewTx(VoucherDto voucherDto, AccountSets accountSets) {
        voucherService.save(voucherDto, accountSets, true);
    }

    /** 按 fullName 关键词模糊查找科目，返回最匹配的科目 ID */
    private Long findSubjectByKeyword(Long accountSetId, String keyword) {
        var qSubject = cn.gson.financial.kernel.model.entity.QSubject.subject;
        return bqf.selectFrom(qSubject)
                .select(qSubject.id)
                .where(qSubject.accountSetsId.eq(accountSetId)
                        .and(qSubject.fullName.containsIgnoreCase(keyword)))
                .orderBy(qSubject.level.desc()) // 优先匹配最具体的科目
                .fetchFirst();
    }
}
