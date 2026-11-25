package cn.gson.financial.service;

import cn.gson.financial.kernel.model.entity.LoanApplication;
import cn.gson.financial.kernel.model.repo.LoanApplicationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * FN-NEW-01 贷款评估 + 申请 Service（mock）。
 *
 * <p><b>评估算法（mock）</b>：基于 customerId 作 seed 派生近 6 月营业额，再分档：
 * <ul>
 *   <li>营业额 ≥ 100 万 → 信用贷 80 万 / 利率 4.5%</li>
 *   <li>营业额 ≥ 50 万 → 信用贷 30 万 / 利率 5.5%</li>
 *   <li>否则 → 信用贷 5 万 / 利率 7.5%</li>
 *   <li>抵押贷（mortgage）→ 100-1000 万 / 利率 3.85%（不查营业额）</li>
 *   <li>票据贴现（discount）→ 申请金额 90% / 利率 4.0%（不查营业额）</li>
 * </ul>
 * Phase 2 把 mockMonthlyRevenue() 切到查 invoice / voucher 表真实数据。
 *
 * <p><b>状态机自动推进</b>：mock 联调用，每次 list / find 时 lazy on-read 推进：
 * <pre>
 *   pending_evaluation --(30s)--> evaluated
 *   evaluated          --(30s)--> submitted
 *   submitted          --(30s)--> under_review
 *   under_review       --(30s)--> approved   (90% 概率，按 id 末位)
 *   under_review       --(30s)--> rejected   (10% 概率)
 * </pre>
 * 已 approved / rejected 视为终态不再推进。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoanApplicationService {

    public static final long AUTO_ADVANCE_SECONDS = 30L;

    /** mock 营业额档位（单位：元） */
    static final BigDecimal REVENUE_HIGH = new BigDecimal("1000000");
    static final BigDecimal REVENUE_MID  = new BigDecimal("500000");

    private final LoanApplicationRepository loanRepo;

    // ═══════════════════════════════════════════════════════════════
    // 评估（不写表，只返回评估结果）
    // ═══════════════════════════════════════════════════════════════

    public EvaluationResult evaluate(Long customerId, Long accountSetsId, String loanType) {
        if (customerId == null) throw new IllegalArgumentException("customerId 不能为空");
        if (accountSetsId == null) throw new IllegalArgumentException("accountSetsId 不能为空");
        if (loanType == null || loanType.isBlank()) throw new IllegalArgumentException("loanType 不能为空");

        String type = loanType.trim().toLowerCase();
        EvaluationResult r = new EvaluationResult();
        r.loanType = type;
        r.evaluationBasis = new LinkedHashMap<>();

        switch (type) {
            case LoanApplication.TYPE_MORTGAGE -> {
                r.evaluatedAmount = new BigDecimal("1000000");
                r.interestRateLow = new BigDecimal("3.85");
                r.interestRateHigh = new BigDecimal("4.50");
                r.evaluationBasis.put("type", "mortgage");
                r.evaluationBasis.put("amountRange", "100 万 - 1000 万");
                r.evaluationBasis.put("note", "需提供房产 / 设备等抵押物");
                r.recommendedProducts = List.of(
                        product("工商银行经营抵押贷", new BigDecimal("3.85"), 60),
                        product("建设银行抵押 e 贷", new BigDecimal("4.10"), 36)
                );
            }
            case LoanApplication.TYPE_DISCOUNT -> {
                r.evaluatedAmount = new BigDecimal("500000");
                r.interestRateLow = new BigDecimal("3.50");
                r.interestRateHigh = new BigDecimal("4.50");
                r.evaluationBasis.put("type", "discount");
                r.evaluationBasis.put("note", "按汇票面额 90% 贴现");
                r.recommendedProducts = List.of(
                        product("票据 e 贴", new BigDecimal("4.00"), 6)
                );
            }
            default -> {
                // credit
                BigDecimal revenue = mockMonthlyRevenue(customerId);
                if (revenue.compareTo(REVENUE_HIGH) >= 0) {
                    r.evaluatedAmount = new BigDecimal("800000");
                    r.interestRateLow = new BigDecimal("4.50");
                    r.interestRateHigh = new BigDecimal("5.50");
                } else if (revenue.compareTo(REVENUE_MID) >= 0) {
                    r.evaluatedAmount = new BigDecimal("300000");
                    r.interestRateLow = new BigDecimal("5.50");
                    r.interestRateHigh = new BigDecimal("6.50");
                } else {
                    r.evaluatedAmount = new BigDecimal("50000");
                    r.interestRateLow = new BigDecimal("7.50");
                    r.interestRateHigh = new BigDecimal("9.00");
                }
                r.evaluationBasis.put("type", "credit");
                r.evaluationBasis.put("monthlyRevenueEstimate", revenue);
                r.evaluationBasis.put("dataSource", "MOCK_DERIVED_FROM_CUSTOMER_ID");
                r.recommendedProducts = List.of(
                        product("微众银行微业贷", r.interestRateLow, 24),
                        product("网商银行经营贷", r.interestRateHigh, 36)
                );
            }
        }
        return r;
    }

    // ═══════════════════════════════════════════════════════════════
    // 申请
    // ═══════════════════════════════════════════════════════════════

    @Transactional
    public LoanApplication apply(Long customerId, Long accountSetsId, String loanType,
                                 BigDecimal applyAmount, String purpose, String materialsJson) {
        if (customerId == null) throw new IllegalArgumentException("customerId 不能为空");
        if (accountSetsId == null) throw new IllegalArgumentException("accountSetsId 不能为空");
        if (loanType == null || loanType.isBlank()) throw new IllegalArgumentException("loanType 不能为空");
        if (applyAmount == null || applyAmount.signum() <= 0) {
            throw new IllegalArgumentException("applyAmount 必须为正数");
        }

        LoanApplication app = new LoanApplication();
        app.setCustomerId(customerId);
        app.setAccountSetsId(accountSetsId);
        app.setLoanType(loanType.trim().toLowerCase());
        app.setApplyAmount(applyAmount);
        app.setPurpose(purpose);
        app.setMaterialsJson(materialsJson);
        app.setStatus(LoanApplication.STATUS_PENDING_EVALUATION);
        return loanRepo.save(app);
    }

    // ═══════════════════════════════════════════════════════════════
    // 查询 — 带 lazy on-read 状态机推进
    // ═══════════════════════════════════════════════════════════════

    @Transactional
    public Page<LoanApplication> page(Long customerId, int page, int size) {
        if (page < 1) page = 1;
        if (size < 1) size = 20;
        if (size > 500) size = 500;
        Pageable pageable = PageRequest.of(page - 1, size);
        Page<LoanApplication> p = (customerId == null)
                ? loanRepo.findAll(pageable)
                : loanRepo.findByCustomerIdOrderByCreatedAtDesc(customerId, pageable);
        p.getContent().forEach(this::advanceIfDue);
        return p;
    }

    @Transactional
    public Optional<LoanApplication> findById(Long id) {
        return loanRepo.findById(id).map(a -> { advanceIfDue(a); return a; });
    }

    /**
     * lazy on-read 推进状态机。已 approved/rejected 不推进。
     * 推进策略：updated_at 距今 ≥ 30s 推进到下一状态。
     * under_review → 按 id 末位 0~8 → approved，末位 9 → rejected。
     */
    void advanceIfDue(LoanApplication a) {
        if (a == null) return;
        String s = a.getStatus();
        if (LoanApplication.STATUS_APPROVED.equals(s) || LoanApplication.STATUS_REJECTED.equals(s)) {
            return;
        }
        if (a.getUpdatedAt() == null) return;
        long secs = ChronoUnit.SECONDS.between(a.getUpdatedAt(), LocalDateTime.now());
        if (secs < AUTO_ADVANCE_SECONDS) return;

        String next = switch (s == null ? "" : s) {
            case LoanApplication.STATUS_PENDING_EVALUATION -> LoanApplication.STATUS_EVALUATED;
            case LoanApplication.STATUS_EVALUATED          -> LoanApplication.STATUS_SUBMITTED;
            case LoanApplication.STATUS_SUBMITTED          -> LoanApplication.STATUS_UNDER_REVIEW;
            case LoanApplication.STATUS_UNDER_REVIEW       -> (a.getId() != null && a.getId() % 10 == 9)
                    ? LoanApplication.STATUS_REJECTED
                    : LoanApplication.STATUS_APPROVED;
            default -> null;
        };
        if (next == null) return;

        a.setStatus(next);
        if (LoanApplication.STATUS_EVALUATED.equals(next)) {
            // 评估通过 — 回填评估金额（用 credit 简单 mock）
            EvaluationResult r = evaluate(a.getCustomerId(), a.getAccountSetsId(), a.getLoanType());
            a.setEvaluatedAmount(r.evaluatedAmount);
            a.setInterestRate(r.interestRateLow);
        } else if (LoanApplication.STATUS_REJECTED.equals(next)) {
            a.setRejectionReason("评估未通过：营业额近 6 月不稳定（mock 拒绝原因）");
        } else if (LoanApplication.STATUS_APPROVED.equals(next)) {
            a.setBankPartner("微众银行");
            a.setContractNo("WZB-" + a.getId() + "-MOCK");
        }
        loanRepo.save(a);
        log.info("[FN-NEW-01] auto-advance loan {} {} -> {}", a.getId(), s, next);
    }

    // ═══════════════════════════════════════════════════════════════
    // mock helpers
    // ═══════════════════════════════════════════════════════════════

    /**
     * mock 近 6 月平均月营业额。Phase 2 替换成查 invoice / voucher 表。
     * 当前用 customerId 派生 — 同一客户多次评估结果稳定。
     */
    BigDecimal mockMonthlyRevenue(Long customerId) {
        if (customerId == null) return BigDecimal.ZERO;
        long abs = Math.abs(customerId);
        long bucket = abs % 3; // 0/1/2 三档
        return switch ((int) bucket) {
            case 0 -> new BigDecimal("150000");  // 低档：15 万
            case 1 -> new BigDecimal("700000");  // 中档：70 万
            default -> new BigDecimal("1500000"); // 高档：150 万
        };
    }

    static Map<String, Object> product(String name, BigDecimal rate, int termMonths) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("interestRate", rate.setScale(2, RoundingMode.HALF_UP));
        m.put("termMonths", termMonths);
        return m;
    }

    // ═══════════════════════════════════════════════════════════════
    // DTO
    // ═══════════════════════════════════════════════════════════════

    public static class EvaluationResult {
        public String loanType;
        public BigDecimal evaluatedAmount;
        public BigDecimal interestRateLow;
        public BigDecimal interestRateHigh;
        public Map<String, Object> evaluationBasis;
        public List<Map<String, Object>> recommendedProducts;
    }
}
