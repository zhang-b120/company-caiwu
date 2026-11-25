package cn.gson.financial.task.workbench.service;

import cn.gson.financial.kernel.model.entity.*;
import cn.gson.financial.kernel.model.repo.*;
import cn.gson.financial.kernel.service.CashierAccountService;
import cn.gson.financial.kernel.service.CashierJournalService;
import cn.gson.financial.kernel.service.SubjectService;
import cn.hutool.core.util.StrUtil;
import com.alibaba.dashscope.aigc.generation.Generation;
import com.alibaba.dashscope.aigc.generation.GenerationParam;
import com.alibaba.dashscope.aigc.generation.GenerationResult;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversation;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationParam;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationResult;
import com.alibaba.dashscope.common.Message;
import com.alibaba.dashscope.common.MultiModalMessage;
import com.alibaba.dashscope.common.Role;
import com.alibaba.excel.EasyExcel;
import com.alibaba.fastjson2.JSON;
import com.itextpdf.text.pdf.PdfReader;
import com.itextpdf.text.pdf.parser.PdfTextExtractor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 任务工作台 - 银行流水智能导入服务
 *
 * Excel 流程（AI 只做格式识别，Java 机械读取数据）：
 *   ① 读取 Excel 为结构化数据（保留行列位置）
 *   ② AI：识别列结构，返回"读取指令"（列索引、金额模式、日期格式等）
 *   ③ Java：按读取指令逐行提取交易记录（支持 4 种金额结构）
 *   ④ Java：收支类别匹配、科目查/建、数据清洗过滤
 *   ⑤ 保存日记账
 *   ⑥ 更新任务工作台状态
 *
 * PDF/图片 流程（AI 完成格式识别 + 数据提取）：
 *   ①② 同上
 *   ③ AI：提取交易记录 + 匹配收支类别
 *   ④⑤⑥ 同上
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskBankImportService {

    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    private TaskBankImportService self;

    @jakarta.annotation.Resource
    @org.springframework.context.annotation.Lazy
    private com.querydsl.jpa.impl.JPAQueryFactory jqf;

    private final AccountSetsRepository accountSetsRepository;
    private final TaskPeriodRepository taskPeriodRepository;
    private final TaskDataSubRepository taskDataSubRepository;
    private final CashierIoTypeRepository cashierIoTypeRepository;
    private final CashierAccountRepository cashierAccountRepository;
    private final CashierAccountService cashierAccountService;
    private final BankLibRepository bankLibRepository;
    private final SubjectService subjectService;
    private final CashierJournalService cashierJournalService;
    private final CashierIoTypeVoucherTemplateRepository voucherTemplateRepository;
    private final BankImportPatternRepository bankImportPatternRepository;
    private final SubjectRepository subjectRepository;
    private final BankStatementRepository bankStatementRepository;
    private final BankStatementDetailRepository bankStatementDetailRepository;
    private final cn.gson.financial.kernel.service.VoucherService voucherService;

    @Value("${qwen.api-key:}")
    private String qwenApiKey;

    @Value("${qwen.model:qwen-plus}")
    private String qwenModel;

    @Value("${qwen.vision-model:qwen-vl-plus}")
    private String qwenVisionModel;

    // 判断对方是否为公司（含关键词或长度>3 视为公司，否则视为个人）
    private static final Set<String> COMPANY_KEYWORDS = Set.of(
            "公司", "企业", "集团", "有限", "股份", "合伙", "事务所", "工厂", "商行", "商店",
            "银行", "基金", "证券", "保险", "信托", "中心", "学校", "医院", "局", "厅", "部"
    );

    /**
     * 判断对方户名是公司还是个人。
     * 规则：姓名长度 <= 3 且不含公司关键词 → 个人；否则 → 公司
     */
    private static boolean isCompany(String counterparty) {
        if (StrUtil.isBlank(counterparty)) return false;
        String name = counterparty.trim();
        for (String kw : COMPANY_KEYWORDS) {
            if (name.contains(kw)) return true;
        }
        return name.length() > 3;
    }

    private static final int MAX_RETRY = 2;
    private static final DateTimeFormatter PERIOD_FMT = DateTimeFormatter.ofPattern("yyyy-MM");
    private static final List<DateTimeFormatter> DATE_FMTS = List.of(
            DateTimeFormatter.ofPattern("yyyy-MM-dd"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd"),
            DateTimeFormatter.ofPattern("yyyyMMdd"),
            DateTimeFormatter.ofPattern("yyyy年MM月dd日"),
            DateTimeFormatter.ofPattern("MM/dd/yyyy"),
            DateTimeFormatter.ofPattern("dd/MM/yyyy"),
            DateTimeFormatter.ofPattern("yyyy.MM.dd")
    );

    // ═══════════════════════════════════════════════════════════════════
    // 主入口
    // ═══════════════════════════════════════════════════════════════════

    public Map<String, Object> importBankStatement(MultipartFile file, Long accountSetId,
                                                    String period, String bankName,
                                                    Long cashierAccountId, Long userId) {
        AccountSets accountSets = accountSetsRepository.findById(accountSetId)
                .orElseThrow(() -> new IllegalArgumentException("账套不存在：" + accountSetId));

        String p = StrUtil.isBlank(period) ? YearMonth.now().format(PERIOD_FMT) : period;
        log.info("══════════════════════════════════════════");
        log.info("【银行流水导入开始】accountSetId={}, period={}, bankNameHint={}, cashierAccountId={}",
                accountSetId, p, bankName, cashierAccountId);

        try {
            String fileName = file.getOriginalFilename() != null
                    ? file.getOriginalFilename().toLowerCase() : "";
            FileType fileType = detectFileType(fileName, file.getContentType());
            log.info("【①文件读取】fileName={}, fileType={}, size={}bytes",
                    file.getOriginalFilename(), fileType, file.getSize());

            // ① 读取文件 + ② AI格式识别 + ③ 数据提取
            List<Map<String, Object>> transactions;
            String detectedBankName;
            String accountNumber;

            if (fileType == FileType.EXCEL) {
                // ── Excel：AI 只识别格式，Java 机械读取数据 ──
                List<Map<Integer, String>> rawData = readExcelAsRawData(file);
                String previewText = rawDataToText(rawData, 25);
                log.info("【①文件读取】Excel结构化读取, 总行数={}, 预览文本长度={}",
                        rawData.size(), previewText.length());
                if (StrUtil.isBlank(previewText)) {
                    throw new RuntimeException("文件内容为空，无法解析");
                }

                log.info("【②格式识别】开始调用AI（仅发送前25行）...");
                Map<String, Object> formatInfo = callStep1ForFormat(previewText, null, null, bankName);
                detectedBankName = str(formatInfo, "bankName");
                accountNumber = str(formatInfo, "accountNumber");
                log.info("【②格式识别】完成: {}", JSON.toJSONString(formatInfo));

                log.info("【③数据提取】Java按格式指令读取Excel...");
                transactions = extractTransactionsFromExcel(rawData, formatInfo);
                log.info("【③数据提取】完成，共 {} 条记录", transactions.size());

            } else {
                // ── PDF/图片：AI 完成格式识别 + 数据提取 ──
                String rawText = null;
                String base64Image = null;
                String mediaType = null;

                if (fileType == FileType.IMAGE) {
                    base64Image = Base64.getEncoder().encodeToString(file.getBytes());
                    mediaType = resolveMediaType(fileName);
                    log.info("【①文件读取】图片模式, mediaType={}", mediaType);
                } else {
                    rawText = readPdfAsText(file);
                    if (StrUtil.isBlank(rawText)) {
                        base64Image = Base64.getEncoder().encodeToString(file.getBytes());
                        mediaType = "application/pdf";
                        log.info("【①文件读取】PDF文本提取为空，转为视觉模式");
                    } else {
                        log.info("【①文件读取】PDF文本提取成功，长度={}", rawText.length());
                    }
                }

                if (StrUtil.isBlank(rawText) && base64Image == null) {
                    throw new RuntimeException("文件内容为空，无法解析");
                }

                log.info("【②格式识别】开始调用AI...");
                Map<String, Object> formatInfo = callStep1ForFormat(rawText, base64Image, mediaType, bankName);
                String formatDescription = JSON.toJSONString(formatInfo);
                detectedBankName = str(formatInfo, "bankName");
                accountNumber = str(formatInfo, "accountNumber");
                log.info("【②格式识别】完成: {}", formatDescription);

                log.info("【③数据提取】AI提取PDF/图片数据...");
                String categoryList = buildCategoryListText(accountSetId);
                String subjectList = buildSubjectListText(accountSetId);
                String patternList = buildPatternListText(accountSetId);
                transactions = callStep2ForData(
                        rawText, base64Image, mediaType, formatDescription,
                        categoryList, subjectList, patternList);
                log.info("【③数据提取】完成，共 {} 条记录", transactions.size());
            }

            // ④ Java：收支类别回填 + 特殊科目查/建 + 清洗过滤
            String finalBankName = StrUtil.isNotBlank(detectedBankName) ? detectedBankName
                    : StrUtil.isNotBlank(bankName) ? bankName : "未知银行";
            log.info("【④过滤处理】开始，finalBankName={}, 目标期间={}", finalBankName, p);
            List<Map<String, Object>> processed = processTransactions(
                    transactions, accountSetId, accountSets, p);
            log.info("【④过滤处理】完成，有效记录={} 条", processed.size());

            if (processed.isEmpty()) {
                log.warn("【④过滤处理】所有记录均被过滤，原始记录数={}", transactions.size());
                return buildResult(transactions.size(), 0, finalBankName,
                        "解析到交易记录但所有记录均被过滤（日期或金额无效）");
            }

            // ④.5 前端未指定账户时，自动匹配或新增银行账户
            log.info("【④.5账户匹配】开始，cashierAccountId={}, accountNumber={}, finalBankName={}",
                    cashierAccountId, accountNumber, finalBankName);
            if (cashierAccountId == null) {
                // 先按账号匹配
                if (StrUtil.isNotBlank(accountNumber)) {
                    cashierAccountId = cashierAccountRepository
                            .findFirstByAccountSetsIdAndCardNumber(accountSetId, accountNumber.trim())
                            .map(ca -> ca.getId()).orElse(null);
                    log.info("【④.5账户匹配】按账号匹配结果: {}", cashierAccountId);
                }
                // 匹配不到 → 按银行名称查找
                if (cashierAccountId == null && StrUtil.isNotBlank(finalBankName) && !"未知银行".equals(finalBankName)) {
                    cashierAccountId = cashierAccountRepository
                            .findFirstByAccountSetsIdAndName(accountSetId, finalBankName)
                            .map(ca -> ca.getId()).orElse(null);
                    log.info("【④.5账户匹配】按名称匹配结果: {}", cashierAccountId);
                }
                // 还是没有 → 自动新增科目 + 银行账户
                if (cashierAccountId == null && StrUtil.isNotBlank(finalBankName) && !"未知银行".equals(finalBankName)) {
                    try {
                        Map<String, Object> created = self.createBankAccount(accountSetId, finalBankName, accountNumber);
                        cashierAccountId = ((Number) created.get("id")).longValue();
                        log.info("【④.5账户匹配】自动新增银行账户: name={}, accountNumber={}, id={}", finalBankName, accountNumber, cashierAccountId);
                    } catch (Exception e) {
                        log.warn("【④.5账户匹配】自动新增银行账户失败: {}", e.getMessage());
                    }
                }
            }
            log.info("【④.5账户匹配】最终cashierAccountId={}", cashierAccountId);

            // ⑤ 保存：先建 BankStatement 留底，再写明细+日记账（明细去重，命中已有跳过）
            log.info("【⑤保存】开始保存 {} 条记录...", processed.size());
            BankStatement statement = new BankStatement();
            statement.setAccountSetsId(accountSetId);
            statement.setCreateId(userId != null ? userId : 0L);
            statement.setCreateTime(LocalDateTime.now());
            statement.setIsDelete(0);
            statement.setPeriod(p);
            statement.setBankName(finalBankName);
            statement.setCashierAccountId(cashierAccountId);
            statement.setFileName(file.getOriginalFilename());
            statement.setDetailCount(0);
            BankStatement savedStatement = bankStatementRepository.save(statement);

            int savedCount = self.saveToDatabase(processed, accountSetId, accountSets, p, finalBankName,
                    accountNumber, cashierAccountId, userId, savedStatement.getId());

            // 回写明细数
            savedStatement.setDetailCount(savedCount);
            bankStatementRepository.save(savedStatement);

            // ⑥ 更新任务工作台状态
            updateTaskDataSub(accountSetId, p, savedCount);

            log.info("【银行流水导入完成】accountSetId={}, period={}, 保存={} 条, bankStatementId={}",
                    accountSetId, p, savedCount, savedStatement.getId());
            log.info("══════════════════════════════════════════");
            return buildResult(transactions.size(), savedCount, finalBankName, null);

        } catch (Exception e) {
            log.error("银行流水导入失败: accountSetId={}", accountSetId, e);
            throw new RuntimeException("银行流水导入失败：" + e.getMessage(), e);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // ① 文件读取
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 读取 Excel 为结构化数据（保留原始行位置，索引即行号）。
     * 返回 List 的 index 对应 Excel 的行号（0-based），空行保留为空 Map。
     */
    private List<Map<Integer, String>> readExcelAsRawData(MultipartFile file) {
        String fileName = file.getOriginalFilename() != null
                ? file.getOriginalFilename().toLowerCase() : "";
        return fileName.endsWith(".xls") ? readXlsAsRawData(file) : readXlsxAsRawData(file);
    }

    private List<Map<Integer, String>> readXlsxAsRawData(MultipartFile file) {
        List<Map<Integer, String>> rows = new ArrayList<>();
        try {
            EasyExcel.read(file.getInputStream())
                    .sheet(0)
                    .registerReadListener(new com.alibaba.excel.read.listener.ReadListener<Map<Integer, String>>() {
                        @Override
                        public void invoke(Map<Integer, String> data,
                                           com.alibaba.excel.context.AnalysisContext ctx) {
                            rows.add(data);
                        }
                        @Override
                        public void doAfterAllAnalysed(com.alibaba.excel.context.AnalysisContext ctx) {}
                    })
                    .headRowNumber(0)
                    .doRead();
        } catch (Exception e) {
            throw new RuntimeException("Excel 读取失败：" + e.getMessage(), e);
        }
        return rows;
    }

    private List<Map<Integer, String>> readXlsAsRawData(MultipartFile file) {
        List<Map<Integer, String>> rows = new ArrayList<>();
        try (Workbook workbook = WorkbookFactory.create(file.getInputStream())) {
            Sheet sheet = workbook.getSheetAt(0);
            int lastRow = sheet.getLastRowNum();
            for (int r = 0; r <= lastRow; r++) {
                Row row = sheet.getRow(r);
                Map<Integer, String> rowData = new LinkedHashMap<>();
                if (row != null) {
                    for (int ci = 0; ci < row.getLastCellNum(); ci++) {
                        rowData.put(ci, getCellString(row.getCell(ci)));
                    }
                }
                rows.add(rowData);
            }
        } catch (Exception e) {
            throw new RuntimeException("XLS 读取失败：" + e.getMessage(), e);
        }
        return rows;
    }

    /**
     * 将结构化数据的前 maxRows 个非空行转为文本，供 AI 格式识别使用。
     * 格式：第N行: ["列0值", "列1值", ...]（N 从1开始，与 Excel 行号一致）
     */
    private String rawDataToText(List<Map<Integer, String>> rows, int maxRows) {
        if (rows == null || rows.isEmpty()) return "";
        int maxCol = rows.stream()
                .mapToInt(r -> r.keySet().stream().mapToInt(Integer::intValue).max().orElse(0))
                .max().orElse(0);

        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (int r = 0; r < rows.size() && count < maxRows; r++) {
            Map<Integer, String> row = rows.get(r);
            List<String> vals = new ArrayList<>();
            boolean allEmpty = true;
            for (int i = 0; i <= maxCol; i++) {
                String v = row.getOrDefault(i, "");
                vals.add(v);
                if (StrUtil.isNotBlank(v)) allEmpty = false;
            }
            if (allEmpty) continue;
            sb.append("第").append(r + 1).append("行: ").append(JSON.toJSONString(vals)).append("\n");
            count++;
        }
        return sb.toString();
    }

    private String getCellString(Cell cell) {
        if (cell == null) return "";
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue().trim();
            case NUMERIC -> {
                if (DateUtil.isCellDateFormatted(cell)) {
                    yield cell.getLocalDateTimeCellValue().toLocalDate().toString();
                }
                yield BigDecimal.valueOf(cell.getNumericCellValue()).stripTrailingZeros().toPlainString();
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

    private String readPdfAsText(MultipartFile file) {
        StringBuilder sb = new StringBuilder();
        try {
            PdfReader reader = new PdfReader(file.getInputStream());
            for (int i = 1; i <= reader.getNumberOfPages(); i++) {
                String text = PdfTextExtractor.getTextFromPage(reader, i);
                if (StrUtil.isNotBlank(text)) sb.append(text).append("\n");
            }
            reader.close();
        } catch (Exception e) {
            log.warn("PDF 文本提取失败: {}", e.getMessage());
        }
        return sb.toString().trim();
    }

    // ═══════════════════════════════════════════════════════════════════
    // 构建给 AI 的参考列表
    // ═══════════════════════════════════════════════════════════════════

    private String buildCategoryListText(Long accountSetId) {
        List<CashierIoType> list = cashierIoTypeRepository.findByAccountSetsId(accountSetId);
        if (list == null || list.isEmpty()) return "（暂无收支类别）";
        StringBuilder sb = new StringBuilder();
        for (CashierIoType t : list) {
            String direction = t.getType() == CashierIoType.Type.收 ? "收" : "支";
            sb.append(t.getName()).append(" | ").append(direction);
            // 查凭证模板默认科目
            CashierIoTypeVoucherTemplate.Direction tplDir =
                    t.getType() == CashierIoType.Type.收
                            ? CashierIoTypeVoucherTemplate.Direction.贷
                            : CashierIoTypeVoucherTemplate.Direction.借;
            voucherTemplateRepository.findByAccountSetsIdAndIoTypeId(accountSetId, t.getId())
                    .stream()
                    .filter(tpl -> CashierAccount.CashierAccountType.银行存款.equals(tpl.getJournalType())
                            && tplDir.equals(tpl.getDirection())
                            && tpl.getSubjectId() != null)
                    .findFirst()
                    .ifPresent(tpl -> {
                        subjectRepository.findById(
                                new cn.gson.financial.kernel.model.hibernate.PrimaryKey(tpl.getSubjectId(), accountSetId))
                                .ifPresent(s -> sb.append(" | 默认科目:").append(s.getCode()).append(" ").append(s.getName()));
                    });
            sb.append("\n");
        }
        return sb.toString();
    }

    private String buildPatternListText(Long accountSetId) {
        List<BankImportPattern> patterns = bankImportPatternRepository
                .findByAccountSetsIdOrderByMatchCountDesc(accountSetId);
        if (patterns == null || patterns.isEmpty()) return "（暂无学习规律）";
        StringBuilder sb = new StringBuilder();
        for (BankImportPattern p : patterns) {
            if (StrUtil.isBlank(p.getCounterparty())) continue;
            sb.append(p.getCounterparty()).append(" | ")
              .append(StrUtil.nullToEmpty(p.getIoTypeName())).append(" | ")
              .append(StrUtil.nullToEmpty(p.getIoTypeDirection()));
            if (StrUtil.isNotBlank(p.getSubjectCode())) {
                sb.append(" | ").append(p.getSubjectCode()).append(" ").append(StrUtil.nullToEmpty(p.getSubjectName()));
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private String buildSubjectListText(Long accountSetId) {
        var subjects = subjectService.list(accountSetId);
        if (subjects == null || subjects.isEmpty()) return "（暂无科目）";
        StringBuilder sb = new StringBuilder();
        for (var s : subjects) {
            sb.append(s.getCode()).append(" | ").append(s.getName()).append("\n");
        }
        return sb.toString();
    }

    // ═══════════════════════════════════════════════════════════════════
    // ③ Java 数据提取：按 AI 返回的格式指令，从 Excel 结构化数据中提取交易记录
    // ═══════════════════════════════════════════════════════════════════

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractTransactionsFromExcel(
            List<Map<Integer, String>> allRows, Map<String, Object> formatInfo) {

        // 行定位（AI 返回的是 1-based，转为 0-based）
        int headerRowIdx = getInt(formatInfo, "headerRowIndex", 1) - 1;
        int dataStartIdx = getInt(formatInfo, "dataStartRowIndex", 2) - 1;
        if (dataStartIdx < 0) dataStartIdx = headerRowIdx + 1;
        List<String> skipKeywords = getStringList(formatInfo, "skipKeywords");

        // 列定位（0-based）
        int dateCol = getInt(formatInfo, "dateColumnIndex", -1);
        int counterpartyCol = getInt(formatInfo, "counterpartyColumnIndex", -1);
        int remarksCol = getInt(formatInfo, "remarksColumnIndex", -1);
        int serialNoCol = getInt(formatInfo, "serialNoColumnIndex", -1);
        int balanceCol = getInt(formatInfo, "balanceColumnIndex", -1);

        // 金额结构
        String amountStructure = str(formatInfo, "amountStructure");
        int debitCol = getInt(formatInfo, "debitColumnIndex", -1);
        int creditCol = getInt(formatInfo, "creditColumnIndex", -1);
        int amountCol = getInt(formatInfo, "amountColumnIndex", -1);
        int directionCol = getInt(formatInfo, "directionColumnIndex", -1);
        List<String> expenseValues = getStringList(formatInfo, "directionExpenseValues");
        List<String> incomeValues = getStringList(formatInfo, "directionIncomeValues");

        // 日期格式
        String datePattern = str(formatInfo, "datePattern");
        DateTimeFormatter customFmt = null;
        if (StrUtil.isNotBlank(datePattern)) {
            try {
                customFmt = DateTimeFormatter.ofPattern(datePattern);
            } catch (Exception e) {
                log.warn("【③数据提取】AI返回的日期格式无效: {}", datePattern);
            }
        }

        log.info("【③数据提取】格式指令: amountStructure={}, dataStartRow={}, dateCol={}, " +
                        "debitCol={}, creditCol={}, amountCol={}, directionCol={}, " +
                        "counterpartyCol={}, remarksCol={}, balanceCol={}",
                amountStructure, dataStartIdx + 1, dateCol,
                debitCol, creditCol, amountCol, directionCol,
                counterpartyCol, remarksCol, balanceCol);

        List<Map<String, Object>> transactions = new ArrayList<>();
        BigDecimal prevBalance = null;

        for (int i = dataStartIdx; i < allRows.size(); i++) {
            Map<Integer, String> row = allRows.get(i);

            // 跳过空行
            String rowText = row.values().stream()
                    .filter(StrUtil::isNotBlank)
                    .collect(Collectors.joining(" "));
            if (StrUtil.isBlank(rowText)) continue;

            // 跳过包含关键词的行（合计、汇总等）
            boolean shouldSkip = false;
            for (String kw : skipKeywords) {
                if (rowText.contains(kw)) {
                    shouldSkip = true;
                    break;
                }
            }
            if (shouldSkip) {
                log.debug("【③数据提取】跳过行（命中关键词）: 第{}行", i + 1);
                continue;
            }

            // 提取日期
            String dateStr = getCell(row, dateCol);
            if (StrUtil.isBlank(dateStr)) continue;
            // 去除日期中可能包含的时间部分
            if (dateStr.contains(" ")) dateStr = dateStr.split(" ")[0];

            // 提取各字段
            String counterparty = getCell(row, counterpartyCol);
            String remarks = getCell(row, remarksCol);
            String serialNo = getCell(row, serialNoCol);
            BigDecimal balance = decimalOrNull(getCell(row, balanceCol));

            // ── 按 amountStructure 判断收支 ──
            BigDecimal income = null;
            BigDecimal expense = null;

            switch (amountStructure != null ? amountStructure : "") {
                case "debit_credit": {
                    BigDecimal debit = decimalOrNull(getCell(row, debitCol));
                    BigDecimal credit = decimalOrNull(getCell(row, creditCol));
                    if (debit != null && debit.compareTo(BigDecimal.ZERO) > 0) expense = debit;
                    if (credit != null && credit.compareTo(BigDecimal.ZERO) > 0) income = credit;
                    break;
                }
                case "amount_direction": {
                    BigDecimal amount = decimalOrNull(getCell(row, amountCol));
                    String direction = getCell(row, directionCol);
                    if (amount != null && amount.compareTo(BigDecimal.ZERO) > 0
                            && StrUtil.isNotBlank(direction)) {
                        String dir = direction.trim();
                        if (expenseValues.stream().anyMatch(v -> v.equalsIgnoreCase(dir))) {
                            expense = amount;
                        } else if (incomeValues.stream().anyMatch(v -> v.equalsIgnoreCase(dir))) {
                            income = amount;
                        }
                    }
                    break;
                }
                case "signed": {
                    String amountStr = getCell(row, amountCol);
                    if (StrUtil.isNotBlank(amountStr)) {
                        String cleaned = amountStr.trim().replace(",", "");
                        boolean isNegative = cleaned.startsWith("-")
                                || (cleaned.startsWith("(") && cleaned.endsWith(")"));
                        BigDecimal amount = decimalOrNull(amountStr);
                        if (amount != null && amount.compareTo(BigDecimal.ZERO) > 0) {
                            if (isNegative) {
                                expense = amount;
                            } else {
                                income = amount;
                            }
                        }
                    }
                    break;
                }
                case "balance_based": {
                    BigDecimal amount = decimalOrNull(getCell(row, amountCol));
                    if (amount != null && amount.compareTo(BigDecimal.ZERO) > 0) {
                        if (balance != null && prevBalance != null) {
                            // 通过余额变化判断：余额涨了=收入，余额跌了=支出
                            if (balance.compareTo(prevBalance) > 0) {
                                income = amount;
                            } else {
                                expense = amount;
                            }
                        } else if (balance != null) {
                            // 第一行：推算上一行余额，判断方向
                            BigDecimal impliedPrev = balance.subtract(amount);
                            if (impliedPrev.compareTo(BigDecimal.ZERO) >= 0) {
                                income = amount; // 余额 = 前余额 + 金额 → 收入
                            } else {
                                expense = amount; // 余额 = 前余额 - 金额 → 支出
                            }
                        }
                    }
                    if (balance != null) prevBalance = balance;
                    break;
                }
                default:
                    log.warn("【③数据提取】未知的 amountStructure: {}", amountStructure);
                    break;
            }

            // 跳过无金额的行
            boolean hasAmount = (income != null && income.compareTo(BigDecimal.ZERO) > 0)
                    || (expense != null && expense.compareTo(BigDecimal.ZERO) > 0);
            if (!hasAmount) continue;

            // 构建交易记录（与 AI 第二步返回格式一致，供 processTransactions 使用）
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("tradeDate", dateStr);
            t.put("income", income);
            t.put("expense", expense);
            t.put("counterparty", counterparty);
            t.put("remarks", remarks);
            t.put("serialNo", serialNo);
            t.put("balance", balance);
            transactions.add(t);
        }

        log.info("【③数据提取】从第{}行到第{}行，共提取 {} 条有效交易",
                dataStartIdx + 1, allRows.size(), transactions.size());
        return transactions;
    }

    /** 取单元格值 */
    private String getCell(Map<Integer, String> row, int colIndex) {
        if (colIndex < 0 || row == null) return null;
        String val = row.get(colIndex);
        return StrUtil.isNotBlank(val) ? val.trim() : null;
    }

    /** 从格式信息中取整数值 */
    private int getInt(Map<String, Object> map, String key, int defaultVal) {
        Object v = map.get(key);
        if (v instanceof Number) return ((Number) v).intValue();
        if (v != null) {
            try { return Integer.parseInt(v.toString()); }
            catch (Exception e) { return defaultVal; }
        }
        return defaultVal;
    }

    /** 从格式信息中取字符串列表 */
    @SuppressWarnings("unchecked")
    private List<String> getStringList(Map<String, Object> map, String key) {
        Object v = map.get(key);
        if (v instanceof List) {
            return ((List<?>) v).stream().map(Object::toString).collect(Collectors.toList());
        }
        return Collections.emptyList();
    }

    // ═══════════════════════════════════════════════════════════════════
    // ② AI 第一步：格式识别
    // ═══════════════════════════════════════════════════════════════════

    private Map<String, Object> callStep1ForFormat(String rawText, String base64Image,
                                                    String mediaType, String bankNameHint) {
        String systemPrompt = loadPrompt("prompts/bank-format.txt");
        String userMsg = "请分析以下银行流水的格式结构。"
                + (StrUtil.isNotBlank(bankNameHint) ? "（提示：可能来自" + bankNameHint + "）" : "")
                + "\n\n## 银行流水原始内容\n\n"
                + (rawText != null ? rawText : "（见图片/PDF）");

        Exception lastError = null;
        for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {
            try {
                String response = base64Image != null
                        ? callQwenVision(base64Image, mediaType, systemPrompt, userMsg)
                        : callQwenText(systemPrompt, userMsg, "第一步-格式识别");
                log.info("【第一步-格式识别】AI原始返回:\n{}", response);
                Map<String, Object> parsed = parseJson(response);
                if (parsed != null && !parsed.isEmpty()) return parsed;
                lastError = new RuntimeException("返回 JSON 为空");
            } catch (Exception e) {
                lastError = e;
                log.warn("格式识别失败（第{}次）: {}", attempt, e.getMessage());
            }
        }
        throw new RuntimeException("格式识别失败：" + lastError.getMessage(), lastError);
    }

    // ═══════════════════════════════════════════════════════════════════
    // ③ AI 第二步：数据提取 + 收支类别 + 特殊科目
    // ═══════════════════════════════════════════════════════════════════

    private List<Map<String, Object>> callStep2ForData(String rawText, String base64Image,
                                                        String mediaType, String formatDescription,
                                                        String categoryList, String subjectList,
                                                        String patternList) {
        String systemPrompt = loadPrompt("prompts/bank-data.txt")
                .replace("${formatDescription}", formatDescription)
                .replace("${categoryList}", categoryList)
                .replace("${subjectList}", subjectList)
                .replace("${patternList}", patternList);

        String userMsg = "请根据格式说明，从以下银行流水中提取所有交易记录并匹配收支类别。\n\n"
                + "## 银行流水原始内容\n\n"
                + (rawText != null ? rawText : "（见图片/PDF）");

        Exception lastError = null;
        for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {
            try {
                String response = base64Image != null
                        ? callQwenVision(base64Image, mediaType, systemPrompt, userMsg)
                        : callQwenText(systemPrompt, userMsg, "第二步-数据提取");
                log.info("【第二步-数据提取】AI原始返回:\n{}", response);
                Map<String, Object> parsed = parseJson(response);
                List<Map<String, Object>> list = getList(parsed, "transactions");
                if (!list.isEmpty()) {
                    log.info("数据提取成功（第{}次）：{} 条", attempt, list.size());
                    return list;
                }
                lastError = new RuntimeException("transactions 列表为空");
            } catch (Exception e) {
                lastError = e;
                log.warn("数据提取失败（第{}次）: {}", attempt, e.getMessage());
            }
        }
        throw new RuntimeException("数据提取失败：" + lastError.getMessage(), lastError);
    }

    // ═══════════════════════════════════════════════════════════════════
    // ④ Java：收支类别回填 + 特殊科目查/建 + 清洗过滤
    // ═══════════════════════════════════════════════════════════════════

    private List<Map<String, Object>> processTransactions(List<Map<String, Object>> transactions,
                                                           Long accountSetId, AccountSets accountSets,
                                                           String period) {
        int[] ym = parsePeriod(period);
        Integer encLen = accountSets.getEncoding() != null
                ? accountSets.getEncoding().stream().reduce(Integer::sum).orElse(0) : 12;

        log.info("开始过滤处理: 总记录={}, 期间过滤={}-{}", transactions.size(),
                ym != null ? ym[0] : "不限", ym != null ? ym[1] : "不限");

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> t : transactions) {

            // 日期校验（不再按期间过滤，billDate 统一使用导入当天）
            String dateStr = str(t, "tradeDate");
            LocalDate date = parseDate(dateStr);
            if (date == null) {
                log.warn("跳过记录（日期解析失败）: tradeDate='{}', row={}", dateStr, t);
                continue;
            }

            // 金额校验
            BigDecimal income = decimalOrNull(t.get("income"));
            BigDecimal expense = decimalOrNull(t.get("expense"));
            boolean hasAmount = (income != null && income.compareTo(BigDecimal.ZERO) > 0)
                    || (expense != null && expense.compareTo(BigDecimal.ZERO) > 0);
            if (!hasAmount) {
                log.warn("跳过记录（金额为空或0）: income={}, expense={}, row={}", t.get("income"), t.get("expense"), t);
                continue;
            }

            Map<String, Object> row = new LinkedHashMap<>(t);
            row.put("_date", date);
            row.put("_income", income);
            row.put("_expense", expense);

            String counterparty = str(t, "counterparty");
            Long subjectId = null;
            Long ioTypeId = null;

            // 优先级1：历史学习规则（BankImportPattern）
            if (StrUtil.isNotBlank(counterparty)) {
                BankImportPattern pattern = bankImportPatternRepository
                        .findByAccountSetsIdAndCounterparty(accountSetId, counterparty.trim())
                        .orElse(null);
                if (pattern != null) {
                    if (pattern.getSubjectId() != null) subjectId = pattern.getSubjectId();
                    if (pattern.getIoTypeId() != null) ioTypeId = pattern.getIoTypeId();
                    log.info("【匹配】命中历史学习规则: counterparty={}, ioTypeId={}, subjectId={}",
                            counterparty, ioTypeId, subjectId);
                }
            }

            // 优先级2：默认规则 — 按收支方向+对方类型（公司/个人）匹配收支类别+对方科目
            if (subjectId == null && StrUtil.isNotBlank(counterparty)) {
                boolean isInc = isIncome(income);
                boolean isCorp = isCompany(counterparty);

                // 收支类别：收+公司→销售收入，收+个人→其他销售收入，支+公司→支出，支+个人→其他支出
                String ioTypeName = isInc
                        ? (isCorp ? "销售收入" : "其他销售收入")
                        : (isCorp ? "支出" : "其他支出");
                CashierIoType.Type ioDirection = isInc ? CashierIoType.Type.收 : CashierIoType.Type.支;
                ioTypeId = cashierIoTypeRepository
                        .findFirstByAccountSetsIdAndNameAndType(accountSetId, ioTypeName, ioDirection)
                        .map(CashierIoType::getId)
                        .orElse(null);
                if (ioTypeId == null) {
                    log.warn("【匹配】收支类别不存在: name={}, type={}", ioTypeName, ioDirection);
                }

                // 对方科目：收+公司→应收账款，收+个人→其他应收款，支+公司→应付账款，支+个人→其他应付款
                String parentSubjectName = isInc
                        ? (isCorp ? "应收账款" : "其他应收款")
                        : (isCorp ? "应付账款" : "其他应付款");
                subjectId = findOrCreateChildSubject(
                        parentSubjectName, counterparty.trim(), accountSetId, accountSets, encLen);

                log.info("【匹配】默认规则: {}+{} → 类别:{}, 科目:{}/{}, ioTypeId={}, subjectId={}",
                        isInc ? "收" : "支", isCorp ? "公司" : "个人",
                        ioTypeName, parentSubjectName, counterparty, ioTypeId, subjectId);
            }
            row.put("_ioTypeId", ioTypeId);
            row.put("_subjectId", subjectId);

            result.add(row);
        }

        // 合并：收支方向一致 + 对方名称一致的记录合并（金额累加）
        result = mergeByCounterparty(result);

        return result;
    }

    /**
     * 合并同一收支方向 + 同一对方名称的记录，金额累加。
     * 合并键 = 收支方向(income/expense) + counterparty
     */
    private List<Map<String, Object>> mergeByCounterparty(List<Map<String, Object>> rows) {
        // key = "收|对方名称" 或 "支|对方名称"
        Map<String, Map<String, Object>> merged = new LinkedHashMap<>();

        for (Map<String, Object> row : rows) {
            BigDecimal income = (BigDecimal) row.get("_income");
            BigDecimal expense = (BigDecimal) row.get("_expense");
            String counterparty = str(row, "counterparty");
            if (StrUtil.isBlank(counterparty)) {
                // 无对方名称的不合并
                merged.put("_no_merge_" + merged.size(), row);
                continue;
            }

            String direction = isIncome(income) ? "收" : "支";
            String key = direction + "|" + counterparty.trim();

            if (merged.containsKey(key)) {
                // 合并：金额累加
                Map<String, Object> existing = merged.get(key);
                BigDecimal existingIncome = (BigDecimal) existing.get("_income");
                BigDecimal existingExpense = (BigDecimal) existing.get("_expense");
                if (income != null && existingIncome != null) {
                    existing.put("_income", existingIncome.add(income));
                }
                if (expense != null && existingExpense != null) {
                    existing.put("_expense", existingExpense.add(expense));
                }
                // 摘要拼接
                String existingRemarks = str(existing, "remarks");
                String newRemarks = str(row, "remarks");
                if (StrUtil.isNotBlank(newRemarks) && !newRemarks.equals(existingRemarks)) {
                    existing.put("remarks", StrUtil.isNotBlank(existingRemarks)
                            ? existingRemarks + "；" + newRemarks : newRemarks);
                }
                log.info("【合并】{} → 累加金额, income={}, expense={}",
                        key, existing.get("_income"), existing.get("_expense"));
            } else {
                // 首次出现，复制一份避免修改原数据
                merged.put(key, new LinkedHashMap<>(row));
            }
        }

        List<Map<String, Object>> result = new ArrayList<>(merged.values());
        if (result.size() < rows.size()) {
            log.info("【合并】合并前={} 条，合并后={} 条", rows.size(), result.size());
        }
        return result;
    }

    /**
     * 按父科目名称 + 子科目名称查找或创建子级科目。
     * 例如：parentName="应收账款", childName="XX有限公司" → 在应收账款下创建子科目
     */
    private Long findOrCreateChildSubject(String parentName, String childName,
                                           Long accountSetId, AccountSets accountSets, Integer encLen) {
        try {
            // 查找父科目（按名称）
            var qSubject = cn.gson.financial.kernel.model.entity.QSubject.subject;
            Subject parent = jqf.selectFrom(qSubject)
                    .where(qSubject.accountSetsId.eq(accountSetId)
                            .and(qSubject.name.eq(parentName)))
                    .fetchFirst();
            if (parent == null) {
                log.warn("【科目匹配】父科目不存在: name={}", parentName);
                return null;
            }

            // 查找子科目（同父 + 同名）
            Subject child = jqf.selectFrom(qSubject)
                    .where(qSubject.accountSetsId.eq(accountSetId)
                            .and(qSubject.parentId.eq(parent.getId()))
                            .and(qSubject.name.eq(childName)))
                    .fetchFirst();
            if (child != null) return child.getId();

            // 子科目不存在，自动创建
            // 生成子科目编码：父编码 + 顺序号（按编码规则的下一级位数补零）
            String childCode = generateChildCode(parent, accountSetId, accountSets);
            if (childCode == null) {
                log.warn("【科目匹配】无法生成子科目编码: parent={}", parent.getCode());
                return null;
            }

            Subject subject = new Subject();
            subject.setAccountSetsId(accountSetId);
            subject.setCode(childCode);
            subject.setName(childName);
            subject.setSystemDefault(false);
            subject.setLeaf(true);
            subject.setStatus(true);
            subject.setParentId(parent.getId());
            subject.setLevel(parent.getLevel() + 1);
            subject.setType(parent.getType());
            subject.setBalanceDirection(parent.getBalanceDirection());

            // 父科目标记为非叶子节点
            if (Boolean.TRUE.equals(parent.getLeaf())) {
                parent.setLeaf(false);
                subjectRepository.save(parent);
            }

            subjectService.save(subject, encLen);
            log.info("【科目匹配】自动创建子科目: parent={}({}), child={}({})",
                    parentName, parent.getCode(), childName, childCode);
            return subject.getId();
        } catch (Exception e) {
            log.warn("【科目匹配】子科目查/建失败: parent={}, child={}, error={}",
                    parentName, childName, e.getMessage());
            return null;
        }
    }

    /**
     * 在父科目下生成下一个子科目编码。
     * 例如父编码 1122，编码规则 [4,2,2]，已有子科目 112201、112202，则生成 112203
     */
    private String generateChildCode(Subject parent, Long accountSetId, AccountSets accountSets) {
        List<Integer> encoding = accountSets.getEncoding();
        if (encoding == null || encoding.isEmpty()) return null;

        // 计算子级编码位数
        int parentCodeLen = parent.getCode().length();
        int accumulated = 0;
        int childLevelWidth = 0;
        for (int levelLen : encoding) {
            accumulated += levelLen;
            if (accumulated > parentCodeLen) {
                childLevelWidth = levelLen;
                break;
            }
        }
        if (childLevelWidth == 0) return null; // 已到最大层级

        // 查找当前最大编号
        var qSubject = cn.gson.financial.kernel.model.entity.QSubject.subject;
        List<Subject> siblings = jqf.selectFrom(qSubject)
                .where(qSubject.accountSetsId.eq(accountSetId)
                        .and(qSubject.parentId.eq(parent.getId())))
                .orderBy(qSubject.code.desc())
                .fetch();

        int nextSeq = 1;
        if (!siblings.isEmpty()) {
            String maxCode = siblings.get(0).getCode();
            String seqPart = maxCode.substring(parentCodeLen);
            try {
                nextSeq = Integer.parseInt(seqPart) + 1;
            } catch (NumberFormatException e) {
                nextSeq = siblings.size() + 1;
            }
        }

        // 补零
        String seqStr = String.valueOf(nextSeq);
        while (seqStr.length() < childLevelWidth) seqStr = "0" + seqStr;

        return parent.getCode() + seqStr;
    }

    /**
     * 按编码查找科目，找不到则自动创建（继承父科目属性）
     */
    private Long findOrCreateSubject(String code, String name, Long accountSetId,
                                      AccountSets accountSets, Integer encLen) {
        try {
            Subject existing = subjectService.listByCode(accountSetId, code);
            if (existing != null) return existing.getId();

            // 推断父科目编码（去掉末级位数）
            String parentCode = inferParentCode(code, accountSets);
            Subject parent = StrUtil.isNotBlank(parentCode)
                    ? subjectService.listByCode(accountSetId, parentCode) : null;

            Subject subject = new Subject();
            subject.setAccountSetsId(accountSetId);
            subject.setCode(code);
            subject.setName(StrUtil.isNotBlank(name) ? name : code);
            subject.setSystemDefault(false);
            subject.setLeaf(true);
            subject.setStatus(true);

            if (parent != null) {
                subject.setParentId(parent.getId());
                subject.setLevel(parent.getLevel() + 1);
                subject.setType(parent.getType());
                subject.setBalanceDirection(parent.getBalanceDirection());
            } else {
                subject.setParentId(0L);
                subject.setLevel(1);
                // 按编码首位推断
                char first = code.charAt(0);
                subject.setType(first == '1' || first == '4' || first == '5' ? "资产" : "负债");
                subject.setBalanceDirection(first == '1' || first == '4' ? "借" : "贷");
            }

            subjectService.save(subject, encLen);
            log.info("自动创建科目: code={}, name={}", code, name);
            return subject.getId();
        } catch (Exception e) {
            log.warn("科目查/建失败，跳过: code={}, error={}", code, e.getMessage());
            return null;
        }
    }

    private String inferParentCode(String code, AccountSets accountSets) {
        if (accountSets.getEncoding() == null || code.length() <= 4) return null;
        List<Integer> levels = accountSets.getEncoding();
        int accumulated = 0;
        for (int levelLen : levels) {
            accumulated += levelLen;
            if (accumulated >= code.length()) {
                // 当前级别，父级 = 去掉本级位数
                int parentLen = accumulated - levelLen;
                return parentLen > 0 ? code.substring(0, parentLen) : null;
            }
        }
        return null;
    }

    // ═══════════════════════════════════════════════════════════════════
    // ⑤ 保存
    // ═══════════════════════════════════════════════════════════════════

    @Transactional
    public int saveToDatabase(List<Map<String, Object>> processed, Long accountSetId,
                                AccountSets accountSets, String period, String bankName,
                                String accountNumber, Long cashierAccountId, Long userId,
                                Long bankStatementId) {
        if (cashierAccountId == null) {
            log.warn("【⑤保存】cashierAccountId为空，无法写入日记账");
            return 0;
        }

        // 根据导入账期计算 billDate（统一为账期第一天）
        YearMonth ym = StrUtil.isNotBlank(period) ? YearMonth.parse(period, PERIOD_FMT) : null;
        LocalDate periodStart = ym != null ? ym.atDay(1) : null;

        log.info("【⑤保存】准备写入留底+日记账，共 {} 条, 账期={}, bankStatementId={}",
                processed.size(), period, bankStatementId);
        int successCount = 0;
        int skipDuplicate = 0;
        for (int i = 0; i < processed.size(); i++) {
            Map<String, Object> t = processed.get(i);
            LocalDate date = (LocalDate) t.get("_date");
            BigDecimal income = (BigDecimal) t.get("_income");
            BigDecimal expense = (BigDecimal) t.get("_expense");
            String counterparty = str(t, "counterparty");
            String remarks = str(t, "remarks");
            Long ioTypeId = (Long) t.get("_ioTypeId");
            Long subjectId = (Long) t.get("_subjectId");

            boolean isIncome = income != null && income.compareTo(BigDecimal.ZERO) > 0;
            boolean isExpense = expense != null && expense.compareTo(BigDecimal.ZERO) > 0;
            if (!isIncome && !isExpense) {
                log.warn("【⑤保存】跳过（金额异常）: income={}, expense={}", income, expense);
                continue;
            }

            try {
                BigDecimal amount = isIncome ? income : expense;

                // 去重：先查 BankStatementDetail 留底，再兜底查 CashierJournal
                // （兜底防止历史导入未写留底的数据被重复入账）
                boolean dupDetail = existsBankStatementDetail(accountSetId, cashierAccountId, date,
                        amount, counterparty, isIncome);
                boolean dupJournal = !dupDetail && existsCashierJournalDuplicate(
                        accountSetId, cashierAccountId, date,
                        isIncome ? amount : null, !isIncome ? amount : null, counterparty);
                if (dupDetail || dupJournal) {
                    skipDuplicate++;
                    log.info("【⑤保存】跳过重复: date={}, counterparty={}, amount={}, isIncome={}, source={}",
                            date, counterparty, amount, isIncome, dupDetail ? "detail" : "journal");
                    continue;
                }

                // 1) 写日记账
                CashierJournal journal = new CashierJournal();
                journal.setAccountSetsId(accountSetId);
                journal.setAccountId(cashierAccountId);
                journal.setType(CashierAccount.CashierAccountType.银行存款);
                journal.setTradeDate(date);                          // 原始交易日期（仅作展示）
                LocalDate billDate = periodStart != null ? periodStart : date;
                journal.setBillDate(billDate);                       // 单据日期（账期归属：界面所选账期第一天）
                journal.setImportTime(LocalDateTime.now());
                journal.setIoTypeId(ioTypeId);
                journal.setSubjectId(subjectId);
                journal.setDigest(StrUtil.isNotBlank(remarks) ? remarks : (isIncome ? "来账" : "往账"));
                journal.setOppositeAccountName(counterparty);
                journal.setInit(false);
                if (isIncome) {
                    journal.setDebit(income);
                } else {
                    journal.setCredit(expense);
                }
                CashierJournal savedJournal = cashierJournalService.save(journal, accountSets.getEnableDate());

                // 2) 写 BankStatementDetail 留底，回链 cashier_journal_id
                BankStatementDetail detail = new BankStatementDetail();
                detail.setBankStatementId(bankStatementId);
                detail.setAccountSetId(accountSetId);
                detail.setCashierAccountId(cashierAccountId);
                detail.setTradeDate(date != null ? date.atStartOfDay() : null);
                detail.setTradeAmount(amount);
                detail.setTransactionType(isIncome ? "来账" : "往账");
                detail.setRemarks(remarks);
                detail.setBankName(bankName);
                detail.setAccountNumber(accountNumber);
                detail.setIoTypeId(ioTypeId);
                detail.setSubjectId(subjectId);
                detail.setCashierJournalId(savedJournal.getId());
                detail.setIsDelete(0);
                if (isIncome) {
                    detail.setPayName(counterparty);
                } else {
                    detail.setReceiveName(counterparty);
                }
                bankStatementDetailRepository.save(detail);

                log.info("【⑤保存】第{}/{}条写入成功: journalId={}, detailId={}, tradeDate={}",
                        i + 1, processed.size(), savedJournal.getId(), detail.getId(), date);
                successCount++;
            } catch (Exception e) {
                log.error("【⑤保存】第{}/{}条写入失败: date={}, 原因={}",
                        i + 1, processed.size(), date, e.getMessage(), e);
            }
        }
        log.info("【⑤保存】完成，成功={} 条，跳过重复={} 条，失败={} 条",
                successCount, skipDuplicate, processed.size() - successCount - skipDuplicate);
        return successCount;
    }

    /**
     * 在 BankStatementDetail 中按 (账套 + 银行账户 + 交易日期 + 金额 + 对方户名 + 方向) 查重。
     * 用于多文件追加场景下避免重复入账：同一账户的同一笔流水（无论从哪个文件来）只保留一次。
     */
    private boolean existsBankStatementDetail(Long accountSetId, Long cashierAccountId,
                                               LocalDate tradeDate, BigDecimal amount,
                                               String counterparty, boolean isIncome) {
        cn.gson.financial.kernel.model.entity.QBankStatementDetail qd =
                cn.gson.financial.kernel.model.entity.QBankStatementDetail.bankStatementDetail;

        LocalDateTime dayStart = tradeDate.atStartOfDay();
        LocalDateTime dayEnd = tradeDate.plusDays(1).atStartOfDay();

        var where = qd.accountSetId.eq(accountSetId)
                .and(qd.cashierAccountId.eq(cashierAccountId))
                .and(qd.tradeDate.goe(dayStart))
                .and(qd.tradeDate.lt(dayEnd))
                .and(qd.tradeAmount.eq(amount))
                .and(qd.isDelete.eq(0));

        // 方向匹配
        where = where.and(qd.transactionType.eq(isIncome ? "来账" : "往账"));

        // 对方户名匹配：来账看付款人，往账看收款人
        if (StrUtil.isNotBlank(counterparty)) {
            if (isIncome) {
                where = where.and(qd.payName.eq(counterparty));
            } else {
                where = where.and(qd.receiveName.eq(counterparty));
            }
        }

        return jqf.selectFrom(qd).where(where).fetchFirst() != null;
    }

    /**
     * 兜底：在 CashierJournal 中按 (账套+银行账户+交易日期+金额+方向+对方) 查重。
     * 用于历史导入未写 BankStatementDetail 留底的数据，防止再次上传时重复入账。
     */
    private boolean existsCashierJournalDuplicate(Long accountSetId, Long cashierAccountId,
                                                   LocalDate tradeDate, BigDecimal debit, BigDecimal credit,
                                                   String counterparty) {
        cn.gson.financial.kernel.model.entity.QCashierJournal qj =
                cn.gson.financial.kernel.model.entity.QCashierJournal.cashierJournal;

        var where = qj.accountSetsId.eq(accountSetId)
                .and(qj.accountId.eq(cashierAccountId))
                .and(qj.tradeDate.eq(tradeDate));

        if (debit != null) where = where.and(qj.debit.eq(debit));
        if (credit != null) where = where.and(qj.credit.eq(credit));
        if (StrUtil.isNotBlank(counterparty)) {
            where = where.and(qj.oppositeAccountName.eq(counterparty));
        }

        return jqf.selectFrom(qj).where(where).fetchFirst() != null;
    }

    // ═══════════════════════════════════════════════════════════════════
    // 银行流水文件管理（任务工作台「解析银行流水」子卡片用）
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 查询指定账期下已上传的银行流水文件列表。
     */
    public List<Map<String, Object>> listBankStatementFiles(Long accountSetId, String period) {
        List<BankStatement> statements = bankStatementRepository
                .findByAccountSetsIdAndPeriodAndIsDeleteOrderByCreateTimeDesc(accountSetId, period, 0);
        if (statements.isEmpty()) return Collections.emptyList();

        // 预取相关银行账户名称
        Set<Long> accountIds = statements.stream()
                .map(BankStatement::getCashierAccountId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> accountNameMap = new HashMap<>();
        if (!accountIds.isEmpty()) {
            cn.gson.financial.kernel.model.entity.QCashierAccount qa =
                    cn.gson.financial.kernel.model.entity.QCashierAccount.cashierAccount;
            jqf.selectFrom(qa).where(qa.accountSetsId.eq(accountSetId).and(qa.id.in(accountIds)))
                    .fetch().forEach(a -> accountNameMap.put(a.getId(), a.getName()));
        }

        // 一次性查所有 statementId 下是否存在已生成凭证的明细，用于前端展示「是否可直接删除」
        cn.gson.financial.kernel.model.entity.QBankStatementDetail qd =
                cn.gson.financial.kernel.model.entity.QBankStatementDetail.bankStatementDetail;
        cn.gson.financial.kernel.model.entity.QCashierJournal qj =
                cn.gson.financial.kernel.model.entity.QCashierJournal.cashierJournal;
        Set<Long> withVoucherStatementIds = new HashSet<>();
        if (!statements.isEmpty()) {
            List<Long> stIds = statements.stream().map(BankStatement::getId).collect(Collectors.toList());
            // 子查询：取所有这些 statement 下的 journalId，再看哪些 journal 有 voucherId
            List<com.querydsl.core.Tuple> tuples = jqf
                    .select(qd.bankStatementId, qj.voucherId)
                    .from(qd)
                    .leftJoin(qj).on(qj.id.eq(qd.cashierJournalId))
                    .where(qd.bankStatementId.in(stIds).and(qd.isDelete.eq(0)).and(qj.voucherId.isNotNull()))
                    .fetch();
            tuples.forEach(t -> withVoucherStatementIds.add(t.get(qd.bankStatementId)));
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (BankStatement s : statements) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s.getId());
            m.put("fileName", s.getFileName());
            m.put("bankName", s.getBankName());
            m.put("cashierAccountId", s.getCashierAccountId());
            m.put("cashierAccountName", s.getCashierAccountId() != null
                    ? accountNameMap.get(s.getCashierAccountId()) : null);
            m.put("uploadTime", s.getCreateTime());
            m.put("detailCount", s.getDetailCount() != null ? s.getDetailCount() : 0);
            m.put("hasVoucher", withVoucherStatementIds.contains(s.getId()));
            result.add(m);
        }
        return result;
    }

    /**
     * 删除单个银行流水文件，级联清理：
     *  1) 该 BankStatement 下所有 BankStatementDetail
     *  2) 这些 detail 关联的 CashierJournal（若日记账已生成凭证，先删凭证）
     *  3) 软删 BankStatement（isDelete=1）
     *  4) 重算余额
     *
     * @return 包含 detailCount/journalCount/voucherCount 的统计
     */
    @Transactional
    public Map<String, Object> deleteBankStatementFile(Long bankStatementId, Long accountSetId) {
        BankStatement statement = bankStatementRepository
                .findFirstByIdAndAccountSetsIdAndIsDelete(bankStatementId, accountSetId, 0)
                .orElseThrow(() -> new IllegalArgumentException("银行流水文件不存在或已删除"));

        AccountSets accountSets = accountSetsRepository.findById(accountSetId)
                .orElseThrow(() -> new IllegalArgumentException("账套不存在"));

        List<BankStatementDetail> details = bankStatementDetailRepository
                .findByBankStatementIdAndIsDelete(bankStatementId, 0);

        Set<Long> journalIds = details.stream()
                .map(BankStatementDetail::getCashierJournalId)
                .filter(Objects::nonNull).collect(Collectors.toSet());

        int voucherCount = 0;
        int journalCount = 0;

        if (!journalIds.isEmpty()) {
            cn.gson.financial.kernel.model.entity.QCashierJournal qj =
                    cn.gson.financial.kernel.model.entity.QCashierJournal.cashierJournal;
            List<CashierJournal> journals = jqf.selectFrom(qj)
                    .where(qj.accountSetsId.eq(accountSetId).and(qj.id.in(journalIds)))
                    .fetch();

            // 先批量删凭证（VoucherService 删凭证时会通过 voucherDeleted 回调清掉 journal.voucherId）
            List<Long> voucherIds = journals.stream()
                    .map(CashierJournal::getVoucherId)
                    .filter(Objects::nonNull).distinct().collect(Collectors.toList());
            if (!voucherIds.isEmpty()) {
                voucherService.batchDelete(accountSets, voucherIds);
                voucherCount = voucherIds.size();
            }

            // 再逐条删日记账
            LocalDate enableDate = accountSets.getEnableDate();
            for (CashierJournal j : journals) {
                try {
                    cashierJournalService.delete(j.getId(), accountSetId, enableDate);
                    journalCount++;
                } catch (Exception e) {
                    log.warn("删除日记账失败: journalId={}, error={}", j.getId(), e.getMessage());
                }
            }
        }

        // 软删明细
        for (BankStatementDetail d : details) {
            d.setIsDelete(1);
        }
        if (!details.isEmpty()) bankStatementDetailRepository.saveAll(details);

        // 软删文件头
        statement.setIsDelete(1);
        bankStatementRepository.save(statement);

        log.info("删除银行流水文件: bankStatementId={}, accountSetId={}, 明细={}, 日记账={}, 凭证={}",
                bankStatementId, accountSetId, details.size(), journalCount, voucherCount);

        // 如果该账期已无任何文件，把任务工作台 bank 子任务回退为 pending
        if (StrUtil.isNotBlank(statement.getPeriod())) {
            List<BankStatement> remaining = bankStatementRepository
                    .findByAccountSetsIdAndPeriodAndIsDeleteOrderByCreateTimeDesc(
                            accountSetId, statement.getPeriod(), 0);
            if (remaining.isEmpty()) {
                resetTaskDataSubToPending(accountSetId, statement.getPeriod());
            }
        }

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("detailCount", details.size());
        r.put("journalCount", journalCount);
        r.put("voucherCount", voucherCount);
        return r;
    }

    private void resetTaskDataSubToPending(Long accountSetId, String period) {
        try {
            TaskPeriod tp = taskPeriodRepository
                    .findByAccountSetIdAndPeriod(accountSetId, period).orElse(null);
            if (tp == null) return;
            taskDataSubRepository.findByTaskPeriodIdAndSubType(tp.getId(), "bank").ifPresent(sub -> {
                sub.setStatus("pending");
                sub.setResultJson(null);
                sub.setExecutedAt(null);
                sub.setErrorMsg(null);
                taskDataSubRepository.save(sub);
            });
        } catch (Exception e) {
            log.warn("回退 bank 子任务状态失败: {}", e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // 银行账户新增（快捷建档：科目 + CashierAccount 一并创建）
    // ═══════════════════════════════════════════════════════════════════

    @Transactional
    public Map<String, Object> createBankAccount(Long accountSetId, String name, String cardNumber) {
        if (StrUtil.isBlank(name)) throw new IllegalArgumentException("银行账户名称不能为空");

        // 重名校验
        if (cashierAccountRepository.findFirstByAccountSetsIdAndName(accountSetId, name).isPresent()) {
            throw new IllegalArgumentException("银行账户已存在：" + name);
        }

        AccountSets accountSets = accountSetsRepository.findById(accountSetId)
                .orElseThrow(() -> new IllegalArgumentException("账套不存在"));
        int encLen = accountSets.getEncoding() != null
                ? accountSets.getEncoding().stream().reduce(Integer::sum).orElse(12) : 12;

        // ── 1. 找/建科目 ──
        Long subjectId = null;
        String subjectCode = null;
        Subject bankParent = subjectService.listByName(accountSetId, "银行存款");
        if (bankParent != null) {
            // 先查是否已有同名科目
            Subject existingSub = subjectService.listByName(accountSetId, name);
            if (existingSub != null) {
                subjectId = existingSub.getId();
                subjectCode = existingSub.getCode();
            } else {
                try {
                    // 找父科目下最大子编码
                    String maxChildCode = subjectService.getMaxChildCode(bankParent.getId(), accountSetId);
                    String newCode;
                    if (StrUtil.isNotBlank(maxChildCode)) {
                        try { newCode = String.valueOf(Long.parseLong(maxChildCode) + 1); }
                        catch (NumberFormatException e) {
                            List<Integer> enc = accountSets.getEncoding();
                            int childLen = enc != null && enc.size() > 1 ? enc.get(1) : 2;
                            newCode = bankParent.getCode() + StrUtil.padPre("1", childLen, "0");
                        }
                    } else {
                        List<Integer> enc = accountSets.getEncoding();
                        int childLen = enc != null && enc.size() > 1 ? enc.get(1) : 2;
                        newCode = bankParent.getCode() + StrUtil.padPre("1", childLen, "0");
                    }
                    Subject child = new Subject();
                    child.setAccountSetsId(accountSetId);
                    child.setCode(newCode);
                    child.setName(name);
                    child.setParentId(bankParent.getId());
                    child.setLevel(bankParent.getLevel() + 1);
                    child.setType(bankParent.getType());
                    child.setBalanceDirection(bankParent.getBalanceDirection());
                    child.setLeaf(true);
                    child.setSystemDefault(false);
                    child.setStatus(true);
                    Subject saved = subjectService.save(child, encLen);
                    subjectId = saved.getId();
                    subjectCode = saved.getCode();
                } catch (Exception e) {
                    log.warn("创建银行存款子科目失败，跳过科目关联: {}", e.getMessage());
                }
            }
        }

        // ── 2. 生成账户编码 ──
        String accCode = subjectCode;
        if (StrUtil.isBlank(accCode)) {
            String maxAccCode = cashierAccountRepository
                    .findFirstByAccountSetsIdAndTypeOrderByCodeDesc(accountSetId, CashierAccount.CashierAccountType.银行存款)
                    .map(CashierAccount::getCode).orElse(null);
            if (StrUtil.isNotBlank(maxAccCode)) {
                try { accCode = String.valueOf(Long.parseLong(maxAccCode) + 1); }
                catch (NumberFormatException ex) { accCode = "yhck" + System.currentTimeMillis() % 10000; }
            } else {
                accCode = "100201";
            }
        }

        // ── 3. 创建银行账户 ──
        CashierAccount account = new CashierAccount();
        account.setAccountSetsId(accountSetId);
        account.setType(CashierAccount.CashierAccountType.银行存款);
        account.setName(name);
        account.setCode(accCode);
        if (StrUtil.isNotBlank(cardNumber)) account.setCardNumber(cardNumber.trim());
        if (subjectId != null) account.setSubjectId(subjectId);

        CashierAccount saved = cashierAccountService.save(account);
        log.info("快捷新增银行账户: accountSetId={}, name={}, id={}", accountSetId, name, saved.getId());

        // 同步到全局银行库（BankLib），若不存在则新增并启用
        try {
            boolean libExists = bankLibRepository.findFirstByBankNameAndIsDelete(name, 0).isPresent();
            if (!libExists) {
                // bankCode 取 accCode；若已被占用则用名称本身截断作备用
                String bankCode = accCode;
                if (bankLibRepository.findFirstByBankCodeAndIsDelete(bankCode, 0).isPresent()) {
                    bankCode = name.length() > 20 ? name.substring(0, 20) : name;
                }
                BankLib lib = new BankLib();
                lib.setBankCode(bankCode);
                lib.setBankName(name);
                lib.setEnabled(1);
                lib.setIsAutoRecognize(1);
                lib.setIsDelete(0);
                bankLibRepository.save(lib);
                log.info("同步新增银行库: bankCode={}, bankName={}", bankCode, name);
            }
        } catch (Exception e) {
            log.warn("同步银行库失败（不影响主流程）: {}", e.getMessage());
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", saved.getId());
        result.put("name", saved.getName());
        result.put("cardNumber", saved.getCardNumber() != null ? saved.getCardNumber() : "");
        return result;
    }

    private void updateTaskDataSub(Long accountSetId, String period, int count) {
        try {
            TaskPeriod tp = taskPeriodRepository
                    .findByAccountSetIdAndPeriod(accountSetId, period)
                    .orElseGet(() -> {
                        TaskPeriod newTp = new TaskPeriod();
                        newTp.setAccountSetId(accountSetId);
                        newTp.setPeriod(period);
                        return taskPeriodRepository.save(newTp);
                    });

            TaskDataSub sub = taskDataSubRepository
                    .findByTaskPeriodIdAndSubType(tp.getId(), "bank")
                    .orElseGet(() -> {
                        TaskDataSub s = new TaskDataSub();
                        s.setTaskPeriodId(tp.getId());
                        s.setSubType("bank");
                        return s;
                    });

            sub.setStatus("done");
            sub.setResultJson("{\"count\":" + count + "}");
            sub.setExecutedAt(LocalDateTime.now());
            sub.setErrorMsg(null);
            taskDataSubRepository.save(sub);

            tp.setStepData("done");
            taskPeriodRepository.save(tp);
        } catch (Exception e) {
            log.warn("更新任务状态失败（不影响主流程）: {}", e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Qwen 文本模型
    // ═══════════════════════════════════════════════════════════════════

    private String callQwenText(String systemPrompt, String userContent, String stepName) {
        log.info("调用 Qwen [{}]: model={}", stepName, qwenModel);
        try {
            Generation gen = new Generation();
            GenerationParam param = GenerationParam.builder()
                    .apiKey(qwenApiKey)
                    .model(qwenModel)
                    .messages(List.of(
                            Message.builder().role(Role.SYSTEM.getValue()).content(systemPrompt).build(),
                            Message.builder().role(Role.USER.getValue()).content(userContent).build()
                    ))
                    .temperature(0.1f)
                    .maxTokens(8000)
                    .resultFormat(GenerationParam.ResultFormat.MESSAGE)
                    .build();
            GenerationResult result = gen.call(param);
            String content = result.getOutput().getChoices().get(0).getMessage().getContent();
            if (StrUtil.isBlank(content)) throw new RuntimeException("返回内容为空");
            if (result.getUsage() != null) {
                log.info("Qwen [{}] token: in={}, out={}", stepName,
                        result.getUsage().getInputTokens(), result.getUsage().getOutputTokens());
            }
            return cleanJson(content);
        } catch (com.alibaba.dashscope.exception.NoApiKeyException e) {
            throw new RuntimeException("API Key 未配置", e);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("调用 Qwen 失败: " + e.getMessage(), e);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Qwen 视觉模型
    // ═══════════════════════════════════════════════════════════════════

    private String callQwenVision(String base64Image, String mediaType,
                                   String systemPrompt, String userText) {
        log.info("调用 Qwen VL: model={}", qwenVisionModel);
        try {
            MultiModalConversation conv = new MultiModalConversation();
            MultiModalMessage systemMsg = MultiModalMessage.builder()
                    .role(Role.SYSTEM.getValue())
                    .content(List.of(Map.of("text", systemPrompt)))
                    .build();
            MultiModalMessage userMsg = MultiModalMessage.builder()
                    .role(Role.USER.getValue())
                    .content(List.of(
                            Map.of("image", "data:" + mediaType + ";base64," + base64Image),
                            Map.of("text", userText)
                    ))
                    .build();
            MultiModalConversationParam param = MultiModalConversationParam.builder()
                    .apiKey(qwenApiKey)
                    .model(qwenVisionModel)
                    .messages(List.of(systemMsg, userMsg))
                    .temperature(0.1f)
                    .maxTokens(8000)
                    .build();
            MultiModalConversationResult result = conv.call(param);
            String content = result.getOutput().getChoices().get(0).getMessage().getContent()
                    .stream().filter(m -> m.containsKey("text"))
                    .map(m -> m.get("text").toString())
                    .collect(Collectors.joining());
            if (StrUtil.isBlank(content)) throw new RuntimeException("VL 返回内容为空");
            return cleanJson(content);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("调用 Qwen VL 失败: " + e.getMessage(), e);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // 工具方法
    // ═══════════════════════════════════════════════════════════════════

    private enum FileType { EXCEL, PDF, IMAGE }

    private FileType detectFileType(String fileName, String contentType) {
        if (fileName.endsWith(".pdf") || (contentType != null && contentType.contains("pdf")))
            return FileType.PDF;
        if (fileName.endsWith(".xlsx") || fileName.endsWith(".xls")
                || (contentType != null && (contentType.contains("spreadsheet") || contentType.contains("excel"))))
            return FileType.EXCEL;
        if (fileName.endsWith(".jpg") || fileName.endsWith(".jpeg")
                || fileName.endsWith(".png") || fileName.endsWith(".bmp")
                || (contentType != null && contentType.startsWith("image/")))
            return FileType.IMAGE;
        return FileType.EXCEL;
    }

    private String resolveMediaType(String fileName) {
        if (fileName.endsWith(".png")) return "image/png";
        if (fileName.endsWith(".bmp")) return "image/bmp";
        return "image/jpeg";
    }

    private String loadPrompt(String path) {
        try {
            ClassPathResource res = new ClassPathResource(path);
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(res.getInputStream(), StandardCharsets.UTF_8))) {
                return reader.lines().collect(Collectors.joining("\n"));
            }
        } catch (Exception e) {
            throw new RuntimeException("加载提示词失败: " + path, e);
        }
    }

    private String cleanJson(String content) {
        content = content.trim();
        if (content.startsWith("```json")) content = content.substring(7);
        else if (content.startsWith("```")) content = content.substring(3);
        if (content.endsWith("```")) content = content.substring(0, content.length() - 3);
        return content.trim();
    }

    private Map<String, Object> parseJson(String content) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = JSON.parseObject(content, Map.class);
            if (parsed == null) throw new RuntimeException("JSON 解析返回 null");
            return parsed;
        } catch (Exception e) {
            throw new RuntimeException("JSON 解析失败: " + content.substring(0, Math.min(200, content.length())), e);
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getList(Map<String, Object> map, String key) {
        if (map == null) return Collections.emptyList();
        Object val = map.get(key);
        return val instanceof List ? (List<Map<String, Object>>) val : Collections.emptyList();
    }

    private String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v != null ? v.toString() : null;
    }

    private boolean isIncome(BigDecimal income) {
        return income != null && income.compareTo(BigDecimal.ZERO) > 0;
    }

    private BigDecimal decimalOrNull(Object v) {
        if (v == null) return null;
        String s = v.toString().trim().replace(",", "");
        if (s.isEmpty() || "null".equalsIgnoreCase(s)) return null;
        if (s.startsWith("(") && s.endsWith(")")) s = s.substring(1, s.length() - 1);
        try { return new BigDecimal(s).abs(); } catch (Exception e) { return null; }
    }

    private LocalDate parseDate(String dateStr) {
        if (StrUtil.isBlank(dateStr)) return null;
        for (DateTimeFormatter fmt : DATE_FMTS) {
            try { return LocalDate.parse(dateStr.trim(), fmt); }
            catch (DateTimeParseException ignored) {}
        }
        return null;
    }

    private int[] parsePeriod(String period) {
        if (StrUtil.isBlank(period)) return null;
        try {
            String[] parts = period.split("-");
            if (parts.length == 2)
                return new int[]{Integer.parseInt(parts[0]), Integer.parseInt(parts[1])};
        } catch (Exception ignored) {}
        return null;
    }

    private Map<String, Object> buildResult(int total, int saved, String bankName, String msg) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("success", true);
        r.put("totalParsed", total);
        r.put("savedCount", saved);
        r.put("bankName", bankName);
        if (msg != null) r.put("msg", msg);
        return r;
    }
}
