package cn.gson.financial.task.workbench.service;

import cn.gson.financial.kernel.model.entity.AccountSets;
import cn.gson.financial.kernel.model.entity.Subject;
import cn.gson.financial.kernel.model.dto.BalanceInitialDto;
import cn.gson.financial.kernel.model.entity.TaskInitResult;
import cn.gson.financial.kernel.model.entity.TaskPeriod;
import cn.gson.financial.kernel.model.repo.AccountSetsRepository;
import cn.gson.financial.kernel.model.repo.TaskInitResultRepository;
import cn.gson.financial.kernel.model.repo.TaskPeriodRepository;
import cn.gson.financial.kernel.model.vo.SubjectVo;
import cn.gson.financial.kernel.service.BalanceCalcService;
import cn.gson.financial.kernel.service.BalanceInitialService;
import cn.gson.financial.kernel.service.CurrencyService;
import cn.gson.financial.kernel.service.SubjectService;
import cn.hutool.core.util.StrUtil;
import com.alibaba.dashscope.aigc.generation.Generation;
import com.alibaba.dashscope.aigc.generation.GenerationParam;
import com.alibaba.dashscope.aigc.generation.GenerationResult;
import com.alibaba.dashscope.common.Message;
import com.alibaba.dashscope.common.Role;
import com.alibaba.excel.EasyExcel;
import com.alibaba.fastjson2.JSON;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 任务工作台 - 期初导入服务
 *
 * 流程（v2 — AI 只识别结构，Java 精确提取数字）：
 *   ① EasyExcel 读取 Excel 原始数据
 *   ② AI 识别表格列结构（1 次调用）：哪列是编码、名称、期末借方、期末贷方等
 *   ③ Java 按列号直接从 Excel 精确提取科目编码、名称、余额数字
 *   ④ AI 识别科目元信息（1~2 次调用）：编码规范化、类型、方向、父子关系
 *   ⑤ Java 保存科目
 *   ⑥ Java 合并：AI 规范化编码 + Excel 原始数字
 *   ⑦ Java 计算 beginBalance
 *   ⑧ Java 借贷平衡校验
 *   ⑨ Java 保存期初余额
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskInitImportService {

    @Autowired
    @Lazy
    private TaskInitImportService self;

    private final AccountSetsRepository accountSetsRepository;
    private final TaskPeriodRepository taskPeriodRepository;
    private final TaskInitResultRepository taskInitResultRepository;
    private final SubjectService subjectService;
    private final BalanceInitialService balanceInitialService;
    private final BalanceCalcService balanceCalcService;
    private final CurrencyService currencyService;
    private final ImportProgressService importProgressService;

    @Value("${qwen.api-key:}")
    private String qwenApiKey;

    @Value("${qwen.model:qwen-plus}")
    private String qwenModel;

    private static final int MAX_RETRY = 2;
    private static final DateTimeFormatter PERIOD_FMT = DateTimeFormatter.ofPattern("yyyy-MM");

    // ═══════════════════════════════════════════════════════════════════
    // 内部数据结构
    // ═══════════════════════════════════════════════════════════════════

    /** AI 识别的列映射 */
    private static class ColumnMapping {
        int codeCol = -1;
        int nameCol = -1;
        int periodEndDebitCol = -1;
        int periodEndCreditCol = -1;
        int periodBeginDebitCol = -1;
        int periodBeginCreditCol = -1;
        int yearDebitCol = -1;
        int yearCreditCol = -1;
        int dataStartRow = 0; // 1-based（与 EasyExcel 行号一致）
        // 单列余额+方向格式（如科目余额表导出格式）
        int balanceCol = -1;    // 单列余额（借贷合一）
        int directionCol = -1;  // 方向列（借/贷/平）
        int periodCol = -1;     // 年月列（用于多月数据过滤，取最后一个月）
    }

    /** Java 从 Excel 直接提取的一行原始数据 */
    private static class RawSubjectRow {
        String rawCode;
        String name;
        BigDecimal periodEndDebit;
        BigDecimal periodEndCredit;
        BigDecimal periodDebit;    // 本期发生额借方
        BigDecimal periodCredit;   // 本期发生额贷方
        BigDecimal yearTotalDebit;
        BigDecimal yearTotalCredit;
    }

    // ═══════════════════════════════════════════════════════════════════
    // 主入口
    // ═══════════════════════════════════════════════════════════════════

    public Map<String, Object> importInit(MultipartFile file, String accountType,
                                          Long accountSetId, String period) {
        return importInit(file, accountType, accountSetId, period, "manual");
    }

    public Map<String, Object> importInit(MultipartFile file, String accountType,
                                          Long accountSetId, String period, String source) {
        AccountSets accountSets = accountSetsRepository.findById(accountSetId)
                .orElseThrow(() -> new IllegalArgumentException("账套不存在：" + accountSetId));

        String p = StrUtil.isBlank(period) ? YearMonth.now().format(PERIOD_FMT) : period;
        markStepRunning(accountSetId, p, accountSets);

        importProgressService.start(accountSetId);
        try {
            List<Integer> encoding = accountSets.getEncoding() != null
                    ? accountSets.getEncoding() : List.of(4, 3, 3, 2);

            // ① 读取 Excel 原始数据（合并单元格已展开）
            progress(accountSetId, "正在读取 Excel 文件...");
            List<Map<Integer, String>> rawRows = readExcelRaw(file);
            progress(accountSetId, "Excel 读取完成，共 " + rawRows.size() + " 行");

            // ② AI 识别列映射（只发前 10 行，AI 只返回列号）
            progress(accountSetId, "AI 正在识别表格结构...");
            ColumnMapping mapping = aiGetColumnMapping(rawRows);
            progress(accountSetId, String.format("表格结构识别完成：数据从第 %d 行开始，科目编码列=%d，名称列=%d",
                    mapping.dataStartRow, mapping.codeCol, mapping.nameCol));

            // ③ Java 按列映射逐行提取数据
            progress(accountSetId, "正在提取科目数据...");
            List<RawSubjectRow> rawData = extractByColumnMapping(rawRows, mapping);
            progress(accountSetId, "提取到 " + rawData.size() + " 个科目");

            if (rawData.isEmpty()) {
                throw new RuntimeException("未从 Excel 中提取到任何科目数据，请检查表格格式");
            }

            // 校验：打印提取后的原始合计（和 Excel 合计行对比）
            logRawDataSummary("提取后", rawData);

            // ③.5 预处理：合并同名同父级的重复行（Excel 中同一个公司出现两行）
            rawData = mergeRawDuplicates(rawData);
            progress(accountSetId, "数据预处理完成，合并后 " + rawData.size() + " 个科目");

            // 校验：打印合并后的合计
            logRawDataSummary("合并后", rawData);

            // ④ Java 规范化科目（编码、名称、类型、方向、父子关系）
            progress(accountSetId, "正在规范化科目信息...");
            List<Map<String, Object>> normalizedSubjects = normalizeSubjects(rawData, accountSetId, encoding);
            progress(accountSetId, "科目规范化完成，共 " + normalizedSubjects.size() + " 个");

            // ④.5 编码去重（Excel 内部同编码不同名）
            deduplicateSubjectCodes(normalizedSubjects, encoding);

            // ④.6 统一检查编码与系统科目的冲突，提前调整
            progress(accountSetId, "正在检查科目编码冲突...");
            resolveCodeConflicts(normalizedSubjects, accountSetId, encoding);

            // ⑤⑥⑦⑧⑨ 保存科目 + 计算余额 + 保存余额 + 标记完成
            progress(accountSetId, "正在比对并保存科目...");
            Map<String, Subject> codeMap = new HashMap<>();
            List<Map<String, Object>> balances = buildBalances(rawData, normalizedSubjects, p);

            // 校验：打印构建余额后的合计
            {
                BigDecimal bDebit = BigDecimal.ZERO, bCredit = BigDecimal.ZERO;
                int parentSkipped = 0, leafCount = 0;
                for (Map<String, Object> b : balances) {
                    BigDecimal d = decimal(b.get("periodEndDebit"));
                    BigDecimal c = decimal(b.get("periodEndCredit"));
                    bDebit = bDebit.add(d != null ? d : BigDecimal.ZERO);
                    bCredit = bCredit.add(c != null ? c : BigDecimal.ZERO);
                    leafCount++;
                }
                log.info("构建余额合计: 共{}条, 期末借方={}, 期末贷方={}", leafCount, bDebit, bCredit);
            }

            int[] counts = self.saveSubjectsAndBalances(
                    normalizedSubjects, balances, accountSetId, accountSets, p, codeMap);
            int newSubjectCount = counts[0];
            int balanceCount = counts[1];
            saveInitResult(accountSetId, p, null, source, newSubjectCount, balanceCount, true, null);

            String successMsg = String.format("导入成功：新增科目 %d 个，期初余额 %d 条", newSubjectCount, balanceCount);
            log.info("期初导入完成: accountSetId={}, period={}, 新增科目={}, 期初余额={}",
                    accountSetId, p, newSubjectCount, balanceCount);
            importProgressService.complete(accountSetId, true, successMsg);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("newSubjectCount", newSubjectCount);
            result.put("balanceCount", balanceCount);
            result.put("msg", successMsg);
            return result;

        } catch (Exception e) {
            markStepError(accountSetId, p, accountSets, e.getMessage());
            saveInitResult(accountSetId, p, null, source, null, null, false, e.getMessage());
            log.error("期初导入失败: accountSetId={}", accountSetId, e);
            importProgressService.complete(accountSetId, false, "导入失败：" + e.getMessage());
            throw new RuntimeException("期初导入失败：" + e.getMessage(), e);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // ① Excel 读取（保留原始 Map<Integer, String> 格式）
    // ═══════════════════════════════════════════════════════════════════

    private List<Map<Integer, String>> readExcelRaw(MultipartFile file) {
        List<Map<Integer, String>> rows = new ArrayList<>();
        // 记录合并单元格信息：用于展开表头合并格
        List<com.alibaba.excel.metadata.CellExtra> mergedCells = new ArrayList<>();

        try {
            EasyExcel.read(file.getInputStream())
                    .extraRead(com.alibaba.excel.enums.CellExtraTypeEnum.MERGE)
                    .sheet(0)
                    .registerReadListener(new com.alibaba.excel.read.listener.ReadListener<Map<Integer, String>>() {
                        @Override
                        public void invoke(Map<Integer, String> data, com.alibaba.excel.context.AnalysisContext context) {
                            rows.add(data);
                        }
                        @Override
                        public void doAfterAllAnalysed(com.alibaba.excel.context.AnalysisContext context) {}
                        @Override
                        public void extra(com.alibaba.excel.metadata.CellExtra extra, com.alibaba.excel.context.AnalysisContext context) {
                            if (extra.getType() == com.alibaba.excel.enums.CellExtraTypeEnum.MERGE) {
                                mergedCells.add(extra);
                            }
                        }
                    })
                    .headRowNumber(0)
                    .doRead();
        } catch (Exception e) {
            throw new RuntimeException("Excel 文件读取失败：" + e.getMessage(), e);
        }
        if (rows.isEmpty()) throw new RuntimeException("Excel 文件为空");

        // 展开合并单元格：将合并区域内所有空格填充为左上角格子的值
        // 这样 AI 能正确理解"期末余额"覆盖哪几列，不会错位
        for (com.alibaba.excel.metadata.CellExtra merge : mergedCells) {
            int firstRow = merge.getFirstRowIndex();
            int lastRow  = merge.getLastRowIndex();
            int firstCol = merge.getFirstColumnIndex();
            int lastCol  = merge.getLastColumnIndex();
            if (firstRow >= rows.size()) continue;
            String anchorVal = rows.get(firstRow).getOrDefault(firstCol, "");
            if (StrUtil.isBlank(anchorVal)) continue;
            for (int r = firstRow; r <= lastRow && r < rows.size(); r++) {
                for (int c = firstCol; c <= lastCol; c++) {
                    rows.get(r).putIfAbsent(c, anchorVal);
                    // 仅第一格保留原值，其余空格才填充（putIfAbsent 已保证不覆盖非空格）
                    if (r != firstRow || c != firstCol) {
                        String cur = rows.get(r).get(c);
                        if (StrUtil.isBlank(cur)) {
                            rows.get(r).put(c, anchorVal);
                        }
                    }
                }
            }
        }

        log.info("Excel 读取完成: {} 行, 合并格展开: {} 处", rows.size(), mergedCells.size());
        return rows;
    }

    // ═══════════════════════════════════════════════════════════════════
    // ② AI 列映射识别（仅发送前 10 行，AI 返回列号）
    // ═══════════════════════════════════════════════════════════════════

    private ColumnMapping aiGetColumnMapping(List<Map<Integer, String>> rawRows) {
        String headerText = formatExcelText(rawRows, 10);
        String systemPrompt = loadPrompt("prompts/init-column-mapping.txt");
        String userMsg = "以下是 Excel 前 10 行内容：\n\n" + headerText;

        String response = callQwen(systemPrompt, userMsg, "列映射识别");
        Map<String, Object> json = JSON.parseObject(response);

        ColumnMapping mapping = new ColumnMapping();
        // AI 看到的行号是 1-based（因为 formatExcelText 输出 "第1行"、"第2行"...），转为 0-based
        int aiRow = json.get("data_start_row") instanceof Number
                ? ((Number) json.get("data_start_row")).intValue() : 1;
        mapping.dataStartRow = aiRow - 1;
        mapping.codeCol = toInt(json.get("subject_code_col"), -1);
        mapping.nameCol = toInt(json.get("subject_name_col"), -1);
        mapping.periodEndDebitCol = toInt(json.get("end_debit_col"), -1);
        mapping.periodEndCreditCol = toInt(json.get("end_credit_col"), -1);
        mapping.yearDebitCol = toInt(json.get("year_debit_col"), -1);
        mapping.yearCreditCol = toInt(json.get("year_credit_col"), -1);
        mapping.balanceCol = toInt(json.get("balance_col"), -1);
        mapping.directionCol = toInt(json.get("direction_col"), -1);
        mapping.periodCol = toInt(json.get("period_col"), -1);

        if (mapping.codeCol < 0 || mapping.nameCol < 0) {
            throw new RuntimeException("AI 未能识别科目编码列或名称列，请检查表格格式");
        }
        if (mapping.periodEndDebitCol < 0 && mapping.periodEndCreditCol < 0 && mapping.balanceCol < 0) {
            throw new RuntimeException("AI 未能识别期末余额列，请检查表格格式");
        }

        // 安全校验：如果 dataStartRow 前一行看起来像数据行（第一列含数字），则回退
        if (mapping.dataStartRow > 0 && mapping.dataStartRow < rawRows.size()) {
            Map<Integer, String> prevRow = rawRows.get(mapping.dataStartRow - 1);
            String prevCode = StrUtil.trimToEmpty(prevRow.getOrDefault(mapping.codeCol, ""));
            if (prevCode.matches("^\\d+.*")) {
                log.info("AI dataStartRow={} 前一行已有数据编码 '{}'，回退一行", mapping.dataStartRow, prevCode);
                mapping.dataStartRow = mapping.dataStartRow - 1;
            }
        }

        log.info("AI 列映射结果: dataStartRow={}, code={}, name={}, endDebit={}, endCredit={}, balance={}, direction={}, period={}, yearDebit={}, yearCredit={}",
                mapping.dataStartRow, mapping.codeCol, mapping.nameCol,
                mapping.periodEndDebitCol, mapping.periodEndCreditCol,
                mapping.balanceCol, mapping.directionCol, mapping.periodCol,
                mapping.yearDebitCol, mapping.yearCreditCol);
        return mapping;
    }

    private int toInt(Object obj, int defaultVal) {
        if (obj instanceof Number) return ((Number) obj).intValue();
        return defaultVal;
    }

    // ═══════════════════════════════════════════════════════════════════
    // ③ Java 按列映射逐行提取数据
    // ═══════════════════════════════════════════════════════════════════

    private static final Set<String> SKIP_KEYWORDS = Set.of("合计", "小计", "总计", "本页合计");
    // 注意：不能包含"累计"，否则会误过滤"累计折旧"(1602)、"累计摊销"(1702)等合法科目

    private List<RawSubjectRow> extractByColumnMapping(List<Map<Integer, String>> rawRows, ColumnMapping mapping) {
        List<RawSubjectRow> result = new ArrayList<>();

        // 多月格式：先找出最后一个年月，只处理该月数据
        String targetPeriod = null;
        if (mapping.periodCol >= 0) {
            for (int i = mapping.dataStartRow; i < rawRows.size(); i++) {
                String p = StrUtil.trimToEmpty(rawRows.get(i).getOrDefault(mapping.periodCol, ""));
                if (p.matches("\\d{4}-\\d{2}") && (targetPeriod == null || p.compareTo(targetPeriod) > 0)) {
                    targetPeriod = p;
                }
            }
            log.info("多月格式：检测到最后一个期间 {}，仅提取该月数据", targetPeriod);
        }

        for (int i = mapping.dataStartRow; i < rawRows.size(); i++) {
            Map<Integer, String> row = rawRows.get(i);

            // 多月过滤：只取目标月份的行
            if (targetPeriod != null) {
                String rowPeriod = StrUtil.trimToEmpty(row.getOrDefault(mapping.periodCol, ""));
                if (!targetPeriod.equals(rowPeriod)) continue;
            }

            String code = StrUtil.trimToEmpty(row.getOrDefault(mapping.codeCol, ""));
            String name = StrUtil.trimToEmpty(row.getOrDefault(mapping.nameCol, ""));

            // 跳过空行
            if (StrUtil.isBlank(code) && StrUtil.isBlank(name)) continue;
            // 跳过合计行
            if (SKIP_KEYWORDS.stream().anyMatch(kw -> code.contains(kw) || name.contains(kw))) {
                log.info("跳过合计行: row={}, code={}, name={}", i, code, name);
                continue;
            }
            // 跳过没有编码的行（可能是说明行）
            if (StrUtil.isBlank(code)) continue;
            // 编码至少要包含数字
            if (!code.matches(".*\\d.*")) continue;

            RawSubjectRow r = new RawSubjectRow();
            r.rawCode = code;
            r.name = name;

            if (mapping.balanceCol >= 0 && mapping.periodEndDebitCol < 0 && mapping.periodEndCreditCol < 0) {
                // 单列余额+方向格式：根据方向列拆分到借方或贷方
                BigDecimal balance = parseDecimalSafe(row.getOrDefault(mapping.balanceCol, ""));
                String direction = mapping.directionCol >= 0
                        ? StrUtil.trimToEmpty(row.getOrDefault(mapping.directionCol, "")) : "";
                if ("借".equals(direction)) {
                    r.periodEndDebit = balance;
                    r.periodEndCredit = null;
                } else if ("贷".equals(direction)) {
                    r.periodEndDebit = null;
                    r.periodEndCredit = balance;
                } else {
                    // 方向为"平"或未知，余额为0
                    r.periodEndDebit = null;
                    r.periodEndCredit = null;
                }
            } else {
                r.periodEndDebit = parseDecimalSafe(row.getOrDefault(mapping.periodEndDebitCol, ""));
                r.periodEndCredit = parseDecimalSafe(row.getOrDefault(mapping.periodEndCreditCol, ""));
            }

            r.yearTotalDebit = mapping.yearDebitCol >= 0
                    ? parseDecimalSafe(row.getOrDefault(mapping.yearDebitCol, "")) : null;
            r.yearTotalCredit = mapping.yearCreditCol >= 0
                    ? parseDecimalSafe(row.getOrDefault(mapping.yearCreditCol, "")) : null;
            result.add(r);
        }

        log.info("按列映射提取完成: 共 {} 个科目", result.size());
        return result;
    }

    /** 安全解析数字：处理逗号分隔、括号负数、空值 */
    private BigDecimal parseDecimalSafe(String text) {
        if (StrUtil.isBlank(text)) return null;
        text = text.trim();
        // 处理括号表示负数：(1,234.56) → -1234.56
        boolean negative = false;
        if (text.startsWith("(") && text.endsWith(")")) {
            text = text.substring(1, text.length() - 1);
            negative = true;
        }
        // 去掉逗号和空格
        text = text.replace(",", "").replace(" ", "").replace("，", "");
        // 去掉货币符号
        text = text.replace("¥", "").replace("￥", "").replace("$", "");
        if (StrUtil.isBlank(text) || "-".equals(text)) return null;
        try {
            BigDecimal val = new BigDecimal(text);
            return negative ? val.negate() : val;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // ④ Java 科目编码规范化（替代 AI identifySubjects）
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 纯 Java 规则规范化科目。
     * <p>
     * 核心逻辑：
     * 1. 用客户的原始编码（rawCode）前缀关系确定父子关系
     * 2. 父级科目按名称匹配系统已有科目 → 使用系统编码
     * 3. 子科目不做编码补位，而是在父级编码下按系统规则顺序分配新编码
     * 4. 父级科目不导入余额（标记 isParent）
     */
    private List<Map<String, Object>> normalizeSubjects(List<RawSubjectRow> rawData,
                                                         Long accountSetId, List<Integer> encoding) {
        // 加载系统现有科目
        Map<String, Subject> systemCodeMap = new LinkedHashMap<>();
        Map<String, List<Subject>> nameIndex = new LinkedHashMap<>();
        subjectService.listByAstId(accountSetId).forEach(s -> {
            systemCodeMap.put(s.getCode(), s);
            String key = (s.getName() != null ? s.getName() : "") + "|" +
                    (s.getCode() != null && s.getCode().length() >= 4 ? s.getCode().substring(0, 4) : "");
            nameIndex.computeIfAbsent(key, k -> new ArrayList<>()).add(s);
        });

        // 1. 清洗 rawCode 并建立前缀关系
        Set<String> allCleanCodes = new LinkedHashSet<>();
        Map<String, RawSubjectRow> rawCodeToRow = new LinkedHashMap<>();
        for (RawSubjectRow raw : rawData) {
            String clean = raw.rawCode.replace(".", "").replace("-", "").replace(" ", "").replace("_", "");
            allCleanCodes.add(clean);
            rawCodeToRow.put(raw.rawCode, raw);
        }

        // 2. 标记父子关系 + 找每个科目的直接父级 rawCode
        Map<String, String> childToParentRaw = new LinkedHashMap<>(); // cleanCode → 直接父级的 cleanCode
        Set<String> parentCleanCodes = new LinkedHashSet<>();
        for (String code : allCleanCodes) {
            // 找最长的前缀匹配作为直接父级
            String bestParent = null;
            for (String other : allCleanCodes) {
                if (other.length() < code.length() && code.startsWith(other)) {
                    if (bestParent == null || other.length() > bestParent.length()) {
                        bestParent = other;
                    }
                }
            }
            if (bestParent != null) {
                childToParentRaw.put(code, bestParent);
                parentCleanCodes.add(bestParent);
            }
        }

        // 3. 逐个科目处理：先处理顶层，再处理子科目（按 cleanCode 长度排序）
        List<RawSubjectRow> sorted = new ArrayList<>(rawData);
        sorted.sort(Comparator.comparingInt(r ->
                r.rawCode.replace(".", "").replace("-", "").replace(" ", "").replace("_", "").length()));

        // rawCode(clean) → 分配的系统编码
        Map<String, String> assignedCodes = new LinkedHashMap<>();
        // 父级编码下已分配的子科目计数器
        Map<String, Integer> childSeqMap = new LinkedHashMap<>();
        List<Map<String, Object>> result = new ArrayList<>();

        for (RawSubjectRow raw : sorted) {
            String rawCode = raw.rawCode;
            String cleanCode = rawCode.replace(".", "").replace("-", "").replace(" ", "").replace("_", "");
            String name = cleanRawSubjectName(raw.name);
            boolean isParent = parentCleanCodes.contains(cleanCode);

            // 确定编码
            String normalizedCode;
            String parentCode = "";

            // 查找直接父级的系统编码
            String parentClean = childToParentRaw.get(cleanCode);
            if (parentClean != null) {
                parentCode = assignedCodes.getOrDefault(parentClean, "");
            }

            // 优先按名称 + 前4位匹配系统已有科目
            String top4 = cleanCode.length() >= 4 ? cleanCode.substring(0, 4) : cleanCode;
            String matchKey = name + "|" + top4;
            List<Subject> nameMatches = nameIndex.get(matchKey);
            Subject matched = nameMatches != null && !nameMatches.isEmpty() ? nameMatches.get(0) : null;

            if (matched != null) {
                normalizedCode = matched.getCode();
                parentCode = deriveParentCode(normalizedCode, encoding);
                log.debug("科目名称匹配: rawCode={}, name={} → 系统编码={}", rawCode, name, normalizedCode);
            } else if (StrUtil.isNotBlank(parentCode)) {
                // 有父级 → 在父级编码下按顺序分配
                int parentLevel = getCodeLevel(parentCode, encoding);
                if (parentLevel >= 0 && parentLevel + 1 < encoding.size()) {
                    int childWidth = encoding.get(parentLevel + 1); // 子级位宽（下一层级的宽度）
                    int seq = childSeqMap.getOrDefault(parentCode, 0) + 1;

                    // 检查系统中已有的最大编号，避免冲突
                    int maxExisting = getMaxChildSeq(systemCodeMap, parentCode, childWidth);
                    if (seq <= maxExisting) seq = maxExisting + 1;

                    childSeqMap.put(parentCode, seq);
                    normalizedCode = parentCode + String.format("%0" + childWidth + "d", seq);
                    log.debug("分配子科目编码: rawCode={}, name={}, 父级={} → {}", rawCode, name, parentCode, normalizedCode);
                } else {
                    // 超出编码层级，用补位兜底
                    normalizedCode = normalizeCode(rawCode, encoding);
                    log.warn("编码层级超出规则，使用补位: rawCode={}, name={} → {}", rawCode, name, normalizedCode);
                }
            } else {
                // 顶层科目，没有父级 → 按编码规则取前4位
                normalizedCode = normalizeCode(rawCode, encoding);
                // 顶层只取一级编码长度
                if (normalizedCode.length() > encoding.get(0)) {
                    normalizedCode = normalizedCode.substring(0, encoding.get(0));
                }
                log.debug("顶层科目: rawCode={}, name={} → {}", rawCode, name, normalizedCode);
            }

            assignedCodes.put(cleanCode, normalizedCode);

            // 确定类型和余额方向
            String type = inferSubjectType(normalizedCode);
            String direction = inferBalanceDirection(normalizedCode, parentCode, systemCodeMap);

            Subject existing = matched != null ? matched : systemCodeMap.get(normalizedCode);
            if (existing != null) {
                if (StrUtil.isNotBlank(existing.getType())) type = existing.getType();
                if (StrUtil.isNotBlank(existing.getBalanceDirection())) direction = existing.getBalanceDirection();
            }

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("rawCode", rawCode);
            m.put("code", normalizedCode);
            m.put("name", name);
            m.put("parentCode", parentCode);
            m.put("type", type);
            m.put("balanceDirection", direction);
            m.put("isParent", isParent);
            result.add(m);
        }

        // 按编码排序
        result.sort(Comparator.comparing(m -> str(m, "code")));

        long parentCount = result.stream().filter(m -> Boolean.TRUE.equals(m.get("isParent"))).count();
        long leafCount = result.size() - parentCount;
        log.info("科目规范化完成: 共 {} 个（父级 {} 个，叶子 {} 个）", result.size(), parentCount, leafCount);
        return result;
    }

    /** 获取编码所在层级（0-based） */
    /**
     * 统一检查 normalizedSubjects 中的编码是否与系统已有科目冲突。
     * 冲突规则：同编码但不同名 → 编码自动递增。
     * 同时处理名称冲突：不同类别下同名 → 加父级前缀。
     */
    private void resolveCodeConflicts(List<Map<String, Object>> subjects, Long accountSetId, List<Integer> encoding) {
        // 已分配的编码集合（包含系统已有 + 本批次已分配的）
        Set<String> usedCodes = new HashSet<>();

        for (Map<String, Object> m : subjects) {
            String code = str(m, "code");
            String name = str(m, "name");
            if (StrUtil.isBlank(code)) continue;

            // 检查编码冲突
            Subject existing = subjectService.listByCode(accountSetId, code);
            if (existing != null && !existing.getName().equals(name)) {
                // 同编码不同名 → 编码递增
                String originalCode = code;
                int level = getCodeLevel(code, encoding);
                if (level >= 0 && level < encoding.size()) {
                    int childWidth = encoding.get(level);
                    String parentPart = code.substring(0, code.length() - childWidth);
                    int seq = Integer.parseInt(code.substring(code.length() - childWidth));
                    do {
                        seq++;
                        code = parentPart + String.format("%0" + childWidth + "d", seq);
                    } while (subjectService.listByCode(accountSetId, code) != null || usedCodes.contains(code));
                    m.put("code", code);
                    log.info("编码冲突调整: 原code={} name={} 系统name={} → 新code={}", originalCode, name, existing.getName(), code);
                }
            }

            // 检查名称冲突
            if (StrUtil.isNotBlank(name)) {
                String cleanName = cleanRawSubjectName(name);
                Subject byName = subjectService.listByName(accountSetId, cleanName);
                if (byName != null && !isSameTopCategory(code, byName.getCode())) {
                    // 不同类别同名 → 加父级前缀
                    String parentCode = str(m, "parentCode");
                    // 从本批次中查找父级名称
                    for (Map<String, Object> p : subjects) {
                        if (parentCode.equals(str(p, "code"))) {
                            String parentName = str(p, "name");
                            String newName = parentName + "-" + name;
                            m.put("name", newName);
                            log.info("名称冲突调整: code={}, 原名={}, 新名={}", code, name, newName);
                            break;
                        }
                    }
                }
            }

            usedCodes.add(code);
        }
    }

    private int getCodeLevel(String code, List<Integer> encoding) {
        int pos = 0;
        for (int i = 0; i < encoding.size(); i++) {
            pos += encoding.get(i);
            if (code.length() == pos) return i;
        }
        return -1;
    }

    /** 获取系统中某父级编码下已有的最大子编号 */
    private int getMaxChildSeq(Map<String, Subject> systemCodeMap, String parentCode, int childWidth) {
        int max = 0;
        for (String code : systemCodeMap.keySet()) {
            if (code.length() == parentCode.length() + childWidth && code.startsWith(parentCode)) {
                try {
                    int seq = Integer.parseInt(code.substring(parentCode.length()));
                    if (seq > max) max = seq;
                } catch (NumberFormatException ignored) {}
            }
        }
        return max;
    }

    /** 编码规范化：去掉分隔符，按编码规则逐级拆分，不足位数在当前级前面补0 */
    private String normalizeCode(String rawCode, List<Integer> encoding) {
        // 去掉所有分隔符
        String clean = rawCode.replace(".", "").replace("-", "").replace(" ", "").replace("_", "");

        // 按编码规则逐级拆分并补位
        // encoding 如 [4,3,3,2]：一级4位，二级3位，三级3位，四级2位
        StringBuilder result = new StringBuilder();
        int pos = 0;
        for (int i = 0; i < encoding.size(); i++) {
            int levelWidth = encoding.get(i);
            if (pos >= clean.length()) break; // 没有更多位了，当前科目到此层级

            // 取剩余字符
            int remaining = clean.length() - pos;
            if (remaining >= levelWidth) {
                // 当前级位数足够，直接取
                result.append(clean, pos, pos + levelWidth);
                pos += levelWidth;
            } else {
                // 当前级位数不足，前面补0
                String part = clean.substring(pos);
                result.append("0".repeat(levelWidth - part.length())).append(part);
                pos = clean.length(); // 消耗完毕
            }
        }

        return result.toString();
    }

    /** 根据编码规则推导父科目编码 */
    private String deriveParentCode(String code, List<Integer> encoding) {
        // 从编码规则判断当前科目层级
        int cumLen = 0;
        for (int i = 0; i < encoding.size(); i++) {
            cumLen += encoding.get(i);
            if (code.length() == cumLen) {
                // 当前是第 i+1 级
                if (i == 0) return ""; // 一级科目无父级
                // 父级编码 = 截取到上一级的长度
                int parentLen = cumLen - encoding.get(i);
                return code.substring(0, parentLen);
            }
        }
        // 编码长度不匹配任何层级，尝试截取一级编码作为父级
        if (code.length() > encoding.get(0)) {
            return code.substring(0, encoding.get(0));
        }
        return "";
    }

    /** 按编码首位推断科目类型 */
    private String inferSubjectType(String code) {
        if (StrUtil.isBlank(code)) return "资产";
        char first = code.charAt(0);
        return switch (first) {
            case '1' -> "资产";
            case '2' -> "负债";
            case '3' -> "权益";
            case '4' -> "成本";
            case '5' -> "损益";
            case '6' -> "损益";
            default -> "资产";
        };
    }

    /** 推断余额方向：优先继承系统已有科目或父科目方向 */
    private String inferBalanceDirection(String code, String parentCode,
                                          Map<String, Subject> systemCodeMap) {
        // 1. 系统已有科目直接使用
        Subject existing = systemCodeMap.get(code);
        if (existing != null && StrUtil.isNotBlank(existing.getBalanceDirection())) {
            return existing.getBalanceDirection();
        }
        // 2. 继承父科目方向
        if (StrUtil.isNotBlank(parentCode)) {
            Subject parent = systemCodeMap.get(parentCode);
            if (parent != null && StrUtil.isNotBlank(parent.getBalanceDirection())) {
                return parent.getBalanceDirection();
            }
        }
        // 3. 备抵科目特殊处理
        if (code.startsWith("1602") || code.startsWith("1231") || code.startsWith("1702")) {
            return "贷";
        }
        // 4. 按首位推断
        char first = code.charAt(0);
        return switch (first) {
            case '1', '4' -> "借"; // 资产、成本
            case '2', '3' -> "贷"; // 负债、权益
            case '5' -> (code.startsWith("5001") || code.startsWith("5051")) ? "贷" : "借"; // 收入贷、费用借
            case '6' -> "借"; // 费用类
            default -> "借";
        };
    }

    /** 清洗科目名称：去掉缩进符号、编码前缀 */
    /**
     * 预处理：合并 Excel 中同名同父级的重复行。
     * 判断规则：名称相同 + rawCode 的直接父级相同 → 合并余额。
     */
    private List<RawSubjectRow> mergeRawDuplicates(List<RawSubjectRow> rawData) {
        // 建立 rawCode 前缀关系
        Set<String> allCleanCodes = new LinkedHashSet<>();
        for (RawSubjectRow r : rawData) {
            allCleanCodes.add(r.rawCode.replace(".", "").replace("-", "").replace(" ", "").replace("_", ""));
        }

        // 找每行的直接父级 rawCode
        Map<String, String> childToParent = new LinkedHashMap<>();
        for (String code : allCleanCodes) {
            String bestParent = "";
            for (String other : allCleanCodes) {
                if (other.length() < code.length() && code.startsWith(other)) {
                    if (other.length() > bestParent.length()) bestParent = other;
                }
            }
            childToParent.put(code, bestParent);
        }

        // 按"清洗后名称 + 父级 cleanCode"分组合并
        Map<String, RawSubjectRow> merged = new LinkedHashMap<>();
        for (RawSubjectRow r : rawData) {
            String cleanCode = r.rawCode.replace(".", "").replace("-", "").replace(" ", "").replace("_", "");
            String parentClean = childToParent.getOrDefault(cleanCode, "");
            String cleanName = cleanRawSubjectName(r.name);
            String key = cleanName + "|" + parentClean;

            RawSubjectRow existing = merged.get(key);
            if (existing != null) {
                // 合并余额
                if (r.periodEndDebit != null) {
                    existing.periodEndDebit = (existing.periodEndDebit != null ? existing.periodEndDebit : BigDecimal.ZERO).add(r.periodEndDebit);
                }
                if (r.periodEndCredit != null) {
                    existing.periodEndCredit = (existing.periodEndCredit != null ? existing.periodEndCredit : BigDecimal.ZERO).add(r.periodEndCredit);
                }
                if (r.yearTotalDebit != null) {
                    existing.yearTotalDebit = (existing.yearTotalDebit != null ? existing.yearTotalDebit : BigDecimal.ZERO).add(r.yearTotalDebit);
                }
                if (r.yearTotalCredit != null) {
                    existing.yearTotalCredit = (existing.yearTotalCredit != null ? existing.yearTotalCredit : BigDecimal.ZERO).add(r.yearTotalCredit);
                }
                log.info("Excel 预处理合并: name={}, rawCode={} 合并到 rawCode={}", cleanName, r.rawCode, existing.rawCode);
            } else {
                // 创建副本避免修改原始数据
                RawSubjectRow copy = new RawSubjectRow();
                copy.rawCode = r.rawCode;
                copy.name = r.name;
                copy.periodEndDebit = r.periodEndDebit;
                copy.periodEndCredit = r.periodEndCredit;
                copy.yearTotalDebit = r.yearTotalDebit;
                copy.yearTotalCredit = r.yearTotalCredit;
                merged.put(key, copy);
            }
        }

        List<RawSubjectRow> result = new ArrayList<>(merged.values());
        if (result.size() < rawData.size()) {
            log.info("Excel 预处理：合并前 {} 行，合并后 {} 行", rawData.size(), result.size());
        }
        return result;
    }

    /** 打印原始数据合计（用于和 Excel 合计行对比，验证提取正确性） */
    private void logRawDataSummary(String phase, List<RawSubjectRow> rawData) {
        BigDecimal totalEndDebit = BigDecimal.ZERO, totalEndCredit = BigDecimal.ZERO;
        BigDecimal totalYearDebit = BigDecimal.ZERO, totalYearCredit = BigDecimal.ZERO;
        int countWithBalance = 0;
        for (RawSubjectRow r : rawData) {
            if (r.periodEndDebit != null) totalEndDebit = totalEndDebit.add(r.periodEndDebit);
            if (r.periodEndCredit != null) totalEndCredit = totalEndCredit.add(r.periodEndCredit);
            if (r.yearTotalDebit != null) totalYearDebit = totalYearDebit.add(r.yearTotalDebit);
            if (r.yearTotalCredit != null) totalYearCredit = totalYearCredit.add(r.yearTotalCredit);
            if ((r.periodEndDebit != null && r.periodEndDebit.compareTo(BigDecimal.ZERO) != 0)
                    || (r.periodEndCredit != null && r.periodEndCredit.compareTo(BigDecimal.ZERO) != 0)) {
                countWithBalance++;
            }
        }
        log.info("原始数据合计[{}]: 共{}行(有余额{}行), 期末借方合计={}, 期末贷方合计={}, 本年累计借方={}, 本年累计贷方={}",
                phase, rawData.size(), countWithBalance, totalEndDebit, totalEndCredit, totalYearDebit, totalYearCredit);
    }

    private String cleanRawSubjectName(String name) {
        if (StrUtil.isBlank(name)) return name;
        // 去掉换行符（Excel 单元格内换行）
        name = name.replace("\n", "").replace("\r", "");
        // 去掉缩进/树形符号
        name = name.replaceAll("^[├└│─·\\s　]+", "");
        // 去掉可能的编码前缀（如 "1001 库存现金" → "库存现金"）
        name = name.replaceAll("^\\d[\\d.\\-]*\\s+", "");
        // 去掉 HTML 标签残留（如 <br>）
        name = name.replaceAll("<br\\s*/?>", "");
        return name.trim();
    }

    // ═══════════════════════════════════════════════════════════════════
    // ⑤ 构建余额数据（替代 mergeBalances，不再需要 rawCode↔AI code 映射）
    // ═══════════════════════════════════════════════════════════════════

    private List<Map<String, Object>> buildBalances(List<RawSubjectRow> rawData,
                                                     List<Map<String, Object>> normalizedSubjects,
                                                     String period) {
        // rawCode → 原始数据
        Map<String, RawSubjectRow> rawMap = new LinkedHashMap<>();
        for (RawSubjectRow r : rawData) rawMap.put(r.rawCode, r);

        // 按 code 聚合：同一父级下同名科目合并余额
        Map<String, Map<String, Object>> codeBalanceMap = new LinkedHashMap<>();
        boolean isJanuary = period != null && period.endsWith("-01");

        for (Map<String, Object> s : normalizedSubjects) {
            String rawCode = str(s, "rawCode");
            String code = str(s, "code");
            // 父级科目不入余额（由子科目聚合）
            if (Boolean.TRUE.equals(s.get("isParent"))) {
                log.info("buildBalances 跳过父级科目: code={}, rawCode={}", code, rawCode);
                continue;
            }
            RawSubjectRow raw = rawMap.get(rawCode);
            if (raw == null) continue;

            BigDecimal endDebit = raw.periodEndDebit != null ? raw.periodEndDebit : BigDecimal.ZERO;
            BigDecimal endCredit = raw.periodEndCredit != null ? raw.periodEndCredit : BigDecimal.ZERO;
            BigDecimal yearDebit = isJanuary ? BigDecimal.ZERO
                    : (raw.yearTotalDebit != null ? raw.yearTotalDebit : BigDecimal.ZERO);
            BigDecimal yearCredit = isJanuary ? BigDecimal.ZERO
                    : (raw.yearTotalCredit != null ? raw.yearTotalCredit : BigDecimal.ZERO);

            Map<String, Object> existing = codeBalanceMap.get(code);
            if (existing != null) {
                // 同一 code 已存在 → 合并余额
                BigDecimal prevDebit = (BigDecimal) existing.getOrDefault("periodEndDebit", BigDecimal.ZERO);
                BigDecimal prevCredit = (BigDecimal) existing.getOrDefault("periodEndCredit", BigDecimal.ZERO);
                BigDecimal prevYearDebit = (BigDecimal) existing.getOrDefault("yearTotalDebit", BigDecimal.ZERO);
                BigDecimal prevYearCredit = (BigDecimal) existing.getOrDefault("yearTotalCredit", BigDecimal.ZERO);
                existing.put("periodEndDebit", prevDebit.add(endDebit));
                existing.put("periodEndCredit", prevCredit.add(endCredit));
                existing.put("yearTotalDebit", prevYearDebit.add(yearDebit));
                existing.put("yearTotalCredit", prevYearCredit.add(yearCredit));
                log.info("同编码科目余额合并: code={}, rawCode={}", code, rawCode);
            } else {
                Map<String, Object> balance = new LinkedHashMap<>();
                balance.put("code", code);
                balance.put("periodEndDebit", endDebit);
                balance.put("periodEndCredit", endCredit);
                balance.put("yearTotalDebit", yearDebit);
                balance.put("yearTotalCredit", yearCredit);
                balance.put("isParent", Boolean.TRUE.equals(s.get("isParent")));
                codeBalanceMap.put(code, balance);
            }
        }
        return new ArrayList<>(codeBalanceMap.values());
    }

    /**
     * 将原始行数据格式化为文本（供 AI 阅读表头）
     */
    private String formatExcelText(List<Map<Integer, String>> rawRows, int maxRows) {
        int maxCol = rawRows.stream()
                .mapToInt(r -> r.keySet().stream().mapToInt(Integer::intValue).max().orElse(0))
                .max().orElse(0);

        StringBuilder sb = new StringBuilder();
        int limit = Math.min(maxRows, rawRows.size());
        for (int r = 0; r < limit; r++) {
            Map<Integer, String> row = rawRows.get(r);
            List<String> vals = new ArrayList<>();
            boolean allEmpty = true;
            for (int i = 0; i <= maxCol; i++) {
                String v = row.getOrDefault(i, "");
                vals.add(v);
                if (StrUtil.isNotBlank(v)) allEmpty = false;
            }
            if (allEmpty) continue;
            sb.append("第").append(r + 1).append("行: ").append(JSON.toJSONString(vals)).append("\n");
        }
        return sb.toString();
    }

    // ═══════════════════════════════════════════════════════════════════
    // ②③ AI 直接从表格提取有效科目数据
    // ═══════════════════════════════════════════════════════════════════

    @SuppressWarnings("unchecked")
    private List<RawSubjectRow> aiExtractSubjects(List<Map<Integer, String>> rawRows) {
        String tableText = formatExcelText(rawRows, rawRows.size());
        String systemPrompt = loadPrompt("prompts/init-structure.txt");
        String userMsg = "请从以下科目余额表中提取所有有效科目数据：\n\n" + tableText;

        String response = callQwen(systemPrompt, userMsg, "科目数据提取");
        Map<String, Object> parsed = parseJson(response);

        // 兼容短 key "s" 和长 key "subjects"
        List<Map<String, Object>> subjects = getList(parsed, "s");
        if (subjects.isEmpty()) subjects = getList(parsed, "subjects");
        if (subjects.isEmpty()) {
            throw new RuntimeException("AI 未能从表格中提取到任何科目数据，请检查表格格式");
        }

        List<RawSubjectRow> result = new ArrayList<>();
        for (Map<String, Object> s : subjects) {
            // 短 key 映射: a=code, b=name, c=endDebit, d=endCredit, e=periodDebit, f=periodCredit, g=yearDebit, h=yearCredit
            String code = str(s, "a") != null ? str(s, "a") : str(s, "code");
            String name = str(s, "b") != null ? str(s, "b") : str(s, "name");
            if (StrUtil.isBlank(code)) continue;

            RawSubjectRow r = new RawSubjectRow();
            r.rawCode = code.trim();
            r.name = name != null ? name.trim() : "";
            r.periodEndDebit = decimalValAny(s, "c", "endDebit");
            r.periodEndCredit = decimalValAny(s, "d", "endCredit");
            r.periodDebit = decimalValAny(s, "e", "periodDebit");
            r.periodCredit = decimalValAny(s, "f", "periodCredit");
            r.yearTotalDebit = decimalValAny(s, "g", "yearDebit");
            r.yearTotalCredit = decimalValAny(s, "h", "yearCredit");

            log.debug("AI提取: code={}, name={}, endDebit={}, endCredit={}, periodDebit={}, periodCredit={}, yearDebit={}, yearCredit={}",
                    r.rawCode, r.name, r.periodEndDebit, r.periodEndCredit,
                    r.periodDebit, r.periodCredit, r.yearTotalDebit, r.yearTotalCredit);
            result.add(r);
        }

        log.info("AI 从表格提取到 {} 个有效科目", result.size());
        return result;
    }

    private BigDecimal decimalVal(Map<String, Object> map, String key) {
        Object v = map.get(key);
        if (v == null) return null;
        if (v instanceof Number) {
            return new BigDecimal(v.toString());
        }
        String s = v.toString().trim();
        if (s.isEmpty() || "null".equalsIgnoreCase(s)) return null;
        try {
            return new BigDecimal(s.replace(",", ""));
        } catch (Exception e) {
            return null;
        }
    }

    /** 尝试短 key 优先，回退到长 key */
    private BigDecimal decimalValAny(Map<String, Object> map, String shortKey, String longKey) {
        BigDecimal v = decimalVal(map, shortKey);
        return v != null ? v : decimalVal(map, longKey);
    }

    /** 将科目识别的短 key 还原为长 key（兼容两种格式） */
    private Map<String, Object> expandSubjectKeys(Map<String, Object> s) {
        Map<String, Object> r = new LinkedHashMap<>(s);
        // 短 key → 长 key 映射（仅在短 key 存在时添加）
        if (s.containsKey("a") && !s.containsKey("rawCode")) r.put("rawCode", s.get("a"));
        if (s.containsKey("b") && !s.containsKey("code")) r.put("code", s.get("b"));
        if (s.containsKey("c") && !s.containsKey("name")) r.put("name", s.get("c"));
        if (s.containsKey("d") && !s.containsKey("parentCode")) r.put("parentCode", s.get("d"));
        if (s.containsKey("e") && !s.containsKey("type")) r.put("type", s.get("e"));
        if (s.containsKey("f") && !s.containsKey("balanceDirection")) r.put("balanceDirection", s.get("f"));
        return r;
    }

    /** 从单元格读取值 */
    private String getCell(Map<Integer, String> row, int colIdx) {
        if (colIdx < 0) return null;
        return row.getOrDefault(colIdx, "");
    }

    /** 解析单元格中的数字，处理千分位、括号负数等 */
    private BigDecimal parseCellDecimal(String cellValue) {
        if (StrUtil.isBlank(cellValue)) return null;
        String s = cellValue.trim();
        // "—"、"-"（单独出现时）视为空
        if ("—".equals(s) || "-".equals(s) || "－".equals(s)) return null;
        // 处理括号负数：(100.00) → -100.00
        boolean negative = false;
        if (s.startsWith("(") && s.endsWith(")")) {
            s = s.substring(1, s.length() - 1);
            negative = true;
        }
        // 去掉千分位逗号
        s = s.replace(",", "").replace("，", "");
        try {
            BigDecimal val = new BigDecimal(s);
            if (negative) val = val.negate();
            return val;
        } catch (Exception e) {
            return null;
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // ④ AI 识别科目元信息（编码规范化、类型、方向、父子关系）
    // ═══════════════════════════════════════════════════════════════════

    private List<Map<String, Object>> identifySubjects(List<RawSubjectRow> rawData,
                                                        String systemSubjects,
                                                        String encodingRule,
                                                        String accountType,
                                                        Long accountSetId) {
        // 构建待处理科目列表文本
        StringBuilder subjectList = new StringBuilder();
        for (RawSubjectRow r : rawData) {
            subjectList.append(r.rawCode).append(" | ").append(r.name).append("\n");
        }

        String prompt = loadPrompt("prompts/init-subjects.txt")
                .replace("${encodingRule}", encodingRule)
                .replace("${accountType}", "general_taxpayer".equals(accountType) ? "一般纳税人" : "小规模纳税人")
                .replace("${systemSubjects}", systemSubjects)
                .replace("${subjectList}", subjectList.toString());

        String userMsg = "请对以上科目列表进行规范化处理。";
        for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {
            try {
                String response = callQwen(prompt, userMsg, "科目识别");
                Map<String, Object> parsed = parseJson(response);
                // 兼容短 key "s" 和长 key "subjects"
                List<Map<String, Object>> subjects = getList(parsed, "s");
                if (subjects.isEmpty()) subjects = getList(parsed, "subjects");
                // 短 key 还原
                List<Map<String, Object>> result = new ArrayList<>();
                for (Map<String, Object> s : subjects) {
                    result.add(expandSubjectKeys(s));
                }
                if (!result.isEmpty()) {
                    log.info("AI 科目识别完成，识别到 {} 个科目", result.size());
                    return result;
                }
                log.warn("AI 科目识别返回空列表（第{}次）", attempt);
            } catch (Exception e) {
                log.warn("AI 科目识别失败（第{}次）: {}", attempt, e.getMessage());
                if (attempt == MAX_RETRY) throw new RuntimeException("AI 科目识别失败：" + e.getMessage(), e);
            }
        }
        throw new RuntimeException("AI 科目识别结果为空");
    }

    // ═══════════════════════════════════════════════════════════════════
    // ⑥ 合并：AI 规范化编码 + Excel 原始数字
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 将 AI 返回的规范化科目信息与 Java 提取的原始数字合并。
     * 数字来自 Java 精确读取，编码来自 AI 规范化，确保两者正确关联。
     */
    private List<Map<String, Object>> mergeBalances(List<RawSubjectRow> rawData,
                                                     List<Map<String, Object>> aiSubjects,
                                                     String period) {
        // rawCode → 原始数据映射
        Map<String, RawSubjectRow> rawMap = new LinkedHashMap<>();
        for (RawSubjectRow r : rawData) {
            rawMap.put(r.rawCode, r);
        }

        List<Map<String, Object>> result = new ArrayList<>();
        int matched = 0, unmatched = 0;

        for (Map<String, Object> s : aiSubjects) {
            String rawCode = str(s, "rawCode");
            String code = str(s, "code");

            RawSubjectRow raw = rawMap.get(rawCode);
            if (raw == null) {
                log.warn("AI 返回的 rawCode 在原始数据中找不到: rawCode={}, code={}", rawCode, code);
                unmatched++;
                continue;
            }

            Map<String, Object> balance = new LinkedHashMap<>();
            balance.put("code", code);
            // 数字直接来自 Java 提取的 Excel 原始数据，零误差
            balance.put("periodEndDebit", raw.periodEndDebit);
            balance.put("periodEndCredit", raw.periodEndCredit);
            // 1月份期初导入时，本年累计不需要导入（年初无累计）
            boolean isJanuary = period != null && period.endsWith("-01");
            if (isJanuary) {
                balance.put("yearTotalDebit", BigDecimal.ZERO);
                balance.put("yearTotalCredit", BigDecimal.ZERO);
            } else {
                // 优先用"本年累计发生额"；若表格没有此分组，则用"本期发生额"代替
                BigDecimal yearDebit  = raw.yearTotalDebit  != null ? raw.yearTotalDebit  : raw.periodDebit;
                BigDecimal yearCredit = raw.yearTotalCredit != null ? raw.yearTotalCredit : raw.periodCredit;
                balance.put("yearTotalDebit",  yearDebit  != null ? yearDebit  : BigDecimal.ZERO);
                balance.put("yearTotalCredit", yearCredit != null ? yearCredit : BigDecimal.ZERO);
            }
            result.add(balance);
            matched++;
        }

        log.info("数据合并完成: 匹配={}, 未匹配={}", matched, unmatched);

        // 检查是否有原始数据没有被 AI 识别到
        Set<String> aiRawCodes = aiSubjects.stream()
                .map(s -> str(s, "rawCode"))
                .filter(StrUtil::isNotBlank)
                .collect(Collectors.toSet());
        for (RawSubjectRow r : rawData) {
            if (!aiRawCodes.contains(r.rawCode)) {
                boolean hasBalance = (r.periodEndDebit != null && r.periodEndDebit.compareTo(BigDecimal.ZERO) != 0)
                        || (r.periodEndCredit != null && r.periodEndCredit.compareTo(BigDecimal.ZERO) != 0);
                if (hasBalance) {
                    log.warn("⚠ Excel 科目未被 AI 识别（有非零余额）: rawCode={}, name={}, debit={}, credit={}",
                            r.rawCode, r.name, r.periodEndDebit, r.periodEndCredit);
                }
            }
        }

        return result;
    }

    // ═══════════════════════════════════════════════════════════════════
    // Qwen API 调用（通用）
    // ═══════════════════════════════════════════════════════════════════

    private String callQwen(String systemPrompt, String userContent, String stepName) {
        log.info("调用 Qwen [{}]: model={}, systemPrompt长度={}, userContent长度={}",
                stepName, qwenModel, systemPrompt.length(), userContent.length());
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
                    .maxTokens(16384)
                    .resultFormat(GenerationParam.ResultFormat.MESSAGE)
                    .build();

            GenerationResult result = gen.call(param);
            String content = result.getOutput().getChoices().get(0).getMessage().getContent();

            if (result.getUsage() != null) {
                log.info("Qwen [{}] token 消耗: prompt={}, completion={}",
                        stepName, result.getUsage().getInputTokens(), result.getUsage().getOutputTokens());
            }
            if (StrUtil.isBlank(content)) {
                throw new RuntimeException("Qwen 返回内容为空");
            }

            String cleaned = cleanJson(content);
            log.info("Qwen [{}] 返回内容长度: {}", stepName, cleaned.length());
            log.debug("Qwen [{}] 返回内容前500字: {}", stepName,
                    cleaned.substring(0, Math.min(500, cleaned.length())));
            return cleaned;

        } catch (com.alibaba.dashscope.exception.NoApiKeyException e) {
            throw new RuntimeException("API Key 未配置或无效", e);
        } catch (com.alibaba.dashscope.exception.InputRequiredException e) {
            throw new RuntimeException("请求参数缺失: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("调用 Qwen 失败: " + e.getMessage(), e);
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
            if (parsed == null) throw new RuntimeException("JSON解析返回null");
            return parsed;
        } catch (Exception e) {
            String preview = content.substring(0, Math.min(300, content.length()));
            throw new RuntimeException("JSON解析失败: " + e.getMessage() + "，内容前300字: " + preview, e);
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getList(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val instanceof List) return (List<Map<String, Object>>) val;
        return Collections.emptyList();
    }

    // ═══════════════════════════════════════════════════════════════════
    // 提示词加载
    // ═══════════════════════════════════════════════════════════════════

    private String loadPrompt(String path) {
        try {
            ClassPathResource resource = new ClassPathResource(path);
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
                return reader.lines().collect(Collectors.joining("\n"));
            }
        } catch (Exception e) {
            throw new RuntimeException("加载提示词失败: " + path, e);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // 系统科目列表（给 AI 参考）
    // ═══════════════════════════════════════════════════════════════════

    private String buildSubjectListText(Long accountSetId) {
        List<SubjectVo> subjects = subjectService.list(accountSetId);
        if (subjects == null || subjects.isEmpty()) return "（暂无科目）";
        // 紧凑格式：编码,名称,类别缩写,方向  （节省 input tokens）
        StringBuilder sb = new StringBuilder();
        for (SubjectVo s : subjects) {
            sb.append(s.getCode()).append(",")
              .append(s.getName()).append(",")
              .append(shortType(s.getType())).append(",")
              .append(s.getBalanceDirection() != null ? s.getBalanceDirection() : "").append("\n");
        }
        return sb.toString();
    }

    /** 科目类别缩写：资产→资, 负债→负, 权益→权, 成本→成, 损益→损 */
    private String shortType(String type) {
        if (type == null) return "";
        switch (type) {
            case "资产": return "资";
            case "负债": return "负";
            case "权益": return "权";
            case "成本": return "成";
            case "损益": return "损";
            default: return type;
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // ⑤ 科目对比与新增
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 在同一个事务中保存科目 + 计算余额 + 保存余额 + 标记完成。
     * AI 调用已在事务外完成，这里只做 DB 写入，事务快速释放。
     */
    @Transactional
    public int[] saveSubjectsAndBalances(List<Map<String, Object>> aiSubjects,
                                         List<Map<String, Object>> balances,
                                         Long accountSetId, AccountSets accountSets,
                                         String period, Map<String, Subject> codeMap) {
        int newSubjectCount = saveNewSubjects(aiSubjects, accountSetId, accountSets, codeMap);
        progress(accountSetId, "科目处理完成，新增 " + newSubjectCount + " 个");

        progress(accountSetId, "正在计算期初余额...");
        List<Map<String, Object>> calculatedBalances =
                calculateBeginBalances(balances, accountSetId, codeMap);
        validateBalance(calculatedBalances, accountSetId, codeMap);

        progress(accountSetId, "正在保存期初余额...");
        Long localCurrencyId = currencyService.fetchLocal(accountSetId).getId();
        int balanceCount = saveInitialBalances(
                calculatedBalances, accountSetId, accountSets, localCurrencyId, codeMap);

        markStepDone(accountSetId, period, accountSets);
        return new int[]{newSubjectCount, balanceCount};
    }

    private int saveNewSubjects(List<Map<String, Object>> list, Long accountSetId,
                                AccountSets accountSets, Map<String, Subject> codeMap) {
        Integer encLen = accountSets.getEncoding().stream().reduce(Integer::sum).orElse(0);
        int count = 0;
        for (Map<String, Object> m : list) {
            String code = str(m, "code");
            String name = str(m, "name");
            if (StrUtil.isBlank(code)) continue;
            try {
                // 编码和名称冲突已在 resolveCodeConflicts 中统一处理
                // 这里只做：存在则复用，不存在则新增

                // 按编码查 → 存在且同名 → 复用
                Subject existing = subjectService.listByCode(accountSetId, code);
                if (existing != null) {
                    fixDirectionIfNeeded(existing, code, accountSetId, codeMap, m, encLen);
                    codeMap.put(code, existing);
                    continue;
                }

                // 按名称查 → 同类别同名 → 复用
                if (StrUtil.isNotBlank(name)) {
                    Subject byName = subjectService.listByName(accountSetId, name);
                    if (byName != null && isSameTopCategory(code, byName.getCode())) {
                        fixDirectionIfNeeded(byName, code, accountSetId, codeMap, m, encLen);
                        codeMap.put(code, byName);
                        continue;
                    }
                }

                // 新增科目
                Subject subject = new Subject();
                subject.setAccountSetsId(accountSetId);
                subject.setSystemDefault(false);
                subject.setCode(code);
                subject.setType(str(m, "type"));
                subject.setBalanceDirection(str(m, "balanceDirection"));

                String parentCode = str(m, "parentCode");
                Subject parent = null;
                if (StrUtil.isNotBlank(parentCode)) {
                    parent = codeMap.containsKey(parentCode)
                            ? codeMap.get(parentCode)
                            : subjectService.listByCode(accountSetId, parentCode);
                }

                // 方向继承：子科目强制继承父科目方向（覆盖 AI 可能的错误判断）
                if (parent != null && StrUtil.isNotBlank(parent.getBalanceDirection())) {
                    subject.setBalanceDirection(parent.getBalanceDirection());
                }

                // 清理科目名称中的父级前缀
                name = cleanSubjectName(name, parent);
                subject.setName(name);

                if (parent != null) {
                    subject.setParentId(parent.getId());
                    subject.setLevel(parent.getLevel() + 1);
                } else {
                    subject.setParentId(0L);
                    subject.setLevel(1);
                }
                subject.setLeaf(true);
                subject.setStatus(true);
                subject.setCash(isCashSubject(code));

                // 插入前再做一次"同账套+同名+同类型+同父级"精确查找——
                // 系统标准科目模板可能用不同编码占用了相同名称（例如 1012 其他货币资金 vs 用户的 1009）
                Subject sameNameUnderParent = subjectService.findByNameTypeParent(
                        accountSetId, subject.getName(), subject.getType(), subject.getParentId());
                if (sameNameUnderParent != null) {
                    log.info("复用已存在科目: 用户code={} → 系统code={} name={}",
                            code, sameNameUnderParent.getCode(), subject.getName());
                    codeMap.put(code, sameNameUnderParent);
                    continue;
                }

                subjectService.save(subject, encLen);
                codeMap.put(code, subject);
                count++;
            } catch (Exception e) {
                throw new RuntimeException(
                        "保存科目失败: code=" + code + ", name=" + name + " — " + e.getMessage(), e);
            }
        }
        return count;
    }

    /**
     * 修正已有科目的余额方向：确保子科目方向与父科目一致。
     * 如果父科目方向与已有科目方向不同，更新并持久化。
     */
    private void fixDirectionIfNeeded(Subject existing, String code, Long accountSetId,
                                       Map<String, Subject> codeMap,
                                       Map<String, Object> m, Integer encLen) {
        String parentCode = str(m, "parentCode");
        if (StrUtil.isBlank(parentCode)) return;

        Subject parent = codeMap.containsKey(parentCode)
                ? codeMap.get(parentCode)
                : subjectService.listByCode(accountSetId, parentCode);
        if (parent == null || StrUtil.isBlank(parent.getBalanceDirection())) return;

        String parentDirection = parent.getBalanceDirection();
        if (!parentDirection.equals(existing.getBalanceDirection())) {
            log.info("修正科目方向: code={} name={} 原方向={} → 新方向={} (继承父科目{})",
                    code, existing.getName(), existing.getBalanceDirection(), parentDirection, parentCode);
            existing.setBalanceDirection(parentDirection);
            subjectService.save(existing, encLen);
        }
    }

    /**
     * 判断两个科目编码是否属于同一个一级科目（前4位相同）。
     * 避免同名公司分别挂在应收账款(1122)和应付账款(2202)下时互相干扰。
     */
    private boolean isSameTopCategory(String code1, String code2) {
        if (StrUtil.isBlank(code1) || StrUtil.isBlank(code2)) return false;
        int len = Math.min(4, Math.min(code1.length(), code2.length()));
        return code1.substring(0, len).equals(code2.substring(0, len));
    }

    /**
     * 编码去重：当 AI 将不同名称的子科目归一化为相同编码时，为后续的自动分配新编码。
     * 例如：224105（个人法人）和 224106（李家新）都被 AI 归一化为 2241002，
     * 第一个保留 2241002，第二个自动改为 2241003。
     */
    private void deduplicateSubjectCodes(List<Map<String, Object>> aiSubjects, List<Integer> encoding) {
        // code → 已占用该编码的科目名称
        Map<String, String> codeToName = new LinkedHashMap<>();
        // parentCode → 当前最大子编码序号（用于生成下一个可用编码）
        Map<String, Integer> parentMaxSeq = new HashMap<>();

        // 第一遍：收集所有已使用的编码，计算每个父编码下的最大序号
        for (Map<String, Object> m : aiSubjects) {
            String code = str(m, "code");
            String parentCode = str(m, "parentCode");
            if (StrUtil.isBlank(code) || StrUtil.isBlank(parentCode)) continue;
            if (code.length() > parentCode.length()) {
                String seqPart = code.substring(parentCode.length());
                try {
                    int seq = Integer.parseInt(seqPart);
                    parentMaxSeq.merge(parentCode, seq, Math::max);
                } catch (NumberFormatException ignored) {}
            }
        }

        // 第二遍：去重
        for (Map<String, Object> m : aiSubjects) {
            String code = str(m, "code");
            String name = str(m, "name");
            String parentCode = str(m, "parentCode");
            if (StrUtil.isBlank(code)) continue;

            String existingName = codeToName.get(code);
            if (existingName == null) {
                // 首次出现，占用该编码
                codeToName.put(code, name != null ? name : "");
            } else if (!existingName.equals(name != null ? name : "")) {
                // 编码相同但名称不同 → 需要分配新编码
                if (StrUtil.isNotBlank(parentCode)) {
                    // 计算子编码位数
                    int childLen = code.length() - parentCode.length();
                    // 递增序号
                    int nextSeq = parentMaxSeq.getOrDefault(parentCode, 0) + 1;
                    parentMaxSeq.put(parentCode, nextSeq);
                    // 生成新编码：父编码 + 左补零的序号
                    String newCode = parentCode + String.format("%0" + childLen + "d", nextSeq);

                    log.info("编码去重: 原code={} name={} → 新code={} (父科目={}, 原编码已被[{}]占用)",
                            code, name, newCode, parentCode, existingName);
                    m.put("code", newCode);
                    codeToName.put(newCode, name != null ? name : "");
                } else {
                    log.warn("编码去重: code={} 名称冲突但无父编码，无法自动分配: 已有={}, 当前={}",
                            code, existingName, name);
                }
            }
            // 编码相同且名称相同 → 真正的重复行，保持不变
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // ⑦ Java 计算 beginBalance
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 根据 Java 精确提取的余额数据和系统科目余额方向，计算每个科目的 beginBalance。
     *
     * 规则（确定性，不依赖 AI 判断）：
     *   periodEndDebit  有值 → 数据在借方列：系统=借 → 正数，系统=贷 → 负数
     *   periodEndCredit 有值 → 数据在贷方列：系统=贷 → 正数，系统=借 → 负数
     *   两列都为 null    → 期末余额为零，beginBalance = 0
     */
    private List<Map<String, Object>> calculateBeginBalances(List<Map<String, Object>> balances,
                                                              Long accountSetId,
                                                              Map<String, Subject> codeMap) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> m : balances) {
            String code = str(m, "code");
            if (StrUtil.isBlank(code)) continue;

            Subject subject = codeMap.containsKey(code)
                    ? codeMap.get(code)
                    : subjectService.listByCode(accountSetId, code);
            if (subject == null) {
                log.warn("科目不存在，跳过余额计算: code={}", code);
                continue;
            }

            String direction = subject.getBalanceDirection();
            BigDecimal periodEndDebit  = decimalOrNull(m.get("periodEndDebit"));
            BigDecimal periodEndCredit = decimalOrNull(m.get("periodEndCredit"));

            BigDecimal beginBalance;
            String logSource;

            BigDecimal debit = periodEndDebit != null ? periodEndDebit : BigDecimal.ZERO;
            BigDecimal credit = periodEndCredit != null ? periodEndCredit : BigDecimal.ZERO;

            if (debit.compareTo(BigDecimal.ZERO) != 0 && credit.compareTo(BigDecimal.ZERO) != 0) {
                // 合并后两列都有值（如同名科目合并），取净额
                BigDecimal net = debit.subtract(credit);
                beginBalance = "借".equals(direction) ? net : net.negate();
                logSource = "期末借方=" + debit + " 期末贷方=" + credit + " 净额=" + net;
            } else if (debit.compareTo(BigDecimal.ZERO) != 0) {
                beginBalance = "借".equals(direction) ? debit : debit.negate();
                logSource = "期末借方=" + debit;
            } else if (credit.compareTo(BigDecimal.ZERO) != 0) {
                beginBalance = "贷".equals(direction) ? credit : credit.negate();
                logSource = "期末贷方=" + credit;
            } else {
                beginBalance = BigDecimal.ZERO;
                logSource = "期末为零";
            }

            log.info("beginBalance: code={} name={} direction={} {} → {}",
                    code, subject.getName(), direction, logSource, beginBalance);

            Map<String, Object> calc = new LinkedHashMap<>(m);
            calc.put("beginBalance", beginBalance);
            result.add(calc);
        }
        return result;
    }

    // ═══════════════════════════════════════════════════════════════════
    // ⑧ 借贷平衡校验
    // ═══════════════════════════════════════════════════════════════════

    private void validateBalance(List<Map<String, Object>> balances, Long accountSetId,
                                  Map<String, Subject> codeMap) {
        BigDecimal totalDebit  = BigDecimal.ZERO;
        BigDecimal totalCredit = BigDecimal.ZERO;

        for (Map<String, Object> m : balances) {
            String code = str(m, "code");
            if (StrUtil.isBlank(code)) continue;

            // 跳过父级科目（使用 normalizeSubjects 阶段标记的 isParent）
            if (Boolean.TRUE.equals(m.get("isParent"))) {
                log.info("校验跳过父级: code={}, beginBalance={}", code, m.get("beginBalance"));
                continue;
            }

            BigDecimal beginBalance = decimal(m.get("beginBalance"));
            if (beginBalance.compareTo(BigDecimal.ZERO) == 0) continue;

            String direction = getBalanceDirection(code, accountSetId, codeMap);
            if ("借".equals(direction)) {
                totalDebit = totalDebit.add(beginBalance);
            } else {
                totalCredit = totalCredit.add(beginBalance);
            }
            log.info("校验计入: code={}, direction={}, beginBalance={}, 累计借={}, 累计贷={}",
                    code, direction, beginBalance, totalDebit, totalCredit);
        }

        BigDecimal diff = totalDebit.subtract(totalCredit);
        log.info("借贷平衡校验: 借方合计={}, 贷方合计={}, 差额={}", totalDebit, totalCredit, diff);

        if (diff.abs().compareTo(new BigDecimal("0.01")) > 0) {
            log.warn("⚠ 借贷不平衡！差额={}", diff);
        } else {
            log.info("✓ 借贷平衡校验通过");
        }
    }

    private String getBalanceDirection(String code, Long accountSetId, Map<String, Subject> codeMap) {
        Subject s = codeMap.get(code);
        if (s != null && StrUtil.isNotBlank(s.getBalanceDirection())) return s.getBalanceDirection();
        s = subjectService.listByCode(accountSetId, code);
        if (s != null && StrUtil.isNotBlank(s.getBalanceDirection())) return s.getBalanceDirection();
        // 按编码首位推断
        if (StrUtil.isNotBlank(code)) {
            char first = code.charAt(0);
            if (first == '1' || first == '4') return "借";
            if (first == '2' || first == '3') return "贷";
            if (first == '5') return code.startsWith("5001") || code.startsWith("5051") ? "贷" : "借";
        }
        return "借";
    }

    // ═══════════════════════════════════════════════════════════════════
    // ⑨ 保存期初余额
    // ═══════════════════════════════════════════════════════════════════

    private int saveInitialBalances(List<Map<String, Object>> list, Long accountSetId,
                                    AccountSets accountSets, Long localCurrencyId,
                                    Map<String, Subject> codeMap) {
        Map<Long, BalanceInitialDto> balanceMap = new LinkedHashMap<>();
        for (Map<String, Object> m : list) {
            try {
                String code = str(m, "code");
                if (StrUtil.isBlank(code)) continue;

                Subject subject = codeMap.containsKey(code)
                        ? codeMap.get(code)
                        : subjectService.listByCode(accountSetId, code);
                if (subject == null) {
                    log.warn("科目不存在，跳过期初余额保存: code={}", code);
                    continue;
                }

                BigDecimal zero = BigDecimal.ZERO;
                BalanceInitialDto dto = new BalanceInitialDto();
                dto.setAccountSetsId(accountSetId);
                dto.setSubjectId(subject.getId());
                dto.setSubjectCode(code);
                dto.setCurrencyId(localCurrencyId);
                dto.setAuxiliaryDetailId(0L);
                dto.setBalanceDirection(subject.getBalanceDirection());
                // calculatedBalances 里只有叶子科目（父级已在 buildBalances 中跳过），
                // 强制 leaf=true，避免 DB 陈旧的 leaf=false 导致余额在 clear() 中被清零
                dto.setLeaf(true);
                dto.setLevel(subject.getLevel() != null ? subject.getLevel() : 1);
                dto.setTreeId(subject.getId());
                dto.setParentTreeId(subject.getParentId());
                dto.setBeginBalance(zero); dto.setBeginBalanceFor(zero); dto.setBeginQuantity(zero);
                dto.setYearTotalDebit(zero); dto.setYearTotalDebitFor(zero); dto.setYearTotalDebitQuantity(zero);
                dto.setYearTotalCredit(zero); dto.setYearTotalCreditFor(zero); dto.setYearTotalCreditQuantity(zero);
                dto.setYearTotal(zero); dto.setYearTotalFor(zero); dto.setYearTotalQuantity(zero);
                dto.setCreditQuantity(zero); dto.setDebitQuantity(zero);

                BigDecimal beginBalance = decimal(m.get("beginBalance"));
                dto.setBeginBalance(beginBalance);
                dto.setBeginBalanceFor(beginBalance);

                BigDecimal debit = decimal(m.get("yearTotalDebit"));
                dto.setYearTotalDebit(debit);
                dto.setYearTotalDebitFor(debit);

                BigDecimal credit = decimal(m.get("yearTotalCredit"));
                dto.setYearTotalCredit(credit);
                dto.setYearTotalCreditFor(credit);

                // 年初余额 = 从期初余额反推（期初余额 = 年初余额 + 本年累计变动）
                // 借方科目：yearTotal = beginBalance - debit + credit
                // 贷方科目：yearTotal = beginBalance + debit - credit
                BigDecimal yearTotal;
                if ("借".equals(subject.getBalanceDirection())) {
                    yearTotal = beginBalance.subtract(debit).add(credit);
                } else {
                    yearTotal = beginBalance.add(debit).subtract(credit);
                }
                dto.setYearTotal(yearTotal);
                dto.setYearTotalFor(yearTotal);

                if (balanceMap.containsKey(subject.getId())) {
                    // 同一科目出现多次（同父级下同名），合并余额
                    BalanceInitialDto prev = balanceMap.get(subject.getId());
                    prev.setBeginBalance(prev.getBeginBalance().add(dto.getBeginBalance()));
                    prev.setBeginBalanceFor(prev.getBeginBalanceFor().add(dto.getBeginBalanceFor()));
                    prev.setYearTotalDebit(prev.getYearTotalDebit().add(dto.getYearTotalDebit()));
                    prev.setYearTotalDebitFor(prev.getYearTotalDebitFor().add(dto.getYearTotalDebitFor()));
                    prev.setYearTotalCredit(prev.getYearTotalCredit().add(dto.getYearTotalCredit()));
                    prev.setYearTotalCreditFor(prev.getYearTotalCreditFor().add(dto.getYearTotalCreditFor()));
                    // 重新计算年初余额：借方科目 yearTotal = beginBalance - debit + credit
                    //                   贷方科目 yearTotal = beginBalance + debit - credit
                    if ("借".equals(subject.getBalanceDirection())) {
                        prev.setYearTotal(prev.getBeginBalance().subtract(prev.getYearTotalDebit()).add(prev.getYearTotalCredit()));
                    } else {
                        prev.setYearTotal(prev.getBeginBalance().add(prev.getYearTotalDebit()).subtract(prev.getYearTotalCredit()));
                    }
                    prev.setYearTotalFor(prev.getYearTotal());
                    log.info("期初余额科目合并: code={}, 合并后beginBalance={}", code, prev.getBeginBalance());
                } else {
                    balanceMap.put(subject.getId(), dto);
                }
            } catch (Exception e) {
                log.warn("处理期初余额失败，跳过: code={}, error={}", str(m, "code"), e.getMessage());
            }
        }

        // 为所有父级科目创建空的 DTO（用于后续聚合）
        for (Subject subject : codeMap.values()) {
            if (!balanceMap.containsKey(subject.getId())) {
                BigDecimal zero = BigDecimal.ZERO;
                BalanceInitialDto dto = new BalanceInitialDto();
                dto.setAccountSetsId(accountSetId);
                dto.setSubjectId(subject.getId());
                dto.setSubjectCode(subject.getCode());
                dto.setCurrencyId(localCurrencyId);
                dto.setAuxiliaryDetailId(0L);
                dto.setBalanceDirection(subject.getBalanceDirection());
                // 父级科目强制 leaf=false，避免 DB 陈旧值导致不被 clear()，影响汇总
                dto.setLeaf(false);
                dto.setLevel(subject.getLevel() != null ? subject.getLevel() : 1);
                dto.setTreeId(subject.getId());
                dto.setParentTreeId(subject.getParentId());
                dto.setBeginBalance(zero); dto.setBeginBalanceFor(zero); dto.setBeginQuantity(zero);
                dto.setYearTotalDebit(zero); dto.setYearTotalDebitFor(zero); dto.setYearTotalDebitQuantity(zero);
                dto.setYearTotalCredit(zero); dto.setYearTotalCreditFor(zero); dto.setYearTotalCreditQuantity(zero);
                dto.setYearTotal(zero); dto.setYearTotalFor(zero); dto.setYearTotalQuantity(zero);
                dto.setCreditQuantity(zero); dto.setDebitQuantity(zero);
                balanceMap.put(subject.getId(), dto);
            }
        }

        // 按层级从深到浅排序，子级先处理，逐级向父级聚合（null level 视为 1）
        List<BalanceInitialDto> balanceList = new ArrayList<>(balanceMap.values());
        balanceList.sort(Comparator.comparing(
                (BalanceInitialDto d) -> d.getLevel() != null ? d.getLevel() : 1).reversed());

        // 父级科目清零，然后从子级聚合
        Map<Long, BalanceInitialDto> idMap = balanceList.stream()
                .collect(Collectors.toMap(BalanceInitialDto::getSubjectId, d -> d));
        for (BalanceInitialDto dto : balanceList) {
            if (!Boolean.TRUE.equals(dto.getLeaf())) {
                dto.clear(); // 父级清零，等待子级聚合
            }
        }
        for (BalanceInitialDto dto : balanceList) {
            // level==null 时也参与汇总（只要 parentTreeId 有效即可）
            if (dto.getParentTreeId() != null && dto.getParentTreeId() != 0L) {
                BalanceInitialDto parent = idMap.get(dto.getParentTreeId());
                if (parent != null) {
                    parent.add(dto);
                    parent.calculateYearTotal();
                }
            }
        }

        // 为所有叶子节点也计算 yearTotal（确保一致性）
        for (BalanceInitialDto dto : balanceList) {
            if (Boolean.TRUE.equals(dto.getLeaf())) {
                dto.calculateYearTotal();
            }
        }

        if (!balanceList.isEmpty()) {
            balanceInitialService.updateInitBalance(balanceList, accountSets);
            balanceCalcService.calcSynchronization(accountSetId, YearMonth.from(accountSets.getEnableDate()));
        }
        return (int) balanceList.stream().filter(d -> d.isUsed()).count();
    }

    // ═══════════════════════════════════════════════════════════════════
    // TaskInitResult 写入 & TaskPeriod 状态管理
    // ═══════════════════════════════════════════════════════════════════

    private void saveInitResult(Long accountSetId, String period, Long ocTaskId,
                                String source, Integer subjectCount, Integer balanceCount,
                                boolean success, String errorMsg) {
        try {
            TaskPeriod tp = getOrCreate(accountSetId, period, null);
            TaskInitResult r = taskInitResultRepository.findByTaskPeriodId(tp.getId())
                    .orElseGet(() -> {
                        TaskInitResult nr = new TaskInitResult();
                        nr.setTaskPeriodId(tp.getId());
                        return nr;
                    });
            r.setOcTaskId(ocTaskId);
            r.setSource(source);
            r.setSubjectCount(subjectCount);
            r.setBalanceCount(balanceCount);
            r.setSuccess(success);
            r.setErrorMsg(errorMsg);
            r.setExecutedAt(LocalDateTime.now());
            taskInitResultRepository.save(r);
        } catch (Exception ex) {
            log.warn("保存期初导入结果失败（不影响主流程）", ex);
        }
    }

    private void markStepRunning(Long accountSetId, String period, AccountSets accountSets) {
        try {
            TaskPeriod tp = getOrCreate(accountSetId, period, accountSets);
            tp.setStepInit("running");
            tp.setErrorStep(null);
            tp.setErrorMsg(null);
            taskPeriodRepository.save(tp);
        } catch (Exception ex) {
            log.warn("标记运行状态失败", ex);
        }
    }

    private void markStepDone(Long accountSetId, String period, AccountSets accountSets) {
        TaskPeriod tp = getOrCreate(accountSetId, period, accountSets);
        tp.setStepInit("done");
        tp.setErrorStep(null);
        tp.setErrorMsg(null);
        taskPeriodRepository.save(tp);
    }

    private void markStepError(Long accountSetId, String period, AccountSets accountSets, String errMsg) {
        try {
            TaskPeriod tp = getOrCreate(accountSetId, period, accountSets);
            tp.setStepInit("error");
            tp.setErrorStep("step_init");
            tp.setErrorMsg(StrUtil.isBlank(errMsg) ? "期初导入失败" : errMsg);
            taskPeriodRepository.save(tp);
        } catch (Exception ex) {
            log.error("标记错误状态失败", ex);
        }
    }

    private TaskPeriod getOrCreate(Long accountSetId, String period, AccountSets accountSets) {
        return taskPeriodRepository.findByAccountSetIdAndPeriod(accountSetId, period)
                .orElseGet(() -> {
                    TaskPeriod tp = new TaskPeriod();
                    tp.setAccountSetId(accountSetId);
                    tp.setPeriod(period);
                    if (accountSets != null && StrUtil.isNotBlank(accountSets.getOcInstanceId())) {
                        tp.setOcInstanceId(accountSets.getOcInstanceId());
                    }
                    return taskPeriodRepository.save(tp);
                });
    }

    // ═══════════════════════════════════════════════════════════════════
    // 工具方法
    // ═══════════════════════════════════════════════════════════════════

    /** 前端订阅进度流入口 */
    public org.springframework.web.servlet.mvc.method.annotation.SseEmitter subscribeProgress(Long accountSetId) {
        return importProgressService.subscribe(accountSetId);
    }

    /** 同时写日志并推送进度到前端 */
    private void progress(Long accountSetId, String message) {
        log.info("[期初导入] {}", message);
        importProgressService.log(accountSetId, message);
    }

    private String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v != null ? v.toString() : null;
    }

    private int intVal(Map<String, Object> map, String key, int defaultVal) {
        Object v = map.get(key);
        if (v == null) return defaultVal;
        try { return Integer.parseInt(v.toString().trim()); } catch (Exception e) { return defaultVal; }
    }

    private BigDecimal decimal(Object v) {
        if (v == null) return BigDecimal.ZERO;
        if (v instanceof BigDecimal) return (BigDecimal) v;
        if (v instanceof Number) return BigDecimal.valueOf(((Number) v).doubleValue());
        try { return new BigDecimal(v.toString().trim()); } catch (Exception e) { return BigDecimal.ZERO; }
    }

    /** 返回 null 表示该列没有数据（区别于数值为0） */
    private BigDecimal decimalOrNull(Object v) {
        if (v == null) return null;
        if (v instanceof BigDecimal) return (BigDecimal) v;
        String s = v.toString().trim();
        if (s.isEmpty() || "null".equalsIgnoreCase(s)) return null;
        try {
            return new BigDecimal(s);
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isCashSubject(String code) {
        if (StrUtil.isBlank(code)) return false;
        return code.startsWith("1001") || code.startsWith("1002") || code.startsWith("1012");
    }

    /**
     * 清理科目名称中的父级前缀（AI已处理，这里作为兜底）
     */
    private String cleanSubjectName(String name, Subject parent) {
        if (StrUtil.isBlank(name) || parent == null || StrUtil.isBlank(parent.getName())) return name;
        String parentName = parent.getName();
        for (String sep : new String[]{".", "．", "-", "——", "—", "/", "_", "：", ":"}) {
            String prefix = parentName + sep;
            if (name.startsWith(prefix) && name.length() > prefix.length()) {
                String cleaned = name.substring(prefix.length()).trim();
                if (StrUtil.isNotBlank(cleaned)) {
                    log.debug("清理科目名称父级前缀: '{}' → '{}'", name, cleaned);
                    return cleaned;
                }
            }
        }
        return name;
    }
}
