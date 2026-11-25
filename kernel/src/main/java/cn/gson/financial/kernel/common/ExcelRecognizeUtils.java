package cn.gson.financial.kernel.common;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.lang.Assert;
import lombok.Data;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellRangeAddress;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * @Author zhb
 * @Date 2025-09-16
 * @Description
 */
public class ExcelRecognizeUtils {

    public static String RECOGNIZE_TYPE_HEADER = "header";
    public static String RECOGNIZE_TYPE_SKIP = "skip";
    public static String RECOGNIZE_TYPE_END = "end";

    public static int ENABLED_YES = 1;
    public static int ENABLED_NO = 0;


    /**
     * 表头识别结果
     *  todo 方便操作 临时使用内部类
     */
    public static class HeaderDataResult {
        //表头行索引
        public int headerRowIndex;
        //表头列数据列映射索引
        public Map<String, Integer> keyToColumnIndex;
        //表头列标题
        public List<String> headerValueList;
    }

    public static ReadOptions getReadOptions(){
        return ReadOptions.defaults();
    }
    /**
     * 读取控制参数
     *  todo 方便操作 临时使用内部类
     */
    @Data
    public static class ReadOptions {
        public static ReadOptions defaults() { return new ReadOptions(); }
        //sheet页面
        public int sheetIndex = 0;
        //表头关键词
        public List<String> headerField = new ArrayList<>();
        //最大扫描表头行数
        public int maxScanRows = 20;
        //连续空行多少行后停止读取数据
        public int blankRowStopNum = 2;
        //是否启用“按关键词判定数据跳过行”
        public boolean skipRows = false;
        //汇总关键字 */
        public Set<String> skipRowField = new HashSet<>();
        //是否启用“按关键词判定数据结束行”
        public boolean endRows = false;
        //结束行关键词集合（出现后视为数据读取结束，不含本行）
        public Set<String> endRowField = new HashSet<>();
        //结束行匹配方式：true=全部命中；false=任一命中
        public boolean endRowMatchAll = true;

    }

    /**
     * 基于文件路径读取文件数据（获取所有数据）
     * @param filePath -- 文件路径
     * @param options --文件读取控制参数
     * @return
     * @throws IOException
     */
    public static List<Map<String, String>> readRecordsByField(String filePath, ReadOptions options) {
        try {
            InputStream fis = new FileInputStream(filePath);
            Workbook workbook = WorkbookFactory.create(fis);
            return readRecordsByField(workbook, options);
        } catch (Exception e) {
            Assert.isTrue(Boolean.FALSE, "文件读取异常！" + e);
        }
        return null;
    }

    /**
     * 基于数据流读取文件数据（获取所有数据）
     * @param inputStream
     * @param options
     * @return
     * @throws IOException
     */
    public static List<Map<String, String>> readRecordsByField(InputStream inputStream, ReadOptions options) throws IOException {
        Workbook workbook = WorkbookFactory.create(inputStream);
        return readRecordsByField(workbook, options);
    }

    /**
     * 基于工作表读取文件数据（获取所有数据）
     * @param workbook
     * @param options
     * @return
     */
    public static List<Map<String, String>> readRecordsByField(Workbook workbook, ReadOptions options) {
        Sheet sheet = workbook.getSheetAt(options.sheetIndex);
        int headerRowIndex = findHeaderRowByField(sheet, options);
        List<String> headerValueList = readHeaderrowValueList(sheet, headerRowIndex);
        // 建立“表头行所有列”的映射（键为表头原文），避免只返回关键词列
        Map<String, Integer> keyToIndex = new LinkedHashMap<>();
        for (int i = 0; i < headerValueList.size(); i++) {
            String value = StringUtils.trimToEmpty(headerValueList.get(i));
            if (value.isEmpty()) {
                continue;
            }
            // 若重复列名，只保留首次出现，避免覆盖
            keyToIndex.putIfAbsent(value, i);
        }
        HeaderDataResult detect = new HeaderDataResult();
        detect.headerRowIndex = headerRowIndex;
        detect.keyToColumnIndex = keyToIndex;
        detect.headerValueList = headerValueList;
        return readRows(sheet, detect, options);
    }

    /**
     * 关键词全命中识别表头行
     * @param sheet
     * @param options
     * @return
     */
    public static int findHeaderRowByField(Sheet sheet, ReadOptions options) {
        Objects.requireNonNull(sheet, "sheet 不能为空");
        int lastRow = Math.min(sheet.getLastRowNum(), options.maxScanRows);
        //转为Set, 去除重复数据
        Set<String> checkSet = options.headerField.stream().map(item -> cleanText(item)).collect(Collectors.toSet());
        for (int i = sheet.getFirstRowNum(); i <= lastRow; i++) {
            List<String> rowValueList = readHeaderrowValueList(sheet, i);
            if (rowValueList.isEmpty()) {
                continue;
            }
            Set<String> rowSet = rowValueList.stream().map(String::trim).collect(Collectors.toSet());
            if (rowSet.containsAll(checkSet)) {
                return i;
            }
        }
        // 兜底：返回第一行（避免 -1 导致读取异常）
        return sheet.getFirstRowNum();
    }

    /**
     * 读取表头行
     * @param sheet
     * @param rowIndex
     * @return
     */
    private static List<String> readHeaderrowValueList(Sheet sheet, int rowIndex) {
        //获取行数据
        Row row = sheet.getRow(rowIndex);
        if (ObjectUtils.isEmpty(row)) {
            return Collections.emptyList();
        }
        DataFormatter formatter = new DataFormatter(Locale.CHINA, true);
        //获取最后一个单元格
        short lastCell = row.getLastCellNum();
        if (lastCell < 0) {
            return Collections.emptyList();
        }
        List<String> valueList = new ArrayList<>();
        for (int c = 0; c < lastCell; c++) {
            String value = getMergedCellString(sheet, rowIndex, c, formatter);
            valueList.add(cleanText(value));
        }
        return valueList;
    }

    /**
     * 清洗文本数据
     */
    private static String cleanText(String str) {
        if (str == null) {
            return null;
        }
        // 去除首尾空格,去除换行符
        str = str.trim().replaceAll("[\\r\\n\\t]+", "");
        return str;
    }

    /**
     * 读取考虑合并单元格的字符串表示
     * @param sheet
     * @param rowIndex
     * @param colIndex
     * @param formatter
     * @return
     */
    private static String getMergedCellString(Sheet sheet, int rowIndex, int colIndex, DataFormatter formatter) {
        Cell cell = getMergedCell(sheet, rowIndex, colIndex);
        if (ObjectUtils.isEmpty(cell)) {
            return "";
        }
        return formatter.formatCellValue(cell);
    }

    /**
     * 若 (row,col) 位于合并区域，返回该区域左上角单元格；否则返回常规单元格
     * @param sheet
     * @param rowIndex
     * @param colIndex
     * @return
     */
    private static Cell getMergedCell(Sheet sheet, int rowIndex, int colIndex) {
        for (int i = 0; i < sheet.getNumMergedRegions(); i++) {
            CellRangeAddress range = sheet.getMergedRegion(i);
            if (range.isInRange(rowIndex, colIndex)) {
                Row topRow = sheet.getRow(range.getFirstRow());
                if (ObjectUtils.isEmpty(topRow)) {
                    return null;
                }
                return topRow.getCell(range.getFirstColumn(), Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
            }
        }
        Row row = sheet.getRow(rowIndex);
        if (ObjectUtils.isEmpty(row)) {
            return null;
        }
        return row.getCell(colIndex, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
    }

    /**
     * 读取数据行
     * @param sheet
     * @param detect
     * @param options
     * @return
     */
    private static List<Map<String, String>> readRows(Sheet sheet, HeaderDataResult detect, ReadOptions options) {
        DataFormatter formatter = new DataFormatter(Locale.CHINA, true);
        List<Map<String, String>> records = new ArrayList<>();
        int startRow = detect.headerRowIndex + 1;
        //设置空行初始值
        int blankStreak = 0;
        for (int r = startRow; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (ObjectUtils.isEmpty(row)) {
                //空行超过设定值直接结束
                if (++blankStreak >= options.blankRowStopNum) {
                    break;
                }
                continue;
            }

            // 结束行关键词判断（在构造 record 之前即可终止）
            if (options.endRows && !options.endRowField.isEmpty()) {
                List<String> rawValues = readrowValueList(row);
                if (isEndRow(rawValues, options.endRowField, options.endRowMatchAll)) {
                    break; // 不包含当前行
                }
            }

            Map<String, String> record = new LinkedHashMap<>();
            AtomicBoolean allBlank = new AtomicBoolean(true);
            detect.keyToColumnIndex.forEach((k, v) ->{
                String value = "";
                if (v != null && v >= 0) {
                    Cell cell = row.getCell(v, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                    if (cell != null) {
                        value = formatter.formatCellValue(cell);
                    }
                }
                if (StringUtils.isNotBlank(value)) {
                    allBlank.set(false);
                }
                record.put(k, cleanText(value));
            });

            if (allBlank.get()) {
                if (++blankStreak >= options.blankRowStopNum) {
                    break;
                }
                continue;
            }
            blankStreak = 0;
            //是否需要跳过此行
            if (options.skipRows) {
                boolean summary = record.values().stream()
                        .filter(StringUtils::isNotBlank)
                        .anyMatch(v -> options.skipRowField.stream().anyMatch(v::contains));
                if (summary) {
                    continue;
                }
            }
            records.add(record);
        }
        return records;
    }

    /**
     * 读取行数据
     * @param row
     * @return
     */
    private static List<String> readrowValueList(Row row) {
        DataFormatter formatter = new DataFormatter(Locale.CHINA, true);
        short lastCell = row.getLastCellNum();
        List<String> values = new ArrayList<>();
        if (lastCell < 0) {
            return values;
        }
        for (int c = 0; c < lastCell; c++) {
            Cell cell = row.getCell(c, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
            String value = "";
            if (ObjectUtils.isNotEmpty(cell)) {
                value = formatter.formatCellValue(cell);
            }
            values.add(StringUtils.trimToEmpty(value));
        }
        return values;
    }

    /**
     * 数据读取结束判断
     *  excel中数据可能存在动态数据，采用模糊匹配
     * @param allList
     * @param checkSet
     * @param matchAll
     * @return
     */
    private static boolean isEndRow(List<String> allList, Set<String> checkSet, boolean matchAll) {
        if (allList == null || allList.isEmpty()) {
            return false;
        }
        if (matchAll) {
            // checkSet中的每个关键词都必须出现在allList中
            return checkSet.stream()
                    .filter(StringUtils::isNotEmpty)
                    .allMatch(keyword -> allList.stream()
                            .filter(StringUtils::isNotEmpty)
                            .anyMatch(row -> row.contains(keyword)));
        } else {
            // allList中包含checkSet中的任意一个关键词
            return allList.stream()
                    .filter(StringUtils::isNotEmpty)
                    .anyMatch(row -> checkSet.stream()
                            .filter(StringUtils::isNotEmpty)
                            .anyMatch(row::contains));
        }
    }

    /**
     * 从文件读取数据（仅获取表头判断数据）
     *  算法，根据key（所需要的字段）->List（excel表头类匹配字段）进行sheet页数据匹配，
     *      key为确定值， value为非确定值，
     *       确定表头行 因sheet数据存在干扰数据，例如个性化表头数据，会存在value重复匹配，
     *      针对方案 参考 matchHeader方法
     *          根据匹配到的key进行得分阈值处理，如果所有key都能匹配到，则此行为表头行
     *          如果未全部匹配到，则一个key积一分，轮询每一行数据重复进行匹配，得分最高者为表头
     *          确定好表头行后，无需读取所有列数据，仅需读取key映射数据
     *          最终根据表头行，读取表头行之后的数据为表数据，（具体数据获取完全根据索引获取）
     *          数据读取到配置空行数后或者已配置的结束标志进行结束 参考isEndRow方法
     *  todo 因数据及逻辑算法问题 方法暂时弃用
     *  todo 匹配机制有问题（模版太多，字段无法完全覆盖）
     *  todo 后期可在收集到大多数模版后进行评估使用
     *  todo ********使用时修改数据库映射，即headerAliasMap的获取*******
     * @param filePath
     * @param headerAliasMap -- key为映射字段，value为Excel表表头字段
     * @param options
     * @return -- 返回
     * @throws IOException
     */
    public static List<Map<String, String>> readRecords(String filePath, Map<String, List<String>> headerAliasMap, ReadOptions options) throws IOException {
        try (InputStream fis = new FileInputStream(filePath);
             Workbook workbook = WorkbookFactory.create(fis)) {
            return readRecords(workbook, headerAliasMap, options);
        }
    }

    /**
     * 从输入流读取数据
     * @param inputStream
     * @param headerAliasMap
     * @param options
     * @return
     * @throws IOException
     */
    public static List<Map<String, String>> readRecords(InputStream inputStream, Map<String, List<String>> headerAliasMap, ReadOptions options) throws IOException {
        Workbook workbook = WorkbookFactory.create(inputStream);
        return readRecords(workbook, headerAliasMap, options);
    }

    /**
     * 从指定工作簿/工作表读取数据
     * @param workbook
     * @param headerAliasMap
     * @param options
     * @return
     */
    public static List<Map<String, String>> readRecords(Workbook workbook, Map<String, List<String>> headerAliasMap, ReadOptions options) {
        Sheet sheet = workbook.getSheetAt(options.sheetIndex);
        HeaderDataResult detect = recognizeHeader(sheet, headerAliasMap, options);
        return readRows(sheet, detect, options);
    }

    /**
     * 自动识别表头行(阈值机制)
     * @param sheet
     * @param headerAliasMap
     * @param options
     * @return
     */
    public static HeaderDataResult recognizeHeader(Sheet sheet, Map<String, List<String>> headerAliasMap, ReadOptions options) {
        Objects.requireNonNull(sheet, "sheet 不能为空");
        int lastRow = Math.min(sheet.getLastRowNum(), options.maxScanRows);

        Map<String, List<String>> aliasMap = formatAliasMap(headerAliasMap);

        int bestRowIndex = -1;
        int bestScore = -1;
        List<String> bestheaderValueList = Collections.emptyList();
        Map<String, Integer> bestMapping = Collections.emptyMap();

        for (int r = sheet.getFirstRowNum(); r <= lastRow; r++) {
            Row row = sheet.getRow(r);
            if (ObjectUtils.isEmpty(row)){
                continue;
            }
            List<String> values = readHeaderrowValueList(sheet, r);
            if (values.stream().allMatch(StringUtils::isBlank)) continue;

            Map<String, Integer> mapping = matchHeader(values, aliasMap);
            int score = (int) mapping.keySet().stream().distinct().count();
            if (score > bestScore) {
                bestScore = score;
                bestRowIndex = r;
                bestheaderValueList = values;
                bestMapping = mapping;
                if (score == aliasMap.size()) break; // 全部命中，提前结束
            }
        }

        // 若未命中任何别名，兜底：选择第一个非空行作为表头
        if (bestRowIndex < 0) {
            for (int r = sheet.getFirstRowNum(); r <= lastRow; r++) {
                Row row = sheet.getRow(r);
                if (ObjectUtils.isEmpty(row)) {
                    continue;
                }
                List<String> values = readrowValueList(row);
                if (values.stream().anyMatch(StringUtils::isNotBlank)) {
                    bestRowIndex = r;
                    bestheaderValueList = values;
                    bestMapping = defaultSequentialMapping(values, aliasMap);
                    break;
                }
            }
        }

        HeaderDataResult result = new HeaderDataResult();
        result.headerRowIndex = Math.max(bestRowIndex, sheet.getFirstRowNum());
        result.keyToColumnIndex = bestMapping;
        result.headerValueList = bestheaderValueList;
        return result;
    }

    /**
     * 将原始表头行匹配为 key->列索引
     * @param headerValueList
     * @param aliasMap
     * @return
     */
    private static Map<String, Integer> matchHeader(List<String> headerValueList, Map<String, List<String>> aliasMap) {
        Map<String, Integer> mapping = new LinkedHashMap<>();
        List<String> formatHeader = headerValueList.stream().map(ExcelRecognizeUtils::format).collect(Collectors.toList());
        for (Map.Entry<String, List<String>> entry : aliasMap.entrySet()) {
            String key = entry.getKey();
            List<String> aliases = entry.getValue();
            int bestIdx = -1;
            for (int c = 0; c < formatHeader.size(); c++) {
                String hv = formatHeader.get(c);
                if (StringUtils.isBlank(hv)) continue;
                for (String al : aliases) {
                    if (hv.equals(al) || hv.contains(al) || al.contains(hv)) {
                        bestIdx = c;
                        break;
                    }
                }
                if (bestIdx >= 0) break;
            }
            mapping.put(key, bestIdx);
        }
        return mapping;
    }

    /**
     * 当没有别名命中时的序号映射（按出现顺序截取）
     * @param headerValueList
     * @param aliasMap
     * @return
     */
    private static Map<String, Integer> defaultSequentialMapping(List<String> headerValueList, Map<String, List<String>> aliasMap) {
        Map<String, Integer> mapping = new LinkedHashMap<>();
        int idx = 0;
        for (String key : aliasMap.keySet()) {
            if (idx < headerValueList.size()) mapping.put(key, idx++);
            else mapping.put(key, -1);
        }
        return mapping;
    }

    /**
     * 匹配表头
     * @param aliasMap
     * @return
     */
    private static Map<String, List<String>> formatAliasMap(Map<String, List<String>> aliasMap) {
        Map<String, List<String>> resp = new HashMap<>();
        aliasMap.forEach((k, v) -> {
            if(CollUtil.isEmpty(v)){
                v = Collections.emptyList();
            }else{
                // 把 key 本身作为校验字段
                v.add(k);
                v = v.stream()
                        .filter(StringUtils::isNotBlank)
                        .map(ExcelRecognizeUtils::format)
                        .distinct().collect(Collectors.toList());
            }
            resp.put(k, v);
        });
        return resp;
    }

    /**
     * 字段矫正
     *  去空白、全角/半角括号与冒号等标点、英文大小写、去单位
     * @param str
     * @return
     */
    private static String format(String str) {
        if (StringUtils.isEmpty(str)){
            return "";
        }
        String v = str.trim();
        v = v.replace("\u00A0", " ").trim();
        // 去掉常见括号/方括号中的描述
        v = v.replace("（", "(").replace("）", ")").replace("[", "(").replace("]", ")");
        v = v.replace("【", "(").replace("】", ")");
        v = v.replace(":", "").replace("：", "");
        v = v.replaceAll("\\s+", "");
        return v.toLowerCase(Locale.ROOT);
    }
}