package cn.gson.financial.task.workbench.service;

import cn.gson.financial.kernel.model.dto.CheckoutTemplateDto;
import cn.gson.financial.kernel.model.dto.ProfitCheckDto;
import cn.gson.financial.kernel.model.dto.SubjectDto;
import cn.gson.financial.kernel.model.dto.VoucherDetailsDto;
import cn.gson.financial.kernel.model.dto.VoucherDto;
import cn.gson.financial.kernel.model.entity.AccountSets;
import cn.gson.financial.kernel.model.entity.CheckoutTemplate;
import cn.gson.financial.kernel.model.entity.QAccountSets;
import cn.gson.financial.kernel.model.entity.QVoucherWord;
import cn.gson.financial.kernel.model.entity.Subject;
import cn.gson.financial.kernel.model.entity.VoucherWord;
import cn.gson.financial.kernel.model.hibernate.BlazeJPAQueryFactory;
import cn.gson.financial.kernel.service.AbsService;
import cn.gson.financial.kernel.service.BalanceCalcService;
import cn.gson.financial.kernel.service.CheckoutService;
import cn.gson.financial.kernel.service.CheckoutTemplateService;
import cn.gson.financial.kernel.service.CurrencyService;
import cn.gson.financial.kernel.service.SubjectService;
import cn.gson.financial.kernel.service.VoucherService;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.lang.Dict;
import cn.hutool.core.util.NumberUtil;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 任务工作台 — 结转损益 & 结转本年利润 自动生成。
 *
 * <p><b>⚠️ FN-FIX-DISABLE-NATIVE-BOOKKEEPING（2026-05-29）已停用，做账走 xiaowei（X 方案）</b>
 * <p>本类的 {@link #execute(Long, int, int)} / {@link #buildCarryForwardVoucher} /
 * {@link #generateYearEndProfitTransfer} 等建凭证方法在生产链路上**不可达**：
 * <ul>
 *   <li>OpenAPI 路径：{@code OpenApiCheckoutController.profitCarryAuto} 已改 verify-only
 *       （FN-FIX-PL-CARRY-VERIFY-ONLY），不再调 {@code taskCarryForwardService.execute}。</li>
 *   <li>任务工作台路径：{@code TaskBookkeepService.execute} 顶部直接拒绝（FN-FIX-DISABLE-NATIVE-BOOKKEEPING），
 *       下层 {@code executeCarryForwardInNewTx} → {@code taskCarryForwardService.execute} 已不可达。</li>
 * </ul>
 * <p>代码保留是为了：① fallback（如果必须临时回滚到 FN 自建模式）② 单元测试 / 测试 fixture 仍可调
 * （{@code TaskCarryForwardServiceSignedBalanceTest} / {@code TaskCarryForwardServiceE2ETest} 直接 mock
 * 构造此类）③ 审计追溯历史业务规则。
 *
 * <p>历史功能（已停用，仅供追溯）：
 * <ul>
 *   <li>每月：结转损益凭证（关闭所有损益类科目余额 → 本年利润）</li>
 *   <li>12月：额外生成结转本年利润凭证（本年利润 → 未分配利润），盈亏模板不同</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskCarryForwardService extends AbsService {

    @Resource
    @Lazy
    protected BlazeJPAQueryFactory bqf;

    private final CheckoutService checkoutService;
    private final CheckoutTemplateService checkoutTemplateService;
    private final SubjectService subjectService;
    private final VoucherService voucherService;
    private final BalanceCalcService balanceCalcService;
    private final CurrencyService currencyService;

    private static final QAccountSets qAccountSets = QAccountSets.accountSets;

    /**
     * 自动结转损益，12 月额外结转本年利润。
     *
     * <p><b>⚠️ 已停用（生产链路不可达）— 见类 Javadoc</b>。所有上游入口已切断：
     * OpenApiCheckoutController.profitCarryAuto 改 verify-only / TaskBookkeepService.execute
     * 顶部拒绝。本方法仅供单元测试与 fallback。
     *
     * @param accountSetId 账套 ID
     * @param year         年
     * @param month        月
     * @return 结果 map：type / status / msg
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Map<String, Object> execute(Long accountSetId, int year, int month) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("type", "carry_forward");
        try {
            AccountSets accountSets = bqf.selectFrom(qAccountSets)
                    .where(qAccountSets.id.eq(accountSetId))
                    .fetchFirst();
            if (accountSets == null) {
                r.put("status", "error");
                r.put("msg", "结转损益：账套不存在");
                return r;
            }

            // ── Step 0: 执行所有结转模板（计提税金、结转成本等，必须在结转损益之前）
            Long localCurrencyId = currencyService.fetchLocal(accountSetId).getId();
            String tplMsg = generateCheckoutTemplateVouchers(accountSetId, accountSets, year, month, localCurrencyId);
            if (tplMsg != null) {
                log.info("结转模板执行结果: {}", tplMsg);
            }

            // ── Step 1: 结转损益（每月）─────────────────────────────
            List<ProfitCheckDto> checkItems = checkoutService.profitCheck(accountSetId, year, month);
            if (CollUtil.isEmpty(checkItems)) {
                // 无损益余额 → 直接标记已结转
                checkoutService.updateStatus(accountSetId, year, month, 1);
                r.put("status", "skipped");
                r.put("msg", "结转损益：损益类科目无余额，无需结转");
                return r;
            }

            // 找本年利润科目（先于 lr 计算，避免无 leaf 时白扫一遍）
            Subject bnlrSubject = subjectService.loadByName("本年利润", accountSetId);
            if (bnlrSubject == null) {
                bnlrSubject = subjectService.loadByName("非限定性净资产", accountSetId);
            }
            if (bnlrSubject != null && !bnlrSubject.getLeaf()) {
                bnlrSubject = subjectService.loadFirstLeafChildren(bnlrSubject);
            }
            if (bnlrSubject == null) {
                r.put("status", "error");
                r.put("msg", "结转损益：未找到[本年利润]科目，请检查科目配置");
                return r;
            }

            // FN-FIX-CARRY-FORWARD-SIGNED-BALANCE：净利润用带符号余额单次扫描算，不按 balanceDirection 分桶
            //   贷方向（收入类）的 balance 直接贡献 +；借方向（费用类）的 balance 贡献 -；
            //   contra 子目（如借方向科目 balance<0）自然吸收为反向贡献，盈亏不再算反。
            BigDecimal lr = checkItems.stream()
                    .map(d -> "借".equals(d.getBalanceDirection())
                            ? d.getBalance().negate()  // 费用：自然方向余额取负贡献到 lr
                            : d.getBalance())          // 收入：自然方向余额直接贡献到 lr
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            // 生成结转损益凭证：损益冲销行（按余额符号定借贷）+ 本年利润配平塞子
            LocalDate voucherDate = LocalDate.of(year, month, 1).with(TemporalAdjusters.lastDayOfMonth());
            String word = resolveDefaultVoucherWord(accountSetId);
            int code = voucherService.loadCode(accountSetId, word, voucherDate);

            VoucherDto cfDto = buildCarryForwardVoucher(checkItems, bnlrSubject, word, code, voucherDate,
                    localCurrencyId, month, "结转损益");
            cfDto.setAccountSetsId(accountSetId);
            voucherService.save(cfDto, accountSets, true);

            checkoutService.updateStatus(accountSetId, year, month, 1);
            balanceCalcService.calc(accountSetId, YearMonth.of(year, month));
            log.info("结转损益凭证生成完成 accountSetId={} {}年{}月", accountSetId, year, month);

            // ── Step 2: 结转本年利润（仅 12 月，且盈利时）─────────────
            StringBuilder msg = new StringBuilder("结转损益：生成完成");
            if (tplMsg != null) msg.append("；").append(tplMsg);
            if (month == 12 && lr.compareTo(BigDecimal.ZERO) > 0) {
                String yearEndMsg = generateYearEndProfitTransfer(accountSetId, accountSets, year, month, lr, localCurrencyId);
                msg.append("；").append(yearEndMsg);
            } else if (month == 12) {
                msg.append("；结转本年利润：亏损状态，跳过自动结转");
            }
            r.put("status", "done");
            r.put("msg", msg.toString());
        } catch (Exception e) {
            log.error("结转损益凭证生成失败: accountSetId={} {}年{}月", accountSetId, year, month, e);
            r.put("status", "error");
            r.put("msg", "结转损益生成失败：" + e.getMessage());
        }
        return r;
    }

    // ── 私有方法 ────────────────────────────────────────────────

    /**
     * 自动执行结转模板生成凭证。
     * 执行顺序：结转销售成本 → 计提税金 → 结转未交增值税 → 计提所得税，在结转损益之前执行。
     * <p>
     * 生成规则：
     *   1. 结转销售成本：金额不为0时生成
     *   2. 计提税金：金额为负数（应计提）时生成
     *   3. 结转未交增值税：仅一般纳税人生成
     *   4. 计提所得税：金额为负数（应计提）时生成
     *   5. 结转本年利润：此处跳过，由 Step 2（仅12月）单独处理
     *
     * @return 执行结果描述，如果没有可执行的模板返回 null
     */
    private String generateCheckoutTemplateVouchers(Long accountSetId, AccountSets accountSets,
                                                      int year, int month, Long localCurrencyId) {
        List<CheckoutTemplateDto> templates = checkoutTemplateService.list(accountSetId);
        if (CollUtil.isEmpty(templates)) return null;

        Map<Long, Dict> showData = checkoutTemplateService.tplShowData(accountSetId, localCurrencyId, year, month);
        boolean isCommonlyVat = accountSets.getVatType() == cn.gson.financial.kernel.model.enums.VatType.COMMONLY;

        int generated = 0;
        List<String> msgs = new ArrayList<>();
        LocalDate voucherDate = LocalDate.of(year, month, 1).with(TemporalAdjusters.lastDayOfMonth());

        for (CheckoutTemplateDto tpl : templates) {
            String title = tpl.getTitle();
            if (title == null) continue;

            // ── 跳过判断 ──────────────────────────────────────────
            // 结转损益：内置逻辑处理（Step 1），不走模板
            if ("结转损益".equals(title)) continue;
            // 结转本年利润：仅12月在 Step 2 单独处理
            if (title.contains("本年利润")) continue;

            Dict tplDict = showData.get(tpl.getId());
            if (tplDict == null) continue;

            // 已有凭证则跳过
            if (tplDict.containsKey("voucherId") && tplDict.get("voucherId") != null) {
                log.info("结转模板[{}]已有凭证，跳过", title);
                continue;
            }

            @SuppressWarnings("unchecked")
            List<CheckoutTemplate.CtItem> items = (List<CheckoutTemplate.CtItem>) tplDict.get("items");
            BigDecimal amount = tplDict.getBigDecimal("amount");

            if (CollUtil.isEmpty(items) || amount == null || amount.compareTo(BigDecimal.ZERO) == 0) {
                continue;
            }

            // ── 按模板类型判断是否应生成 ──────────────────────────
            boolean shouldGenerate;
            if (title.contains("结转") && title.contains("增值税")) {
                // 结转未交增值税：仅一般纳税人
                shouldGenerate = isCommonlyVat;
                if (!shouldGenerate) log.info("结转模板[{}]跳过：小规模纳税人无需结转增值税", title);
            } else if (title.contains("计提")) {
                // 计提税金、计提所得税：金额不为负数时生成凭证
                shouldGenerate = amount.compareTo(BigDecimal.ZERO) >= 0;
                if (!shouldGenerate) log.info("结转模板[{}]跳过：金额={} 为负数，不生成", title, amount);
            } else {
                // 结转销售成本等：金额不为0即生成
                shouldGenerate = true;
            }

            if (!shouldGenerate) continue;

            // ── 生成凭证 ──────────────────────────────────────────
            try {
                String word = resolveVoucherWordForTemplate(accountSetId, tpl.getVoucherWordId());
                int code = voucherService.loadCode(accountSetId, word, voucherDate);
                String summary = String.format("第%d期 %s", month, title);

                List<VoucherDetailsDto> details = new ArrayList<>();
                BigDecimal totalDebit = BigDecimal.ZERO;
                BigDecimal totalCredit = BigDecimal.ZERO;

                for (CheckoutTemplate.CtItem item : items) {
                    BigDecimal itemAmt = item.getAmount().setScale(2, java.math.RoundingMode.HALF_UP);
                    if (itemAmt.compareTo(BigDecimal.ZERO) == 0) continue;

                    // 非末级科目自动降级到第一个末级子科目
                    Long subjectId = item.getSubjectId();
                    Subject subject = subjectService.getById(
                            new cn.gson.financial.kernel.model.hibernate.PrimaryKey(subjectId, accountSetId));
                    if (subject != null && !subject.getLeaf()) {
                        Subject leaf = subjectService.loadFirstLeafChildren(subject);
                        if (leaf != null) {
                            subjectId = leaf.getId();
                            log.info("结转模板[{}]科目降级: {} → {} ({})",
                                    title, subject.getCode(), leaf.getCode(), leaf.getName());
                        }
                    }

                    VoucherDetailsDto d = new VoucherDetailsDto();
                    d.setSubjectId(subjectId);
                    d.setSummary(summary);
                    d.setCurrencyId(localCurrencyId);
                    if ("贷".equals(item.getBalanceDirection())) {
                        d.setCreditAmount(itemAmt);
                        totalCredit = totalCredit.add(itemAmt);
                    } else {
                        d.setDebitAmount(itemAmt);
                        totalDebit = totalDebit.add(itemAmt);
                    }
                    details.add(d);
                }

                // 尾差修正：给金额少的一方最大项加差额
                BigDecimal diff = totalDebit.subtract(totalCredit);
                if (diff.abs().compareTo(BigDecimal.ZERO) != 0
                        && diff.abs().compareTo(new BigDecimal("0.1")) <= 0) {
                    String adjustDir = diff.compareTo(BigDecimal.ZERO) > 0 ? "贷" : "借";
                    BigDecimal absDiff = diff.abs();
                    details.stream()
                            .filter(d -> adjustDir.equals("借") ? d.getDebitAmount() != null : d.getCreditAmount() != null)
                            .max((a, b) -> {
                                BigDecimal amtA = "借".equals(adjustDir) ? a.getDebitAmount() : a.getCreditAmount();
                                BigDecimal amtB = "借".equals(adjustDir) ? b.getDebitAmount() : b.getCreditAmount();
                                return amtA.compareTo(amtB);
                            })
                            .ifPresent(d -> {
                                if ("借".equals(adjustDir)) {
                                    d.setDebitAmount(d.getDebitAmount().add(absDiff));
                                } else {
                                    d.setCreditAmount(d.getCreditAmount().add(absDiff));
                                }
                            });
                    if ("借".equals(adjustDir)) totalDebit = totalDebit.add(absDiff);
                    else totalCredit = totalCredit.add(absDiff);
                    log.info("结转模板[{}]尾差修正: {}", title, diff);
                }

                VoucherDto dto = new VoucherDto();
                dto.setWord(word);
                dto.setCode(code);
                dto.setVoucherDate(voucherDate);
                dto.setCarryForward(true);
                dto.setCheckTplId(tpl.getId());
                dto.setAccountSetsId(accountSetId);
                dto.setDebitAmount(totalDebit);
                dto.setCreditAmount(totalCredit);
                dto.setDetails(details);

                voucherService.save(dto, accountSets, true);
                generated++;
                log.info("结转模板[{}]凭证生成完成 accountSetId={} 金额={}", title, accountSetId, amount);

            } catch (Exception e) {
                log.error("结转模板[{}]凭证生成失败: accountSetId={}", title, accountSetId, e);
                msgs.add(title + "失败:" + e.getMessage());
            }
        }

        if (generated > 0) {
            balanceCalcService.calc(accountSetId, YearMonth.of(year, month));
        }

        if (generated == 0 && msgs.isEmpty()) return null;
        String result = "结转模板：生成 " + generated + " 张凭证";
        if (!msgs.isEmpty()) result += "，异常：" + String.join("；", msgs);
        return result;
    }

    /**
     * 根据 ProfitCheckDto 列表 + 本年利润科目构建结转凭证 DTO。
     * <p>FN-FIX-CARRY-FORWARD-SIGNED-BALANCE：按"余额符号"定借贷、金额取绝对值。
     * <p>
     * 算法（每个损益科目 item）：
     * <pre>
     *   natDebit     = "借".equals(item.balanceDirection)   // 科目自然方向
     *   amt          = |item.balance|                        // 永远入正数
     *   balOnNatural = item.balance > 0                      // 余额在自然方向
     *   creditSide   = balOnNatural ? natDebit : !natDebit   // 冲销记到余额实际所在方向的反向
     * </pre>
     * <ul>
     *   <li>普通费用（借方向、balance > 0）：creditSide=true → 记贷方，平掉借方余额</li>
     *   <li>contra 费用（借方向、balance &lt; 0，如 财务费用_利息收入）：creditSide=false → 记借方，平掉贷方余额</li>
     *   <li>普通收入（贷方向、balance > 0）：creditSide=false → 记借方，平掉贷方余额</li>
     *   <li>contra 收入（贷方向、balance &lt; 0，少见）：creditSide=true → 记贷方，平掉借方余额</li>
     * </ul>
     * <p>本年利润行：取所有损益冲销行结束后的借贷差额作为「配平塞子」：
     * <pre>
     *   diff = totalDebit - totalCredit
     *   diff > 0 → 本年利润记贷方 diff（盈利）
     *   diff &lt; 0 → 本年利润记借方 |diff|（亏损）
     *   diff = 0 → 不出本年利润行（极少见，仅 lr=0 时）
     * </pre>
     */
    /** package-private 让单测可直接调（避免必须走 execute 全链路） */
    VoucherDto buildCarryForwardVoucher(List<ProfitCheckDto> items,
                                          Subject bnlrSubject,
                                          String word, int code, LocalDate voucherDate,
                                          Long currencyId, int month, String summaryPrefix) {
        List<VoucherDetailsDto> itemDetails = new ArrayList<>();
        BigDecimal totalDebit = BigDecimal.ZERO;
        BigDecimal totalCredit = BigDecimal.ZERO;
        String summary = String.format("第%d期 %s", month, summaryPrefix);

        // ── 损益冲销行 ────────────────────────────────────────────
        for (ProfitCheckDto item : items) {
            BigDecimal bal = item.getBalance();
            if (bal == null || bal.compareTo(BigDecimal.ZERO) == 0) continue;

            boolean natDebit     = "借".equals(item.getBalanceDirection()); // 科目自然方向
            BigDecimal amt       = bal.abs();                               // 永远入正数，杜绝负数分录
            boolean balOnNatural = bal.signum() > 0;                        // >0 在自然方向；<0 = contra
            boolean creditSide   = balOnNatural ? natDebit : !natDebit;     // 冲销记到余额实际所在方向的反向

            VoucherDetailsDto d = new VoucherDetailsDto();
            d.setSubjectId(item.getSubjectId());
            d.setSummary(summary);
            d.setCurrencyId(currencyId);
            if (CollUtil.isNotEmpty(item.getAuxiliary())) {
                d.setAuxiliary(item.getAuxiliary());
            }
            if (creditSide) {
                d.setCreditAmount(amt);
                totalCredit = totalCredit.add(amt);
            } else {
                d.setDebitAmount(amt);
                totalDebit = totalDebit.add(amt);
            }
            itemDetails.add(d);
        }

        // ── 本年利润配平塞子 ───────────────────────────────────────
        BigDecimal diff = totalDebit.subtract(totalCredit);
        VoucherDetailsDto bnLrRow = null;
        if (diff.signum() != 0) {
            bnLrRow = new VoucherDetailsDto();
            bnLrRow.setSubjectId(bnlrSubject.getId());
            bnLrRow.setSummary(summary);
            bnLrRow.setCurrencyId(currencyId);
            if (diff.signum() > 0) {
                // 借方多，本年利润记贷方（盈利）
                bnLrRow.setCreditAmount(diff);
                totalCredit = totalCredit.add(diff);
            } else {
                // 贷方多，本年利润记借方（亏损）
                BigDecimal absDiff = diff.negate();
                bnLrRow.setDebitAmount(absDiff);
                totalDebit = totalDebit.add(absDiff);
            }
        }

        // 合并分录：本年利润在首位（与历史显示顺序保持一致），其后是损益冲销行
        List<VoucherDetailsDto> details = new ArrayList<>();
        if (bnLrRow != null) details.add(bnLrRow);
        details.addAll(itemDetails);

        VoucherDto dto = new VoucherDto();
        dto.setWord(word);
        dto.setCode(code);
        dto.setVoucherDate(voucherDate);
        dto.setCarryForward(true);
        dto.setDebitAmount(totalDebit);
        dto.setCreditAmount(totalCredit);
        dto.setDetails(details);
        return dto;
    }

    /**
     * 结转本年利润（年末 12 月专用）。
     * 根据净利润正负选择对应模板（标题含"盈利"或"亏损"），
     * 若未配置模板则提示用户手动操作。
     *
     * @return 操作结果描述文字
     */
    private String generateYearEndProfitTransfer(Long accountSetId, AccountSets accountSets,
                                                   int year, int month,
                                                   BigDecimal lr, Long localCurrencyId) {
        try {
            List<CheckoutTemplateDto> templates = checkoutTemplateService.list(accountSetId);
            if (CollUtil.isEmpty(templates)) {
                return "结转本年利润：未配置模板，请在[期末结转]模块手动操作";
            }

            // 按净利润正负选择匹配模板（标题需含"本年利润"，且含"盈利"或"亏损"）
            String keyword = lr.compareTo(BigDecimal.ZERO) >= 0 ? "盈利" : "亏损";
            CheckoutTemplateDto target = templates.stream()
                    .filter(t -> t.getTitle() != null
                            && t.getTitle().contains("本年利润")
                            && t.getTitle().contains(keyword))
                    .findFirst().orElse(null);

            if (target == null) {
                // 兜底：只要含"本年利润"
                target = templates.stream()
                        .filter(t -> t.getTitle() != null && t.getTitle().contains("本年利润"))
                        .findFirst().orElse(null);
            }
            if (target == null) {
                return "结转本年利润：未找到含[本年利润]的模板，请在[期末结转]模块手动操作";
            }

            // 通过 tplShowData 获取含计算金额的模板项
            Map<Long, Dict> showData = checkoutTemplateService.tplShowData(accountSetId, localCurrencyId, year, month);
            Dict tplDict = showData.get(target.getId());
            if (tplDict == null) {
                return "结转本年利润：模板[" + target.getTitle() + "]计算失败，请手动操作";
            }

            @SuppressWarnings("unchecked")
            List<CheckoutTemplate.CtItem> items = (List<CheckoutTemplate.CtItem>) tplDict.get("items");
            BigDecimal amount = tplDict.getBigDecimal("amount");

            if (CollUtil.isEmpty(items) || amount == null || amount.compareTo(BigDecimal.ZERO) == 0) {
                return "结转本年利润：金额为零，跳过（净利润=" + lr.toPlainString() + "）";
            }

            // 凭证字
            String word = resolveVoucherWordForTemplate(accountSetId, target.getVoucherWordId());
            LocalDate voucherDate = LocalDate.of(year, month, 1).with(TemporalAdjusters.lastDayOfMonth());
            int code = voucherService.loadCode(accountSetId, word, voucherDate);

            // 构建明细：此处 CtItem.balanceDirection 表示【分录记账方向】（与 ProfitCheckDto 不同）
            // balanceDirection == "贷" → setCreditAmount（直接贷记）；否则 → setDebitAmount（直接借记）
            List<VoucherDetailsDto> details = new ArrayList<>();
            BigDecimal totalDebit = BigDecimal.ZERO;
            BigDecimal totalCredit = BigDecimal.ZERO;
            String summary = String.format("第%d期 %s", month, target.getTitle());

            for (CheckoutTemplate.CtItem item : items) {
                if (item.getAmount().compareTo(BigDecimal.ZERO) == 0) continue;
                VoucherDetailsDto d = new VoucherDetailsDto();
                d.setSubjectId(item.getSubjectId());
                d.setSummary(summary);
                d.setCurrencyId(localCurrencyId);
                if ("贷".equals(item.getBalanceDirection())) {
                    d.setCreditAmount(item.getAmount());
                    totalCredit = totalCredit.add(item.getAmount());
                } else {
                    d.setDebitAmount(item.getAmount());
                    totalDebit = totalDebit.add(item.getAmount());
                }
                details.add(d);
            }

            VoucherDto dto = new VoucherDto();
            dto.setWord(word);
            dto.setCode(code);
            dto.setVoucherDate(voucherDate);
            dto.setCarryForward(true);
            dto.setCheckTplId(target.getId());
            dto.setAccountSetsId(accountSetId);
            dto.setDebitAmount(totalDebit);
            dto.setCreditAmount(totalCredit);
            dto.setDetails(details);

            voucherService.save(dto, accountSets, true);
            balanceCalcService.calc(accountSetId, YearMonth.of(year, month));
            log.info("结转本年利润凭证生成完成 accountSetId={} {}年{} 模板={}", accountSetId, year, keyword, target.getTitle());
            return "结转本年利润（" + keyword + "）：生成完成";

        } catch (Exception e) {
            log.error("结转本年利润凭证生成失败: accountSetId={}", accountSetId, e);
            return "结转本年利润：生成失败（" + e.getMessage() + "），请手动操作";
        }
    }

    /**
     * 获取账套默认凭证字（取第一个，兜底用"记"）。
     */
    private String resolveDefaultVoucherWord(Long accountSetId) {
        VoucherWord vw = bqf.selectFrom(QVoucherWord.voucherWord)
                .where(QVoucherWord.voucherWord.accountSetsId.eq(accountSetId))
                .orderBy(QVoucherWord.voucherWord.id.asc())
                .fetchFirst();
        return vw != null ? vw.getWord() : "记";
    }

    private String resolveVoucherWordForTemplate(Long accountSetId, Long voucherWordId) {
        if (voucherWordId == null) return resolveDefaultVoucherWord(accountSetId);
        VoucherWord vw = bqf.selectFrom(QVoucherWord.voucherWord)
                .where(QVoucherWord.voucherWord.id.eq(voucherWordId)
                        .and(QVoucherWord.voucherWord.accountSetsId.eq(accountSetId)))
                .fetchFirst();
        return vw != null ? vw.getWord() : resolveDefaultVoucherWord(accountSetId);
    }
}
