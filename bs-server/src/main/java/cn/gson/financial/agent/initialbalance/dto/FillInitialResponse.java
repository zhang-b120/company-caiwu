package cn.gson.financial.agent.initialbalance.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * fill-initial 接口（8081）返回结构：已填充科目、未匹配科目、生成 Excel 的 base64。
 */
@Data
public class FillInitialResponse {

    private Boolean success;
    private String message;
    private Integer filled_count;
    private Integer added_count;
    /** 已填充科目列表（用于新增科目、导入期初） */
    private List<FilledSubject> filled_subjects;
    /** 未匹配科目（返回给前端展示） */
    private List<UnmatchedItem> unmatched;
    /** 生成的期初模板 Excel：base64 与文件名 */
    private ExcelFile excel_file;

    @Data
    public static class FilledSubject {
        private String code;
        private String name;
        private BigDecimal beginBalance;
        private BigDecimal debitTotal;
        private BigDecimal creditTotal;
        private String balanceDirection;
        private Boolean isNew;
        private String parentCode;
        private Integer level;
    }

    @Data
    public static class UnmatchedItem {
        private String code;
        private String name;
        private String reason;
    }

    @Data
    public static class ExcelFile {
        private String base64;
        private String filename;
    }
}
