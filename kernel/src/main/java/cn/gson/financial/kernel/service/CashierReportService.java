package cn.gson.financial.kernel.service;

import cn.gson.financial.kernel.model.entity.QSubject;
import cn.gson.financial.kernel.model.vo.AccountSummaryItemVo;
import cn.gson.financial.kernel.model.vo.CashierAccountCheckVo;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.Dict;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 
 * <p><b>Copyright © 2010-2022开发公司All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.kernel.service</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2022年11月10日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@RequiredArgsConstructor
@Service
@Transactional(readOnly = true)
public class CashierReportService extends AbsService {

    private final QSubject qSubject = QSubject.subject;

    private final CashierJournalService cashierJournalService;

    /** 账实相符容差：半分。出纳金额为 2 位小数（分），吸收 Double 求和的浮点残差，≥0.01 视为真差异。 */
    private static final double RECONCILE_EPS = 0.005;

    /**
     * FN-FIX-CASHIER-CHECK-LOGIC：资金（出纳）检查的<b>单一真相源</b>，openapi + web 共用，消除两路分叉。
     *
     * <p>正确语义 = <b>全部出纳日记账已做凭证（cashiered）且 期末账实相符</b>。
     * 修复前两路都用反极性表达式 {@code cashiered && (期初差异 != 期末差异)}（要求"本期新产生账实差异"才算过），
     * 且只在 {@link #accountCheck} 顶层找「差异」行——而「差异」行实际嵌在每个科目的 {@code children} 里、
     * 顶层（project=科目名）永远找不到 → openapi 恒 {@code hasDiff=false} 恒不过、web 静默退回 cashiered
     * 且永远发现不了真账实不符。账实相符 / 0 业务月账套因此被误判未过、卡关账。
     *
     * <p>判定：① cashiered=false（真有日记账未做凭证）→ 直接未过（不放宽真缺凭证）；
     * ② localCurrencyId 为空（无本位币上下文，无法对账）→ 维持 cashiered（与原 openapi 一致）；
     * ③ 遍历 accountCheck 结果<b>每个科目 children 里的「差异」行</b>，期末差异 {@code balance}（科目期末余额−出纳期末余额）
     *    全部 ≈ 0（半分容差）即账实相符 → 与 cashiered 与运算。
     *
     * @param accountSetsId  账套
     * @param start          期间起（月首）
     * @param end            期间止（月末）
     * @param localCurrencyId 本位币 id，可空
     * @return 资金检查是否通过
     */
    public boolean cashierPassed(Long accountSetsId, LocalDate start, LocalDate end, Long localCurrencyId) {
        boolean cashiered = cashierJournalService.cashierCheck(accountSetsId, start, end);
        if (!cashiered || localCurrencyId == null) {
            return cashiered;
        }
        return isReconciled(accountCheck(start, end, null, localCurrencyId, accountSetsId));
    }

    /**
     * 期末账实相符判定（纯逻辑，包级可见便于单测）：
     * 遍历 accountCheck 结果每个科目 {@code children} 里的「差异」行，期末差异 {@code balance} 全部 ≈ 0
     * （半分容差）即账实相符。无「差异」行（无现金账户/无业务）= 相符。
     */
    static boolean isReconciled(List<CashierAccountCheckVo> rows) {
        if (rows == null) {
            return true;
        }
        return rows.stream()
                .flatMap(vo -> vo.getChildren() == null ? Stream.<CashierAccountCheckVo>empty() : vo.getChildren().stream())
                .filter(it -> "差异".equals(it.getProject()))
                .allMatch(it -> it.getBalance() == null || Math.abs(it.getBalance()) < RECONCILE_EPS);
    }

    public List<AccountSummaryItemVo> accountSummary(LocalDate start, LocalDate end, Long accountSetsId) {
        Dict base = Dict.create().set("accountSetsId", accountSetsId)
                .set("start", start)
                .set("end", end.plusMonths(1).minusDays(1));
        return lazyDao.findBySql("accountSummary", base, AccountSummaryItemVo.class);
    }

    public List<AccountSummaryItemVo> ioTypeSummary(LocalDate start, LocalDate end, Long accountSetsId) {
        Dict base = Dict.create().set("accountSetsId", accountSetsId)
                .set("start", start)
                .set("end", end.plusMonths(1).minusDays(1));
        return lazyDao.findBySql("ioTypeSummary", base, AccountSummaryItemVo.class);
    }

    public List<CashierAccountCheckVo> accountCheck(LocalDate start, LocalDate end, Long cashierAccountId, Long currencyId, Long accountSetsId) {
        Dict base = Dict.create().set("accountSetsId", accountSetsId)
                .set("cashierAccountId", cashierAccountId)
                .set("currencyId", currencyId)
                .set("start", start)
                .set("end", end.plusMonths(1).minusDays(1));
        List<CashierAccountCheckVo> accountSubtotal = lazyDao.findBySql("accountSubtotal", base, CashierAccountCheckVo.class);

        Map<Long, CashierAccountCheckVo> dictMap = accountSubtotal.stream().collect(Collectors.toMap(CashierAccountCheckVo::getSubjectId, dict -> dict));

        Set<Long> codeList = accountSubtotal.stream().map(CashierAccountCheckVo::getSubjectId).collect(Collectors.toSet());

        List<CashierAccountCheckVo> collect = bqf.selectFrom(qSubject)
                .select(qSubject.id, qSubject.name)
                .where(qSubject.accountSetsId.eq(accountSetsId).and(qSubject.id.in(codeList)))
                .orderBy(qSubject.queryCode.asc())
                .fetch().stream().collect(ArrayList::new, (list, tuple) -> {
                    CashierAccountCheckVo checkVo = new CashierAccountCheckVo();
                    checkVo.setProject(tuple.get(qSubject.name));
                    checkVo.setSubjectId(tuple.get(qSubject.id));
                    list.add(checkVo);
                }, List::addAll);

        collect.forEach(dict -> {
            List<CashierAccountCheckVo> children = new ArrayList<>();
            Double initBalance = lazyDao.getSingleValue("subjectInitBalance",
                    Dict.create().set("accountSetsId", accountSetsId)
                            .set("currencyId", currencyId)
                            .set("year", start.getYear())
                            .set("period", start.getMonthValue())
                            .set("subjectId", dict.getSubjectId())
                    , Double.class);
            CashierAccountCheckVo subjectSubtotal = lazyDao.loadBySql("subjectSubtotal",
                    Dict.create().set("accountSetsId", accountSetsId)
                            .set("start", start)
                            .set("end", end.plusMonths(1).minusDays(1))
                            .set("subjectId", dict.getSubjectId())
                    , CashierAccountCheckVo.class);
            CashierAccountCheckVo row = dictMap.get(dict.getSubjectId());
            CashierAccountCheckVo set = new CashierAccountCheckVo();
            set.setProject("会计科目");
            set.setSubjectId(dict.getSubjectId());
            set.setName(dict.getProject());
            set.setCurrencyName(row.getCurrencyName());
            set.setInitBalance(Optional.ofNullable(initBalance).orElse(0d));
            if (subjectSubtotal != null) {
                BeanUtil.copyProperties(subjectSubtotal, set, CopyOptions.create().ignoreNullValue());
                Double sr = Optional.ofNullable(set.getSr()).orElse(0d);
                Double zc = Optional.ofNullable(set.getZc()).orElse(0d);
                set.setBalance(set.getInitBalance() + sr - zc);
            } else {
                set.setBalance(set.getInitBalance());
            }
            children.add(set);
            row.setProject("资金账户");
            row.setParentId(dict.getSubjectId());
            children.add(row);

            Double initBalance1 = Optional.ofNullable(row.getInitBalance()).orElse(0d);
            Double sr = Optional.ofNullable(row.getSr()).orElse(0d);
            Double zc = Optional.ofNullable(row.getZc()).orElse(0d);
            Double sBalance = Optional.ofNullable(set.getBalance()).orElse(0d);
            Double aBalance = Optional.ofNullable(row.getBalance()).orElse(0d);

            CashierAccountCheckVo diff = new CashierAccountCheckVo();
            diff.setSr(Optional.ofNullable(set.getSr()).orElse(0d) - sr);
            diff.setZc(Optional.ofNullable(set.getZc()).orElse(0d) - zc);
            diff.setInitBalance(set.getInitBalance() - initBalance1);
            diff.setBalance(sBalance - aBalance);
            diff.setProject("差异");

            children.add(diff);
            dict.setChildren(children);
        });
        return collect;
    }
}
