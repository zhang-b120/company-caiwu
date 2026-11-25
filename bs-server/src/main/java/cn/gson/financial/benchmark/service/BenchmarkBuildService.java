package cn.gson.financial.benchmark.service;

import cn.gson.financial.kernel.model.entity.*;
import cn.gson.financial.kernel.model.enums.TaxpayerType;
import cn.gson.financial.kernel.model.repo.IndustryBenchmarkRepository;
import cn.gson.financial.kernel.service.AbsService;
import cn.gson.financial.kernel.service.CurrencyService;
import cn.hutool.core.util.StrUtil;
import com.querydsl.core.Tuple;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 工单 121 · BENCH-BUILD · 自建客户群匿名聚合（financial 侧 batch · 预计算）。
 * <p>
 * 流程：跨所有账套查 {@code fxy_financial_balance}（各账套<b>最近已结账期</b>）→ 按 119 同口径
 * （{@link BenchmarkMetricCalculator}）算每户指标 → 按 <b>行业 × 规模档 × 纳税人类型</b> 分组聚合
 * （p25/p50/p75/avg + sampleCount）→ 写 {@code fxy_financial_industry_benchmark}（source=SELF）。
 * <p>
 * <b>★ 匿名红线（合规底线，代码强制）</b>：
 * <ul>
 *   <li>聚合即丢弃个体——单户指标仅在内存 List 暂存，<b>绝不持久化任何单户数据</b>；</li>
 *   <li>{@code sampleCount < SAMPLE_THRESHOLD（10）} 的分组<b>不写值</b>（连行都不建，避免泄露小样本存在性）；</li>
 *   <li>仅内部用、仅聚合分位数。</li>
 * </ul>
 * <p>
 * 触发：手动 / 定时（非实时）。授权前提见 121 工单：服务协议已覆盖"客户数据用于匿名行业对标"。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BenchmarkBuildService extends AbsService {

    /** 匿名红线：分组样本数下限，<此值不出值不建行 */
    public static final int SAMPLE_THRESHOLD = 10;

    /** 本表只产 SELF */
    private static final String SOURCE_SELF = "SELF";

    private final IndustryBenchmarkRepository benchmarkRepository;
    private final BenchmarkMetricCalculator calculator;
    private final CurrencyService currencyService;

    private final QAccountSets qAccountSets = QAccountSets.accountSets;
    private final QCheckout qCheckout = QCheckout.checkout;
    private final QBalance qBalance = QBalance.balance;
    private final QSubject qSubject = QSubject.subject;

    /**
     * 跑批：重算并 upsert 全部 SELF 基准。返回统计摘要。
     *
     * @return { accountsScanned, accountsContributed, accountsSkipped, groups, rowsUpserted, threshold, builtAt }
     */
    @Transactional
    public Map<String, Object> buildSelfBenchmark() {
        long t0 = System.currentTimeMillis();

        // 1) 全部账套（id>=1，与 crmGetAccountSummaryData 口径一致）
        List<AccountSets> accountSetsList = bqf.selectFrom(qAccountSets)
                .where(qAccountSets.id.goe(1L))
                .fetch();

        int scanned = accountSetsList.size();
        int contributed = 0, skipped = 0;

        // groupKey(industry|scale|taxpayer) → metricKey → List<value>（仅内存暂存，绝不落库）
        Map<String, Map<String, List<BigDecimal>>> agg = new LinkedHashMap<>();

        for (AccountSets as : accountSetsList) {
            try {
                String industryCode = as.getIndustryCode();
                if (StrUtil.isBlank(industryCode)) { skipped++; continue; }   // 无行业码无法作行业基准
                // 词表对齐 READ（finmaster resolveTaxpayerType 用 GENERAL/SMALL/ALL）：
                // 用权威枚举 TaxpayerType.fromAccountSets 推导后映射，避免直接 vatType.name()(SMALL_SCALE/COMMONLY) join 不上
                String taxpayerType = mapTaxpayerToReadVocab(
                        TaxpayerType.fromAccountSets(as.getIndividualBusiness(), as.getVatType()));

                // 最近已结账期（status==2 视为已结账，与 crmGetAccountSummaryData 一致）
                LocalDate checkedDate = bqf.selectFrom(qCheckout)
                        .select(qCheckout.checkDate)
                        .where(qCheckout.accountSetsId.eq(as.getId()).and(qCheckout.status.eq(2)))
                        .orderBy(qCheckout.checkDate.desc())
                        .fetchFirst();
                if (checkedDate == null) { skipped++; continue; }             // 从未结账，跳过

                Long localCurrencyId;
                try {
                    localCurrencyId = currencyService.fetchLocal(as.getId()).getId();
                } catch (Exception e) {
                    skipped++; continue;                                       // 未设本位币，跳过
                }

                List<BenchmarkMetricCalculator.BalanceRow> rows =
                        loadBalanceRows(as.getId(), checkedDate.getYear(), checkedDate.getMonthValue(), localCurrencyId);
                if (rows.isEmpty()) { skipped++; continue; }

                Map<String, BigDecimal> metrics = calculator.compute(rows);
                if (metrics.isEmpty()) { skipped++; continue; }

                String scaleBand = deriveScaleBand(calculator.annualRevenue(rows));
                if (scaleBand == null) { skipped++; continue; }               // 无营收无法定规模档，跳过该户(不出值)
                String groupKey = industryCode + "|" + scaleBand + "|" + taxpayerType;

                Map<String, List<BigDecimal>> byMetric = agg.computeIfAbsent(groupKey, k -> new LinkedHashMap<>());
                for (Map.Entry<String, BigDecimal> e : metrics.entrySet()) {
                    byMetric.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(e.getValue());
                }
                contributed++;
            } catch (Exception e) {
                log.warn("[BENCH-BUILD] 账套 {} 处理失败，跳过：{}", as.getId(), e.toString());
                skipped++;
            }
        }

        // 2) 分组聚合 + 样本阈值 + upsert（★ 只写聚合值）
        int groups = 0, rowsUpserted = 0;
        LocalDateTime now = LocalDateTime.now();
        for (Map.Entry<String, Map<String, List<BigDecimal>>> ge : agg.entrySet()) {
            String[] dim = ge.getKey().split("\\|", -1);
            String industryCode = dim[0], scaleBand = dim[1], taxpayerType = dim[2];
            boolean groupHasValue = false;
            for (Map.Entry<String, List<BigDecimal>> me : ge.getValue().entrySet()) {
                List<BigDecimal> values = me.getValue();
                if (values.size() < SAMPLE_THRESHOLD) continue;                // 匿名红线：小样本不出值
                groupHasValue = true;
                upsert(industryCode, scaleBand, taxpayerType, me.getKey(), values, now);
                rowsUpserted++;
            }
            if (groupHasValue) groups++;
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("accountsScanned", scanned);
        summary.put("accountsContributed", contributed);
        summary.put("accountsSkipped", skipped);
        summary.put("groupsWithValue", groups);
        summary.put("rowsUpserted", rowsUpserted);
        summary.put("sampleThreshold", SAMPLE_THRESHOLD);
        summary.put("costMs", System.currentTimeMillis() - t0);
        summary.put("builtAt", now.toString());
        log.info("[BENCH-BUILD] 完成 {}", summary);
        return summary;
    }

    /** 查单户某期科目余额（aux=0 + 本位币），转 calculator 入参 */
    private List<BenchmarkMetricCalculator.BalanceRow> loadBalanceRows(Long accountSetsId, int year, int month, Long localCurrencyId) {
        List<Tuple> tuples = bqf.selectFrom(qBalance)
                .select(qBalance, qSubject.code)
                .leftJoin(qSubject).on(qSubject.id.eq(qBalance.subjectId)
                        .and(qSubject.accountSetsId.eq(qBalance.accountSetsId)))
                .where(qBalance.accountSetsId.eq(accountSetsId)
                        .and(qBalance.year.eq(year))
                        .and(qBalance.period.eq(month))
                        .and(qBalance.auxiliaryDetailId.eq(0L))
                        .and(qBalance.currencyId.eq(localCurrencyId)))
                .fetch();

        List<BenchmarkMetricCalculator.BalanceRow> rows = new ArrayList<>(tuples.size());
        for (Tuple t : tuples) {
            Balance b = t.get(qBalance);
            if (b == null) continue;
            rows.add(new BenchmarkMetricCalculator.BalanceRow(
                    t.get(qSubject.code), b.getBeginBalance(), b.getEndBalance(),
                    b.getYearTotalDebit(), b.getYearTotalCredit()));
        }
        return rows;
    }

    /**
     * 规模档派生（按年营业收入；<b>可调阈值，审查点</b>）。
     * <p>说明：account_sets 与 CRM customer_company 均无规模字段，故按营收派生；
     * 国标《中小企业划型》本为行业相关 + 营收/人数/资产多维，MVP 先用营收单维近似。
     * <p><b>词表对齐 READ</b>：finmaster {@code resolveScaleBand} 词表为 MICRO/SMALL/MEDIUM/ALL（无 LARGE/UNKNOWN）。
     * 故：大型(≥4亿) <b>折叠到 MEDIUM</b>；<b>无营收返 null → 调用方跳过该户（不出值）</b>，不归任何组。
     *
     * @return MICRO/SMALL/MEDIUM；<b>无营收返 null（跳过）</b>
     */
    private String deriveScaleBand(BigDecimal annualRevenue) {
        if (annualRevenue == null || annualRevenue.signum() <= 0) return null;                   // 无营收→跳过该户(不出值)
        if (annualRevenue.compareTo(BigDecimal.valueOf(1_000_000L)) < 0) return "MICRO";        // <100万
        if (annualRevenue.compareTo(BigDecimal.valueOf(20_000_000L)) < 0) return "SMALL";       // 100万~2000万
        if (annualRevenue.compareTo(BigDecimal.valueOf(400_000_000L)) < 0) return "MEDIUM";     // 2000万~4亿
        return "MEDIUM";                                                                         // ≥4亿(大型)→折叠 MEDIUM(READ 词表无 LARGE)
    }

    /**
     * 词表对齐 READ：把 financial 权威枚举 {@link TaxpayerType} 映射到 finmaster
     * {@code resolveTaxpayerType} 的词表 GENERAL/SMALL/ALL。
     * <ul>
     *   <li>GENERAL（一般纳税人）→ {@code GENERAL}</li>
     *   <li>SMALL_SCALE（小规模）→ {@code SMALL}（★READ 用 SMALL，非 SMALL_SCALE）</li>
     *   <li>INDIVIDUAL（个体）→ {@code SMALL}（个体≈小规模征收，对齐 READ 的 SMALL 桶；READ 无 INDIVIDUAL 桶）</li>
     * </ul>
     */
    private static String mapTaxpayerToReadVocab(TaxpayerType t) {
        if (t == null) return "ALL";
        switch (t) {
            case GENERAL:     return "GENERAL";
            case SMALL_SCALE: return "SMALL";
            case INDIVIDUAL:  return "SMALL";
            default:          return "ALL";
        }
    }

    /** upsert 一行聚合基准（按唯一键查已存在 → 更新；否则新增） */
    private void upsert(String industryCode, String scaleBand, String taxpayerType, String metricKey,
                        List<BigDecimal> values, LocalDateTime now) {
        IndustryBenchmark row = benchmarkRepository
                .findFirstByIndustryCodeAndScaleBandAndTaxpayerTypeAndMetricKeyAndSource(
                        industryCode, scaleBand, taxpayerType, metricKey, SOURCE_SELF)
                .orElseGet(IndustryBenchmark::new);
        row.setIndustryCode(industryCode);
        row.setScaleBand(scaleBand);
        row.setTaxpayerType(taxpayerType);
        row.setMetricKey(metricKey);
        row.setSource(SOURCE_SELF);
        row.setP25(percentile(values, 25));
        row.setP50(percentile(values, 50));
        row.setP75(percentile(values, 75));
        row.setAvg(avg(values));
        row.setSampleCount(values.size());
        row.setUpdatedAt(now);
        benchmarkRepository.save(row);
    }

    /** 线性插值分位数（type-7，与 numpy/Excel 默认一致），6 位小数 */
    static BigDecimal percentile(List<BigDecimal> values, int p) {
        if (values == null || values.isEmpty()) return null;
        List<BigDecimal> s = new ArrayList<>(values);
        Collections.sort(s);
        int n = s.size();
        if (n == 1) return s.get(0);
        double rank = (p / 100.0) * (n - 1);
        int lo = (int) Math.floor(rank);
        int hi = (int) Math.ceil(rank);
        if (lo == hi) return s.get(lo);
        BigDecimal frac = BigDecimal.valueOf(rank - lo);
        BigDecimal vlo = s.get(lo), vhi = s.get(hi);
        return vlo.add(vhi.subtract(vlo).multiply(frac)).setScale(6, RoundingMode.HALF_UP);
    }

    static BigDecimal avg(List<BigDecimal> values) {
        if (values == null || values.isEmpty()) return null;
        BigDecimal sum = BigDecimal.ZERO;
        for (BigDecimal v : values) sum = sum.add(v);
        return sum.divide(BigDecimal.valueOf(values.size()), 6, RoundingMode.HALF_UP);
    }
}
