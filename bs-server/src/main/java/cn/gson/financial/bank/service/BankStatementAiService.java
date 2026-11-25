package cn.gson.financial.bank.service;

import cn.gson.financial.kernel.model.dto.VoucherDetailsDto;
import cn.gson.financial.kernel.model.dto.VoucherDto;
import cn.gson.financial.kernel.model.entity.*;
import cn.gson.financial.kernel.model.hibernate.PrimaryKey;
import cn.gson.financial.kernel.model.repo.*;
import cn.gson.financial.kernel.service.CashierAccountService;
import cn.gson.financial.kernel.service.CashierIoTypeService;
import cn.gson.financial.kernel.service.CashierJournalService;
import cn.gson.financial.kernel.service.SubjectService;
import cn.gson.financial.kernel.service.VoucherService;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 银行流水 AI 服务
 *
 * 提供两个公开入口：
 *  1. aiUpload()       — 上传任意格式 Excel，AI 解析后存入 BankStatementDetail（留底）
 *  2. processFromStatement() — 从 BankStatementDetail 读取，匹配/自动建模板，生成日记账和凭证
 *  3. importAndProcess()（兼容旧接口，直接从 Excel 处理，不留底）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BankStatementAiService {

    private final CashierJournalService cashierJournalService;
    private final VoucherService voucherService;
    private final CashierIoTypeRepository cashierIoTypeRepository;
    private final CashierIoTypeVoucherTemplateRepository voucherTemplateRepository;
    private final AccountSetsRepository accountSetsRepository;
    private final CashierAccountRepository cashierAccountRepository;
    private final VoucherWordRepository voucherWordRepository;
    private final CashierJournalRepository cashierJournalRepository;
    private final BankStatementRepository bankStatementRepository;
    private final BankStatementDetailRepository bankStatementDetailRepository;
    private final SubjectRepository subjectRepository;
    private final CashierIoTypeService cashierIoTypeService;
    private final CashierAccountService cashierAccountService;
    private final SubjectService subjectService;

    private static final int MAX_ROWS = 1000;

    @Value("${agent.deepseek.api-url}")
    private String deepseekApiUrl;

    @Value("${agent.deepseek.api-key}")
    private String deepseekApiKey;

    @Value("${agent.deepseek.model}")
    private String deepseekModel;

    // ─────────────────────────────────────────────────────────────────────
    // 内部数据结构
    // ─────────────────────────────────────────────────────────────────────

    static class BankRow {
        int index;
        LocalDate tradeDate;
        BigDecimal amount;
        String direction;       // "来账" 或 "往账"
        String counterparty;
        String remarks;
        Long ioTypeId;          // 匹配后填入
        BankStatementDetail detail; // 留底行引用（processFromStatement 时填入）
    }

    // ─────────────────────────────────────────────────────────────────────
    // 入口 1：上传留底（任意格式 Excel → BankStatementDetail）
    // ─────────────────────────────────────────────────────────────────────

    /**
     * 上传留底：任意格式 Excel → AI 解析 → 存入 BankStatementDetail
     *
     * @param accountName  银行账户名称（如"工商银行基本户"），用于查找或自动创建 CashierAccount
     * @param bankName     银行名称（如"工商银行"），用于填充明细的 bankName 字段及科目命名
     * @param cardNumber   银行账号（可选），用于匹配已有账户或新建时存档
     * @param period       账期，格式 YYYY-MM（存入 BankStatement.period，供 ai-process 使用）
     */
    @Transactional
    public Map<String, Object> aiUpload(MultipartFile file, Long accountSetId,
                                        String accountName, String bankName,
                                        String cardNumber, Long userId, String period) {
        AccountSets accountSets = accountSetsRepository.findById(accountSetId)
                .orElseThrow(() -> new IllegalArgumentException("账套不存在: " + accountSetId));

        // 查找或自动创建 CashierAccount
        CashierAccount cashierAccount = findOrCreateCashierAccount(
                accountSetId, accountName, bankName, cardNumber, accountSets);

        String[] csvResult = readExcelAsCsv(file);
        boolean truncated = "1".equals(csvResult[1]);
        if (StrUtil.isBlank(csvResult[0])) {
            return buildUploadResult(0L, cashierAccount.getId(), 0, truncated, "Excel 内容为空");
        }

        // AI 解析列结构
        List<BankRow> rows = parseRowsWithDeepSeek(csvResult[0]);
        if (CollUtil.isEmpty(rows)) {
            return buildUploadResult(0L, cashierAccount.getId(), 0, truncated, "未解析到有效数据行");
        }

        // 创建 BankStatement 头记录
        BankStatement header = new BankStatement();
        header.setAccountSetsId(accountSetId);
        header.setCreateId(userId);
        header.setCreateTime(LocalDateTime.now());
        header.setIsDelete(0);
        header.setPeriod(StrUtil.isNotBlank(period) ? period : null);
        BankStatement saved = bankStatementRepository.save(header);

        // 将解析结果写入 BankStatementDetail
        List<BankStatementDetail> details = new ArrayList<>();
        for (BankRow row : rows) {
            BankStatementDetail detail = new BankStatementDetail();
            detail.setBankStatementId(saved.getId());
            detail.setTradeDate(row.tradeDate.atStartOfDay());
            detail.setTradeAmount(row.amount);
            detail.setTransactionType(row.direction);
            detail.setRemarks(row.remarks);
            detail.setBankName(bankName);
            detail.setIsDelete(0);
            if ("来账".equals(row.direction)) {
                detail.setPayName(row.counterparty);
            } else {
                detail.setReceiveName(row.counterparty);
            }
            details.add(detail);
        }
        bankStatementDetailRepository.saveAll(details);

        String msg = truncated ? "Excel 超过 " + MAX_ROWS + " 行，已截断处理前 " + MAX_ROWS + " 行" : null;
        Map<String, Object> result = buildUploadResult(saved.getId(), cashierAccount.getId(), rows.size(), truncated, msg);
        if (StrUtil.isNotBlank(period)) result.put("period", period);
        return result;
    }

    /**
     * 查找或自动创建银行账户（CashierAccount）
     * 查找逻辑：按 accountName 在当前账套中查找，找不到则在 1002（银行存款）科目下新建子科目 + CashierAccount
     */
    private CashierAccount findOrCreateCashierAccount(Long accountSetId, String accountName,
                                                       String bankName, String cardNumber,
                                                       AccountSets accountSets) {
        // 1. 按名称查找现有账户
        java.util.Optional<CashierAccount> existing =
                cashierAccountRepository.findFirstByAccountSetsIdAndName(accountSetId, accountName);
        if (existing.isPresent()) {
            log.info("找到已有银行账户：{}", accountName);
            return existing.get();
        }

        // 2. 未找到 → 在 1002（银行存款）下新建子科目
        Subject bankDepositSubject = subjectService.listByCode(accountSetId, "1002");
        if (bankDepositSubject == null) {
            throw new IllegalStateException("未找到科目 1002（银行存款），请先初始化科目体系");
        }

        // createLeaf 会查找同名子科目，不存在则创建，存在则直接返回
        String subjectName = StrUtil.isNotBlank(bankName) ? bankName + "-" + accountName : accountName;
        reactor.util.function.Tuple2<Long, ?> leafResult = subjectService.createLeaf(
                bankDepositSubject.getId(), subjectName, accountSets);
        Long newSubjectId = leafResult.getT1();

        // 3. 生成唯一账户编码（当前最大编码 + 1，不存在时从 "1001" 开始）
        String newCode = cashierAccountRepository
                .findFirstByAccountSetsIdAndTypeOrderByCodeDesc(
                        accountSetId, CashierAccount.CashierAccountType.银行存款)
                .map(a -> String.valueOf(Long.parseLong(a.getCode()) + 1))
                .orElse("1001");

        // 4. 创建 CashierAccount
        CashierAccount newAccount = new CashierAccount();
        newAccount.setAccountSetsId(accountSetId);
        newAccount.setType(CashierAccount.CashierAccountType.银行存款);
        newAccount.setName(accountName);
        newAccount.setCode(newCode);
        newAccount.setSubjectId(newSubjectId);
        if (StrUtil.isNotBlank(cardNumber)) {
            newAccount.setCardNumber(cardNumber);
        }
        CashierAccount created = cashierAccountService.save(newAccount);
        log.info("自动创建银行账户：{} (code={}, subjectId={})", accountName, newCode, newSubjectId);
        return created;
    }

    // ─────────────────────────────────────────────────────────────────────
    // 入口 2：从留底处理（BankStatementDetail → 日记账 → 凭证）
    // ─────────────────────────────────────────────────────────────────────

    public Map<String, Object> processFromStatement(Long bankStatementId, Long accountSetId,
                                                    Long cashierAccountId, String period) {
        AccountSets accountSets = accountSetsRepository.findById(accountSetId)
                .orElseThrow(() -> new IllegalArgumentException("账套不存在: " + accountSetId));
        CashierAccount cashierAccount = cashierAccountRepository.findById(
                        new PrimaryKey(cashierAccountId, accountSetId))
                .orElseThrow(() -> new IllegalArgumentException("现金账户不存在: " + cashierAccountId));

        // 读取留底明细（isDelete=0，按日期排序）
        List<BankStatementDetail> details =
                bankStatementDetailRepository.findByBankStatementIdAndIsDeleteOrderByTradeDate(bankStatementId, 0);
        if (CollUtil.isEmpty(details)) {
            return buildProcessResult(0, 0, 0, 0, 0, 0, "该批次无有效流水明细");
        }

        // BankStatementDetail → BankRow，同时过滤：已处理 + 非 period 月份
        int[] ym = StrUtil.isNotBlank(period) ? parsePeriod(period) : null;
        List<BankRow> rows = new ArrayList<>();
        int alreadyProcessed = 0;
        for (int i = 0; i < details.size(); i++) {
            BankStatementDetail d = details.get(i);
            if (d.getCashierJournalId() != null) {
                alreadyProcessed++;
                continue;
            }
            if (d.getTradeDate() == null || d.getTradeAmount() == null) continue;
            LocalDate date = d.getTradeDate().toLocalDate();
            if (ym != null && (date.getYear() != ym[0] || date.getMonthValue() != ym[1])) continue;

            BankRow row = new BankRow();
            row.index = i;
            row.tradeDate = date;
            row.amount = d.getTradeAmount().abs();
            row.direction = "来账".equals(d.getTransactionType()) ? "来账" : "往账";
            row.counterparty = StrUtil.nullToEmpty(
                    "来账".equals(row.direction) ? d.getPayName() : d.getReceiveName());
            row.remarks = StrUtil.nullToEmpty(d.getRemarks());
            row.detail = d;
            rows.add(row);
        }

        if (CollUtil.isEmpty(rows)) {
            String msg = alreadyProcessed > 0 ? "所有明细已处理过" : "无符合期间的数据";
            return buildProcessResult(details.size(), 0, 0, 0, 0, 0, msg);
        }

        // 关键字匹配
        List<CashierIoType> allIoTypes = cashierIoTypeRepository.findByAccountSetsId(accountSetId);
        matchIoTypeByKeyword(rows, allIoTypes);

        // AI 批量匹配
        List<BankRow> unmatched = rows.stream().filter(r -> r.ioTypeId == null).toList();
        if (!unmatched.isEmpty() && !allIoTypes.isEmpty()) {
            matchIoTypeByAi(unmatched, allIoTypes);
        }

        // 仍未匹配 → AI 自动建收支类别 + 凭证模板
        List<BankRow> stillUnmatched = rows.stream().filter(r -> r.ioTypeId == null).toList();
        int newTemplateCount = 0;
        if (!stillUnmatched.isEmpty()) {
            List<Subject> subjects = subjectRepository.selectByAccountSetsId(accountSetId);
            // 获取默认凭证字
            VoucherWord defaultWord = voucherWordRepository
                    .findFirstByAccountSetsIdAndIsDefault(accountSetId, true).orElse(null);
            Long defaultWordId = defaultWord != null ? defaultWord.getId() : null;

            for (BankRow row : stillUnmatched) {
                try {
                    Long newIoTypeId = autoCreateIoTypeAndTemplate(
                            row, accountSetId, subjects, defaultWordId,
                            cashierAccount.getType());
                    if (newIoTypeId != null) {
                        row.ioTypeId = newIoTypeId;
                        newTemplateCount++;
                    }
                } catch (Exception e) {
                    log.error("AI 自动建收支类别失败，row={} [{}]: {}", row.index, row.remarks, e.getMessage());
                }
            }
        }

        // 创建日记账，回写 cashierJournalId
        LocalDate enableDate = accountSets.getCurrentAccountDate();
        List<CashierJournal> journals = createJournalsFromRows(
                rows, accountSetId, cashierAccountId, cashierAccount, enableDate);

        // 生成凭证
        int[] voucherResult = generateVouchers(journals, accountSetId, accountSets);

        int unmatchedCount = (int) rows.stream().filter(r -> r.ioTypeId == null).count();
        return buildProcessResult(rows.size(), journals.size(), voucherResult[0],
                unmatchedCount, voucherResult[1], newTemplateCount, null);
    }

    // ─────────────────────────────────────────────────────────────────────
    // 入口 3：旧接口兼容（直接从 Excel 处理，不留底）
    // ─────────────────────────────────────────────────────────────────────

    public Map<String, Object> importAndProcess(MultipartFile file, Long accountSetId,
                                                Long cashierAccountId, String period) {
        AccountSets accountSets = accountSetsRepository.findById(accountSetId)
                .orElseThrow(() -> new IllegalArgumentException("账套不存在: " + accountSetId));
        CashierAccount cashierAccount = cashierAccountRepository.findById(
                        new PrimaryKey(cashierAccountId, accountSetId))
                .orElseThrow(() -> new IllegalArgumentException("现金账户不存在: " + cashierAccountId));

        String[] csvResult = readExcelAsCsv(file);
        boolean truncated = "1".equals(csvResult[1]);
        if (StrUtil.isBlank(csvResult[0])) {
            return buildResult(0, 0, 0, 0, 0, "Excel 内容为空", false);
        }
        List<BankRow> rows = parseRowsWithDeepSeek(csvResult[0]);
        if (CollUtil.isEmpty(rows)) {
            return buildResult(0, 0, 0, 0, 0, "未解析到有效数据行", truncated);
        }

        int totalParsed = rows.size();
        if (StrUtil.isNotBlank(period)) {
            int[] ym = parsePeriod(period);
            if (ym != null) {
                final int year = ym[0], month = ym[1];
                rows = rows.stream()
                        .filter(r -> r.tradeDate.getYear() == year && r.tradeDate.getMonthValue() == month)
                        .collect(Collectors.toList());
                if (rows.isEmpty()) {
                    return buildResult(totalParsed, 0, 0, 0, 0,
                            "解析出 " + totalParsed + " 行，但无 " + period + " 期间的数据", truncated);
                }
            }
        }

        List<CashierIoType> allIoTypes = cashierIoTypeRepository.findByAccountSetsId(accountSetId);
        matchIoTypeByKeyword(rows, allIoTypes);
        List<BankRow> unmatched = rows.stream().filter(r -> r.ioTypeId == null).toList();
        if (!unmatched.isEmpty() && !allIoTypes.isEmpty()) {
            matchIoTypeByAi(unmatched, allIoTypes);
        }

        LocalDate enableDate = accountSets.getCurrentAccountDate();
        List<CashierJournal> journals = createJournalsFromRows(
                rows, accountSetId, cashierAccountId, cashierAccount, enableDate);
        int[] voucherResult = generateVouchers(journals, accountSetId, accountSets);

        int unmatchedCount = (int) rows.stream().filter(r -> r.ioTypeId == null).count();
        String msg = truncated ? "Excel 超过 " + MAX_ROWS + " 行，已截断" : null;
        return buildResult(rows.size(), journals.size(), voucherResult[0], unmatchedCount, voucherResult[1], msg, truncated);
    }

    // ─────────────────────────────────────────────────────────────────────
    // AI 解析 Excel → BankRow 列表
    // ─────────────────────────────────────────────────────────────────────

    private List<BankRow> parseRowsWithDeepSeek(String csv) {
        String prompt = "你是财务数据处理专家。以下是银行流水Excel的原始内容（CSV格式）：\n" + csv + "\n\n" +
                "请识别并提取以下字段，返回JSON数组，每个对象包含：\n" +
                "- tradeDate: 交易日期（格式 YYYY-MM-DD）\n" +
                "- amount: 交易金额（正数）\n" +
                "- direction: \"来账\"（收入/贷记/入账）或\"往账\"（支出/借记/出账）\n" +
                "- counterparty: 对方名称（付款人或收款人名称，没有则空字符串）\n" +
                "- remarks: 备注或摘要（没有则空字符串）\n\n" +
                "跳过汇总行、表头行、空行、合计行、小计行。只返回JSON数组，禁止任何解释或markdown格式。";

        String responseText = callDeepSeek(prompt);
        if (StrUtil.isBlank(responseText)) return Collections.emptyList();

        try {
            JSONArray arr = JSON.parseArray(cleanJsonResponse(responseText));
            List<BankRow> rows = new ArrayList<>();
            for (int i = 0; i < arr.size(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                BankRow row = new BankRow();
                row.index = i;
                row.tradeDate = parseDate(obj.getString("tradeDate"));
                row.amount = obj.getBigDecimal("amount");
                row.direction = obj.getString("direction");
                row.counterparty = StrUtil.nullToEmpty(obj.getString("counterparty"));
                row.remarks = StrUtil.nullToEmpty(obj.getString("remarks"));
                if (row.tradeDate != null && row.amount != null && row.amount.compareTo(BigDecimal.ZERO) > 0) {
                    rows.add(row);
                }
            }
            log.info("DeepSeek 解析银行流水：{} 行有效数据", rows.size());
            return rows;
        } catch (Exception e) {
            log.error("解析 DeepSeek Excel 响应失败: {}", responseText, e);
            return Collections.emptyList();
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // 关键字匹配收支类别
    // ─────────────────────────────────────────────────────────────────────

    private void matchIoTypeByKeyword(List<BankRow> rows, List<CashierIoType> ioTypes) {
        for (BankRow row : rows) {
            String searchText = row.remarks + " " + row.counterparty;
            outer:
            for (CashierIoType ioType : ioTypes) {
                JSONArray matchWords = ioType.getMatchWords();
                if (matchWords == null || matchWords.isEmpty()) continue;
                for (int i = 0; i < matchWords.size(); i++) {
                    String keyword = matchWords.getString(i);
                    if (StrUtil.isNotBlank(keyword) && searchText.contains(keyword)) {
                        row.ioTypeId = ioType.getId();
                        break outer;
                    }
                }
            }
        }
        long matched = rows.stream().filter(r -> r.ioTypeId != null).count();
        log.info("关键字匹配：{}/{} 条命中", matched, rows.size());
    }

    // ─────────────────────────────────────────────────────────────────────
    // DeepSeek 批量匹配收支类别
    // ─────────────────────────────────────────────────────────────────────

    private void matchIoTypeByAi(List<BankRow> unmatched, List<CashierIoType> ioTypes) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是财务分类专家。以下是可用的收支类别（格式：id|类型|名称|关键词）：\n");
        for (CashierIoType t : ioTypes) {
            prompt.append(t.getId()).append("|")
                    .append(t.getType()).append("|")
                    .append(t.getName()).append("|")
                    .append(t.getMatchWords() != null ? t.getMatchWords().toJSONString() : "[]")
                    .append("\n");
        }
        prompt.append("\n请为以下银行流水逐行匹配最合适的收支类别，返回JSON数组：")
                .append("[{\"index\":0,\"ioTypeId\":123},...]\n")
                .append("无法判断的填null。只返回JSON数组，禁止任何解释或markdown格式。\n\n")
                .append("流水数据（格式：序号|日期|金额|方向|对方|备注）：\n");
        for (BankRow row : unmatched) {
            prompt.append(row.index).append("|").append(row.tradeDate).append("|")
                    .append(row.amount).append("|").append(row.direction).append("|")
                    .append(row.counterparty).append("|").append(row.remarks).append("\n");
        }

        String responseText = callDeepSeek(prompt.toString());
        if (StrUtil.isBlank(responseText)) return;

        try {
            JSONArray arr = JSON.parseArray(cleanJsonResponse(responseText));
            Map<Integer, Long> indexToIoType = new HashMap<>();
            for (int i = 0; i < arr.size(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                Integer index = obj.getInteger("index");
                Long ioTypeId = obj.getLong("ioTypeId");
                if (index != null && ioTypeId != null) indexToIoType.put(index, ioTypeId);
            }
            for (BankRow row : unmatched) row.ioTypeId = indexToIoType.get(row.index);
            long matched = unmatched.stream().filter(r -> r.ioTypeId != null).count();
            log.info("DeepSeek AI 匹配收支类别：{}/{} 条命中", matched, unmatched.size());
        } catch (Exception e) {
            log.error("解析 DeepSeek 收支匹配响应失败", e);
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // AI 自动创建收支类别 + 凭证模板
    // ─────────────────────────────────────────────────────────────────────

    private Long autoCreateIoTypeAndTemplate(BankRow row, Long accountSetId,
                                              List<Subject> subjects, Long defaultWordId,
                                              CashierAccount.CashierAccountType accountType) {
        if (CollUtil.isEmpty(subjects)) return null;

        // 科目列表发给 DeepSeek
        StringBuilder subjectList = new StringBuilder();
        for (Subject s : subjects) {
            subjectList.append(s.getId()).append("|")
                    .append(s.getCode()).append("|")
                    .append(s.getName()).append("\n");
        }

        String prompt = "你是专业会计师。以下是账套的会计科目列表（格式：id|编码|名称）：\n" +
                subjectList +
                "\n以下是一条银行流水：\n" +
                "方向：" + row.direction + "\n" +
                "对方：" + row.counterparty + "\n" +
                "备注：" + row.remarks + "\n" +
                "金额：" + row.amount + "\n\n" +
                "请根据流水信息，给出：\n" +
                "1. 该流水的收支类别名称（ioTypeName）和编码（ioTypeCode，4位字母数字）\n" +
                "2. 借方科目ID（debitSubjectId）和贷方科目ID（creditSubjectId）\n" +
                "只返回JSON对象，格式：{\"ioTypeName\":\"...\",\"ioTypeCode\":\"...\",\"debitSubjectId\":123,\"creditSubjectId\":456}\n" +
                "禁止任何解释或markdown格式。";

        String responseText = callDeepSeek(prompt);
        if (StrUtil.isBlank(responseText)) return null;

        try {
            JSONObject obj = JSON.parseObject(cleanJsonResponse(responseText));
            String ioTypeName = obj.getString("ioTypeName");
            String ioTypeCode = obj.getString("ioTypeCode");
            Long debitSubjectId = obj.getLong("debitSubjectId");
            Long creditSubjectId = obj.getLong("creditSubjectId");

            if (StrUtil.isBlank(ioTypeName) || StrUtil.isBlank(ioTypeCode)
                    || debitSubjectId == null || creditSubjectId == null) {
                log.warn("DeepSeek 返回的模板信息不完整: {}", responseText);
                return null;
            }

            // 确定收支类型
            CashierIoType.Type ioTypeType = "来账".equals(row.direction)
                    ? CashierIoType.Type.收 : CashierIoType.Type.支;

            // 创建收支类别
            CashierIoType newIoType = new CashierIoType();
            newIoType.setAccountSetsId(accountSetId);
            newIoType.setName(ioTypeName);
            newIoType.setCode(ioTypeCode);
            newIoType.setType(ioTypeType);
            CashierIoType savedIoType = cashierIoTypeService.save(newIoType);

            // 创建借方凭证模板
            CashierIoTypeVoucherTemplate debitTpl = new CashierIoTypeVoucherTemplate();
            debitTpl.setAccountSetsId(accountSetId);
            debitTpl.setIoTypeId(savedIoType.getId());
            debitTpl.setType(ioTypeType);
            debitTpl.setDirection(CashierIoTypeVoucherTemplate.Direction.借);
            debitTpl.setSubjectId(debitSubjectId);
            debitTpl.setWordId(defaultWordId);
            debitTpl.setJournalType(accountType);
            cashierIoTypeService.saveTemplate(debitTpl);

            // 创建贷方凭证模板
            CashierIoTypeVoucherTemplate creditTpl = new CashierIoTypeVoucherTemplate();
            creditTpl.setAccountSetsId(accountSetId);
            creditTpl.setIoTypeId(savedIoType.getId());
            creditTpl.setType(ioTypeType);
            creditTpl.setDirection(CashierIoTypeVoucherTemplate.Direction.贷);
            creditTpl.setSubjectId(creditSubjectId);
            creditTpl.setWordId(defaultWordId);
            creditTpl.setJournalType(accountType);
            cashierIoTypeService.saveTemplate(creditTpl);

            log.info("AI 自动创建收支类别：{} ({})，借方科目={}，贷方科目={}",
                    ioTypeName, ioTypeCode, debitSubjectId, creditSubjectId);
            return savedIoType.getId();
        } catch (Exception e) {
            log.error("自动创建收支类别失败: {}", responseText, e);
            return null;
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // 创建日记账（每条独立事务），可选回写 detail.cashierJournalId
    // ─────────────────────────────────────────────────────────────────────

    private List<CashierJournal> createJournalsFromRows(List<BankRow> rows, Long accountSetId,
                                                        Long cashierAccountId, CashierAccount cashierAccount,
                                                        LocalDate enableDate) {
        List<CashierJournal> created = new ArrayList<>();
        int skipDuplicate = 0;
        for (BankRow row : rows) {
            try {
                if (isDuplicate(accountSetId, cashierAccountId, row)) {
                    log.info("跳过重复流水：日期={} 金额={} 备注={}", row.tradeDate, row.amount, row.remarks);
                    skipDuplicate++;
                    continue;
                }

                CashierJournal journal = new CashierJournal();
                journal.setAccountSetsId(accountSetId);
                journal.setAccountId(cashierAccountId);
                journal.setType(cashierAccount.getType());
                journal.setBillDate(row.tradeDate);
                journal.setIoTypeId(row.ioTypeId);
                journal.setInit(false);
                String digest = StrUtil.isNotBlank(row.remarks) ? row.remarks : row.counterparty;
                journal.setDigest(StrUtil.isNotBlank(digest) ? digest : "银行流水");
                journal.setTransactionPostscript(row.remarks);
                journal.setOppositeAccountName(row.counterparty);
                if ("来账".equals(row.direction)) {
                    journal.setDebit(row.amount);
                } else {
                    journal.setCredit(row.amount);
                }

                CashierJournal saved = saveJournalInNewTransaction(journal, enableDate);
                created.add(saved);

                // 回写留底行的 cashierJournalId（追溯链）
                if (row.detail != null) {
                    row.detail.setCashierJournalId(saved.getId());
                    bankStatementDetailRepository.save(row.detail);
                }
            } catch (Exception e) {
                log.error("创建日记账失败，第{}行 [{}]: {}", row.index, row.remarks, e.getMessage());
            }
        }
        log.info("创建日记账：{}/{} 条成功，跳过重复 {} 条", created.size(), rows.size(), skipDuplicate);
        return created;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CashierJournal saveJournalInNewTransaction(CashierJournal journal, LocalDate enableDate) {
        return cashierJournalService.save(journal, enableDate);
    }

    private boolean isDuplicate(Long accountSetId, Long cashierAccountId, BankRow row) {
        String postscript = StrUtil.nullToEmpty(row.remarks);
        if ("来账".equals(row.direction)) {
            return cashierJournalRepository
                    .existsByAccountSetsIdAndAccountIdAndBillDateAndDebitAndTransactionPostscript(
                            accountSetId, cashierAccountId, row.tradeDate, row.amount, postscript);
        } else {
            return cashierJournalRepository
                    .existsByAccountSetsIdAndAccountIdAndBillDateAndCreditAndTransactionPostscript(
                            accountSetId, cashierAccountId, row.tradeDate, row.amount, postscript);
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // 生成会计凭证，返回 int[]{voucherCount, noTemplateCount}
    // ─────────────────────────────────────────────────────────────────────

    private int[] generateVouchers(List<CashierJournal> journals, Long accountSetId, AccountSets accountSets) {
        Map<Long, String> wordCache = new HashMap<>();
        int voucherCount = 0;
        int noTemplateCount = 0;

        for (CashierJournal journal : journals) {
            if (journal.getIoTypeId() == null) continue;
            try {
                List<CashierIoTypeVoucherTemplate> templates =
                        voucherTemplateRepository.findByAccountSetsIdAndIoTypeId(accountSetId, journal.getIoTypeId());
                if (CollUtil.isEmpty(templates)) {
                    log.warn("收支类别 {} 未配置凭证模板，跳过（日记账 ID={}）",
                            journal.getIoTypeId(), journal.getId());
                    noTemplateCount++;
                    continue;
                }

                BigDecimal amount = journal.getDebit() != null ? journal.getDebit() : journal.getCredit();
                String digest = journal.getDigest();
                Long wordId = templates.get(0).getWordId();
                String word = wordCache.computeIfAbsent(wordId, id -> {
                    VoucherWord vw = voucherWordRepository.findById(new PrimaryKey(id, accountSetId)).orElse(null);
                    return vw != null ? vw.getWord() : "记";
                });

                int code = voucherService.loadCode(accountSetId, word, journal.getBillDate());

                List<VoucherDetailsDto> details = new ArrayList<>();
                for (CashierIoTypeVoucherTemplate tpl : templates) {
                    VoucherDetailsDto detail = new VoucherDetailsDto();
                    detail.setAccountSetsId(accountSetId);
                    detail.setSubjectId(tpl.getSubjectId());
                    detail.setSummary(digest);
                    detail.setVoucherDate(journal.getBillDate());
                    detail.setYear(journal.getBillDate().getYear());
                    detail.setMonth(journal.getBillDate().getMonthValue());
                    if (CashierIoTypeVoucherTemplate.Direction.借 == tpl.getDirection()) {
                        detail.setDebitAmount(amount);
                    } else {
                        detail.setCreditAmount(amount);
                    }
                    details.add(detail);
                }

                VoucherDto voucherDto = new VoucherDto();
                voucherDto.setAccountSetsId(accountSetId);
                voucherDto.setWord(word);
                voucherDto.setCode(code);
                voucherDto.setVoucherDate(journal.getBillDate());
                voucherDto.setYear(journal.getBillDate().getYear());
                voucherDto.setMonth(journal.getBillDate().getMonthValue());
                voucherDto.setRemark(digest);
                voucherDto.setDetails(details);
                voucherDto.setJournalIds(Collections.singletonList(journal.getId()));
                cashierJournalService.generateVoucher(voucherDto, accountSets);
                voucherCount++;
            } catch (Exception e) {
                log.error("生成凭证失败，日记账ID={}, ioTypeId={}: {}",
                        journal.getId(), journal.getIoTypeId(), e.getMessage(), e);
            }
        }
        log.info("生成凭证：{} 张，无模板跳过：{} 条", voucherCount, noTemplateCount);
        return new int[]{voucherCount, noTemplateCount};
    }

    // ─────────────────────────────────────────────────────────────────────
    // Apache POI：读 Excel → CSV（最多 MAX_ROWS 行）
    // ─────────────────────────────────────────────────────────────────────

    String[] readExcelAsCsv(MultipartFile file) {
        StringBuilder sb = new StringBuilder();
        boolean truncated = false;
        try (Workbook workbook = WorkbookFactory.create(file.getInputStream())) {
            int rowCount = 0;
            outer:
            for (Sheet sheet : workbook) {
                for (Row row : sheet) {
                    List<String> cells = new ArrayList<>();
                    int lastCell = row.getLastCellNum();
                    for (int ci = 0; ci < lastCell; ci++) {
                        cells.add(getCellString(row.getCell(ci)));
                    }
                    if (cells.stream().allMatch(StrUtil::isBlank)) continue;
                    sb.append(String.join(",", cells)).append("\n");
                    if (++rowCount >= MAX_ROWS) { truncated = true; break outer; }
                }
            }
        } catch (Exception e) {
            log.error("读取 Excel 失败", e);
        }
        return new String[]{sb.toString(), truncated ? "1" : "0"};
    }

    private String getCellString(Cell cell) {
        if (cell == null) return "";
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue().replace(",", "，").trim();
            case NUMERIC -> {
                if (DateUtil.isCellDateFormatted(cell)) {
                    yield cell.getLocalDateTimeCellValue().toLocalDate().toString();
                }
                BigDecimal bd = BigDecimal.valueOf(cell.getNumericCellValue()).stripTrailingZeros();
                yield bd.toPlainString();
            }
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case FORMULA -> {
                try {
                    yield BigDecimal.valueOf(cell.getNumericCellValue()).stripTrailingZeros().toPlainString();
                } catch (Exception e) {
                    yield cell.getStringCellValue().trim();
                }
            }
            default -> "";
        };
    }

    // ─────────────────────────────────────────────────────────────────────
    // DeepSeek HTTP 调用
    // ─────────────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    String callDeepSeek(String prompt) {
        try {
            RestTemplate restTemplate = new RestTemplate();
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(deepseekApiKey);

            Map<String, Object> requestBody = new LinkedHashMap<>();
            requestBody.put("model", deepseekModel);
            requestBody.put("temperature", 0.1);
            requestBody.put("messages", List.of(Map.of("role", "user", "content", prompt)));

            ResponseEntity<Map> response = restTemplate.postForEntity(
                    deepseekApiUrl, new HttpEntity<>(requestBody, headers), Map.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                List<Map<String, Object>> choices =
                        (List<Map<String, Object>>) response.getBody().get("choices");
                if (CollUtil.isNotEmpty(choices)) {
                    Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
                    return (String) message.get("content");
                }
            }
        } catch (Exception e) {
            log.error("DeepSeek 调用失败", e);
        }
        return null;
    }

    // ─────────────────────────────────────────────────────────────────────
    // 工具方法
    // ─────────────────────────────────────────────────────────────────────

    String cleanJsonResponse(String text) {
        text = text.trim();
        if (text.startsWith("```")) {
            text = text.replaceAll("(?s)```[a-z]*\\n?", "").replaceAll("```", "").trim();
        }
        return text;
    }

    private static final List<DateTimeFormatter> DATE_FORMATTERS = List.of(
            DateTimeFormatter.ofPattern("yyyy-MM-dd"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd"),
            DateTimeFormatter.ofPattern("yyyyMMdd"),
            DateTimeFormatter.ofPattern("yyyy年MM月dd日"),
            DateTimeFormatter.ofPattern("MM/dd/yyyy"),
            DateTimeFormatter.ofPattern("dd/MM/yyyy")
    );

    private LocalDate parseDate(String dateStr) {
        if (StrUtil.isBlank(dateStr)) return null;
        for (DateTimeFormatter fmt : DATE_FORMATTERS) {
            try {
                return LocalDate.parse(dateStr.trim(), fmt);
            } catch (DateTimeParseException ignored) {}
        }
        log.warn("无法解析日期格式: {}", dateStr);
        return null;
    }

    int[] parsePeriod(String period) {
        try {
            String[] parts = period.split("-");
            if (parts.length == 2) {
                return new int[]{Integer.parseInt(parts[0]), Integer.parseInt(parts[1])};
            }
        } catch (Exception ignored) {}
        log.warn("期间格式错误，忽略过滤：{}", period);
        return null;
    }

    private Map<String, Object> buildUploadResult(Long bankStatementId, Long cashierAccountId,
                                                   int total, boolean truncated, String msg) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("bankStatementId", bankStatementId);
        result.put("cashierAccountId", cashierAccountId);
        result.put("total", total);
        if (truncated) result.put("truncated", true);
        if (msg != null) result.put("msg", msg);
        return result;
    }

    private Map<String, Object> buildProcessResult(int total, int journalCount, int voucherCount,
                                                    int unmatchedCount, int noTemplateCount,
                                                    int newTemplateCount, String msg) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", total);
        result.put("journalCount", journalCount);
        result.put("voucherCount", voucherCount);
        result.put("unmatchedCount", unmatchedCount);
        result.put("noTemplateCount", noTemplateCount);
        result.put("newTemplateCount", newTemplateCount);
        if (msg != null) result.put("msg", msg);
        return result;
    }

    private Map<String, Object> buildResult(int total, int journalCount, int voucherCount,
                                            int unmatchedCount, int noTemplateCount,
                                            String msg, boolean truncated) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", total);
        result.put("journalCount", journalCount);
        result.put("voucherCount", voucherCount);
        result.put("unmatchedCount", unmatchedCount);
        result.put("noTemplateCount", noTemplateCount);
        if (truncated) result.put("truncated", true);
        if (msg != null) result.put("msg", msg);
        return result;
    }
}
