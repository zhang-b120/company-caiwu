package cn.gson.financial.service;

import cn.gson.financial.kernel.model.entity.AccountSets;
import cn.gson.financial.kernel.model.entity.FlowPeriod;
import cn.gson.financial.kernel.model.repo.AccountSetsRepository;
import cn.gson.financial.kernel.model.repo.FlowPeriodRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * FN-PIPELINE-COUNTS-BY-USER：工作台流水线计数——<b>查库全量 + 按登录账号</b>。
 * <p>取代 BT 「按分页数据前端派生」的不准计数：基于某登录用户（代账会计/操作员）名下<b>全部账套</b>（不分页），
 * 按 {@code flow_period.st_*} 真实状态分桶统计各阶段户数。
 * <p>阶段判据与 PURE-DISPLAY 对齐：<b>优先 flow_period.st_*；无行=待采集/未开始；不派生</b>。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WorkbenchPipelineService {

    private final AccountSetsRepository accountSetsRepository;
    private final FlowPeriodRepository flowPeriodRepository;

    /** 阶段「已通过」状态：done/skipped/na/exempt 视为该阶段不再「待」，流水线推进到下一阶段。 */
    private static final Set<String> PASSED = Set.of("done", "skipped", "na", "exempt");

    /**
     * 统计某登录用户名下全部账套在 period 的流水线分布。
     *
     * @return {@code {all, collect, bookkeep, carry, predeclare, tax, done}}（6 桶互斥，之和==all）
     */
    public Map<String, Object> pipelineCountsByUser(Long userId, String period) {
        // 全量：该用户名下「全部」未停用账套（user_account_sets 归属），不分页、不系统全量
        List<AccountSets> sets = accountSetsRepository.findActiveByUserAndFilters(userId, null, null, null);

        int all = sets.size();
        int collect = 0, bookkeep = 0, carry = 0, predeclare = 0, tax = 0, done = 0;

        if (all > 0) {
            List<Long> ids = sets.stream().map(AccountSets::getId).collect(Collectors.toList());
            // 批量取该批账套当期 flow_period（无行的账套 = 待采集）
            Map<Long, FlowPeriod> byId = flowPeriodRepository
                    .findByAccountSetIdInAndPeriod(ids, period).stream()
                    .collect(Collectors.toMap(FlowPeriod::getAccountSetId, f -> f, (a, b) -> a));

            for (AccountSets s : sets) {
                switch (bucketOf(byId.get(s.getId()))) {
                    case "collect" -> collect++;
                    case "bookkeep" -> bookkeep++;
                    case "carry" -> carry++;
                    case "predeclare" -> predeclare++;
                    case "tax" -> tax++;
                    default -> done++;
                }
            }
        }

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("all", all);
        r.put("collect", collect);       // 待采集
        r.put("bookkeep", bookkeep);     // 待做账
        r.put("carry", carry);           // 待结转
        r.put("predeclare", predeclare); // 待预申报
        r.put("tax", tax);               // 待报税
        r.put("done", done);             // 已完成
        return r;
    }

    static boolean passed(String st) {
        return st != null && PASSED.contains(st);
    }

    /**
     * 流水线分桶 = 最早一个「未通过」阶段（collect→bookkeep→carry→predeclare→tax→done）。
     * 无 flow_period 行 → 待采集（未开始）。tax 桶覆盖国税+个税+缴款全通过为止。
     */
    static String bucketOf(FlowPeriod fp) {
        if (fp == null) return "collect";
        if (!passed(fp.getStCollect())) return "collect";
        if (!passed(fp.getStBook())) return "bookkeep";
        if (!passed(fp.getStCarry())) return "carry";
        if (!passed(fp.getStPredeclare())) return "predeclare";
        if (!(passed(fp.getStDeclareVat()) && passed(fp.getStDeclareIit()) && passed(fp.getStPayment()))) {
            return "tax";
        }
        return "done";
    }
}
