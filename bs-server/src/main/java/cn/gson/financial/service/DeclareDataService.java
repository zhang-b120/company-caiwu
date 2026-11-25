package cn.gson.financial.service;

import cn.gson.financial.kernel.model.entity.QInvoice;
import cn.gson.financial.kernel.model.enums.InvoiceState;
import cn.gson.financial.kernel.model.enums.InvoiceType;
import cn.gson.financial.kernel.model.hibernate.BlazeJPAQueryFactory;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;

/**
 * FN-RPA-02 申报数据接口 — RPA 脚本从 financial 拉数据填申报表用。
 *
 * <p>支持 4 种 type：vat（增值税）/ iit（个税）/ surcharge（附加税）/ annual（年报）。
 *
 * <p>所有数据来自已有的 invoice / salary / declare_task 表 — 不引入新表。
 * mock 阶段 annual 类型给固定结构，待 Phase 2 接真实利润表。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeclareDataService {

    public static final Set<String> ALLOWED_TYPES = Set.of("vat", "iit", "surcharge", "annual");

    @Resource
    @Lazy
    private BlazeJPAQueryFactory bqf;

    private final JdbcTemplate jdbcTemplate;

    public Map<String, Object> getData(Long accountSetsId, String type, String period) {
        if (accountSetsId == null) throw new IllegalArgumentException("accountSetsId 不能为空");
        if (type == null || type.isBlank()) throw new IllegalArgumentException("type 不能为空");
        String t = type.trim().toLowerCase();
        if (!ALLOWED_TYPES.contains(t)) {
            throw new IllegalArgumentException("type 非法：" + type + "（合法值：" + ALLOWED_TYPES + "）");
        }
        if (period == null || period.isBlank()) throw new IllegalArgumentException("period 不能为空");
        YearMonth ym;
        try {
            ym = YearMonth.parse(period);
        } catch (Exception e) {
            throw new IllegalArgumentException("period 格式非法（应为 yyyy-MM）：" + period);
        }

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("accountSetsId", accountSetsId);
        root.put("period", period);
        root.put("type", t);
        root.put("data", switch (t) {
            case "vat" -> buildVatData(accountSetsId, ym);
            case "iit" -> buildIitData(accountSetsId, ym);
            case "surcharge" -> buildSurchargeData(accountSetsId, ym);
            case "annual" -> buildAnnualData(accountSetsId, ym);
            default -> Map.of();
        });
        return root;
    }

    // ═══════════════════════════════════════════════════════════════
    // VAT（增值税）— 从 invoice 表统计销项 / 进项金额 + 税额
    // ═══════════════════════════════════════════════════════════════

    public Map<String, Object> buildVatData(Long accountSetsId, YearMonth ym) {
        LocalDate start = ym.atDay(1);
        LocalDate end = ym.atEndOfMonth();
        QInvoice q = QInvoice.invoice;

        BigDecimal salesAmount = sumInvoiceAmount(q, accountSetsId, InvoiceType.OUT, start, end, false);
        BigDecimal salesTax = sumInvoiceAmount(q, accountSetsId, InvoiceType.OUT, start, end, true);
        BigDecimal purchaseAmount = sumInvoiceAmount(q, accountSetsId, InvoiceType.IN, start, end, false);
        BigDecimal purchaseTax = sumInvoiceAmount(q, accountSetsId, InvoiceType.IN, start, end, true);
        BigDecimal payableVat = salesTax.subtract(purchaseTax);
        if (payableVat.signum() < 0) payableVat = BigDecimal.ZERO;  // 留底

        Map<String, Object> d = new LinkedHashMap<>();
        d.put("salesAmount", salesAmount);
        d.put("salesTax", salesTax);
        d.put("purchaseAmount", purchaseAmount);
        d.put("purchaseTax", purchaseTax);
        d.put("payableVat", payableVat);
        // 小规模 / 一般纳税人 RPA 端按 taxpayerType 选模板，这里不区分
        d.put("dataSource", "INVOICE_TABLE");
        return d;
    }

    // ═══════════════════════════════════════════════════════════════
    // FN-API-GAPS ④ 预申报真数（VAT 预检）：INV(开票) + ACC(做账/余额) + PREV(上期) 三方
    //   对齐 BT vat-precheck.ts 的 VatPrecheckInput（INV/ACC/PREV），供规则引擎三方对平。
    //   ⚠️ 契约由 FN 提议，字段名 BT 确认后同步（工单 §4）。
    // ═══════════════════════════════════════════════════════════════

    /**
     * VAT 预检三方数据（按 taxpayerType 分支）。FN-BT-DECLDATA-VAT-ALIGN。
     * <p>{@code small_scale} → BT {@code VatPrecheckInput} 口径（{@code inv} 按征收率/票种/免税拆分含税额 +
     * {@code prev.last4qSalesExcl}）；其它 → 既有 INV/ACC/PREV（一般口径）。taxpayerType 为空时按账套 vatType 派生。
     */
    public Map<String, Object> buildVatPrecheck(Long accountSetsId, YearMonth ym, String taxpayerType) {
        String tp = taxpayerType;
        if (tp == null || tp.isBlank()) tp = deriveTaxpayerType(accountSetsId);
        if ("small_scale".equalsIgnoreCase(tp)) {
            return buildSmallScaleVatPrecheck(accountSetsId, ym);
        }
        return buildVatPrecheck(accountSetsId, ym);
    }

    /** 按账套 vatType 派生 small_scale / general。查不到默认 small_scale（小微多数为小规模，且预检需求集中于此）。 */
    String deriveTaxpayerType(Long accountSetsId) {
        try {
            cn.gson.financial.kernel.model.entity.QAccountSets qa = cn.gson.financial.kernel.model.entity.QAccountSets.accountSets;
            cn.gson.financial.kernel.model.enums.VatType vt = bqf.selectFrom(qa)
                    .select(qa.vatType).where(qa.id.eq(accountSetsId)).fetchFirst();
            return vt == cn.gson.financial.kernel.model.enums.VatType.COMMONLY ? "general" : "small_scale";
        } catch (Exception e) {
            log.warn("[FN-BT-DECLDATA-VAT-ALIGN] 派生 taxpayerType 失败 acc={}，默认 small_scale", accountSetsId, e);
            return "small_scale";
        }
    }

    /**
     * 小规模 VAT 预检：{@code inv} 六字段（征收率/票种/免税拆分的<b>含税</b>额，季度口径）+ {@code acc.revenue} +
     * {@code prev.last4qSalesExcl}（近 4 季不含税销售额数组）。字段名对齐 BT {@code VatPrecheckInput}。
     */
    public Map<String, Object> buildSmallScaleVatPrecheck(Long accountSetsId, YearMonth ym) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("accountSetsId", accountSetsId);
        root.put("period", ym.toString());
        root.put("taxType", "vat");
        root.put("taxpayerType", "small_scale");
        root.put("inv", buildSmallScaleInv(accountSetsId, ym));
        // ACC 仅 revenue（BT 小规模 acc 口径），复用总账收入
        Map<String, Object> acc = new LinkedHashMap<>();
        acc.put("revenue", buildAccData(accountSetsId, ym).get("revenue"));
        acc.put("dataSource", "ACCOUNT_LEDGER");
        root.put("acc", acc);
        // PREV：近 4 季不含税销售额（含当季，oldest→newest）
        Map<String, Object> prev = new LinkedHashMap<>();
        prev.put("last4qSalesExcl", last4qSalesExcl(accountSetsId, ym));
        prev.put("quarters", last4qLabels(ym));
        prev.put("dataSource", "PREV_4Q_INVOICE_EXCL");  // 近 4 季开票口径估算，非已提交申报表
        root.put("prev", prev);
        return root;
    }

    private static final java.util.Set<cn.gson.financial.kernel.model.enums.InvoiceCategory> SPECIAL_CATS = java.util.Set.of(
            cn.gson.financial.kernel.model.enums.InvoiceCategory.增值税专用发票,
            cn.gson.financial.kernel.model.enums.InvoiceCategory.全电发票增值税专用发票,
            cn.gson.financial.kernel.model.enums.InvoiceCategory.税控机动车专用发票);

    /** 小规模 inv 六字段：按 OUT 正常发票（季度口径、tradeDate）的 征收率+票种(专/普)+免税 分组汇总含税额。 */
    public Map<String, Object> buildSmallScaleInv(Long accountSetsId, YearMonth ym) {
        int q = (ym.getMonthValue() - 1) / 3;               // 0=Q1 … 3=Q4
        LocalDate qStart = LocalDate.of(ym.getYear(), q * 3 + 1, 1);
        LocalDate qEnd = YearMonth.of(ym.getYear(), q * 3 + 3).atEndOfMonth();
        QInvoice qi = QInvoice.invoice;
        List<cn.gson.financial.kernel.model.entity.Invoice> outs = bqf.selectFrom(qi)
                .where(qi.accountSetsId.eq(accountSetsId)
                        .and(qi.type.eq(InvoiceType.OUT))
                        .and(qi.state.eq(InvoiceState.正常))
                        .and(qi.tradeDate.between(qStart, qEnd)))
                .fetch();

        Map<String, Object> d = groupSmallScaleInv(outs);
        d.put("quarter", ym.getYear() + "-Q" + (q + 1));
        return d;
    }

    /**
     * 纯分组（可测）：按 征收率(由税额/不含税额派生)+票种(专/普)+免税 汇总 OUT 正常发票<b>含税</b>额。
     * 桶：免税(税额=0) / 5%(4%~6.5%) / 3%专票 / 3%普票(含减按1% + 其它)；fixedAssetIncl 无标识置 0。
     */
    static Map<String, Object> groupSmallScaleInv(List<cn.gson.financial.kernel.model.entity.Invoice> outs) {
        BigDecimal taxedIncl3 = BigDecimal.ZERO, specialIncl3 = BigDecimal.ZERO, taxedIncl5 = BigDecimal.ZERO,
                exemptIncl = BigDecimal.ZERO, totalIncl = BigDecimal.ZERO;
        for (cn.gson.financial.kernel.model.entity.Invoice inv : outs) {
            // 未开具发票（未开票占位）不计入"开票"口径
            if (inv.getCategory() == cn.gson.financial.kernel.model.enums.InvoiceCategory.未开具发票) continue;
            BigDecimal excl = nz(inv.getTaxAmountExcluded());
            BigDecimal tax = nz(inv.getTaxAmount());
            BigDecimal incl = excl.add(tax);
            totalIncl = totalIncl.add(incl);
            if (tax.signum() == 0) {                        // 免税（0 税额）
                exemptIncl = exemptIncl.add(incl);
                continue;
            }
            // 征收率桶：<4% 归 3% 类（含减按 1%）/ 4%~6.5% 归 5% 类 / 其余兜进 3% 类
            BigDecimal ratio = excl.signum() > 0 ? tax.divide(excl, 4, java.math.RoundingMode.HALF_UP) : BigDecimal.ZERO;
            boolean rate5 = ratio.compareTo(new BigDecimal("0.04")) >= 0 && ratio.compareTo(new BigDecimal("0.065")) <= 0;
            if (rate5) {
                taxedIncl5 = taxedIncl5.add(incl);
            } else if (SPECIAL_CATS.contains(inv.getCategory())) {
                specialIncl3 = specialIncl3.add(incl);
            } else {
                taxedIncl3 = taxedIncl3.add(incl);
            }
        }

        Map<String, Object> d = new LinkedHashMap<>();
        d.put("taxedIncl3", taxedIncl3);     // 3% 普票（开票部分；未开票收入不在 invoice 表，需 BT/会计补）
        d.put("specialIncl3", specialIncl3); // 3% 专票
        d.put("taxedIncl5", taxedIncl5);     // 5%（不动产/出租）
        d.put("fixedAssetIncl", BigDecimal.ZERO);  // 售固定资产/旧货：invoice 表无标识，置 0 待 BT/会计或资产模块补
        d.put("exemptIncl", exemptIncl);     // 免税销售额（含税）
        d.put("totalIncl", totalIncl);       // 全部开票含税合计
        d.put("dataSource", "INVOICE_TABLE_GROUPED");
        d.put("note", "季度口径(开票日)；含税=不含税额+税额；fixedAssetIncl/未开票部分 invoice 表无标识，BT/会计补");
        return d;
    }

    /** 近 4 季不含税销售额（含当季，oldest→newest）。 */
    public List<BigDecimal> last4qSalesExcl(Long accountSetsId, YearMonth ym) {
        int q = (ym.getMonthValue() - 1) / 3;
        YearMonth curQStart = YearMonth.of(ym.getYear(), q * 3 + 1);
        List<BigDecimal> out = new java.util.ArrayList<>();
        for (int i = 3; i >= 0; i--) {                       // q-3 … q0
            YearMonth qs = curQStart.minusMonths(i * 3L);
            LocalDate start = qs.atDay(1);
            LocalDate end = qs.plusMonths(2).atEndOfMonth();
            out.add(sumOutExcl(accountSetsId, start, end));
        }
        return out;
    }

    private List<String> last4qLabels(YearMonth ym) {
        int q = (ym.getMonthValue() - 1) / 3;
        YearMonth curQStart = YearMonth.of(ym.getYear(), q * 3 + 1);
        List<String> labels = new java.util.ArrayList<>();
        for (int i = 3; i >= 0; i--) {
            YearMonth qs = curQStart.minusMonths(i * 3L);
            labels.add(qs.getYear() + "-Q" + ((qs.getMonthValue() - 1) / 3 + 1));
        }
        return labels;
    }

    /** 某区间 OUT 正常发票不含税销售额合计（tradeDate）。 */
    private BigDecimal sumOutExcl(Long accountSetsId, LocalDate start, LocalDate end) {
        QInvoice q = QInvoice.invoice;
        BigDecimal v = bqf.selectFrom(q)
                .select(q.taxAmountExcluded.sum().coalesce(BigDecimal.ZERO))
                .where(q.accountSetsId.eq(accountSetsId)
                        .and(q.type.eq(InvoiceType.OUT))
                        .and(q.state.eq(InvoiceState.正常))
                        .and(q.tradeDate.between(start, end)))
                .fetchFirst();
        return v == null ? BigDecimal.ZERO : v;
    }

    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }

    /**
     * VAT 预检三方数据（一般口径，既有）：
     * <ul>
     *   <li>{@code INV} — 开票数据（{@link #buildVatData}，invoice 表；与电税预填同源，验收主口径）</li>
     *   <li>{@code ACC} — 做账数 / FN 余额（账面：主营/其他业务收入 + 销项/进项税额，总账 voucher_details）</li>
     *   <li>{@code PREV} — 上期（上月开票口径估算，非已提交申报表，仅作三方对平参照）</li>
     * </ul>
     */
    public Map<String, Object> buildVatPrecheck(Long accountSetsId, YearMonth ym) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("accountSetsId", accountSetsId);
        root.put("period", ym.toString());
        root.put("taxType", "vat");
        root.put("INV", buildVatData(accountSetsId, ym));
        root.put("ACC", buildAccData(accountSetsId, ym));
        YearMonth prev = ym.minusMonths(1);
        Map<String, Object> prevData = buildVatData(accountSetsId, prev);
        prevData.put("period", prev.toString());
        prevData.put("dataSource", "PREV_PERIOD_INVOICE");  // 上期开票口径估算（非已提交申报表）
        root.put("PREV", prevData);
        return root;
    }

    /** ACC — 账面数（总账 voucher_details 本期发生额）：收入 + 销项/进项税额。 */
    public Map<String, Object> buildAccData(Long accountSetsId, YearMonth ym) {
        int y = ym.getYear(), m = ym.getMonthValue();
        // 收入：主营业务收入(企业 6001 / 小企业 6001 / 部分准则 5001) + 其他业务收入(5051/6051)，本期贷-借净额
        BigDecimal revenue = sumLedgerNet(accountSetsId, y, m,
                "s.code LIKE '5001%' OR s.code LIKE '6001%' OR s.code LIKE '5051%' OR s.code LIKE '6051%'", true);
        BigDecimal outputTax = sumLedgerNet(accountSetsId, y, m, "s.name LIKE '%销项税额%'", true);   // 贷-借
        BigDecimal inputTax = sumLedgerNet(accountSetsId, y, m, "s.name LIKE '%进项税额%'", false);  // 借-贷
        BigDecimal payableVat = outputTax.subtract(inputTax);
        if (payableVat.signum() < 0) payableVat = BigDecimal.ZERO;
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("revenue", revenue);
        d.put("outputTax", outputTax);
        d.put("inputTax", inputTax);
        d.put("payableVat", payableVat);
        d.put("dataSource", "ACCOUNT_LEDGER");
        return d;
    }

    /** 总账本期发生额净额：creditMinusDebit=true 取贷-借（收入/销项），false 取借-贷（进项）。subjectCond 为固定字面量（无用户输入）。 */
    private BigDecimal sumLedgerNet(Long accountSetsId, int year, int month, String subjectCond, boolean creditMinusDebit) {
        String expr = creditMinusDebit ? "vd.credit_amount - vd.debit_amount" : "vd.debit_amount - vd.credit_amount";
        String sql = "SELECT COALESCE(SUM(" + expr + "), 0) FROM fxy_financial_voucher_details vd "
                + "JOIN fxy_financial_voucher v ON v.id = vd.voucher_id "
                + "JOIN fxy_financial_subject s ON s.id = vd.subject_id AND s.account_sets_id = v.account_sets_id "
                + "WHERE v.account_sets_id = ? AND v.year = ? AND v.month = ? AND (" + subjectCond + ")";
        try {
            BigDecimal v = jdbcTemplate.queryForObject(sql, BigDecimal.class, accountSetsId, year, month);
            return v == null ? BigDecimal.ZERO : v;
        } catch (Exception e) {
            log.warn("[FN-API-GAPS] ACC 总账汇总失败 acc={} {}-{} cond={}", accountSetsId, year, month, subjectCond, e);
            return BigDecimal.ZERO;
        }
    }

    BigDecimal sumInvoiceAmount(QInvoice q, Long accountSetsId, InvoiceType type,
                                 LocalDate start, LocalDate end, boolean tax) {
        BigDecimal v = bqf.selectFrom(q)
                .select((tax ? q.taxAmount.sum() : q.amount.sum()).coalesce(BigDecimal.ZERO))
                .where(q.accountSetsId.eq(accountSetsId)
                        .and(q.type.eq(type))
                        .and(q.state.eq(InvoiceState.正常))
                        .and(q.tradeDate.between(start, end)))
                .fetchFirst();
        return v == null ? BigDecimal.ZERO : v;
    }

    // ═══════════════════════════════════════════════════════════════
    // IIT（个税）— 从 salary 表统计应发 + 个税合计
    // ═══════════════════════════════════════════════════════════════

    public Map<String, Object> buildIitData(Long accountSetsId, YearMonth ym) {
        Map<String, Object> d = new LinkedHashMap<>();
        try {
            Map<String, Object> row = jdbcTemplate.queryForMap(
                    "select count(1) as employee_count, " +
                    "  coalesce(sum(should_amount), 0) as payroll_total, " +
                    "  coalesce(sum(individual_income_tax), 0) as iit_total " +
                    "from fxy_financial_salary " +
                    "where account_sets_id = ? and year = ? and month = ?",
                    accountSetsId, ym.getYear(), ym.getMonthValue());
            d.put("employeeCount", row.get("employee_count"));
            d.put("payrollTotal", row.get("payroll_total"));
            d.put("iitTotal", row.get("iit_total"));
            d.put("dataSource", "SALARY_TABLE");
        } catch (Exception e) {
            log.warn("[FN-RPA-02] iit query failed acc={} period={}", accountSetsId, ym, e);
            d.put("employeeCount", 0);
            d.put("payrollTotal", BigDecimal.ZERO);
            d.put("iitTotal", BigDecimal.ZERO);
            d.put("dataSource", "SALARY_TABLE_ERROR");
        }
        return d;
    }

    // ═══════════════════════════════════════════════════════════════
    // Surcharge（附加税）— 城建税 / 教育费附加 / 地方教育附加 按 VAT 实缴税额派生
    // ═══════════════════════════════════════════════════════════════

    public Map<String, Object> buildSurchargeData(Long accountSetsId, YearMonth ym) {
        Map<String, Object> vat = buildVatData(accountSetsId, ym);
        BigDecimal payableVat = (BigDecimal) vat.getOrDefault("payableVat", BigDecimal.ZERO);
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("baseVat", payableVat);
        // 默认城建税率 7% 市区 / 教育费附加 3% / 地方教育附加 2%
        d.put("urbanConstructionTax", payableVat.multiply(new BigDecimal("0.07")));
        d.put("educationSurcharge", payableVat.multiply(new BigDecimal("0.03")));
        d.put("localEducationSurcharge", payableVat.multiply(new BigDecimal("0.02")));
        d.put("dataSource", "DERIVED_FROM_VAT");
        d.put("note", "城建税率按市区 7% 估算；县城 5% / 乡 1% 不区分 — Phase 2 接 customer.urbanLevel 字段");
        return d;
    }

    // ═══════════════════════════════════════════════════════════════
    // Annual（年报）— 全年累计，mock 阶段简化
    // ═══════════════════════════════════════════════════════════════

    public Map<String, Object> buildAnnualData(Long accountSetsId, YearMonth ym) {
        // 累计该年 1-12 月所有发票（简化版）
        LocalDate start = LocalDate.of(ym.getYear(), 1, 1);
        LocalDate end = LocalDate.of(ym.getYear(), 12, 31);
        QInvoice q = QInvoice.invoice;

        BigDecimal salesYTD = sumInvoiceAmount(q, accountSetsId, InvoiceType.OUT, start, end, false);
        BigDecimal purchaseYTD = sumInvoiceAmount(q, accountSetsId, InvoiceType.IN, start, end, false);

        Map<String, Object> d = new LinkedHashMap<>();
        d.put("year", ym.getYear());
        d.put("annualRevenue", salesYTD);
        d.put("annualCost", purchaseYTD);
        d.put("annualProfit", salesYTD.subtract(purchaseYTD));
        d.put("dataSource", "INVOICE_TABLE_YTD");
        d.put("note", "annual 当前只汇总发票口径；Phase 2 接 ReportService 出真实利润表 / 资产负债表");
        return d;
    }
}
