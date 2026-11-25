package cn.gson.financial.agent.initialbalance.dto;

import lombok.Data;
import java.math.BigDecimal;
import java.util.List;

/**
 * 期初数据导入响应 DTO
 * <p>
 * 工作流 /import 接收参数（multipart/form-data）：
 * <ul>
 *   <li>balance_file          文件  是  科目余额表 Excel 文件</li>
 *   <li>default_accounts_file  文件  是  系统科目 Excel 文件</li>
 *   <li>period                 表单  否  会计期间，默认 "2025-01"</li>
 *   <li>code_rule              表单  否  编码规则，如 4-3-3-2 或 [4,3,3,2]</li>
 * </ul>
 */
@Data
public class InitialBalanceImportResponse {

    /**
     * 是否成功
     */
    private Boolean success;

    /**
     * 响应数据
     */
    private ResponseData data;

    /**
     * 响应消息
     */
    private String message;

    /**
     * 错误详情（错误时返回）
     */
    private String detail;

    /**
     * 响应数据内部类
     */
    @Data
    public static class ResponseData {
        /**
         * 新科目列表
         */
        private List<NewSubject> newSubjects;

        /**
         * 期初余额列表
         */
        private List<InitialBalance> initialBalances;

        /**
         * 会计期间（如 2026-01-01）
         */
        private String period;

        /**
         * 模糊匹配说明：科目余额表中某行（编码/名称）在系统中有多个同名科目，无法唯一确定时，
         * 工作流会暂时匹配到其中一个，并在此列出「余额表编码/名称、当前匹配到的系统编码、其他可选系统编码」供人工核对。
         */
        private List<AmbiguousMatch> ambiguousMatches;

        /**
         * 一级科目汇总后的期初余额（可选）。
         * 例如余额表只有一级科目，而系统只能在末级记账时，工作流会给出一级科目的汇总数，
         * 并在 message 中提示“未自动分配，请手工分配到末级科目”。
         */
        private List<Level1OnlyBalance> level1OnlyBalances;

        /**
         * 自动匹配结果（可选）。
         * 记录「余额表科目」与「系统科目」的无歧义匹配关系。
         */
        private List<AutoMapping> autoMappings;
    }

    /**
     * 模糊匹配项：余额表某科目在系统中有多个同名科目时的说明
     */
    @Data
    public static class AmbiguousMatch {
        /** 余额表中的科目编码 */
        private String balanceCode;
        /** 余额表中的科目名称（如「应付账款」） */
        private String balanceName;
        /** 当前暂时匹配到的系统科目编码 */
        private String matchedSystemCode;
        /** 其他可选的系统科目编码列表 */
        private List<String> alternativeSystemCodes;
        /** 说明文案（如「请人工核对」） */
        private String message;
    }

    /**
     * 一级科目期初汇总数据（仅一级科目，一般用于展示/提示，不直接写入期初表）
     */
    @Data
    public static class Level1OnlyBalance {
        /** 一级科目编码（如 2211） */
        private String code;
        /** 一级科目名称（如 应付职工薪酬） */
        private String name;
        /** 汇总后的期初余额 */
        private BigDecimal beginBalance;
        /** 汇总后的本年累计借方 */
        private BigDecimal yearTotalDebit;
        /** 汇总后的本年累计贷方 */
        private BigDecimal yearTotalCredit;
        /** 提示信息（如“余额表只有一级科目，未自动分配，请手工分配到末级科目”） */
        private String message;
    }

    /**
     * 自动匹配结果：余额表科目与系统科目的“无歧义”匹配关系
     */
    @Data
    public static class AutoMapping {
        /** 余额表中的科目编码（fromCode） */
        private String fromCode;
        /** 余额表中的科目名称（fromName） */
        private String fromName;
        /** 匹配到的系统科目编码（toCode） */
        private String toCode;
        /** 匹配到的系统科目名称（toName） */
        private String toName;
        /** 匹配原因/说明（reason） */
        private String reason;
    }
    
    /**
     * 新科目数据
     */
    @Data
    public static class NewSubject {
        /**
         * 科目编码（符合编码规则）
         */
        private String code;
        
        /**
         * 科目名称
         */
        private String name;
        
        /**
         * 科目类别（资产/负债/权益等）
         */
        private String type;
        
        /**
         * 余额方向（借/贷）
         */
        private String balanceDirection;
        
        /**
         * 原始编码（用于追踪）
         */
        private String originalCode;
        
        /**
         * 上级科目编码（可选）
         */
        private String parentCode;
        
        /**
         * 数量核算单位（可选）
         */
        private String unit;
        
        /**
         * 辅助核算（可选）
         */
        private String auxiliaryAccounting;
        
        /**
         * 外币核算（可选）
         */
        private String currencyAccounting;
    }
    
    /**
     * 期初余额数据
     */
    @Data
    public static class InitialBalance {
        /**
         * 科目编码
         */
        private String code;

        /**
         * 工作流返回的余额方向（借/贷），与系统科目方向不一致时需录相反数
         */
        private String balanceDirection;
        
        /**
         * 期初余额
         */
        private BigDecimal beginBalance;
        
        /**
         * 本年累计借方
         */
        private BigDecimal yearTotalDebit;
        
        /**
         * 本年累计贷方
         */
        private BigDecimal yearTotalCredit;
        
        /**
         * 年初余额（可选）
         */
        private BigDecimal yearTotal;
        
        /**
         * 期初数量（可选）
         */
        private BigDecimal beginQuantity;
        
        /**
         * 辅助核算编码（可选）
         */
        private String auxiliaryCode;
        
        /**
         * 辅助核算名称（可选）
         */
        private String auxiliaryName;
    }
}
