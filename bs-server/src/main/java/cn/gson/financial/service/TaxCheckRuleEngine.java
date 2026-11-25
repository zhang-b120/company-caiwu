package cn.gson.financial.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * FN-NEW-02 税务检查规则引擎 — 9 维度。
 *
 * <p>Phase 1 实现 3 维度（D1 进项税合规 / D2 销项申报一致性 / D3 个税申报一致性），
 * 余 6 维度（D4-D9）返回占位 finding "Phase 2 待实现"。
 *
 * <p>所有规则是纯函数：input → finding。数据由 service 拼装后传入，
 * 不依赖 DB / 远程调用，便于单测。
 */
public final class TaxCheckRuleEngine {

    public static final String RISK_GREEN  = "green";
    public static final String RISK_YELLOW = "yellow";
    public static final String RISK_RED    = "red";

    /** D2/D3 差额阈值 — 申报与底账 5% 以上视为风险 */
    public static final BigDecimal MATERIAL_DIFF_RATIO = new BigDecimal("0.05");
    /** D1 进项税认证时限 — 360 天 */
    public static final long INPUT_TAX_CERTIFY_DAYS = 360L;

    private TaxCheckRuleEngine() {}

    // ═══════════════════════════════════════════════════════════════
    // 维度 1：进项税合规
    // 输入：进项发票开票日期 + 认证日期（如已认证）
    // 规则：开票日期到认证日期超 360 天为风险；未认证发票按"今日 - 开票日"算
    // ═══════════════════════════════════════════════════════════════

    public static Finding checkInputTaxCompliance(List<InputInvoice> invoices, LocalDate today) {
        Finding f = new Finding();
        f.dimension = 1;
        f.title = "进项税合规";
        if (invoices == null || invoices.isEmpty()) {
            f.riskLevel = RISK_GREEN;
            f.score = 100;
            f.detail = "本期无进项发票";
            f.evidence = List.of();
            return f;
        }
        List<Map<String, Object>> overdue = new ArrayList<>();
        for (InputInvoice inv : invoices) {
            if (inv.issueDate == null) continue;
            LocalDate end = inv.certifyDate != null ? inv.certifyDate : today;
            long days = ChronoUnit.DAYS.between(inv.issueDate, end);
            if (days > INPUT_TAX_CERTIFY_DAYS) {
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("invoiceNo", inv.invoiceNo);
                e.put("issueDate", inv.issueDate);
                e.put("certifyDate", inv.certifyDate);
                e.put("daysElapsed", days);
                overdue.add(e);
            }
        }
        f.evidence = overdue;
        if (overdue.isEmpty()) {
            f.riskLevel = RISK_GREEN;
            f.score = 100;
            f.detail = "全部 " + invoices.size() + " 张进项发票合规";
        } else if (overdue.size() < invoices.size() / 10 + 1) {
            f.riskLevel = RISK_YELLOW;
            f.score = 70;
            f.detail = overdue.size() + " 张发票超 360 天未认证";
        } else {
            f.riskLevel = RISK_RED;
            f.score = 40;
            f.detail = overdue.size() + " 张发票超 360 天未认证（比例 >10%）";
        }
        return f;
    }

    // ═══════════════════════════════════════════════════════════════
    // 维度 2：销项税申报一致性
    // 输入：当期申报增值税额 + 当期销项发票税额合计
    // 规则：abs(申报 - 销项) / 销项 > 5% 为风险
    // ═══════════════════════════════════════════════════════════════

    public static Finding checkOutputTaxConsistency(BigDecimal declaredVat, BigDecimal invoicedOutputTax) {
        Finding f = new Finding();
        f.dimension = 2;
        f.title = "销项税申报一致性";
        BigDecimal d = declaredVat == null ? BigDecimal.ZERO : declaredVat;
        BigDecimal i = invoicedOutputTax == null ? BigDecimal.ZERO : invoicedOutputTax;
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("declared", d);
        e.put("invoiced", i);
        f.evidence = List.of(e);

        if (d.signum() == 0 && i.signum() == 0) {
            f.riskLevel = RISK_GREEN;
            f.score = 100;
            f.detail = "本期无申报无开票";
            return f;
        }
        BigDecimal base = i.signum() == 0 ? d.abs() : i.abs();
        BigDecimal diff = d.subtract(i).abs();
        BigDecimal ratio = diff.divide(base, 4, RoundingMode.HALF_UP);
        e.put("diffAbs", diff);
        e.put("diffRatio", ratio);
        if (ratio.compareTo(MATERIAL_DIFF_RATIO) <= 0) {
            f.riskLevel = RISK_GREEN;
            f.score = 100;
            f.detail = "申报与销项一致（差 " + ratio.multiply(BigDecimal.valueOf(100)) + "%）";
        } else if (ratio.compareTo(BigDecimal.valueOf(0.20)) <= 0) {
            f.riskLevel = RISK_YELLOW;
            f.score = 60;
            f.detail = "申报与销项差异 " + ratio.multiply(BigDecimal.valueOf(100)) + "%（5%~20%）";
        } else {
            f.riskLevel = RISK_RED;
            f.score = 30;
            f.detail = "申报与销项差异 " + ratio.multiply(BigDecimal.valueOf(100)) + "%（>20%）";
        }
        return f;
    }

    // ═══════════════════════════════════════════════════════════════
    // 维度 3：个税申报一致性
    // 输入：当期个税申报金额 + 当期工资应发合计
    // 规则：abs(申报 - 工资) / 工资 > 5% 为风险
    // ═══════════════════════════════════════════════════════════════

    public static Finding checkPersonalTaxConsistency(BigDecimal declaredPit, BigDecimal payrollTotal) {
        Finding f = new Finding();
        f.dimension = 3;
        f.title = "个税申报一致性";
        BigDecimal d = declaredPit == null ? BigDecimal.ZERO : declaredPit;
        BigDecimal p = payrollTotal == null ? BigDecimal.ZERO : payrollTotal;
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("declaredPit", d);
        e.put("payrollTotal", p);
        f.evidence = List.of(e);

        if (d.signum() == 0 && p.signum() == 0) {
            f.riskLevel = RISK_GREEN;
            f.score = 100;
            f.detail = "本期无个税申报无工资";
            return f;
        }
        BigDecimal base = p.signum() == 0 ? d.abs() : p.abs();
        BigDecimal diff = d.subtract(p).abs();
        BigDecimal ratio = diff.divide(base, 4, RoundingMode.HALF_UP);
        e.put("diffAbs", diff);
        e.put("diffRatio", ratio);
        if (ratio.compareTo(MATERIAL_DIFF_RATIO) <= 0) {
            f.riskLevel = RISK_GREEN;
            f.score = 100;
            f.detail = "个税申报与工资一致";
        } else if (ratio.compareTo(BigDecimal.valueOf(0.20)) <= 0) {
            f.riskLevel = RISK_YELLOW;
            f.score = 60;
            f.detail = "个税申报与工资差异 " + ratio.multiply(BigDecimal.valueOf(100)) + "%（5%~20%）";
        } else {
            f.riskLevel = RISK_RED;
            f.score = 30;
            f.detail = "个税申报与工资差异 " + ratio.multiply(BigDecimal.valueOf(100)) + "%（>20%）";
        }
        return f;
    }

    // ═══════════════════════════════════════════════════════════════
    // 维度 4-9：Phase 2 占位
    // ═══════════════════════════════════════════════════════════════

    static final String[][] PHASE2_DIMS = {
            {"4", "凭证合规（借贷不平 / 摘要空）"},
            {"5", "跨期项目（未完工跨期等）"},
            {"6", "关联交易合理性"},
            {"7", "长期挂账（超 1 年）"},
            {"8", "异常零申报（连续 6 月零申报）"},
            {"9", "三大报表勾稽"}
    };

    public static List<Finding> phase2Placeholders() {
        List<Finding> list = new ArrayList<>(PHASE2_DIMS.length);
        for (String[] d : PHASE2_DIMS) {
            Finding f = new Finding();
            f.dimension = Integer.parseInt(d[0]);
            f.title = d[1];
            f.riskLevel = RISK_GREEN;
            f.score = -1;  // -1 表示未实施
            f.detail = "Phase 2 待实现";
            f.evidence = List.of();
            list.add(f);
        }
        return list;
    }

    // ═══════════════════════════════════════════════════════════════
    // 汇总
    // ═══════════════════════════════════════════════════════════════

    /**
     * 按 Phase 1 实现的 3 维度（D1-D3）算总分平均 + 风险等级。
     * Phase 2 占位不参与计分（score=-1 跳过）。
     */
    public static Summary summarize(List<Finding> findings) {
        Summary s = new Summary();
        int sum = 0;
        int count = 0;
        int worstRisk = 0; // 0=green, 1=yellow, 2=red
        for (Finding f : findings) {
            if (f.score < 0) continue;
            sum += f.score;
            count++;
            int r = switch (f.riskLevel) {
                case RISK_RED -> 2;
                case RISK_YELLOW -> 1;
                default -> 0;
            };
            if (r > worstRisk) worstRisk = r;
        }
        s.overallScore = count == 0 ? 100 : sum / count;
        s.riskLevel = switch (worstRisk) {
            case 2 -> RISK_RED;
            case 1 -> RISK_YELLOW;
            default -> RISK_GREEN;
        };
        return s;
    }

    // ═══════════════════════════════════════════════════════════════
    // DTOs
    // ═══════════════════════════════════════════════════════════════

    public static class InputInvoice {
        public String invoiceNo;
        public LocalDate issueDate;
        public LocalDate certifyDate;

        public InputInvoice() {}
        public InputInvoice(String no, LocalDate issue, LocalDate certify) {
            this.invoiceNo = no; this.issueDate = issue; this.certifyDate = certify;
        }
    }

    public static class Finding {
        public int dimension;
        public String title;
        public String riskLevel;
        public int score;
        public String detail;
        public List<Map<String, Object>> evidence;

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("dimension", dimension);
            m.put("title", title);
            m.put("riskLevel", riskLevel);
            m.put("score", score);
            m.put("detail", detail);
            m.put("evidence", evidence);
            return m;
        }
    }

    public static class Summary {
        public int overallScore;
        public String riskLevel;
    }
}
