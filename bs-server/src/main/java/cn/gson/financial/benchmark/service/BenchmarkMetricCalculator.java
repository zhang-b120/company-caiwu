package cn.gson.financial.benchmark.service;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工单 121 · BENCH-BUILD · 财务指标计算器（<b>119 同口径，数值版</b>）。
 * <p>
 * finmaster {@code HealthReportGateway.computeMetricsFromBalances}（119）产出的是<b>展示字符串</b>
 * （带 %/倍/"不适用"），无法做百分位聚合。本类按<b>同一套公式</b>从单户科目余额算出
 * <b>原始 BigDecimal</b>，供 {@link BenchmarkBuildService} 跨账套聚合分位数。
 * <p>
 * 单位约定（与 119 数值口径一致，便于 BENCH-READ 对比 value vs p50）：
 * <ul>
 *   <li>百分比类（debtRatio/grossMargin/netMargin/roe/costExpenseProfitRate/vatBurdenRate/inputOutputRatio）→ 存百分数（x/y*100）</li>
 *   <li>倍数类（currentRatio/quickRatio/cashRatio）+ 周转类（*Turnover）→ 存原始比值（x/y）</li>
 * </ul>
 * 「不适用」（0 除 / 缺科目 / 无营收 等）→ 该指标<b>不计入 Map</b>（不污染样本）。
 * <p>
 * ⚠️ 与 119 为<b>两份同口径实现</b>，调整公式时两边须同步（见 121 工单已知风险）。
 */
@Component
public class BenchmarkMetricCalculator {

    /** 单户一行科目余额（仅取计算所需字段，避免依赖具体实体类） */
    public static class BalanceRow {
        public final String code;
        public final BigDecimal begin;
        public final BigDecimal end;
        public final BigDecimal ytdDebit;
        public final BigDecimal ytdCredit;

        public BalanceRow(String code, BigDecimal begin, BigDecimal end, BigDecimal ytdDebit, BigDecimal ytdCredit) {
            this.code = code;
            this.begin = nz(begin);
            this.end = nz(end);
            this.ytdDebit = nz(ytdDebit);
            this.ytdCredit = nz(ytdCredit);
        }
    }

    /**
     * 算单户全部可得指标（119 同口径，数值）。返回 metricKey → 数值；不可得的指标不入 Map。
     */
    public Map<String, BigDecimal> compute(List<BalanceRow> rows) {
        Map<String, BigDecimal> m = new LinkedHashMap<>();
        if (rows == null || rows.isEmpty()) return m;

        BigDecimal assetEnd = BigDecimal.ZERO, assetBegin = BigDecimal.ZERO;
        BigDecimal liabEnd = BigDecimal.ZERO;
        BigDecimal curAssetEnd = BigDecimal.ZERO, curLiabEnd = BigDecimal.ZERO;
        BigDecimal cashEnd = BigDecimal.ZERO;
        BigDecimal invEnd = BigDecimal.ZERO, invBegin = BigDecimal.ZERO;
        BigDecimal arEnd = BigDecimal.ZERO, arBegin = BigDecimal.ZERO;
        BigDecimal revenue5 = null, revenue6 = null, cost5 = null, cost6 = null;
        BigDecimal taxSur5 = null, taxSur6 = null;
        BigDecimal sell5 = null, sell6 = null, admin5 = null, admin6 = null, fin5 = null, fin6 = null;
        BigDecimal smallScaleVat = null, inputTax = null, outputTax = null;
        boolean hasSmallScaleVat = false, hasGeneralVat = false;

        for (BalanceRow r : rows) {
            String code = r.code;
            if (code == null) continue;
            if (code.length() == 4 && code.startsWith("1")) {
                assetEnd = assetEnd.add(r.end);
                assetBegin = assetBegin.add(r.begin);
                int l1 = level1(code);
                if (l1 > 0 && l1 < 1500) curAssetEnd = curAssetEnd.add(r.end);
            }
            if (code.length() == 4 && code.startsWith("2")) {
                liabEnd = liabEnd.add(r.end);
                int l1 = level1(code);
                if (l1 > 0 && l1 < 2500) curLiabEnd = curLiabEnd.add(r.end);
            }
            if ("1001".equals(code) || "1002".equals(code) || "1012".equals(code)) cashEnd = cashEnd.add(r.end);
            if (code.length() == 4 && code.startsWith("14")) { invEnd = invEnd.add(r.end); invBegin = invBegin.add(r.begin); }
            if ("1122".equals(code)) { arEnd = arEnd.add(r.end); arBegin = arBegin.add(r.begin); }
            switch (code) {
                case "5001": revenue5 = r.ytdCredit; break;
                case "6001": revenue6 = r.ytdCredit; break;
                case "5401": cost5 = r.ytdDebit; break;
                case "6401": cost6 = r.ytdDebit; break;
                case "5403": taxSur5 = r.ytdDebit; break;
                case "6403": taxSur6 = r.ytdDebit; break;
                case "5601": sell5 = r.ytdDebit; break;
                case "6601": sell6 = r.ytdDebit; break;
                case "5602": admin5 = r.ytdDebit; break;
                case "6602": admin6 = r.ytdDebit; break;
                case "5603": fin5 = r.ytdDebit; break;
                case "6603": fin6 = r.ytdDebit; break;
                default: break;
            }
            if ("2221026".equals(code)) { smallScaleVat = r.ytdCredit; hasSmallScaleVat = true; }
            if ("222100101".equals(code)) { inputTax = r.ytdDebit; hasGeneralVat = true; }
            if ("222100105".equals(code)) { outputTax = r.ytdCredit; hasGeneralVat = true; }
        }

        BigDecimal revenue = revenue5 != null ? revenue5 : revenue6;
        BigDecimal cost = cost5 != null ? cost5 : cost6;
        BigDecimal taxSur = taxSur5 != null ? taxSur5 : taxSur6;
        BigDecimal sellExp = sell5 != null ? sell5 : sell6;
        BigDecimal adminExp = admin5 != null ? admin5 : admin6;
        BigDecimal finExp = fin5 != null ? fin5 : fin6;
        boolean revOk = revenue != null && revenue.signum() > 0;
        BigDecimal equityEnd = assetEnd.subtract(liabEnd);
        BigDecimal periodExp = nz(sellExp).add(nz(adminExp)).add(nz(finExp));
        BigDecimal netProfit = revOk ? revenue.subtract(nz(cost)).subtract(nz(taxSur)).subtract(periodExp) : null;

        // ── 偿债能力 ──
        if (assetEnd.signum() > 0) put(m, "debtRatio", pct(liabEnd, assetEnd));
        if (curLiabEnd.signum() > 0) {
            put(m, "currentRatio", ratio(curAssetEnd, curLiabEnd));
            put(m, "quickRatio", ratio(curAssetEnd.subtract(invEnd), curLiabEnd));
            put(m, "cashRatio", ratio(cashEnd, curLiabEnd));
        }
        // ── 盈利能力 ──
        if (revOk) {
            put(m, "grossMargin", pct(revenue.subtract(nz(cost)), revenue));
            put(m, "netMargin", pct(netProfit, revenue));
        }
        if (netProfit != null && equityEnd.signum() > 0) put(m, "roe", pct(netProfit, equityEnd));
        BigDecimal costExpBase = nz(cost).add(nz(taxSur)).add(periodExp);
        if (netProfit != null && costExpBase.signum() > 0) put(m, "costExpenseProfitRate", pct(netProfit, costExpBase));
        // ── 营运能力（平均 =(期初+期末)/2）──
        BigDecimal avgAsset = assetBegin.add(assetEnd).divide(TWO, 2, RoundingMode.HALF_UP);
        BigDecimal avgAr = arBegin.add(arEnd).divide(TWO, 2, RoundingMode.HALF_UP);
        BigDecimal avgInv = invBegin.add(invEnd).divide(TWO, 2, RoundingMode.HALF_UP);
        if (revOk && avgAsset.signum() > 0) put(m, "totalAssetTurnover", ratio(revenue, avgAsset));
        if (revOk && avgAr.signum() > 0) put(m, "receivableTurnover", ratio(revenue, avgAr));
        if (cost != null && cost.signum() > 0 && avgInv.signum() > 0) put(m, "inventoryTurnover", ratio(cost, avgInv));
        // ── 增值税税负 + 进销项比 ──
        BigDecimal vat = null;
        boolean creditCarryover = false;
        if (hasGeneralVat) {
            vat = nz(outputTax).subtract(nz(inputTax));
            if (vat.signum() < 0) creditCarryover = true;
        } else if (hasSmallScaleVat) {
            vat = smallScaleVat;
        }
        if (!creditCarryover && vat != null && revOk) put(m, "vatBurdenRate", pct(vat, revenue));
        if (hasGeneralVat && outputTax != null && outputTax.signum() > 0) put(m, "inputOutputRatio", pct(nz(inputTax), outputTax));

        return m;
    }

    /** 年营业收入（用于派生规模档）；无营收返 null */
    public BigDecimal annualRevenue(List<BalanceRow> rows) {
        if (rows == null) return null;
        BigDecimal rev5 = null, rev6 = null;
        for (BalanceRow r : rows) {
            if ("5001".equals(r.code)) rev5 = r.ytdCredit;
            if ("6001".equals(r.code)) rev6 = r.ytdCredit;
        }
        return rev5 != null ? rev5 : rev6;
    }

    // ───────── 辅助 ─────────
    private static final BigDecimal TWO = BigDecimal.valueOf(2);
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private static void put(Map<String, BigDecimal> m, String k, BigDecimal v) {
        if (v != null) m.put(k, v);
    }

    /** 百分比 = a/b*100，6 位小数 */
    private static BigDecimal pct(BigDecimal a, BigDecimal b) {
        if (a == null || b == null || b.signum() == 0) return null;
        return a.multiply(HUNDRED).divide(b, 6, RoundingMode.HALF_UP);
    }

    /** 比值 = a/b，6 位小数 */
    private static BigDecimal ratio(BigDecimal a, BigDecimal b) {
        if (a == null || b == null || b.signum() == 0) return null;
        return a.divide(b, 6, RoundingMode.HALF_UP);
    }

    /** 一级科目码数值（4 位码 parse；非数字返 -1，不计入流动项） */
    private static int level1(String code) {
        try {
            return Integer.parseInt(code);
        } catch (Exception e) {
            return -1;
        }
    }

    private static BigDecimal nz(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }
}
