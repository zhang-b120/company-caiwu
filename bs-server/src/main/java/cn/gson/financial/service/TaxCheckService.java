package cn.gson.financial.service;

import cn.gson.financial.kernel.model.entity.TaxCheckReport;
import cn.gson.financial.kernel.model.repo.TaxCheckReportRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/**
 * FN-NEW-02 税务检查 Service — 拼装规则引擎输入 + 写报告。
 *
 * <p>当前 mock 阶段使用 {@link MockInputProvider} 提供 D1/D2/D3 数据；
 * Phase 2 切换到 invoice / declare_task / salary 真实表 + XW connector。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaxCheckService {

    private final TaxCheckReportRepository reportRepo;

    private volatile ObjectMapper objectMapper;

    @PostConstruct
    public void initObjectMapper() {
        if (this.objectMapper == null) {
            this.objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        }
    }

    private ObjectMapper om() {
        ObjectMapper m = this.objectMapper;
        if (m == null) {
            // 单测场景 @PostConstruct 没跑过，lazy init
            initObjectMapper();
            m = this.objectMapper;
        }
        return m;
    }

    /**
     * 跑 9 维度检查 + 写报告。
     * 不传 inputProvider 时用 {@link DefaultMockInputProvider}（联调可调通，但是固定 mock 数据）。
     */
    @Transactional
    public TaxCheckReport run(Long customerId, Long accountSetsId,
                              LocalDate periodStart, LocalDate periodEnd) {
        return run(customerId, accountSetsId, periodStart, periodEnd, new DefaultMockInputProvider());
    }

    /** 测试用 — 注入自定义 mock 数据 */
    @Transactional
    public TaxCheckReport run(Long customerId, Long accountSetsId,
                              LocalDate periodStart, LocalDate periodEnd,
                              MockInputProvider input) {
        if (customerId == null) throw new IllegalArgumentException("customerId 不能为空");
        if (accountSetsId == null) throw new IllegalArgumentException("accountSetsId 不能为空");
        if (periodStart == null || periodEnd == null) {
            throw new IllegalArgumentException("periodStart/End 不能为空");
        }
        if (periodEnd.isBefore(periodStart)) {
            throw new IllegalArgumentException("periodEnd 不能早于 periodStart");
        }

        // 1. 跑 3 维度
        List<TaxCheckRuleEngine.Finding> findings = new ArrayList<>();
        findings.add(TaxCheckRuleEngine.checkInputTaxCompliance(
                input.getInputInvoices(customerId, accountSetsId, periodStart, periodEnd),
                LocalDate.now()));
        findings.add(TaxCheckRuleEngine.checkOutputTaxConsistency(
                input.getDeclaredVat(customerId, accountSetsId, periodStart, periodEnd),
                input.getInvoicedOutputTax(customerId, accountSetsId, periodStart, periodEnd)));
        findings.add(TaxCheckRuleEngine.checkPersonalTaxConsistency(
                input.getDeclaredPersonalTax(customerId, accountSetsId, periodStart, periodEnd),
                input.getPayrollTotal(customerId, accountSetsId, periodStart, periodEnd)));

        // 2. Phase 2 占位
        findings.addAll(TaxCheckRuleEngine.phase2Placeholders());

        // 3. 汇总
        TaxCheckRuleEngine.Summary summary = TaxCheckRuleEngine.summarize(findings);

        // 4. 序列化 + 写表
        TaxCheckReport report = new TaxCheckReport();
        report.setCustomerId(customerId);
        report.setAccountSetsId(accountSetsId);
        report.setPeriodStart(periodStart);
        report.setPeriodEnd(periodEnd);
        report.setOverallScore(summary.overallScore);
        report.setRiskLevel(summary.riskLevel);
        try {
            List<Map<String, Object>> dims = findings.stream().map(TaxCheckRuleEngine.Finding::toMap).toList();
            report.setDimensionsJson(om().writeValueAsString(dims));
            // findings 仅含 risk != green 的，给 wx 简化卡片用
            List<Map<String, Object>> risky = findings.stream()
                    .filter(f -> !TaxCheckRuleEngine.RISK_GREEN.equals(f.riskLevel))
                    .map(TaxCheckRuleEngine.Finding::toMap)
                    .toList();
            report.setFindingsJson(om().writeValueAsString(risky));
            report.setRecommendationsJson(om().writeValueAsString(generateRecommendations(findings)));
        } catch (Exception ex) {
            log.error("[FN-NEW-02] 序列化 findings 失败 customerId={}", customerId, ex);
            throw new RuntimeException("序列化失败", ex);
        }
        return reportRepo.save(report);
    }

    static List<String> generateRecommendations(List<TaxCheckRuleEngine.Finding> findings) {
        List<String> rec = new ArrayList<>();
        for (TaxCheckRuleEngine.Finding f : findings) {
            if (TaxCheckRuleEngine.RISK_GREEN.equals(f.riskLevel) || f.score < 0) continue;
            rec.add(switch (f.dimension) {
                case 1 -> "请尽快认证逾期进项发票，超 360 天将无法抵扣";
                case 2 -> "建议核对增值税申报表与销项发票合计，可能存在漏报";
                case 3 -> "建议核对个税申报与工资表，可能存在漏报员工";
                default -> "请关注：" + f.title;
            });
        }
        if (rec.isEmpty()) rec.add("当前税务状况良好，无重大风险点");
        return rec;
    }

    public TaxCheckReport findById(Long id) {
        return id == null ? null : reportRepo.findById(id).orElse(null);
    }

    public org.springframework.data.domain.Page<TaxCheckReport> page(Long customerId, int page, int size) {
        if (page < 1) page = 1;
        if (size < 1) size = 20;
        if (size > 500) size = 500;
        org.springframework.data.domain.Pageable pageable =
                org.springframework.data.domain.PageRequest.of(page - 1, size);
        return customerId == null
                ? reportRepo.findAll(pageable)
                : reportRepo.findByCustomerIdOrderByCreatedAtDesc(customerId, pageable);
    }

    // ═══════════════════════════════════════════════════════════════
    // Mock 数据源（Phase 2 替换为真实 invoice / declare_task / salary 查询）
    // ═══════════════════════════════════════════════════════════════

    public interface MockInputProvider {
        List<TaxCheckRuleEngine.InputInvoice> getInputInvoices(Long c, Long a, LocalDate s, LocalDate e);
        BigDecimal getDeclaredVat(Long c, Long a, LocalDate s, LocalDate e);
        BigDecimal getInvoicedOutputTax(Long c, Long a, LocalDate s, LocalDate e);
        BigDecimal getDeclaredPersonalTax(Long c, Long a, LocalDate s, LocalDate e);
        BigDecimal getPayrollTotal(Long c, Long a, LocalDate s, LocalDate e);
    }

    /**
     * 默认 mock provider — 给联调用的固定数据。
     * 按 customerId % 4 派生不同档位，保证小程序联调时能看到 green/yellow/red 三种风险。
     */
    static class DefaultMockInputProvider implements MockInputProvider {
        @Override
        public List<TaxCheckRuleEngine.InputInvoice> getInputInvoices(Long c, Long a, LocalDate s, LocalDate e) {
            long bucket = c == null ? 0 : Math.abs(c) % 4;
            List<TaxCheckRuleEngine.InputInvoice> list = new ArrayList<>();
            list.add(new TaxCheckRuleEngine.InputInvoice("INV-001", LocalDate.now().minusDays(30), LocalDate.now().minusDays(20)));
            if (bucket >= 2) {
                // 加一张逾期发票（>360 天未认证）触发风险
                list.add(new TaxCheckRuleEngine.InputInvoice("INV-002-OVERDUE",
                        LocalDate.now().minusDays(400), null));
            }
            return list;
        }

        @Override
        public BigDecimal getDeclaredVat(Long c, Long a, LocalDate s, LocalDate e) {
            long bucket = c == null ? 0 : Math.abs(c) % 4;
            return bucket == 1 ? new BigDecimal("8000") : new BigDecimal("10000");
        }

        @Override
        public BigDecimal getInvoicedOutputTax(Long c, Long a, LocalDate s, LocalDate e) {
            return new BigDecimal("10000");
        }

        @Override
        public BigDecimal getDeclaredPersonalTax(Long c, Long a, LocalDate s, LocalDate e) {
            long bucket = c == null ? 0 : Math.abs(c) % 4;
            // bucket=3 触发 D3 风险：申报 8000 vs 工资合计 10000 → 20% 差异
            return bucket == 3 ? new BigDecimal("8000") : new BigDecimal("10000");
        }

        @Override
        public BigDecimal getPayrollTotal(Long c, Long a, LocalDate s, LocalDate e) {
            // mock 同量级，无业务真实意义 — Phase 2 改成真表 declared_pit << payroll 正常
            return new BigDecimal("10000");
        }
    }
}
