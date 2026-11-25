package cn.gson.financial.kernel.service;

import cn.gson.financial.kernel.config.AccountSetsProcessor;
import cn.gson.financial.kernel.config.BackupProcessor;
import cn.gson.financial.kernel.model.dto.CheckoutTemplateDto;
import cn.gson.financial.kernel.model.entity.*;
import cn.gson.financial.kernel.model.hibernate.AbsEntity;
import cn.gson.financial.kernel.model.hibernate.PrimaryKey;
import cn.gson.financial.kernel.model.mapper.CheckoutTemplateMapper;
import cn.gson.financial.kernel.model.repo.CheckoutTemplateRepository;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.lang.Dict;
import cn.hutool.core.util.NumberUtil;
import com.alibaba.fastjson.JSON;
import com.googlecode.aviator.AviatorEvaluator;
import com.googlecode.aviator.AviatorEvaluatorInstance;
import com.googlecode.aviator.EvalMode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * 
 * <p><b>Copyright © 2010-2023 开发公司 All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.kernel.service</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2023年04月18日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CheckoutTemplateService extends AbsService implements AccountSetsProcessor, BackupProcessor {
    private final AviatorEvaluatorInstance evaluator = AviatorEvaluator.newInstance(EvalMode.INTERPRETER);

    private final CheckoutTemplateRepository checkoutTemplateRepository;

    private final SubjectService subjectService;

    private final QCheckoutTemplate qCheckoutTemplate = QCheckoutTemplate.checkoutTemplate;

    private final QBalance qBalance = QBalance.balance;

    private final CheckoutTemplateMapper checkoutTemplateMapper;

    private final QSubject qSubject = QSubject.subject;

    private final VoucherService voucherService;

    @Transactional
    public CheckoutTemplate save(CheckoutTemplate entity) {
        Assert.notEmpty(entity.getItems(), "凭证项不能为空~");
        Assert.isTrue(entity.getItems().stream().anyMatch(s -> CheckoutTemplate.ValueType.按公式取值.equals(s.getValueType())), "凭证项至少须有一项为按公式取值~");
        Map<String, List<CheckoutTemplate.CtItem>> itemsAutoGroup = entity.getItems().stream()
                .filter(s -> CheckoutTemplate.ValueType.自动平衡.equals(s.getValueType()))
                .collect(Collectors.groupingBy(CheckoutTemplate.CtItem::getBalanceDirection));
        Assert.isTrue(itemsAutoGroup.size() <= 1, "所有自动平衡行必须在同一方向～");
        return checkoutTemplateRepository.save(entity);
    }

    public List<CheckoutTemplateDto> list(Long accountSetsId) {
        return bqf.selectFrom(qCheckoutTemplate)
                .where(qCheckoutTemplate.accountSetsId.eq(accountSetsId))
                .orderBy(qCheckoutTemplate.pos.asc(), qCheckoutTemplate.id.asc())
                .fetch().stream().map(checkoutTemplateMapper::toDto).collect(Collectors.toList());
    }

    @Transactional
    public void delete(PrimaryKey primaryKey) {
        checkoutTemplateRepository.deleteById(primaryKey);
    }

    @Override
    public void init(AccountSets accountSets, AccountSetsTemplate accountSetsTemplate) {
        checkoutTemplateRepository.saveAll(bqf.selectFrom(qCheckoutTemplate)
                .where(qCheckoutTemplate.accountSetsId.eq(accountSetsTemplate.getId()))
                .fetch().stream().map(s -> {
                    bqf.detach(s);
                    CheckoutTemplate bean = BeanUtil.toBean(s, CheckoutTemplate.class);
                    bean.setAccountSetsId(accountSets.getId());
                    return bean;
                }).collect(Collectors.toList()));
    }

    // ═══════════════════════════════════════════════════════════════
    // FN-VERIFY-CHECKOUT-TEMPLATES-GET-API-V5：6 张标准结转模板「只读巡检」
    //
    // ⚠️ 不动账套数据：本工单 spec 明确要求 FN 不强制兜底；让运维知情决定改不改。
    //    health-check 端点返「该账套 6 个 derivedCode 哪个有 / 哪个缺」让 BT / 运维 / xiaowei 看；
    //    实际补建走 db/manual-cleanup/ 下的 SQL 由运维手工跑。
    // ═══════════════════════════════════════════════════════════════

    /** 6 个标准 derivedCode 与对应推荐 title（仅作为运维参考的目标值；FN 不主动写入） */
    public static final List<String[]> STANDARD_SIX_TEMPLATES = List.of(
            new String[]{"COST_CARRY",          "结转销售成本"},
            new String[]{"SURTAX_ACCRUAL",      "计提税金"},
            new String[]{"VAT_SETTLEMENT",      "结转未交增值税"},
            new String[]{"INCOME_TAX_ACCRUAL",  "计提所得税"},
            new String[]{"YEAR_PROFIT",         "结转本年利润（盈利）"},
            new String[]{"YEAR_LOSS",           "结转本年利润（亏损）"}
    );

    /** 巡检结果：账套 → { present[], missing[], bareYearProfit (rename 候选) } */
    public Map<String, Object> healthCheckSixTemplates(Long accountSetsId) {
        List<CheckoutTemplate> existing = loadTemplatesForTest(accountSetsId);

        Set<String> presentCodes = new LinkedHashSet<>();
        List<Map<String, Object>> bareYearProfit = new ArrayList<>();
        List<Map<String, Object>> allByDerived = new ArrayList<>();

        for (CheckoutTemplate t : existing) {
            String code = deriveCode(t.getTitle());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", String.valueOf(t.getId()));
            row.put("title", t.getTitle());
            row.put("derivedCode", code);
            allByDerived.add(row);

            if (code != null && !"YEAR_PROFIT_OR_LOSS".equals(code)) {
                presentCodes.add(code);
            }
            // 候选 rename：title="结转本年利润" 既不含 盈利 也不含 亏损 后缀
            if ("YEAR_PROFIT_OR_LOSS".equals(code)) {
                Map<String, Object> bare = new LinkedHashMap<>();
                bare.put("id", String.valueOf(t.getId()));
                bare.put("title", t.getTitle());
                bare.put("suggestedRenameTo", "结转本年利润（盈利）");
                bareYearProfit.add(bare);
            }
        }

        List<String> missing = new ArrayList<>();
        for (String[] entry : STANDARD_SIX_TEMPLATES) {
            if (!presentCodes.contains(entry[0])) missing.add(entry[0]);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("accountSetsId", String.valueOf(accountSetsId));
        result.put("expectedStandardCodes", STANDARD_SIX_TEMPLATES.stream()
                .map(e -> e[0]).collect(Collectors.toList()));
        result.put("presentCodes", new ArrayList<>(presentCodes));
        result.put("missingCodes", missing);
        result.put("bareYearProfit", bareYearProfit);
        result.put("templates", allByDerived);
        result.put("healthy", missing.isEmpty() && bareYearProfit.isEmpty());
        return result;
    }

    /** test hook（protected — 允许跨包 anonymous subclass 覆写）：返指定账套全部 CheckoutTemplate；单测覆写避开 BlazeJPAQuery */
    protected List<CheckoutTemplate> loadTemplatesForTest(Long accountSetsId) {
        return bqf.selectFrom(qCheckoutTemplate)
                .where(qCheckoutTemplate.accountSetsId.eq(accountSetsId))
                .fetch();
    }

    /** title → derivedCode（OpenApiCheckoutController.deriveTemplateCode 委托给此方法）*/
    public static String deriveCode(String title) {
        if (title == null) return null;
        if (title.contains("结转销售成本") || title.contains("结转成本")) return "COST_CARRY";
        if (title.contains("计提") && title.contains("税金")) return "SURTAX_ACCRUAL";
        if (title.contains("结转") && title.contains("未交增值税")) return "VAT_SETTLEMENT";
        if (title.contains("计提") && title.contains("所得税")) return "INCOME_TAX_ACCRUAL";
        if (title.contains("结转本年利润") && title.contains("盈利")) return "YEAR_PROFIT";
        if (title.contains("结转本年利润") && title.contains("亏损")) return "YEAR_LOSS";
        if (title.contains("结转本年利润")) return "YEAR_PROFIT_OR_LOSS";
        return null;
    }

    @Override
    @Transactional
    public void clear(Long accountSetsId) {
        jqf.delete(qCheckoutTemplate)
                .where(qCheckoutTemplate.accountSetsId.eq(accountSetsId))
                .execute();
    }

    @Override
    public String backup(Long accountSetsId) {
        return JSON.toJSONString(bqf.selectFrom(qCheckoutTemplate)
                .where(qCheckoutTemplate.accountSetsId.eq(accountSetsId)).fetch());
    }

    @Override
    public Class<?> backupType() {
        return CheckoutTemplate.class;
    }

    /**
     * 恢复备份
     *
     * @param accountSetsId
     * @param json
     * @return
     */
    @Override
    public void recover(Long accountSetsId, String json) {
        List<CheckoutTemplate> entityList = JSON.parseArray(json, CheckoutTemplate.class);
        for (CheckoutTemplate entity : entityList) {
            entity.setAccountSetsId(accountSetsId);
            checkoutTemplateRepository.save(entity);
        }
    }

    /**
     * 获取指定账户设置ID
     *
     * @param accountSetsId 账户设置ID
     * @return 结算模板数据
     */
    public Dict tplData(Long accountSetsId, Long tplId) {
        Optional<CheckoutTemplate> optional = checkoutTemplateRepository.findById(new PrimaryKey(tplId, accountSetsId));
        Assert.isTrue(optional.isPresent(), "模板不存在~");
        CheckoutTemplate template = optional.get();
        List<CheckoutTemplate.CtItem> itemList = template.getItems();

        itemList = this.getVoucherItems(accountSetsId, itemList);

        return Dict.create()
                .set("itemList", itemList)
                .set("subject", getSubjectMap(accountSetsId, itemList));
    }


    /**
     * 获取指定账户设置ID、年份和月份的结算模板数据
     *
     * @param accountSetsId   账户设置ID
     * @param localCurrencyId
     * @param checkYear       年份
     * @param checkMonth      月份
     * @return 包含结算模板ID和金额的Map
     */
    public Map<Long, Dict> tplShowData(Long accountSetsId, Long localCurrencyId, Integer checkYear, Integer checkMonth) {
        // 查询符合条件的结算模板列表
        Iterable<CheckoutTemplate> checkoutTemplates = checkoutTemplateRepository.findAll(qCheckoutTemplate.accountSetsId.eq(accountSetsId));
        // 创建一个空的Map用于存储结算模板ID和金额
        Map<Long, Dict> data = new HashMap<>();
        QVoucherDetails qVoucherDetails = QVoucherDetails.voucherDetails;

        // 先通过checkTplId查询已生成的凭证
        List<Long> templateIds = new ArrayList<>();
        checkoutTemplates.forEach(template -> templateIds.add(template.getId()));
        final Map<Long, Voucher> checkVoucherMap;
        if (CollUtil.isNotEmpty(templateIds)) {
            checkVoucherMap = voucherService.loadByCheckTplId(accountSetsId, templateIds, checkYear, checkMonth);
        } else {
            checkVoucherMap = new HashMap<>();
        }

        // 查询符合条件的凭证明细列表（用于兼容旧逻辑，如果通过checkTplId找不到凭证时使用）
        Map<Long, List<VoucherDetails>> voucherMap = bqf.selectFrom(qVoucherDetails)
                .where(qVoucherDetails.accountSetsId.eq(accountSetsId)
                        .and(qVoucherDetails.year.eq(checkYear))
                        .and(qVoucherDetails.month.eq(checkMonth))
                        .and(qVoucherDetails.carryForward.isFalse()))
                .fetch().stream().collect(Collectors.groupingBy(VoucherDetails::getVoucherId));

        // 遍历结算模板列表
        checkoutTemplates.forEach(template -> {
            // 获取当前结算模板的所有项目
            List<CheckoutTemplate.CtItem> itemList = template.getItems();
            // 获取当前结算模板的凭证项目
            itemList = this.getVoucherItems(accountSetsId, itemList);
            // 获取当前结算模板的所有科目ID
            Set<Long> itemSubjectIds = itemList.stream()
                    .map(CheckoutTemplate.CtItem::getSubjectId).collect(Collectors.toSet());
            List<CheckoutTemplate.CtItem> finalItemList = itemList;

            Dict dict = Dict.create();
            
            // 优先通过checkTplId查找已生成的凭证
            Voucher checkVoucher = checkVoucherMap.get(template.getId());
            if (checkVoucher != null) {
                // 如果找到了已生成的凭证，从凭证中获取金额
                dict.set("voucherId", checkVoucher.getId());
                dict.set("amount", checkVoucher.getDebitAmount());
            } else {
                // 如果没有通过checkTplId找到，则使用旧的科目匹配逻辑（兼容旧数据）
                voucherMap.forEach((voucherId, details) -> {
                    // 获取当前凭证的所有科目ID
                    Set<Long> subjectIds = details.stream().map(VoucherDetails::getSubjectId).collect(Collectors.toSet());
                    // 当前凭证的所有科目ID和当前结算模板的所有科目ID的交集
                    Collection<Long> intersection = CollUtil.intersection(itemSubjectIds, subjectIds);
                    // 如果交集不为空
                    if (CollUtil.isNotEmpty(intersection) && itemSubjectIds.size() == intersection.size() && subjectIds.size() == itemSubjectIds.size()) {
                        // 获取当前凭证的所有科目ID和当前结算模板的所有科目ID的交集的凭证项目
                        List<VoucherDetails> voucherDetails = details.stream().filter(s -> intersection.contains(s.getSubjectId()) && s.isDebit()).toList();
                        if (CollUtil.isNotEmpty(voucherDetails)) {
                            // 遍历当前凭证的所有科目ID和当前结算模板的所有科目ID的交集的凭证项目
                            voucherDetails.forEach(vd -> {
                                // 获取当前凭证的所有科目ID和当前结算模板的所有科目ID的交集的凭证项目的金额
                                BigDecimal amount = vd.getAmount();
                                // 将当前结算模板的借方金额加上当前凭证的所有科目ID和当前结算模板的所有科目ID的交集的凭证项目的金额
                                finalItemList.stream().filter(s -> s.getSubjectId().equals(vd.getSubjectId())).forEach(s -> s.setAmount(s.getAmount().add(amount)));
                            });
                            // 将当前凭证的ID存入Map中
                            HashSet<Object> vids = dict.get("voucherId", new HashSet<>());
                            // 将当前凭证的ID存入Set中
                            vids.add(voucherId);
                            // 将当前凭证的ID存入Map中
                            dict.put("voucherId", vids);
                        }
                    }
                });

                if (dict.containsKey("voucherId")) {
                    // 计算当前结算模板的借方金额
                    BigDecimal amount = itemList.stream().filter(s -> "借".equals(s.getBalanceDirection()))
                            .map(CheckoutTemplate.CtItem::getAmount)
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
                    // 将当前结算模板的ID和借方金额存入Map中
                    dict.set("amount", amount);
                } else {
                    AtomicReference<BigDecimal> totalDebitAmount = new AtomicReference<>(BigDecimal.ZERO);
                    AtomicReference<BigDecimal> totalCreditAmount = new AtomicReference<>(BigDecimal.ZERO);
                    template.getItems().stream()
                            .filter(it -> CheckoutTemplate.ValueType.按公式取值.equals(it.getValueType()))
                            .forEach(it -> {
                            List<Object> rules = new ArrayList<>();
                            Map<String, Object> vars = new HashMap<>();
                            List<CheckoutTemplate.RuleItem> itRules = it.getRules();
                            for (int i = 0; i < itRules.size(); i++) {
                                CheckoutTemplate.RuleItem rule = itRules.get(i);
                                BigDecimal endBalance = bqf.selectFrom(qBalance)
                                        .select(qBalance.endBalance)
                                        .where(qBalance.accountSetsId.eq(accountSetsId)
                                                .and(qBalance.year.eq(checkYear))
                                                .and(qBalance.period.eq(checkMonth))
                                                .and(qBalance.subjectId.eq(rule.getSubjectId()))
                                                .and(qBalance.auxiliaryDetailId.eq(0L))
                                                .and(qBalance.currencyId.eq(localCurrencyId)))
                                        .fetchFirst();
                                if (endBalance != null) {
                                    BigDecimal mul = NumberUtil.mul(endBalance, NumberUtil.div(rule.getRate(), 100));
                                    if (rules.isEmpty() && "-".equals(rule.getOperator())) {
                                        rules.add("0-");
                                    }
                                    rules.add("var" + i);
                                    vars.put("var" + i, mul);
                                }
                            }
                            if (CollUtil.isNotEmpty(rules)) {
                                try {
                                    Object calculate = evaluator.execute(CollUtil.join(rules, ""), vars);
                                    if (calculate != null
                                        && NumberUtil.isNumber(calculate.toString())
                                        && NumberUtil.isValidNumber(new BigDecimal(calculate.toString()))) {
                                        it.setAmount(new BigDecimal(calculate.toString()));
                                        switch (it.getBalanceDirection()) {
                                            case "借":
                                                totalDebitAmount.getAndUpdate(d -> d.add(it.getAmount()));
                                                break;
                                            case "贷":
                                                totalCreditAmount.getAndUpdate(d -> d.add(it.getAmount()));
                                                break;
                                        }
                                    }
                                } catch (Exception e) {
                                    log.error("结转模板：{},{}计算失败，accountSetsId：{}", template.getTitle(), it.getSummary(), accountSetsId, e);
                                    }
                                }
                            });

                    AtomicReference<BigDecimal> abs = new AtomicReference<>(BigDecimal.ZERO);
                    template.getItems().stream().filter(it -> CheckoutTemplate.ValueType.自动平衡.equals(it.getValueType()))
                            .findFirst().ifPresent(ctItem -> {
                                if (ctItem.getBalanceDirection().equals("借")) {
                                    abs.set(totalCreditAmount.accumulateAndGet(totalDebitAmount.get(), BigDecimal::subtract));
                                } else {
                                    abs.set(totalDebitAmount.accumulateAndGet(totalCreditAmount.get(), BigDecimal::subtract));
                                }
                            });
                    template.getItems().stream()
                            .filter(it -> CheckoutTemplate.ValueType.自动平衡.equals(it.getValueType()))
                            .forEach(it -> {
                                it.setAmount(abs.accumulateAndGet(NumberUtil.div(it.getEqRate(), 100), BigDecimal::multiply));
                                switch (it.getBalanceDirection()) {
                                    case "借":
                                        totalDebitAmount.getAndUpdate(d -> d.add(it.getAmount()));
                                        break;
                                    case "贷":
                                        totalCreditAmount.getAndUpdate(d -> d.add(it.getAmount()));
                                        break;
                                }

                            });
                    // ── 尾差修正：先四舍五入到分，再用最后一项吸收差额 ──
                    // 1. 所有金额统一四舍五入到2位小数
                    totalDebitAmount.set(BigDecimal.ZERO);
                    totalCreditAmount.set(BigDecimal.ZERO);
                    for (CheckoutTemplate.CtItem it : template.getItems()) {
                        if (it.getAmount().compareTo(BigDecimal.ZERO) == 0) continue;
                        it.setAmount(it.getAmount().setScale(2, java.math.RoundingMode.HALF_UP));
                        switch (it.getBalanceDirection()) {
                            case "借" -> totalDebitAmount.getAndUpdate(d -> d.add(it.getAmount()));
                            case "贷" -> totalCreditAmount.getAndUpdate(d -> d.add(it.getAmount()));
                        }
                    }
                    // 2. 如果借贷不平（≤0.1元视为进位误差），调整借方或贷方最后一项
                    BigDecimal roundingDiff = totalDebitAmount.get().subtract(totalCreditAmount.get());
                    if (roundingDiff.abs().compareTo(BigDecimal.ZERO) != 0
                            && roundingDiff.abs().compareTo(new BigDecimal("0.1")) <= 0) {
                        // 确定需要调整的方向：哪边金额小就加到哪边
                        String adjustDir = roundingDiff.compareTo(BigDecimal.ZERO) > 0 ? "贷" : "借";
                        // 找该方向最后一项（金额最大项）进行调整
                        template.getItems().stream()
                                .filter(it -> adjustDir.equals(it.getBalanceDirection())
                                        && it.getAmount().compareTo(BigDecimal.ZERO) != 0)
                                .max(java.util.Comparator.comparing(it -> it.getAmount().abs()))
                                .ifPresent(adjustItem -> {
                                    adjustItem.setAmount(adjustItem.getAmount().add(roundingDiff.abs()));
                                    log.info("结转模板[{}]尾差修正: 差额={}, 调整{}方科目+{}",
                                            template.getTitle(), roundingDiff, adjustDir, roundingDiff.abs());
                                });
                        // 修正后重算合计
                        if ("借".equals(adjustDir)) {
                            totalDebitAmount.getAndUpdate(d -> d.add(roundingDiff.abs()));
                        } else {
                            totalCreditAmount.getAndUpdate(d -> d.add(roundingDiff.abs()));
                        }
                    }

                    dict.set("amount", totalDebitAmount.get());
                    dict.set("items", template.getItems());
                    dict.set("subject", getSubjectMap(accountSetsId, template.getItems()));
                }
            }
            dict.set("title", template.getTitle());
            data.put(template.getId(), dict);
        });
        // 返回包含结算模板ID和金额的Map
        return data;
    }


    private List<CheckoutTemplate.CtItem> getVoucherItems(Long accountSetsId, List<CheckoutTemplate.CtItem> itemList) {
        List<CheckoutTemplate.CtItem> newItems = new ArrayList<>();
        //按公式取值的合计金额
        for (CheckoutTemplate.CtItem s : itemList) {
            try {
                Subject subject = subjectService.getById(new PrimaryKey(s.getSubjectId(), accountSetsId));
                bqf.detach(subject);
                // 判断是否是末级科目
                if (!subject.getLeaf()) {
                    List<Subject> leafSubjects = bqf.selectFrom(qSubject)
                            .where(qSubject.accountSetsId.eq(accountSetsId)
                                    .and(qSubject.code.like(subject.getCode() + "%"))
                                    .and(qSubject.leaf.isTrue()))
                            .fetch();
                    leafSubjects.forEach(sb -> {
                        bqf.detach(sb);
                        CheckoutTemplate.CtItem bean = BeanUtil.toBean(s, CheckoutTemplate.CtItem.class);
                        bean.setSubjectId(sb.getId());
                        bean.setAmount(BigDecimal.ZERO);
                        newItems.add(bean);
                    });
                } else {
                    newItems.add(s);
                }
            } catch (Exception e) {
                newItems.add(s);
            }
        }
        return newItems;
    }


    private Map<Long, Subject> getSubjectMap(Long accountSetsId, List<CheckoutTemplate.CtItem> itemList) {
        List<Long> subjectIds = itemList.stream().map(CheckoutTemplate.CtItem::getSubjectId).collect(Collectors.toList());

        List<Subject> subjects = bqf.selectFrom(qSubject)
                .where(qSubject.accountSetsId.eq(accountSetsId)
                        .and(qSubject.id.in(subjectIds)))
                .fetch();
        return subjects.stream().collect(Collectors.toMap(AbsEntity::getId, s -> s));
    }
}
