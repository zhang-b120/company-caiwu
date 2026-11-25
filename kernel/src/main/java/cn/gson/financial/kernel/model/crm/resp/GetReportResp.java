package cn.gson.financial.kernel.model.crm.resp;

import cn.gson.financial.kernel.model.dto.ReportItemsDto;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;

/**
 * RPT-FN-01：CRM 调 financial 取财务报表 + 科目余额 出参。
 * <p>
 * 4 块（86 §D4）：
 * <ul>
 *   <li>{@link #zcfz} 资产负债表（templateKey=zcfz）</li>
 *   <li>{@link #lr} 利润表（templateKey=lr）</li>
 *   <li>{@link #xjll} 现金流量表（templateKey=xjll）</li>
 *   <li>{@link #subjectBalances} 科目余额表（按账套+年月+本位币，aux=0 行）</li>
 * </ul>
 * <p>
 * 三表均复用 {@code ReportService.reportData(...)} 真实计算，含金额；
 * 科目余额按 {@code OpenApiBalanceController.fetchDetailedRows} 同款规则查 {@code qBalance}。
 * <p>
 * ⚠️ R6 高新评估的"高新收入占比"非标准报表项，本接口给不了；
 * CRM 端需另出来源（见 86 派工说明）。
 */
@Data
public class GetReportResp implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long accountSetsId;

    private String period;

    /** 资产负债表（templateKey=zcfz）数据行；若该账套未配置该模板返空列表 */
    private List<ReportItemsDto> zcfz;

    /** 利润表（templateKey=lr）数据行；若该账套未配置该模板返空列表 */
    private List<ReportItemsDto> lr;

    /** 现金流量表（templateKey=xjll）数据行；若该账套未配置该模板返空列表 */
    private List<ReportItemsDto> xjll;

    /** 科目余额表（aux=0 + 本位币 + 本期，按 subjectCode 升序）；无数据返空列表 */
    private List<SubjectBalanceRow> subjectBalances;

    /**
     * 缺数原因清单（RPT-FN-01-FIX）。
     * <p>
     * **空 / null 表示四块均正常取到数（或财务真为 0）**；非空表示某块取不到数，
     * 调用方据此区分「取数失败」与「财务真为 0」，不要再把空数组当成"财务为 0"。
     * <p>
     * 每条 {@link DataIssue#code} 取值见 {@link IssueCode}。
     */
    private List<DataIssue> dataIssues;

    /**
     * 缺数原因码（RPT-FN-01-FIX，4 类 + 兜底）。
     */
    public static final class IssueCode {
        /** 账套不存在 */
        public static final String ACCOUNT_SETS_NOT_FOUND = "ACCOUNT_SETS_NOT_FOUND";
        /** period 格式非法（应为 YYYY-MM） */
        public static final String INVALID_PERIOD = "INVALID_PERIOD";
        /** 账套未设置本位币 */
        public static final String LOCAL_CURRENCY_NOT_SET = "LOCAL_CURRENCY_NOT_SET";
        /** 报表模板缺失 / 无数据项（该账套未初始化该报表模板） */
        public static final String REPORT_TEMPLATE_MISSING = "REPORT_TEMPLATE_MISSING";
        /** 报表公式引用的科目在本账套不存在（ReportService Assert 抛） */
        public static final String FORMULA_SUBJECT_MISSING = "FORMULA_SUBJECT_MISSING";
        /** 报表计算异常（其他未归类） */
        public static final String REPORT_CALC_ERROR = "REPORT_CALC_ERROR";
        /** 该期余额表无任何行 —— 疑未结账 / 该期无数据 */
        public static final String NO_BALANCE_MAYBE_NOT_CHECKED_OUT = "NO_BALANCE_MAYBE_NOT_CHECKED_OUT";
        /** 该期有余额行，但本位币 / aux=0 口径无匹配（落库币种或辅助核算口径不一致） */
        public static final String BALANCE_CURRENCY_OR_AUX_NO_MATCH = "BALANCE_CURRENCY_OR_AUX_NO_MATCH";

        private IssueCode() {}
    }

    /**
     * 单块缺数原因（RPT-FN-01-FIX）。
     */
    @Data
    public static class DataIssue implements Serializable {
        private static final long serialVersionUID = 1L;

        /** 受影响块：zcfz / lr / xjll / subjectBalances / overall */
        private String block;
        /** 原因码，见 {@link IssueCode} */
        private String code;
        /** 人类可读的具体原因（含探测到的数值，便于直接定位） */
        private String message;

        public DataIssue() {}

        public DataIssue(String block, String code, String message) {
            this.block = block;
            this.code = code;
            this.message = message;
        }
    }

    /**
     * 科目余额行（与 {@code OpenApiBalanceController.fetchDetailedRows} 同形）
     */
    @Data
    public static class SubjectBalanceRow implements Serializable {
        private static final long serialVersionUID = 1L;

        private Long subjectId;
        /** 科目编码（如 1001、5001 等） */
        private String subjectCode;
        /** 科目名称 */
        private String subjectName;
        /** 余额方向（借/贷） */
        private String balanceDirection;
        /** 期初余额 */
        private BigDecimal beginBalance;
        /** 本期借方发生 */
        private BigDecimal currentMonthDebit;
        /** 本期贷方发生 */
        private BigDecimal currentMonthCredit;
        /** 期末余额 */
        private BigDecimal endBalance;
        /** 本年累计借方 */
        private BigDecimal yearTotalDebit;
        /** 本年累计贷方 */
        private BigDecimal yearTotalCredit;
    }
}
