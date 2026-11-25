package cn.gson.financial.kernel.model.entity;

import jakarta.persistence.*;
import jakarta.persistence.Table;
import lombok.Data;
import org.hibernate.annotations.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 行业基准库（工单 121 · BENCH-BUILD · financial 本地表）。
 * <p>
 * financial 侧 batch 预计算产物：跨账套按 119 同口径算每户指标 → 按
 * 行业 × 规模档 × 纳税人类型 分组聚合（分位数 + 样本数）→ 落本表（source=SELF）。
 * finmaster 经只读接口 {@code /OutSystem2CRM/crmGetBenchmark} 拉取。
 * <p>
 * <b>★ 匿名红线</b>：本表仅存聚合分位数，<b>绝不含任何单户可识别数据</b>；
 * {@code sampleCount < 阈值（默认 10）} 的分组不写值。
 * <p>
 * 字段/唯一键对齐 121 §31；financial 仅产 SELF，{@code source} 恒为 SELF（留字段做 schema 对齐）。
 * 通过 ddl-auto=update 自动建表（与 {@link BankLib} 同建法，无 Flyway 迁移）。
 */
@Data
@Entity
@DynamicInsert
@DynamicUpdate
@Comment("行业基准库（BENCH-BUILD 匿名聚合，仅聚合值无单户数据）")
@Table(name = "fxy_financial_industry_benchmark", uniqueConstraints = {
        @UniqueConstraint(name = "uk_bench_dim",
                columnNames = {"industry_code", "scale_band", "taxpayer_type", "metric_key", "source"})
})
public class IndustryBenchmark {

    @Id
    @GenericGenerator(name = "CosIdGenerator", strategy = "cn.gson.financial.kernel.model.hibernate.CosIdGenerator")
    @GeneratedValue(generator = "CosIdGenerator")
    @Comment("主键")
    private Long id;

    @Comment("行业代码（国标 GB/T 4754，取自 account_sets.industry_code）")
    @Column(name = "industry_code", length = 32)
    private String industryCode;

    @Comment("规模档：MICRO/SMALL/MEDIUM/ALL（词表对齐 finmaster READ；SELF 按年营收派生只产 MICRO/SMALL/MEDIUM，大型折叠 MEDIUM、无营收跳过；ALL 为 EXTERNAL/兜底桶）")
    @Column(name = "scale_band", length = 16)
    private String scaleBand;

    @Comment("纳税人类型：GENERAL/SMALL/ALL（TaxpayerType.fromAccountSets 推导后映射，词表对齐 finmaster READ）")
    @Column(name = "taxpayer_type", length = 16)
    private String taxpayerType;

    @Comment("指标键（与 119 computeMetricsFromBalances 输出键对齐，如 debtRatio/grossMargin）")
    @Column(name = "metric_key", length = 64)
    private String metricKey;

    @Comment("25 分位数")
    @Column(name = "p25", precision = 20, scale = 6)
    private BigDecimal p25;

    @Comment("中位数（50 分位）")
    @Column(name = "p50", precision = 20, scale = 6)
    private BigDecimal p50;

    @Comment("75 分位数")
    @Column(name = "p75", precision = 20, scale = 6)
    private BigDecimal p75;

    @Comment("均值")
    @Column(name = "avg", precision = 20, scale = 6)
    private BigDecimal avg;

    @Comment("样本数（参与聚合的户数；<阈值的分组不入库）")
    @Column(name = "sample_count")
    private Integer sampleCount;

    @Comment("来源：EXTERNAL 外部公开 / SELF 自建匿名聚合（本表恒 SELF）")
    @Column(name = "source", length = 16)
    private String source;

    @Comment("更新时间")
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
