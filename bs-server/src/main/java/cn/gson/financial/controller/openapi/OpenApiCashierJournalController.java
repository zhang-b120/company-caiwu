package cn.gson.financial.controller.openapi;

import cn.dev33.satoken.annotation.SaIgnore;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.model.dto.CashierAccountDto;
import cn.gson.financial.kernel.model.dto.CashierIoTypeDto;
import cn.gson.financial.kernel.model.entity.AccountSets;
import cn.gson.financial.kernel.model.entity.CashierAccount;
import cn.gson.financial.kernel.model.entity.CashierIoType;
import cn.gson.financial.kernel.model.entity.CashierJournal;
import cn.gson.financial.kernel.model.entity.QAccountSets;
import cn.gson.financial.kernel.model.entity.QCashierAccount;
import cn.gson.financial.kernel.model.entity.QCashierJournal;
import cn.gson.financial.kernel.model.entity.QSubject;
import cn.gson.financial.kernel.model.entity.QVoucher;
import cn.gson.financial.kernel.model.entity.Subject;
import cn.gson.financial.kernel.model.dto.VoucherDetailsDto;
import cn.gson.financial.kernel.model.dto.VoucherDto;
import cn.gson.financial.kernel.model.hibernate.BlazeJPAQueryFactory;
import cn.gson.financial.kernel.service.CashierAccountService;
import cn.gson.financial.kernel.service.CashierIoTypeService;
import cn.gson.financial.kernel.service.CashierJournalService;
import cn.gson.financial.kernel.service.CheckoutService;
import cn.gson.financial.kernel.service.CurrencyService;
import cn.gson.financial.kernel.service.SubjectService;
import cn.gson.financial.kernel.service.VoucherService;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ReUtil;
import cn.hutool.core.util.StrUtil;
import com.querydsl.core.BooleanBuilder;
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
import java.util.*;

/**
 * 开放 API — 银行流水批量写「银行日记账」（cashier_journal）
 * 路径前缀 /openapi/v1/cashier-journal
 * <p>
 * FN-NEW-OPENAPI-CASHIER-JOURNAL-BATCH：XW 解析银行流水后调本接口，直接写进
 * 「资金 → 日记账 → 银行日记账」，让会计能在日记账页面看到 + 点「生成凭证」。
 * 复用 web 端 {@code CashierJournalController.importVoucher} 的核心能力
 * （摘要关键字自动匹配收支类别 + CashierJournalService.save 落账），改成 OpenAPI 版
 * （参数传 accountSetsId，不走 @SessionAccountSet）。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/openapi/v1/cashier-journal")
@SaIgnore
public class OpenApiCashierJournalController {

    private final CashierAccountService cashierAccountService;
    private final CashierIoTypeService cashierIoTypeService;
    private final CashierJournalService cashierJournalService;
    private final CheckoutService checkoutService;

    @Resource
    @Lazy
    private BlazeJPAQueryFactory bqf;

    // FN-NEW-AUTOCREATE-BANK-ACCOUNT：账户不存在时自动建账户所需（@Resource 非构造器注入，
    // 避免改 @RequiredArgsConstructor 签名打断既有单测）
    @Resource
    private SubjectService subjectService;

    @Resource
    private CurrencyService currencyService;

    // FN-NEW-OPENAPI-CASHIER-JOURNAL-GEN-VOUCHER：从银行日记账生成凭证（复用 web 端 generateVoucher）
    @Resource
    private VoucherService voucherService;

    private static final QAccountSets qAccountSets = QAccountSets.accountSets;
    private static final QCashierJournal qCashierJournal = QCashierJournal.cashierJournal;
    private static final QSubject qSubject = QSubject.subject;
    private static final QCashierAccount qCashierAccount = QCashierAccount.cashierAccount;
    // FN-FIX-VOUCHER-DELETE-CASCADE：generate-vouchers 幂等检查需要 JOIN voucher 验真
    private static final QVoucher qVoucher = QVoucher.voucher;

    /** 默认收支类别编码（importVoucher 同款兜底） */
    private static final String DEFAULT_IO_TYPE_CODE = "1";

    @PostMapping("/batch")
    @Transactional
    public JsonResult batch(@RequestBody CashierJournalBatchReq req) {
        if (req == null) return JsonResult.failure("请求体不能为空");
        if (req.getAccountSetsId() == null) return JsonResult.failure("accountSetsId 不能为空");
        if (CollUtil.isEmpty(req.getTransactions())) return JsonResult.failure("transactions 不能为空");

        AccountSets accountSets = findAccountSet(req.getAccountSetsId());
        if (accountSets == null) {
            return JsonResult.failure("账套不存在：" + req.getAccountSetsId());
        }

        // 1. 匹配银行账户（只在「银行存款」类账户里匹配，见 FN-FIX-CASHIER-MATCH-BANK-TYPE）
        CashierAccountDto account = matchBankAccount(req.getAccountSetsId(), req.getBankName(), req.getAccountNumber());

        // FN-NEW-AUTOCREATE-BANK-ACCOUNT：匹配不到 → 自动建一个「待确认」银行账户，不再中断整批
        Long accountId;
        Map<String, Object> autoCreatedInfo = null;
        if (account == null) {
            BigDecimal suggestedOpening = computeSuggestedOpeningBalance(req.getTransactions());
            CashierAccount created;
            try {
                created = autoCreateBankAccount(accountSets, req.getBankName(), req.getAccountNumber(), suggestedOpening);
            } catch (Exception e) {
                log.error("[FN-NEW-AUTOCREATE-BANK-ACCOUNT] 自动建银行账户失败 accountSetsId={} bankName={}",
                        req.getAccountSetsId(), req.getBankName(), e);
                return failureWithType(
                        "未匹配到银行账户且自动创建失败：" + e.getMessage()
                                + "（请在 financial「资金 → 账户设置 → 银行存款」手工建该账户）",
                        "BANK_ACCOUNT_NOT_FOUND");
            }
            accountId = created.getId();
            autoCreatedInfo = new LinkedHashMap<>();
            autoCreatedInfo.put("id", created.getId());
            autoCreatedInfo.put("name", created.getName());
            autoCreatedInfo.put("cardNumber", created.getCardNumber());
            autoCreatedInfo.put("suggestedOpeningBalance", suggestedOpening);
            autoCreatedInfo.put("pendingConfirm", true);

            // FN-NEW-AUTOCREATE-BANK-ACCOUNT 审查反馈（2026-05-22）：
            // 1002 缺失 → subjectId=null 不能静默，否则 generate-vouchers 会因 bankSubjectId==null 整批跳过。
            // 在 autoCreatedInfo 里加 subjectMissing 信号 + 提示文案，让 XW/BT/会计能感知。
            if (created.getSubjectId() == null) {
                autoCreatedInfo.put("subjectMissing", true);
                autoCreatedInfo.put("subjectMissingMessage",
                        "账套缺「1002 银行存款」科目，账户已建但未关联对方科目，"
                                + "日记账无法生成凭证。请先调 POST /openapi/v1/subjects/ensure 建标准科目（key=BANK_DEPOSIT），"
                                + "或在 financial「科目设置」手工新建 1002 末级，"
                                + "再到「资金→账户设置」给本账户挂上对方科目。");
                log.warn("[FN-NEW-AUTOCREATE-BANK-ACCOUNT] 账套 {} 自动建银行账户 id={} 时 1002 科目缺失，subjectId 留空 —— "
                                + "日记账生成凭证会被跳过，请先 ensure 1002 标准科目或手工补",
                        req.getAccountSetsId(), created.getId());
            } else {
                autoCreatedInfo.put("subjectMissing", false);
                log.info("[FN-NEW-AUTOCREATE-BANK-ACCOUNT] 账套 {} 自动建银行账户 id={} name={} subjectId={} 期初建议={}",
                        req.getAccountSetsId(), created.getId(), created.getName(),
                        created.getSubjectId(), suggestedOpening);
            }
        } else {
            accountId = account.getId();
        }

        // 2. 收支类别列表（含 matchWords 智能匹配）
        List<CashierIoTypeDto> ioTypeDtos = cashierIoTypeService.list(req.getAccountSetsId(), null);
        List<CashierIoTypeDto> matchWordsIoTypes = ioTypeDtos.stream()
                .filter(it -> CollUtil.isNotEmpty(it.getMatchWords()))
                .toList();

        // 3. 已结账期间下限（参考 importVoucher）
        LocalDate lastCheckDate = checkoutService.lastCompletedCheckDate(req.getAccountSetsId());

        int inserted = 0;
        int duplicated = 0;
        int subjectMatchedCount = 0;
        List<Long> journalIds = new ArrayList<>();
        List<Map<String, Object>> errors = new ArrayList<>();

        for (int i = 0; i < req.getTransactions().size(); i++) {
            TransactionDto tx = req.getTransactions().get(i);
            try {
                if (tx.getBillDate() == null) {
                    errors.add(rowError(i, "billDate 不能为空"));
                    continue;
                }
                BigDecimal amount = tx.getAmount();
                if (amount == null || amount.signum() == 0) {
                    errors.add(rowError(i, "amount 不能为空或 0"));
                    continue;
                }
                String dir = tx.getDirection();
                if (!"credit".equalsIgnoreCase(dir) && !"debit".equalsIgnoreCase(dir)) {
                    errors.add(rowError(i, "direction 必须是 credit 或 debit：" + dir));
                    continue;
                }

                // 已结账期间拒绝
                if (lastCheckDate != null
                        && !lastCheckDate.withDayOfMonth(lastCheckDate.lengthOfMonth()).isBefore(tx.getBillDate())) {
                    errors.add(rowError(i, tx.getBillDate() + " 属于已结账期间，不允许写入日记账"));
                    continue;
                }

                String digest = StrUtil.isNotBlank(tx.getSummary()) ? tx.getSummary() : tx.getCounterName();

                // 去重
                if (isDuplicate(req.getAccountSetsId(), accountId, tx, digest, amount)) {
                    duplicated++;
                    continue;
                }

                // 方向：credit(银行收钱)=收入→借方 debit；debit(银行付钱)=支出→贷方 credit
                boolean isIncome = "credit".equalsIgnoreCase(dir);
                CashierIoType.Type requiredType = isIncome ? CashierIoType.Type.收 : CashierIoType.Type.支;

                // FN-NEW-CASHIER-JOURNAL-SUBJECT / FN-NEW-CASHIER-IO-TYPE-LIST
                // 收支类别匹配优先级：ioTypeCode（编码精确）> ioType（名字兜底）> 摘要 matchWords > 默认
                Long ioTypeId = resolveIoTypeIdByCode(ioTypeDtos, tx.getIoTypeCode(), requiredType);
                if (ioTypeId == null) {
                    ioTypeId = resolveIoTypeIdByName(ioTypeDtos, tx.getIoType(), requiredType);
                }
                if (ioTypeId == null) {
                    ioTypeId = resolveIoTypeId(digest, requiredType, matchWordsIoTypes, ioTypeDtos);
                }
                // 对方科目：subjectCode 命中末级科目才落 subjectId；查不到/非末级 → 留空 + warning（不阻断）
                Long subjectId = null;
                String subjectName = null;
                if (StrUtil.isNotBlank(tx.getSubjectCode())) {
                    Subject subject = findSubjectByCode(req.getAccountSetsId(), tx.getSubjectCode());
                    if (subject == null) {
                        errors.add(rowError(i, "对方科目编码不存在，已留空 subjectId：" + tx.getSubjectCode()));
                    } else if (!Boolean.TRUE.equals(subject.getLeaf())) {
                        errors.add(rowError(i, "对方科目非末级，已留空 subjectId：" + tx.getSubjectCode()));
                    } else {
                        subjectId = subject.getId();
                        subjectName = subject.getName();
                    }
                }

                CashierJournal journal = new CashierJournal();
                journal.setAccountSetsId(req.getAccountSetsId());
                journal.setAccountId(accountId);
                journal.setType(CashierAccount.CashierAccountType.银行存款);
                journal.setBillDate(tx.getBillDate());
                journal.setTradeDate(tx.getBillDate());
                journal.setImportTime(LocalDateTime.now());
                journal.setDigest(digest);
                journal.setOppositeAccountName(tx.getCounterName());
                journal.setTransactionPostscript(StrUtil.isNotBlank(tx.getSummary()) ? tx.getSummary() : null);
                journal.setBalance(tx.getBalance());
                // serialNo 落到 billNo（单据号）—— cashier_journal 无专用 serialNo 列，借此持久化做去重 key
                // 全零（如"000000000"）是 XW 的占位符，不是真实流水号，不落库
                journal.setBillNo(isPlaceholderSerialNo(tx.getSerialNo()) ? null : tx.getSerialNo());
                if (ioTypeId != null) journal.setIoTypeId(ioTypeId);
                if (subjectId != null) journal.setSubjectId(subjectId);
                // AI 分类置信度 + 依据拼进备注（MVP 不加新列）
                String aiRemark = buildAiRemark(subjectName, tx.getConfidence(), tx.getClassifyNote());
                if (aiRemark != null) journal.setRemark(aiRemark);
                journal.setInit(false);
                // 方向落 debit/credit（实体 setDebit/setCredit 互斥，传 0 会被忽略，故用 abs 正值）
                BigDecimal abs = amount.abs();
                if (isIncome) {
                    journal.setDebit(abs);
                } else {
                    journal.setCredit(abs);
                }

                CashierJournal saved = cashierJournalService.save(journal, accountSets.getEnableDate());
                journalIds.add(saved.getId());
                inserted++;
                if (subjectId != null) subjectMatchedCount++;
            } catch (Exception e) {
                log.warn("[FN-NEW-OPENAPI-CASHIER-JOURNAL-BATCH] 第 {} 笔写入失败: {}", i, e.getMessage());
                errors.add(rowError(i, e.getMessage()));
            }
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("inserted", inserted);
        data.put("duplicated", duplicated);
        data.put("accountId", accountId);
        data.put("journalIds", journalIds);
        data.put("subjectMatchedCount", subjectMatchedCount);
        data.put("autoCreatedAccount", autoCreatedInfo);   // 未触发自动创建时为 null
        data.put("errors", errors);
        return JsonResult.successful(data);
    }

    // ═══════════════════════════════════════════════════════════════
    // 辅助方法（package-private 便于单测 override 规避 BlazeJPAQuery）
    // ═══════════════════════════════════════════════════════════════

    /** 账套查询（测试 hook） */
    AccountSets findAccountSet(Long accountSetsId) {
        return bqf.selectFrom(qAccountSets)
                .where(qAccountSets.id.eq(accountSetsId))
                .fetchFirst();
    }

    /**
     * 按 bankName / accountNumber 在该账套的银行账户里匹配。
     * 优先精确匹配 cardNumber（账号唯一性最强），其次 name 含 bankName。
     */
    CashierAccountDto matchBankAccount(Long accountSetsId, String bankName, String accountNumber) {
        List<CashierAccountDto> accounts = cashierAccountService.list(accountSetsId, null);
        if (CollUtil.isEmpty(accounts)) return null;

        // FN-FIX-CASHIER-MATCH-BANK-TYPE（2026-05-20）：只在「银行存款」类账户里匹配。
        // 之前取全部账户、仅按名字/账号匹配，不看类型 —— 把误建成「现金」类型的
        // 银行账户也匹配上了，导致银行流水被静默写进「现金日记账」。现金账户必须排除。
        List<CashierAccountDto> bankAccounts = accounts.stream()
                .filter(a -> CashierAccount.CashierAccountType.银行存款.name().equals(a.getType()))
                .toList();
        if (CollUtil.isEmpty(bankAccounts)) return null;

        // 1) 账号精确匹配（最可靠）
        if (StrUtil.isNotBlank(accountNumber)) {
            String an = normalizeAccountNumber(accountNumber);
            for (CashierAccountDto a : bankAccounts) {
                if (StrUtil.isNotBlank(a.getCardNumber())
                        && normalizeAccountNumber(a.getCardNumber()).equals(an)) {
                    return a;
                }
            }
        }
        // 2) bankName 匹配账户名称（含即可）
        if (StrUtil.isNotBlank(bankName)) {
            for (CashierAccountDto a : bankAccounts) {
                if (StrUtil.isNotBlank(a.getName())
                        && (a.getName().contains(bankName) || bankName.contains(a.getName()))) {
                    return a;
                }
            }
        }
        return null;
    }

    /** 账号比对时去掉空格 / 横杠等分隔符，避免 "22-829901" vs "22829901" 不匹配 */
    private static String normalizeAccountNumber(String s) {
        return s == null ? "" : s.replaceAll("[\\s\\-]", "");
    }

    /**
     * 解析收支类别：digest 命中某个配了 matchWords 且类型与方向一致的 ioType → 用之；
     * 否则取默认 code "1" 同类型的；都没有返 null（CashierJournalService.save 会按 debit/credit 推余额）。
     */
    Long resolveIoTypeId(String digest, CashierIoType.Type requiredType,
                         List<CashierIoTypeDto> matchWordsIoTypes, List<CashierIoTypeDto> allIoTypes) {
        if (StrUtil.isNotBlank(digest)) {
            for (CashierIoTypeDto w : matchWordsIoTypes) {
                if (w.getType() == requiredType
                        && ReUtil.isMatch(CollUtil.join(w.getMatchWords(), "|"), digest)) {
                    return w.getId();
                }
            }
        }
        for (CashierIoTypeDto t : allIoTypes) {
            if (DEFAULT_IO_TYPE_CODE.equals(t.getCode()) && t.getType() == requiredType) {
                return t.getId();
            }
        }
        return null;
    }

    // ═══════════════════════════════════════════════════════════════
    // FN-NEW-AUTOCREATE-BANK-ACCOUNT：账户不存在时自动建「待确认」银行账户
    // ═══════════════════════════════════════════════════════════════

    /**
     * 自动创建一个 type=银行存款 的 CashierAccount（auto_created=1 待确认）。
     * 字段：name=bankName(空则「未识别银行」) / cardNumber=accountNumber / code=银行存款类下一个可用编码
     * / subjectId=1002 末级(必要时自动建子科目) / currencyId=本位币 / balance=期初建议值。
     */
    CashierAccount autoCreateBankAccount(AccountSets accountSets, String bankName, String accountNumber,
                                         BigDecimal suggestedOpening) {
        Long accountSetsId = accountSets.getId();
        CashierAccount acc = new CashierAccount();
        acc.setAccountSetsId(accountSetsId);
        acc.setType(CashierAccount.CashierAccountType.银行存款);
        acc.setName(StrUtil.isNotBlank(bankName) ? bankName : "未识别银行");
        acc.setCardNumber(accountNumber);
        acc.setCode(nextBankAccountCode(accountSetsId));
        acc.setSubjectId(resolveBankSubjectId(accountSets, acc.getName()));
        acc.setCurrencyId(currencyService.fetchLocal(accountSetsId).getId());
        acc.setBalance(suggestedOpening != null ? suggestedOpening : BigDecimal.ZERO);
        acc.setAutoCreated(true);
        return cashierAccountService.save(acc);
    }

    /** 银行存款类账户下一个可用编码（001/002/...，与现金类编码空间独立，靠 type+code 唯一键隔离） */
    String nextBankAccountCode(Long accountSetsId) {
        List<CashierAccountDto> accounts = cashierAccountService.list(accountSetsId, null);
        int max = 0;
        if (CollUtil.isNotEmpty(accounts)) {
            for (CashierAccountDto a : accounts) {
                if (!CashierAccount.CashierAccountType.银行存款.name().equals(a.getType())) continue;
                if (a.getCode() != null && a.getCode().matches("\\d+")) {
                    try { max = Math.max(max, Integer.parseInt(a.getCode())); } catch (NumberFormatException ignore) {}
                }
            }
        }
        return String.format("%03d", max + 1);
    }

    /**
     * 解析自动建账户应挂的「1002 银行存款」末级科目 id。
     * 1002 末级 → 直接用；1002 非末级 → 在其下自动建一个末级子科目（名称=bankName），
     * 否则日记账生成凭证时会因「1002 非末级」被拒。1002 不存在 → 返 null（账户仍建，subject 留空待会计补）。
     */
    Long resolveBankSubjectId(AccountSets accountSets, String bankName) {
        Subject bank = findSubjectByCode(accountSets.getId(), "1002");
        if (bank == null) return null;
        if (Boolean.TRUE.equals(bank.getLeaf())) return bank.getId();
        return createBankChildSubject(accountSets, bank, bankName);
    }

    /** 在非末级的 1002 下建一个末级子科目（编码顺延、名称=childName），返回新科目 id */
    Long createBankChildSubject(AccountSets accountSets, Subject parent, String childName) {
        Long accountSetsId = accountSets.getId();
        String maxChild = subjectService.getMaxChildCode(parent.getId(), accountSetsId);
        int segLen = (accountSets.getEncoding() != null && accountSets.getEncoding().size() > 1)
                ? accountSets.getEncoding().get(1) : 2;
        String parentCode = parent.getCode();
        String nextCode;
        if (StrUtil.isBlank(maxChild) || maxChild.length() <= parentCode.length()) {
            nextCode = parentCode + String.format("%0" + segLen + "d", 1);
        } else {
            String seg = maxChild.substring(parentCode.length());
            long next = 1;
            try { next = Long.parseLong(seg) + 1; } catch (NumberFormatException ignore) {}
            nextCode = parentCode + String.format("%0" + segLen + "d", next);
        }
        int encLen = 0;
        if (accountSets.getEncoding() != null) {
            for (Integer seg : accountSets.getEncoding()) encLen += seg;
        }
        Subject child = new Subject();
        child.setAccountSetsId(accountSetsId);
        child.setType(parent.getType());
        child.setBalanceDirection(parent.getBalanceDirection());
        child.setStatus(true);
        child.setCash(Boolean.TRUE.equals(parent.getCash()));
        child.setSystemDefault(false);
        child.setLeaf(true);
        child.setCode(nextCode);
        child.setName(StrUtil.isNotBlank(childName) ? childName : "自动银行子科目");
        child.setParentId(parent.getId());
        child.setLevel((parent.getLevel() == null ? 1 : parent.getLevel()) + 1);
        Subject saved = subjectService.testSaveSubject(child, encLen);
        return saved != null ? saved.getId() : null;
    }

    /**
     * 期初余额建议值：取本批 billDate 最早一笔，期初 = 该笔 balance − 带方向金额
     * （credit 收入金额为正、debit 支出为负）。无可用数据返 0。会计须在「待确认」复核时校准。
     */
    static BigDecimal computeSuggestedOpeningBalance(List<TransactionDto> txs) {
        TransactionDto earliest = null;
        for (TransactionDto t : txs) {
            if (t == null || t.getBillDate() == null) continue;
            if (earliest == null || t.getBillDate().isBefore(earliest.getBillDate())) {
                earliest = t;
            }
        }
        if (earliest == null || earliest.getBalance() == null || earliest.getAmount() == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal signed = earliest.getAmount().abs();
        if ("debit".equalsIgnoreCase(earliest.getDirection())) {
            signed = signed.negate();
        }
        return earliest.getBalance().subtract(signed);
    }

    /**
     * FN-NEW-CASHIER-JOURNAL-SUBJECT：按 code 取该账套科目（含 leaf 标记，调用方判末级）。
     * package-private 便于单测 override。
     */
    Subject findSubjectByCode(Long accountSetsId, String code) {
        return bqf.selectFrom(qSubject)
                .where(qSubject.accountSetsId.eq(accountSetsId)
                        .and(qSubject.code.eq(code)))
                .fetchFirst();
    }

    /**
     * FN-NEW-CASHIER-JOURNAL-SUBJECT：按 XW 给的 ioTypeCode 取收支类别 id，类型须与 direction 派生的收/支一致。
     * code 为空 / 找不到 / 类型不符 → 返 null（调用方退回名字匹配或 matchWords 兜底）。
     */
    Long resolveIoTypeIdByCode(List<CashierIoTypeDto> ioTypeDtos, String ioTypeCode, CashierIoType.Type requiredType) {
        if (StrUtil.isBlank(ioTypeCode)) return null;
        for (CashierIoTypeDto t : ioTypeDtos) {
            if (ioTypeCode.equals(t.getCode()) && t.getType() == requiredType) {
                return t.getId();
            }
        }
        return null;
    }

    /**
     * FN-NEW-CASHIER-IO-TYPE-LIST 过渡期兼容：按名字匹配收支类别（XW 升级前可能传 ioType=名字）。
     * 名字为空 / 找不到 / 类型不符 → 返 null（调用方继续退回摘要 matchWords 兜底）。
     */
    Long resolveIoTypeIdByName(List<CashierIoTypeDto> ioTypeDtos, String ioTypeName, CashierIoType.Type requiredType) {
        if (StrUtil.isBlank(ioTypeName)) return null;
        for (CashierIoTypeDto t : ioTypeDtos) {
            if (ioTypeName.equals(t.getName()) && t.getType() == requiredType) {
                return t.getId();
            }
        }
        return null;
    }

    /**
     * 银行日记账「备注」—— 直接用 XW 传来的分类依据 classifyNote。
     * <p>
     * FN-FIX-CASHIER-REMARK-PLAIN（2026-05-20）：去掉原来的「AI:」前缀和「(置信度)」拼接。
     * 备注是给会计看的，必须是能看懂的中文业务白话，不能有技术化前缀 / 英文。
     * XW 已保证 classifyNote 是中文白话（如「银行手续费」「对方为已建档客户」
     * 「系统推断科目，建议会计核对」），文本本身已隐含「建议核对 / 请会计设置」等提示，
     * 置信度不再拼进备注（confidence 字段仍单独传，供内部标记 / 后续置信度列用）。
     * subjectName / confidence 入参保留只为兼容方法签名。
     */
    static String buildAiRemark(String subjectName, String confidence, String classifyNote) {
        return StrUtil.isNotBlank(classifyNote) ? classifyNote.trim() : null;
    }

    private static String confidenceCn(String confidence) {
        return switch (confidence.trim().toLowerCase()) {
            case "high" -> "高";
            case "mid", "medium" -> "中";
            case "low" -> "低";
            default -> confidence;
        };
    }

    /**
     * 去重：serialNo 非空 → 按 (accountSetsId, accountId, billNo=serialNo) 查；
     * serialNo 空 → 按 (accountSetsId, accountId, billDate, 金额方向字段, digest) 兜底。
     */
    boolean isDuplicate(Long accountSetsId, Long accountId, TransactionDto tx, String digest, BigDecimal amount) {
        BooleanBuilder where = new BooleanBuilder();
        where.and(qCashierJournal.accountSetsId.eq(accountSetsId));
        where.and(qCashierJournal.accountId.eq(accountId));

        String effectiveSerialNo = isPlaceholderSerialNo(tx.getSerialNo()) ? null : tx.getSerialNo();
        if (StrUtil.isNotBlank(effectiveSerialNo)) {
            where.and(qCashierJournal.billNo.eq(effectiveSerialNo));
        } else {
            where.and(qCashierJournal.billDate.eq(tx.getBillDate()));
            BigDecimal abs = amount.abs();
            if ("credit".equalsIgnoreCase(tx.getDirection())) {
                where.and(qCashierJournal.debit.eq(abs));
            } else {
                where.and(qCashierJournal.credit.eq(abs));
            }
            if (StrUtil.isNotBlank(digest)) {
                where.and(qCashierJournal.digest.eq(digest));
            }
        }
        return bqf.selectFrom(qCashierJournal).where(where).fetchCount() > 0;
    }

    /** 全零（如"000000000"）是 XW 流水号缺失时的占位符，不是真实唯一流水号 */
    private static boolean isPlaceholderSerialNo(String serialNo) {
        return StrUtil.isBlank(serialNo) || serialNo.matches("0+");
    }

    private static Map<String, Object> rowError(int index, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("index", index);
        m.put("message", message);
        return m;
    }

    private static JsonResult failureWithType(String msg, String errorType) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("errorType", errorType);
        return JsonResult.failure(msg).setData(data);
    }

    // ═══════════════════════════════════════════════════════════════
    // FN-NEW-OPENAPI-CASHIER-JOURNAL-GEN-VOUCHER
    // POST /openapi/v1/cashier-journal/generate-vouchers
    // body: { accountSetsId, period:"YYYY-MM" }
    //
    // 把该账套该期「银行日记账」(type=银行存款, voucher_id IS NULL) 逐条生成凭证：
    //   收(debit有值)→ 借 银行存款 / 贷 对方科目；支(credit有值)→ 借 对方科目 / 贷 银行存款。
    // 复用 web 端 CashierJournalService.generateVoucher（含 voucher_id 回写）。
    // 对方科目未设置 / 银行账户未关联科目 / 已结账期间 → 跳过；逐张创建 partial success。
    // 本方法不加 @Transactional：每张走 generateVoucher 自身事务，单张失败不影响其余。
    // ═══════════════════════════════════════════════════════════════
    @PostMapping("/generate-vouchers")
    public JsonResult generateVouchers(@RequestBody GenVoucherReq req) {
        if (req == null) return JsonResult.failure("请求体不能为空");
        if (req.getAccountSetsId() == null) return JsonResult.failure("accountSetsId 不能为空");
        YearMonth ym;
        try {
            ym = YearMonth.parse(req.getPeriod());
        } catch (Exception e) {
            return JsonResult.failure("period 格式非法（应为 YYYY-MM）：" + req.getPeriod());
        }
        AccountSets accountSets = findAccountSet(req.getAccountSetsId());
        if (accountSets == null) return JsonResult.failure("账套不存在：" + req.getAccountSetsId());

        LocalDate lastCheckDate = checkoutService.lastCompletedCheckDate(req.getAccountSetsId());
        List<CashierJournal> journals = loadUnvoucheredBankJournals(
                req.getAccountSetsId(), ym.getYear(), ym.getMonthValue());

        int generated = 0;
        int skipped = 0;
        List<Long> voucherIds = new ArrayList<>();
        List<Map<String, Object>> skippedDetails = new ArrayList<>();
        List<Map<String, Object>> errors = new ArrayList<>();

        for (CashierJournal j : journals) {
            // 幂等：已生成凭证（查询已过滤，双保险）
            if (j.getVoucherId() != null) {
                skipped++;
                skippedDetails.add(skipDetail(j.getId(), "已生成凭证，跳过"));
                continue;
            }
            // 已结账期间跳过
            if (j.getBillDate() != null && lastCheckDate != null
                    && !lastCheckDate.withDayOfMonth(lastCheckDate.lengthOfMonth()).isBefore(j.getBillDate())) {
                skipped++;
                skippedDetails.add(skipDetail(j.getId(), "属于已结账期间，跳过"));
                continue;
            }
            // 对方科目未设置 → 跳过（依赖 FN-NEW-CASHIER-JOURNAL-SUBJECT 先把 subject_id 填上）
            if (j.getSubjectId() == null) {
                skipped++;
                skippedDetails.add(skipDetail(j.getId(), "对方科目未设置，待会计补"));
                continue;
            }
            // 银行存款方科目 = 该日记账 account_id 对应 CashierAccount 关联科目
            Long bankSubjectId = bankSubjectId(req.getAccountSetsId(), j.getAccountId());
            if (bankSubjectId == null) {
                skipped++;
                skippedDetails.add(skipDetail(j.getId(), "银行账户未关联科目，待会计补"));
                continue;
            }
            try {
                Long vid = generateOne(j, bankSubjectId, accountSets);
                generated++;
                if (vid != null) voucherIds.add(vid);
            } catch (Exception e) {
                log.warn("[FN-NEW-OPENAPI-CASHIER-JOURNAL-GEN-VOUCHER] 日记账 {} 生成凭证失败: {}",
                        j.getId(), e.getMessage());
                Map<String, Object> err = new LinkedHashMap<>();
                err.put("journalId", j.getId());
                err.put("message", e.getMessage());
                errors.add(err);
            }
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("generated", generated);
        data.put("skipped", skipped);
        data.put("voucherIds", voucherIds);
        data.put("skippedDetails", skippedDetails);
        data.put("errors", errors);
        return JsonResult.successful(data);
    }

    /**
     * 单张：按方向组装 VoucherDto（银行存款腿 + 对方科目腿）→ 复用 generateVoucher 落账 + 回写 voucher_id，
     * 再回读该日记账的 voucher_id 返回。package-private 便于单测 override。
     */
    Long generateOne(CashierJournal j, Long bankSubjectId, AccountSets accountSets) {
        boolean income = j.getDebit() != null && j.getDebit().signum() != 0;   // 收：银行收钱
        BigDecimal amount = income ? j.getDebit() : j.getCredit();

        VoucherDto dto = new VoucherDto();
        dto.setAccountSetsId(accountSets.getId());
        dto.setWord("记");
        dto.setVoucherDate(j.getBillDate());
        dto.setCode(voucherService.loadCode(accountSets.getId(), "记", j.getBillDate()));
        dto.setRemark(j.getDigest());
        dto.setCreateMember(accountSets.getCreatorId() != null ? accountSets.getCreatorId() : 0L);
        dto.setJournalIds(new ArrayList<>(List.of(j.getId())));   // generateVoucher 据此回写 voucher_id

        VoucherDetailsDto bankLeg = leg(accountSets.getId(), bankSubjectId, j.getDigest());
        VoucherDetailsDto otherLeg = leg(accountSets.getId(), j.getSubjectId(), j.getDigest());
        if (income) {
            // 收：借 银行存款 / 贷 对方科目
            bankLeg.setDebitAmount(amount);
            otherLeg.setCreditAmount(amount);
        } else {
            // 支：借 对方科目 / 贷 银行存款
            otherLeg.setDebitAmount(amount);
            bankLeg.setCreditAmount(amount);
        }
        dto.setDetails(new ArrayList<>(List.of(bankLeg, otherLeg)));

        cashierJournalService.generateVoucher(dto, accountSets);
        return loadJournalVoucherId(accountSets.getId(), j.getId());
    }

    private static VoucherDetailsDto leg(Long accountSetsId, Long subjectId, String summary) {
        VoucherDetailsDto d = new VoucherDetailsDto();
        d.setAccountSetsId(accountSetsId);
        d.setSubjectId(subjectId);
        d.setSummary(summary);
        d.setDebitAmount(BigDecimal.ZERO);
        d.setCreditAmount(BigDecimal.ZERO);
        d.setAuxiliaryDetailId(0L);
        d.setCurrencyId(0L);
        return d;
    }

    private static Map<String, Object> skipDetail(Long journalId, String reason) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("journalId", journalId);
        m.put("reason", reason);
        return m;
    }

    /**
     * 查该账套该期未生成凭证的银行日记账（排除期初行 init=true）。package-private 便于单测 override。
     * <p>FN-FIX-VOUCHER-DELETE-CASCADE：过滤条件改为 「voucher_id IS NULL <b>OR</b> 引用的 voucher 已不存在」。
     * 防止手工 SQL 删凭证后 cashier_journal.voucher_id 残留指向已删凭证 → 当成「已生成」骗过这层幂等检查。
     */
    List<CashierJournal> loadUnvoucheredBankJournals(Long accountSetsId, int year, int month) {
        LocalDate start = LocalDate.of(year, month, 1);
        LocalDate end = start.withDayOfMonth(start.lengthOfMonth());
        // 子查询：voucher_id IS NULL 或者 voucher 不存在
        return bqf.selectFrom(qCashierJournal)
                .where(qCashierJournal.accountSetsId.eq(accountSetsId)
                        .and(qCashierJournal.type.eq(CashierAccount.CashierAccountType.银行存款))
                        .and(qCashierJournal.voucherId.isNull()
                                .or(qCashierJournal.voucherId.notIn(
                                        bqf.selectFrom(qVoucher).select(qVoucher.id)
                                                .where(qVoucher.accountSetsId.eq(accountSetsId)))))
                        .and(qCashierJournal.init.isFalse())
                        .and(qCashierJournal.billDate.between(start, end)))
                .orderBy(qCashierJournal.billDate.asc(), qCashierJournal.id.asc())
                .fetch();
    }

    /** 银行账户关联科目 id（package-private 便于单测 override） */
    Long bankSubjectId(Long accountSetsId, Long accountId) {
        if (accountId == null) return null;
        return bqf.selectFrom(qCashierAccount).select(qCashierAccount.subjectId)
                .where(qCashierAccount.accountSetsId.eq(accountSetsId).and(qCashierAccount.id.eq(accountId)))
                .fetchFirst();
    }

    /** 回读日记账的 voucher_id（generateVoucher 已写回）。package-private 便于单测 override。 */
    Long loadJournalVoucherId(Long accountSetsId, Long journalId) {
        return bqf.selectFrom(qCashierJournal).select(qCashierJournal.voucherId)
                .where(qCashierJournal.accountSetsId.eq(accountSetsId).and(qCashierJournal.id.eq(journalId)))
                .fetchFirst();
    }

    @Data
    public static class GenVoucherReq {
        private Long accountSetsId;
        private String period;   // YYYY-MM
    }

    // ═══════════════════════════════════════════════════════════════
    // 请求 DTO
    // ═══════════════════════════════════════════════════════════════

    @Data
    public static class CashierJournalBatchReq {
        private Long accountSetsId;
        private String bankName;
        private String accountNumber;
        private String period;          // "yyyy-MM"，目前仅作上下文，落账以每笔 billDate 为准
        private List<TransactionDto> transactions;
    }

    @Data
    public static class TransactionDto {
        private LocalDate billDate;
        private String direction;       // credit=银行收钱(收入) / debit=银行付钱(支出)
        private BigDecimal amount;
        private BigDecimal balance;
        private String counterName;
        private String counterAccount;
        private String summary;
        private String serialNo;        // 幂等去重 key（落到 cashier_journal.billNo）
        // FN-NEW-CASHIER-JOURNAL-SUBJECT：XW 两段式分类（规则 + LLM）算出的对方科目 / 收支类别
        private String subjectCode;     // 对方科目编码（如 1122 应收账款 / 6603 财务费用），命中末级才落 subjectId
        /**
         * 收支类别编码 —— 值 = GET /openapi/v1/cashier-io-type/list 返回的 code 字段（账套真实编码，非名字）。
         * 类型须与 direction 一致（支=debit 对应「支」类，收=credit 对应「收」类）。
         * 优先于 ioType（名字）和摘要 matchWords 匹配。
         */
        private String ioTypeCode;
        /**
         * 过渡期兼容字段：XW 升级前可传收支类别名字（如「工资支出」）。
         * ioTypeCode 有值且命中时优先使用 ioTypeCode；ioTypeCode 未命中才按名字查 ioType。
         * XW 升级改用 ioTypeCode 后此字段自然被忽略。
         */
        private String ioType;
        private String confidence;      // high / mid / low — XW 分类置信度，拼进 remark
        private String classifyNote;    // 分类依据简述（如「对方在客户档案」），拼进 remark
    }
}
