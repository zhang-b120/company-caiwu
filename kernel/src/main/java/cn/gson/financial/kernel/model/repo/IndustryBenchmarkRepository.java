package cn.gson.financial.kernel.model.repo;

import cn.gson.financial.kernel.model.entity.IndustryBenchmark;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 行业基准库仓储（工单 121 · BENCH-BUILD）。
 * <p>主键为单 Long id（{@link IndustryBenchmark} 用 CosIdGenerator 单主键）。
 */
public interface IndustryBenchmarkRepository extends JpaRepository<IndustryBenchmark, Long> {

    /** 按 source 拉全部（finmaster 只读拉取用，默认 SELF） */
    List<IndustryBenchmark> findBySource(String source);

    /** 唯一键查询：upsert 时定位已存在行 */
    Optional<IndustryBenchmark> findFirstByIndustryCodeAndScaleBandAndTaxpayerTypeAndMetricKeyAndSource(
            String industryCode, String scaleBand, String taxpayerType, String metricKey, String source);
}
