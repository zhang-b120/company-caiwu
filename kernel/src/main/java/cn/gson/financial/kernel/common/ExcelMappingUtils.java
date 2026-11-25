package cn.gson.financial.kernel.common;

import cn.gson.financial.kernel.model.entity.ExcelMappingTemplate;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.lang.Assert;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import org.apache.commons.lang3.StringUtils;

import java.util.*;
import java.util.stream.Collectors;

/**
 * @Author zhb
 * @Date 2025-09-20
 * @Description
 *
 * ################################
 *  一、具体映射关系
 *  假设excel表头字段（以下称呼为映射字段）为A,B,C,D,E,F;判断字段(任意表头字段，以下称呼为判断字段)为A,B,C,D,E;数据库字段（以下称呼为保存字段）为S1,S2,S3,S4,
 * 映射类型 mappingType分为四种 DIRECT-直接映射, COMBINE-组合映射, CONDITIONAL-条件映射（判断取值映射字段）, FIXED-固定值映射
 * CONDITIONAL, FIXED判断比较复杂，再次分为conditions-判定条件配置
 * 具体映射关系如下所示
 * 映射条件有多种，具体分为两大类，第一类，使用映射字段的值必然存在映射字段，可能存在判断字段，（以下称呼为第一大类）。第二类，不使用映射字段的值，必然存在判断指端，无存在映射字段（以下称呼为第二大类）。
 * -----------------------
 * 第一大类细分为
 * 1.直接映射：单个字段直接取值（mappingType = DIRECT）
 * 2.组合映射：多个字段拼接取值 (mappingType =COMBINE)
 * 3.条件映射：根据条件选择映射字段 （mappingType =CONDITIONAL）
 *      情况一：判断字段与映射字段相同
 *      情况二：判断字段与映射字段不同
 * 具体解释如下
 * 解释1.只有一个映射字段(A)。无判断字段，保存字段S1是的值为映射字段的值，例：A的值赋值为S1
 * 解释2.存在多个映射字段(A,B,...)，无判断字段，保存字段S2的值为映射字段的值顺序组合，例：A的值拼接B的值赋值给S2
 * 解释3.存在多个映射字段(A,B,...)，保存字段S3根据判断字段(A,B,...)的值判断取哪个映射字段的值。
 * 	细分为两种情况 第一种情况.判断字段和映射字段相同（以下称呼为情况一）。第二种情况.判断字段和映射字段不同（以下称呼为情况二）
 * 	情况一：
 * 		①判断字段的值为文本，判断字段的值包含某文本（符合条件结束立即结束判断，不再执行其他判断字段），则取值为相同映射字段的值
 * 			例判断字段A包含str（假设值）则取值为映射字段A,即A的值赋值给S3
 * 		②判断字段的值为数值，判断字段的值对比int判断（仅会存在一种情况要么大于要么等于小于要么小于，不会同时存在大于小于对立情况，符合条件结束立即结束判断，不再执行其他判断字段），则取值为相同映射字段的值
 * 			例判断字段A大于int（假设值）则取值为映射字段A,即A的值赋值给S3
 * 	情况二：
 * 		①判断字段的值为文本，判断字段的值包含某文本，则取某映射字段的值
 * 			例C的值为x（假设值）
 * 			1)如果x包含str1（假设值）则取值为映射A,即A的值赋值给S3
 * 			2)如果x包含str2（假设值）则取值为映射B,即B的值赋值给S3
 * 		②判断字段的值为数值，判断字段的值对比某数值判断取哪个映射字段值，（仅会存在一种情况要么大于要么等于小于要么小于，不会同时存在大于小于对立情况）
 * 			例C的值为x（假设值）
 * 			1)如果x大于int1（假设值）则取值为映射A,即A的值赋值给S3
 * --------------------------
 * 第二大类细分为   （mappingType =FIXED）
 * 1.单判断字段固定值映射
 * 2.多判断字段固定值映射
 * 解释1.只有一个判断字段(A)。保存字段s4的值根据判断字段D的值进行判断取值v(v值不固定（v1,v2,v3...），需配置)
 * 	细分为两种情况
 * 		①判断字段的值为文本，例D的值(x,y,z...)，为x则s4为v1,y则s4为v2,z则为s4v3...
 * 		②判断字段的值为数值，判断字段的值对比数值int（仅会存在一种情况要么大于要么等于小于要么小于，不会同时存在大于小于对立情况）
 * 			例D的值大于int则s4为v1,等于于int则s4为v2,小于于int则s4为v3...
 *
 * 解释2.存在多个判断字段(A,B,...)，保存字段S5的值根据判断字段(A,B,...)的值进行判断（符合条件结束立即结束判断，不再执行其他判断字段）取值v(v值不固定，需配置)
 * 	细分为两种情况
 * 	①判断字段的值为文本，
 * 		例判断文本为str（假设值）,如果A值包含str，则S5取值为v1,如果B值包含str,则取值为v2
 * 	②判断字段的值为数值int（假设值），判断字段的值对比此值（对比方式存在一种情况要么大于要么等于小于要么小于）
 * 		如果A值大于int，则取值为v1,如果B值大于int,则取值为v2
 *
 *
 * ####################################
 * 二、最终条件解析使用json数据配置 代码使用实体类解析暂定如下：
 * result 根据映射方式分为excel字段值或固定值
 * [{"judgmentField":"A","judgmentType":"str","comparison":"contains","value":"str1","result":"A"}]
 * 实体类字段为 judgmentField， judgmentType，comparison，value，result
 */
public class ExcelMappingUtils {


    public static String MAPPING_TYPE_DIRECT = "DIRECT";//直接映射
    public static String MAPPING_TYPE_COMBINE = "COMBINE";//组合映射
    public static String MAPPING_TYPE_CONDITIONAL = "CONDITIONAL";//条件映射（根据条件选择映射字段）
    public static String MAPPING_TYPE_FIXED = "FIXED";//固定值映射（不使用映射字段的值）

    public static int ENABLED_YES = 1;
    public static int ENABLED_NO = 0;

    @Data
    public static class JudgmentCondition {
        //判断字段
        private String judgmentField;
        //判断类型
        private String judgmentType; // str, int
        //条件
        private String comparison; // contains, equals, gt, lt, ge, le
        //判断值
        private String value;
        //结果值或映射字段名
        private String result;

        public static JudgmentCondition defaults() {
            return new JudgmentCondition();
        }
    }

    public static JudgmentCondition getJudgmentCondition(){
        return JudgmentCondition.defaults();
    }

    /**
     * 执行字段映射
     * @param excelData
     * @param templateList
     * @return
     */
    public static List<Map<String, Object>> executeMapping(List<Map<String, String>> excelData, List<ExcelMappingTemplate> templateList)  {
        return excelData.stream()
                .map(row -> templateList.stream()
                        .collect(Collectors.toMap(
                                ExcelMappingTemplate::getMappingField,
                                 mapping -> (Object)getMappedValue(row, mapping)
                                //(existing, replacement) -> existing,
                                //HashMap::new
                        )))
                .collect(Collectors.toList());
    }

    /**
     * 根据映射配置获取字段值
     * @param rowData
     * @param mapping
     * @return
     */
    private static String getMappedValue(Map<String, String> rowData, ExcelMappingTemplate mapping) {
        switch (mapping.getMappingType()) {
            case "DIRECT":
                return getDirectValue(rowData, mapping);
            case "COMBINE":
                return getCombineValue(rowData, mapping);
            case "CONDITIONAL":
                return getConditionalValue(rowData, mapping);
            case "FIXED":
                return getFixedValue(rowData, mapping);
            default:
                return mapping.getDefaultValue();
        }
    }

    /**
     * 直接映射取值
     * @param rowData
     * @param mapping
     * @return
     */
    public static String getDirectValue(Map<String, String> rowData, ExcelMappingTemplate mapping)  {
        List<String> fieldList = null;
        try {
            fieldList = parseJsonArray(mapping.getExcelFields(), String.class);
        } catch (Exception e) {
            Assert.isTrue(Boolean.FALSE, "ExcelFiles或judgmentFields数据解析异常" + e);
        }
        if (CollUtil.isEmpty(fieldList)) {
            return mapping.getDefaultValue();
        }
        return rowData.getOrDefault(fieldList.get(0), mapping.getDefaultValue());
    }

    /**
     * 组合映射取值
     * @param rowData
     * @param mapping
     * @return
     */
    private static String getCombineValue(Map<String, String> rowData, ExcelMappingTemplate mapping)  {
        List<String> fieldList = null;
        try {
            fieldList = parseJsonArray(mapping.getExcelFields(), String.class);
        } catch (Exception e) {
            Assert.isTrue(Boolean.FALSE, "ExcelFiles或judgmentFields数据解析异常" + e);
        }
        if (CollUtil.isEmpty(fieldList)) {
            return mapping.getDefaultValue();
        }
        String combinedValue = fieldList.stream()
                .map(rowData::get)
                .filter(StringUtils::isNotEmpty)
                .collect(Collectors.joining("-"));
        return StringUtils.isNotEmpty(combinedValue) ? combinedValue : mapping.getDefaultValue();
    }

    /**
     * 条件映射取值（根据条件选择映射字段）
     * @param rowData
     * @param mapping
     * @return
     */
    private static String getConditionalValue(Map<String, String> rowData, ExcelMappingTemplate mapping) {
        List<JudgmentCondition> conditionList = null;
        List<String> judgmentFieldList = null;
        try {
            conditionList = parseJsonArray(mapping.getConditions(), JudgmentCondition.class);
            judgmentFieldList = parseJsonArray(mapping.getJudgmentFields(), String.class);
        } catch (Exception e) {
            Assert.isTrue(Boolean.FALSE, "JsonArray数据解析异常" + e);
        }
        if (CollUtil.isEmpty(conditionList) || CollUtil.isEmpty(judgmentFieldList)) {
            return mapping.getDefaultValue();
        }
        List<JudgmentCondition> finalConditionList = conditionList;
        return judgmentFieldList.stream()
                // 检查该判断字段的所有条件
                .map(judgmentField -> {
                    String judgmentValue = rowData.get(judgmentField);
                    if (judgmentValue == null) {
                        return Optional.<String>empty();
                    }
                    return finalConditionList.stream()
                            //只检查当前判断字段的条件
                            .filter(condition -> judgmentField.equals(condition.getJudgmentField()))
                            .filter(condition -> isConditionMet(judgmentValue, condition))
                            .findFirst()
                            .map(condition -> rowData.get(condition.getResult()));
                })
                .filter(Optional::isPresent)
                .map(Optional::get)
                .findFirst()
                .orElse(mapping.getDefaultValue());
    }

    /**
     * 固定值映射取值（不使用映射字段的值）
     * @param rowData
     * @param mapping
     * @return
     */
    private static String getFixedValue(Map<String, String> rowData, ExcelMappingTemplate mapping) {
        List<JudgmentCondition> conditionList = null;
        List<String> judgmentFieldList = null;
        try {
            conditionList = parseJsonArray(mapping.getConditions(), JudgmentCondition.class);
            judgmentFieldList = parseJsonArray(mapping.getJudgmentFields(), String.class);
        } catch (Exception e) {
            Assert.isTrue(Boolean.FALSE, "JsonArray数据解析异常" + e);
        }
        if (CollUtil.isEmpty(conditionList) || CollUtil.isEmpty(judgmentFieldList)) {
            return mapping.getDefaultValue();
        }
        List<JudgmentCondition> finalConditionList = conditionList;
        return judgmentFieldList.stream()
                // 检查该判断字段的所有条件
                .map(judgmentField -> {
                    String judgmentValue = rowData.get(judgmentField);
                    if (judgmentValue == null){
                        return Optional.<String>empty();
                    }
                    return finalConditionList.stream()
                            // 只检查当前判断字段的条件
                            .filter(condition -> judgmentField.equals(condition.getJudgmentField()))
                            .filter(condition -> isConditionMet(judgmentValue, condition))
                            .findFirst()
                            .map(JudgmentCondition::getResult);
                })
                .filter(Optional::isPresent)
                .map(Optional::get)
                .findFirst()
                .orElse(mapping.getDefaultValue());
    }

    /**
     * 判断条件是否满足
     */
    private static Boolean isConditionMet(String actualValue, JudgmentCondition condition) {
        if ("str".equals(condition.getJudgmentType())) {
            return handleStringComparison(cleanText(actualValue), condition.getComparison(), condition.getValue());
        } else if ("int".equals(condition.getJudgmentType())) {
            return handleIntegerComparison(cleanNumber(actualValue), condition.getComparison(), condition.getValue());
        }
        return Boolean.FALSE;
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
     * 清洗数值数据
     */
    private static String cleanNumber(String str) {
        if (str == null) {
            return null;
        }
        // 去除逗号,去除货币符号等特殊字符
        str = str.trim().replace(",", "").replaceAll("[￥¥$€£]", "");
        return str;
    }

    /**
     * 字符串比较逻辑
     */
    private static Boolean handleStringComparison(String actual, String comparison, String expected) {
        if(StringUtils.isEmpty(actual)){
            return Boolean.FALSE;
        }
        switch (comparison) {
            case "contains":
                return actual.contains(expected);
            case "equals":
                return actual.equals(expected);
            default:
                return Boolean.FALSE;
        }
    }

    /**
     * 整数比较逻辑
     */
    private static Boolean handleIntegerComparison(String actualStr, String comparison, String expectedStr) {
        if(StringUtils.isEmpty(actualStr)){
            return Boolean.FALSE;
        }
        try {
            Double actual = Double.parseDouble(actualStr);
            Double expected = Double.parseDouble(expectedStr);
            switch (comparison) {
                case "gt":
                    return actual > expected;
                case "ge":
                    return actual >= expected;
                case "eq":
                    return actual == expected;
                case "le":
                    return actual <= expected;
                case "lt":
                    return actual < expected;
                default:
                    return Boolean.FALSE;
            }
        }catch (Exception e){
            return Boolean.FALSE;
        }
    }

    /**
     * 解析JSON数组
     */
    private static <T> List<T> parseJsonArray(String parseData, Class<T> clazz) throws Exception{
        if (StringUtils.isEmpty(parseData)) {
            return Collections.emptyList();
        }

        ObjectMapper mapper = new ObjectMapper();
        return mapper.readValue(parseData, mapper.getTypeFactory().constructCollectionType(List.class, clazz));
    }
}