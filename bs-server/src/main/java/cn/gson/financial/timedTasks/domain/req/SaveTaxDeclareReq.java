package cn.gson.financial.timedTasks.domain.req;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 应申报税费种保存请求（与批量任务里 saveTaxItem 风格一致）
 * tableData 每行 Map 的 key 支持英文：taxType、taxItem、taxSubitem、taxPeriod、declarePeriod、validFrom、validTo、mainAddFlag；
 * 或中文：税（费）种、税目、子目、纳税（费）期限、申报期限、认定有效期起、认定有效期止、主附税标志
 */
@Data
public class SaveTaxDeclareReq {
    private Long accountSetsId;
    private List<Map<String, String>> tableData;
    private String flag;
}
