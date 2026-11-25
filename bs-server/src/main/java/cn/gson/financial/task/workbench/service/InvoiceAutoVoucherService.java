package cn.gson.financial.task.workbench.service;

import cn.gson.financial.kernel.model.dto.CurrencyDto;
import cn.gson.financial.kernel.model.dto.VoucherDetailsDto;
import cn.gson.financial.kernel.model.dto.VoucherDto;
import cn.gson.financial.kernel.model.entity.*;
import cn.gson.financial.kernel.model.enums.InvoiceType;
import cn.gson.financial.kernel.model.enums.VatType;
import cn.gson.financial.kernel.model.hibernate.BlazeJPAQueryFactory;
import cn.gson.financial.kernel.model.vo.SubjectVo;
import cn.gson.financial.kernel.service.*;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.dashscope.aigc.generation.Generation;
import com.alibaba.dashscope.aigc.generation.GenerationParam;
import com.alibaba.dashscope.aigc.generation.GenerationResult;
import com.alibaba.dashscope.common.Message;
import com.alibaba.dashscope.common.Role;
import com.alibaba.fastjson.JSONArray;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 任务工作台 — 发票凭证自动生成服务
 * <p>
 * 三步流程：
 * 1. 关键词匹配业务类型（invoiceBusinessTypeService.batchChangeBusinessType）
 * 2. Qwen AI 匹配剩余未匹配发票（生成业务类型+凭证模板）
 * 3. 按业务类型模板批量生成凭证
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InvoiceAutoVoucherService extends AbsService {

    @Resource
    @Lazy
    protected BlazeJPAQueryFactory bqf;

    @Resource
    @Lazy
    private InvoiceAutoVoucherService self; // 自注入，确保 @Transactional(REQUIRES_NEW) 走代理

    private final InvoiceService invoiceService;
    private final InvoiceBusinessTypeService invoiceBusinessTypeService;
    private final CurrencyService currencyService;
    private final VoucherService voucherService;
    private final BalanceCalcService balanceCalcService;
    private final SubjectService subjectService;

    @Value("${qwen.api-key:}")
    private String qwenApiKey;

    private static final String QWEN_MODEL = "qwen-plus";
    private static final int MAX_AI_RETRY = 3;

    private static final QAccountSets qAccountSets = QAccountSets.accountSets;
    private static final QInvoice qInvoice = QInvoice.invoice;
    private static final QSubject qSubject = QSubject.subject;
    private static final QInvoiceBusinessType qInvoiceBusinessType = QInvoiceBusinessType.invoiceBusinessType;
    private static final QInvoiceItems qInvoiceItems = QInvoiceItems.invoiceItems;

    /**
     * 统一的发票期间过滤条件：assignmentDate 优先，为空时 fallback 到 tradeDate。
     * <p>FN-FIX-OPENAPI-INVOICE-PERIOD-ASSIGNMENT-DATE：口径收敛到共享 helper
     * {@link cn.gson.financial.kernel.model.query.InvoicePeriodPredicate}，对内做账与对外 openapi 单一真相源。
     */
    private com.querydsl.core.types.dsl.BooleanExpression invoicePeriodCondition(LocalDate start, LocalDate end) {
        return cn.gson.financial.kernel.model.query.InvoicePeriodPredicate.byPeriod(qInvoice, start, end);
    }

    // ═══════════════════════════════════════════════════════════════════
    // 主入口
    // ═══════════════════════════════════════════════════════════════════

    public Map<String, Object> autoMatchAndGenerateVouchers(Long accountSetId, YearMonth yearMonth) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("type", "invoice");
        boolean wasCheckedOut = false;
        try {
            // 检查期间是否已结账，已结账则临时解锁
            wasCheckedOut = isPeriodCheckedOut(accountSetId, yearMonth);
            if (wasCheckedOut) {
                log.info("期间 {} 已结账，临时解锁以生成发票凭证 accountSetId={}", yearMonth, accountSetId);
                self.setCheckoutStatus(accountSetId, yearMonth, 1);
            }

            LocalDate periodStart = yearMonth.atDay(1);
            LocalDate periodEnd = yearMonth.atEndOfMonth();

            AccountSets accountSets = bqf.selectFrom(qAccountSets)
                    .where(qAccountSets.id.eq(accountSetId)).fetchFirst();
            if (accountSets == null) {
                r.put("status", "error");
                r.put("msg", "账套信息不存在");
                return r;
            }

            // 诊断：统计当期发票的各种状态
            var periodCond = invoicePeriodCondition(periodStart, periodEnd);
            for (InvoiceType dt : new InvoiceType[]{InvoiceType.IN, InvoiceType.OUT}) {
                long total = bqf.selectFrom(qInvoice).where(qInvoice.accountSetsId.eq(accountSetId)
                        .and(qInvoice.type.eq(dt))
                        .and(periodCond)).fetchCount();
                long noType = bqf.selectFrom(qInvoice).where(qInvoice.accountSetsId.eq(accountSetId)
                        .and(qInvoice.type.eq(dt))
                        .and(periodCond)
                        .and(qInvoice.invoiceBusinessTypeId.isNull())).fetchCount();
                long noVoucher = bqf.selectFrom(qInvoice).where(qInvoice.accountSetsId.eq(accountSetId)
                        .and(qInvoice.type.eq(dt))
                        .and(periodCond)
                        .and(qInvoice.invoiceBusinessTypeId.isNotNull())
                        .and(qInvoice.voucherId.isNull())).fetchCount();
                long done = bqf.selectFrom(qInvoice).where(qInvoice.accountSetsId.eq(accountSetId)
                        .and(qInvoice.type.eq(dt))
                        .and(periodCond)
                        .and(qInvoice.voucherId.isNotNull())).fetchCount();
                log.info("发票诊断 accountSetId={} 账期={} type={}: 总计={}, 未匹配类型={}, 已匹配未生成凭证={}, 已完成={}",
                        accountSetId, yearMonth, dt, total, noType, noVoucher, done);
            }

            // ── Step 1: 关键词匹配业务类型 ──────────────────────────
            int matchedIn = self.matchBusinessType(accountSetId, periodStart, periodEnd, InvoiceType.IN);
            int matchedOut = self.matchBusinessType(accountSetId, periodStart, periodEnd, InvoiceType.OUT);
            log.info("关键词匹配完成 accountSetId={} 账期={} 进项:{} 销项:{}",
                    accountSetId, yearMonth, matchedIn, matchedOut);

            // ── Step 2: Qwen AI 匹配剩余未匹配的发票 ────────────────
            int aiMatchedIn = aiMatchUnmatchedInvoices(accountSetId, accountSets, periodStart, periodEnd, InvoiceType.IN);
            int aiMatchedOut = aiMatchUnmatchedInvoices(accountSetId, accountSets, periodStart, periodEnd, InvoiceType.OUT);
            log.info("AI匹配完成 accountSetId={} 账期={} 进项:{} 销项:{}",
                    accountSetId, yearMonth, aiMatchedIn, aiMatchedOut);

            // ── Step 3: 按模板生成凭证 ──────────────────────────────
            CurrencyDto localCurrency = currencyService.fetchLocal(accountSetId);
            Map<Long, Subject> subjectMap = buildSubjectMap(accountSetId);
            LocalDate voucherDate = yearMonth.atEndOfMonth();

            int successCount = 0, failCount = 0, skipCount = 0;

            for (InvoiceType type : new InvoiceType[]{InvoiceType.IN, InvoiceType.OUT}) {
                List<Invoice> invoices = queryInvoicesForVoucherGeneration(accountSetId, yearMonth, type);
                log.info("Step3 凭证生成查询: type={}, 待生成凭证发票数={}", type, invoices.size());
                if (CollUtil.isEmpty(invoices)) continue;

                Map<Long, InvoiceBusinessType> btMap = buildBusinessTypeMap(accountSetId, type);
                log.info("Step3 业务类型模板数: type={}, count={}", type, btMap.size());
                Map<Long, Integer> voucherCodeMap = new HashMap<>();

                for (Invoice invoice : invoices) {
                    Long btId = invoice.getInvoiceBusinessTypeId();
                    if (btId == null) {
                        skipCount++;
                        log.debug("跳过: invoiceId={}, 原因=invoiceBusinessTypeId为空", invoice.getId());
                        continue;
                    }
                    InvoiceBusinessType bt = btMap.get(btId);
                    if (bt == null) {
                        skipCount++;
                        log.warn("跳过: invoiceId={}, 原因=业务类型不存在 btId={}", invoice.getId(), btId);
                        continue;
                    }
                    if (CollUtil.isEmpty(bt.getItems())) {
                        skipCount++;
                        log.warn("跳过: invoiceId={}, 原因=业务类型[{}]凭证模板为空", invoice.getId(), bt.getTitle());
                        continue;
                    }

                    Long vwId = bt.getVoucherWord();
                    String word = resolveVoucherWord(accountSetId, vwId);
                    Integer code = voucherCodeMap.get(vwId);
                    if (code == null) {
                        code = voucherService.loadCode(accountSetId, word, voucherDate);
                    } else {
                        code++;
                    }
                    voucherCodeMap.put(vwId, code);

                    log.info("生成凭证: invoiceId={}, businessType={}, word={}, code={}, amount={}",
                            invoice.getId(), bt.getTitle(), word, code, invoice.getAmount());
                    try {
                        boolean ok = self.generateVoucherForInvoice(invoice, bt, subjectMap, localCurrency,
                                word, code, voucherDate, accountSets, accountSetId);
                        if (ok) {
                            successCount++;
                            log.info("凭证生成成功: invoiceId={}", invoice.getId());
                        } else {
                            failCount++;
                            log.warn("凭证生成失败: invoiceId={} businessType={}", invoice.getId(), bt.getTitle());
                        }
                    } catch (Exception e) {
                        failCount++;
                        log.error("凭证生成异常: invoiceId={} businessType={}: {}",
                                invoice.getId(), bt.getTitle(), e.getMessage());
                    }
                }
            }

            if (successCount > 0) {
                balanceCalcService.calcSynchronization(accountSetId, yearMonth);
            }

            String status = failCount > 0 ? "warn" : "done";
            String msg = String.format("发票凭证：关键词匹配 %d 张，AI匹配 %d 张，生成凭证 %d 张，失败 %d 张，跳过 %d 张",
                    matchedIn + matchedOut, aiMatchedIn + aiMatchedOut, successCount, failCount, skipCount);
            r.put("status", status);
            r.put("generated", successCount);
            r.put("msg", msg);
            log.info("发票凭证汇总: accountSetId={}, {}", accountSetId, msg);

        } catch (Exception e) {
            log.error("发票凭证自动生成失败: accountSetId={}", accountSetId, e);
            r.put("status", "error");
            r.put("msg", "发票凭证生成失败：" + e.getMessage());
        } finally {
            // 无论成功还是异常，确保结账状态恢复（防止永久解锁）
            if (wasCheckedOut) {
                try {
                    self.setCheckoutStatus(accountSetId, yearMonth, 2);
                    log.info("期间 {} 结账状态已恢复 accountSetId={}", yearMonth, accountSetId);
                } catch (Exception e) {
                    log.error("恢复结账状态失败: accountSetId={}, yearMonth={}", accountSetId, yearMonth, e);
                }
            }
        }
        return r;
    }

    // ═══════════════════════════════════════════════════════════════════
    // Step 1: 关键词匹配
    // ═══════════════════════════════════════════════════════════════════

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int matchBusinessType(Long accountSetId, LocalDate periodStart, LocalDate periodEnd, InvoiceType type) {
        List<Long> ids = bqf.selectFrom(qInvoice)
                .select(qInvoice.id)
                .where(qInvoice.accountSetsId.eq(accountSetId)
                        .and(qInvoice.type.eq(type))
                        .and(invoicePeriodCondition(periodStart, periodEnd))
                        .and(qInvoice.invoiceBusinessTypeId.isNull()))
                .fetch();
        if (CollUtil.isEmpty(ids)) {
            log.info("Step1 关键词匹配: type={}, 无未匹配发票", type);
            return 0;
        }
        log.info("Step1 关键词匹配: type={}, 待匹配发票数={}", type, ids.size());
        var result = invoiceBusinessTypeService.batchChangeBusinessType(ids, type, accountSetId);
        if (result.isSuccess()) {
            @SuppressWarnings("unchecked")
            List<Long> unmatched = (List<Long>) result.getData();
            int matched = ids.size() - (unmatched != null ? unmatched.size() : 0);
            log.info("Step1 关键词匹配结果: type={}, 成功={}, 未匹配={}", type, matched, unmatched != null ? unmatched.size() : 0);
            return matched;
        }
        log.warn("Step1 关键词匹配失败: type={}", type);
        return 0;
    }

    // ═══════════════════════════════════════════════════════════════════
    // Step 2: Qwen AI 匹配未匹配的发票
    // ═══════════════════════════════════════════════════════════════════

    private int aiMatchUnmatchedInvoices(Long accountSetId, AccountSets accountSets,
                                          LocalDate periodStart, LocalDate periodEnd, InvoiceType type) {
        // 查询仍未匹配的发票（有商品明细的）
        var periodCond = invoicePeriodCondition(periodStart, periodEnd);
        List<Invoice> unmatchedInvoices = bqf.selectFrom(qInvoice)
                .where(qInvoice.accountSetsId.eq(accountSetId)
                        .and(qInvoice.type.eq(type))
                        .and(periodCond)
                        .and(qInvoice.invoiceBusinessTypeId.isNull())
                        .and(qInvoice.haveDetail.isTrue()))
                .fetch();

        // 也查一下没有商品明细的未匹配发票数量（这些不会进入AI匹配）
        long noDetailCount = bqf.selectFrom(qInvoice)
                .where(qInvoice.accountSetsId.eq(accountSetId)
                        .and(qInvoice.type.eq(type))
                        .and(periodCond)
                        .and(qInvoice.invoiceBusinessTypeId.isNull())
                        .and(qInvoice.haveDetail.ne(true)))
                .fetchCount();

        if (CollUtil.isEmpty(unmatchedInvoices)) {
            if (noDetailCount > 0) {
                log.info("Step2 AI匹配: type={}, 无可匹配发票（有{}张无商品明细，无法AI匹配）", type, noDetailCount);
            } else {
                log.info("Step2 AI匹配: type={}, 无未匹配发票", type);
            }
            return 0;
        }

        log.info("Step2 AI匹配开始: accountSetId={}, type={}, 有明细待匹配={}, 无明细跳过={}",
                accountSetId, type, unmatchedInvoices.size(), noDetailCount);

        // 获取科目信息
        List<SubjectVo> subjects = subjectService.list(accountSetId);

        // 查找往来科目（应付/应收）
        String txSubjectName = (type == InvoiceType.IN) ? "应付账款" : "应收账款";
        Subject txSubject = bqf.selectFrom(qSubject)
                .where(qSubject.accountSetsId.eq(accountSetId)
                        .and(qSubject.fullName.eq(txSubjectName)))
                .fetchFirst();

        // 获取默认凭证字
        VoucherWord defaultVW = bqf.selectFrom(QVoucherWord.voucherWord)
                .where(QVoucherWord.voucherWord.accountSetsId.eq(accountSetId))
                .orderBy(QVoucherWord.voucherWord.isDefault.desc(), QVoucherWord.voucherWord.id.asc())
                .fetchFirst();

        int matchedCount = 0;

        for (Invoice invoice : unmatchedInvoices) {
            try {
                boolean ok = aiMatchSingleInvoice(invoice, accountSets, subjects, txSubject, defaultVW, type);
                if (ok) matchedCount++;
            } catch (Exception e) {
                log.warn("AI匹配发票失败: invoiceId={}, error={}", invoice.getId(), e.getMessage());
            }
        }

        log.info("AI匹配完成: type={}, 成功={}/{}", type, matchedCount, unmatchedInvoices.size());
        return matchedCount;
    }

    /**
     * 用 Qwen AI 为单张发票生成业务类型和凭证模板。
     */
    private boolean aiMatchSingleInvoice(Invoice invoice, AccountSets accountSets,
                                          List<SubjectVo> subjects, Subject txSubject,
                                          VoucherWord defaultVW, InvoiceType type) {
        Long invoiceId = invoice.getId();

        // 查询发票商品明细
        List<InvoiceItems> items = bqf.selectFrom(qInvoiceItems)
                .where(qInvoiceItems.invoiceId.eq(invoiceId))
                .fetch();
        if (CollUtil.isEmpty(items)) {
            log.warn("AI匹配跳过: invoiceId={}, 原因=无商品明细", invoiceId);
            return false;
        }

        String customerName = invoice.getCustomerName();
        if (StringUtils.isBlank(customerName)) {
            log.warn("AI匹配跳过: invoiceId={}, 原因=无客户/供应商名称", invoiceId);
            return false;
        }

        VatType vatType = accountSets.getVatType();
        BigDecimal taxExcluded = invoice.getTaxAmountExcluded() != null ? invoice.getTaxAmountExcluded() : BigDecimal.ZERO;
        BigDecimal taxAmount = invoice.getTaxAmount() != null ? invoice.getTaxAmount() : BigDecimal.ZERO;
        BigDecimal amount = invoice.getAmount() != null ? invoice.getAmount() : BigDecimal.ZERO;

        String goodsNames = items.stream().map(InvoiceItems::getGoodsName)
                .filter(StringUtils::isNotBlank).collect(Collectors.joining(", "));
        log.info("AI匹配发票: invoiceId={}, type={}, vatType={}, customer={}, goods=[{}], amount={}",
                invoiceId, type, vatType, customerName, goodsNames, amount);

        String lastError = null;

        for (int attempt = 0; attempt < MAX_AI_RETRY; attempt++) {
            try {
                String prompt = buildAiPrompt(invoice, accountSets, items, subjects, type,
                        vatType, taxExcluded, taxAmount, amount, lastError);
                log.debug("AI匹配 prompt长度={}, attempt={}", prompt.length(), attempt + 1);

                String response = callQwen(prompt);
                if (StringUtils.isBlank(response)) {
                    lastError = "AI返回空内容";
                    log.warn("AI匹配 attempt {}: invoiceId={}, AI返回空内容", attempt + 1, invoiceId);
                    continue;
                }

                String cleaned = cleanJson(response);
                log.info("AI匹配 attempt {}: invoiceId={}, AI返回={}", attempt + 1, invoiceId,
                        cleaned.length() > 500 ? cleaned.substring(0, 500) + "..." : cleaned);

                com.alibaba.fastjson.JSONObject json = com.alibaba.fastjson.JSON.parseObject(cleaned);
                String businessTypeName = json.getString("businessTypeName");
                com.alibaba.fastjson.JSONArray entries = json.getJSONArray("accountingEntries");

                if (StringUtils.isBlank(businessTypeName) || entries == null || entries.isEmpty()) {
                    lastError = "AI返回的businessTypeName或accountingEntries为空，原始返回: " + cleaned.substring(0, Math.min(200, cleaned.length()));
                    log.warn("AI匹配 attempt {}: invoiceId={}, {}", attempt + 1, invoiceId, lastError);
                    continue;
                }

                log.info("AI匹配 attempt {}: invoiceId={}, businessType={}, entries数量={}",
                        attempt + 1, invoiceId, businessTypeName, entries.size());

                // 解析并验证分录
                List<InvoiceBusinessType.InvoiceVoucherTemplateItems> templateItems = new ArrayList<>();
                List<String> errors = new ArrayList<>();

                for (int i = 0; i < entries.size(); i++) {
                    com.alibaba.fastjson.JSONObject entry = entries.getJSONObject(i);
                    String subjectCode = entry.getString("subjectCode");
                    String subjectName = entry.getString("subjectName");
                    String direction = entry.getString("direction");
                    String valueField = entry.getString("valueField");

                    log.debug("AI匹配 验证分录[{}]: code={}, name={}, direction={}, valueField={}",
                            i, subjectCode, subjectName, direction, valueField);

                    if (!"借".equals(direction)) {
                        errors.add("分录[" + i + "]方向必须为借，收到: " + direction);
                        continue;
                    }

                    // 验证 valueField
                    if (vatType == VatType.SMALL_SCALE) {
                        if (!"amount".equals(valueField)) {
                            errors.add("分录[" + i + "]小规模纳税人valueField必须为amount，收到: " + valueField);
                            continue;
                        }
                    } else {
                        if (!"taxAmountExcluded".equals(valueField) && !"taxAmount".equals(valueField)) {
                            errors.add("分录[" + i + "]一般纳税人valueField必须为taxAmountExcluded或taxAmount，收到: " + valueField);
                            continue;
                        }
                    }

                    // 查找科目
                    Subject subject = findSubject(accountSets.getId(), subjectCode, subjectName, subjects);
                    if (subject == null) {
                        errors.add("分录[" + i + "]科目不存在: code=" + subjectCode + ", name=" + subjectName);
                        continue;
                    }

                    log.debug("AI匹配 分录[{}]验证通过: {} {} → {}", i, subjectCode, subjectName, subject.getCode());

                    InvoiceBusinessType.InvoiceVoucherTemplateItems item = new InvoiceBusinessType.InvoiceVoucherTemplateItems();
                    item.setSubjectTitle(subject.getCode() + " " + subject.getFullName());
                    item.setSubjectId(subject.getId().toString());
                    item.setDirection("借");
                    item.setValueField(valueField);
                    templateItems.add(item);
                }

                if (!errors.isEmpty()) {
                    lastError = String.join("; ", errors);
                    log.warn("AI匹配 attempt {}: invoiceId={}, 验证失败: {}", attempt + 1, invoiceId, lastError);
                    continue;
                }

                // 系统自动添加贷方（往来科目）
                if (txSubject != null) {
                    InvoiceBusinessType.InvoiceVoucherTemplateItems creditItem = new InvoiceBusinessType.InvoiceVoucherTemplateItems();
                    creditItem.setSubjectTitle(txSubject.getCode() + " " + txSubject.getFullName());
                    creditItem.setSubjectId(txSubject.getId().toString());
                    creditItem.setDirection("贷");
                    creditItem.setValueField("amount");
                    templateItems.add(creditItem);
                    log.debug("AI匹配 系统补充贷方: {} {}", txSubject.getCode(), txSubject.getFullName());
                } else {
                    log.warn("AI匹配: invoiceId={}, 往来科目不存在，无法添加贷方", invoiceId);
                }

                // 验证模板条目数量
                int expectedMin = (vatType == VatType.SMALL_SCALE) ? 2 : 3;
                if (templateItems.size() < expectedMin) {
                    lastError = "模板条目不足，期望至少" + expectedMin + "条（含系统贷方），实际" + templateItems.size() + "条";
                    log.warn("AI匹配 attempt {}: invoiceId={}, {}", attempt + 1, invoiceId, lastError);
                    continue;
                }

                // 创建或复用业务类型
                String fullName = "【AI生成】" + businessTypeName;
                InvoiceBusinessType bt = bqf.selectFrom(qInvoiceBusinessType)
                        .where(qInvoiceBusinessType.accountSetsId.eq(accountSets.getId())
                                .and(qInvoiceBusinessType.invoiceType.eq(type))
                                .and(qInvoiceBusinessType.title.eq(fullName)))
                        .fetchFirst();

                if (bt == null) {
                    bt = new InvoiceBusinessType();
                    bt.setAccountSetsId(accountSets.getId());
                    bt.setInvoiceType(type);
                    bt.setTitle(fullName);
                    bt.setItems(templateItems);
                    JSONArray matchWords = new JSONArray();
                    items.stream().map(InvoiceItems::getGoodsName)
                            .filter(StringUtils::isNotBlank)
                            .distinct().forEach(matchWords::add);
                    bt.setMatchWords(matchWords);
                    if (defaultVW != null) bt.setVoucherWord(defaultVW.getId());
                    log.info("AI匹配 创建新业务类型: {}, 模板条目={}, 关键词={}", fullName, templateItems.size(), matchWords);
                } else {
                    JSONArray existingWords = bt.getMatchWords() != null ? bt.getMatchWords() : new JSONArray();
                    Set<String> wordSet = new HashSet<>();
                    existingWords.forEach(w -> wordSet.add(w.toString()));
                    items.stream().map(InvoiceItems::getGoodsName)
                            .filter(StringUtils::isNotBlank).forEach(wordSet::add);
                    JSONArray merged = new JSONArray();
                    merged.addAll(wordSet);
                    bt.setMatchWords(merged);
                    if (CollUtil.isEmpty(bt.getItems())) {
                        bt.setItems(templateItems);
                    }
                    log.info("AI匹配 复用已有业务类型: {}, 合并关键词后={}", fullName, merged.size());
                }

                self.saveBusinessTypeAndBindInvoice(bt, invoiceId);

                // 打印完整的凭证模板内容
                StringBuilder tplLog = new StringBuilder();
                tplLog.append("凭证模板:\n");
                for (InvoiceBusinessType.InvoiceVoucherTemplateItems ti : templateItems) {
                    tplLog.append(String.format("  %s %s (valueField=%s)\n",
                            ti.getDirection(), ti.getSubjectTitle(), ti.getValueField()));
                }
                tplLog.append(String.format("  发票金额: 不含税=%s, 税额=%s, 合计=%s", taxExcluded, taxAmount, amount));
                log.info("AI匹配成功: invoiceId={}, businessType={}, customer={}, amount={}\n{}",
                        invoiceId, fullName, customerName, amount, tplLog);
                return true;

            } catch (Exception e) {
                lastError = e.getMessage();
                log.warn("AI匹配发票异常 attempt {}/{}: invoiceId={}, error={}", attempt + 1, MAX_AI_RETRY, invoiceId, e.getMessage());
            }
        }

        log.warn("AI匹配最终失败: invoiceId={}, customer={}, goods=[{}], lastError={}",
                invoiceId, customerName, goodsNames, lastError);
        return false;
    }

    // ═══════════════════════════════════════════════════════════════════
    // AI Prompt 构建
    // ═══════════════════════════════════════════════════════════════════

    private String buildAiPrompt(Invoice invoice, AccountSets accountSets, List<InvoiceItems> items,
                                  List<SubjectVo> subjects, InvoiceType type,
                                  VatType vatType, BigDecimal taxExcluded, BigDecimal taxAmount,
                                  BigDecimal amount, String lastError) {
        StringBuilder sb = new StringBuilder();

        // 重试时加入上次错误
        if (StringUtils.isNotBlank(lastError)) {
            sb.append("【【【重要】】】上次生成结果有误，请修正后重新生成。\n");
            sb.append("上次的错误：").append(lastError).append("\n");
            sb.append("请务必修正以上问题，特别注意：subjectCode必须从下方科目列表中选择，valueField只能填字段名（不是数字）。\n\n");
        }

        sb.append("你是一个财务专家，需要根据企业信息和发票商品明细，判断业务类型并返回会计分录模板。\n\n");

        // 企业信息
        sb.append("【企业信息】\n");
        sb.append("企业名称：").append(accountSets.getCompanyName()).append("\n");
        if (vatType != null) {
            sb.append("增值税类型：").append(vatType.display).append("\n");
        }
        if (accountSets.getAccountStandard() != null) {
            sb.append("会计准则：").append(accountSets.getAccountStandard().display).append("\n");
        }
        if (StringUtils.isNotBlank(accountSets.getProvince())) {
            sb.append("所在省份：").append(accountSets.getProvince()).append("\n");
        }
        if (StringUtils.isNotBlank(accountSets.getAddress())) {
            sb.append("经营地址：").append(accountSets.getAddress()).append("\n");
        }
        if (StringUtils.isNotBlank(accountSets.getAiProfile())) {
            sb.append("\n【企业业务规则（最高优先级：选择科目时必须优先遵守以下规则）】\n");
            sb.append(accountSets.getAiProfile()).append("\n");
            sb.append("↑ 以上是企业管理员配置的业务规则，当商品名称或业务类型与上述规则匹配时，");
            sb.append("必须按规则选择科目，不得凭通用会计惯例自行判断。\n");
        }
        sb.append("\n");

        // 发票对方信息
        String counterParty = invoice.getCustomerName();
        sb.append(type == InvoiceType.IN ? "供应商名称：" : "客户名称：").append(counterParty).append("\n\n");

        // 商品明细
        sb.append("商品明细：\n");
        for (InvoiceItems item : items) {
            sb.append("- 商品名称：").append(StringUtils.defaultString(item.getGoodsName()));
            if (item.getQuantity() != null) sb.append("，数量：").append(item.getQuantity());
            if (StringUtils.isNotBlank(item.getUnit())) sb.append("，单位：").append(item.getUnit());
            if (StringUtils.isNotBlank(item.getAmount())) sb.append("，金额：").append(item.getAmount());
            if (item.getTaxRate() != null) sb.append("，税率：").append(item.getTaxRate()).append("%");
            if (item.getTax() != null) sb.append("，税额：").append(item.getTax());
            sb.append("\n");
        }

        // 金额汇总
        sb.append("\n【发票金额汇总】\n");
        sb.append("不含税金额（taxAmountExcluded）：").append(taxExcluded).append("\n");
        sb.append("税额（taxAmount）：").append(taxAmount).append("\n");
        sb.append("价税合计（amount）：").append(amount).append("\n\n");

        // 发票类型和会计规则
        boolean isSmall = (vatType == VatType.SMALL_SCALE);
        if (type == InvoiceType.IN) {
            sb.append("【发票类型】进项发票（采购发票），企业是购买方。\n\n");

            if (isSmall) {
                sb.append("【会计类型：小规模纳税人】\n");
                sb.append("进项发票会计分录规则：\n");
                sb.append("1. 你只需返回1条借方分录：资产或费用科目\n");
                sb.append("   - direction 填 \"借\"\n");
                sb.append("   - valueField 填 \"amount\"（代表价税合计金额，注意这是字段名不是数字）\n");
                sb.append("2. 贷方（应付账款-供应商）由系统自动生成，你不需要返回\n");
                sb.append("3. 小规模纳税人不能抵扣进项税额，税额计入成本或费用\n\n");
            } else {
                sb.append("【会计类型：一般纳税人】\n");
                sb.append("进项发票会计分录规则：\n");
                sb.append("1. 你需要返回恰好2条借方分录：\n");
                sb.append("   第一条：资产或费用科目\n");
                sb.append("   - direction 填 \"借\"\n");
                sb.append("   - valueField 填 \"taxAmountExcluded\"（代表不含税金额，注意这是字段名不是数字）\n");
                sb.append("   第二条：应交税费-应交增值税（进项税额）科目\n");
                sb.append("   - direction 填 \"借\"\n");
                sb.append("   - valueField 填 \"taxAmount\"（代表税额，注意这是字段名不是数字）\n");
                sb.append("2. 贷方（应付账款-供应商）由系统自动生成，你不需要返回\n\n");
            }
            sb.append("【严禁使用】应收账款、主营业务收入、其他业务收入等科目（这些只用于销项发票）\n\n");
        } else {
            sb.append("【发票类型】销项发票（销售发票），企业是销售方。\n\n");

            if (isSmall) {
                sb.append("【会计类型：小规模纳税人】\n");
                sb.append("销项发票会计分录规则：\n");
                sb.append("1. 你只需返回1条借方分录：应收账款科目\n");
                sb.append("   - direction 填 \"借\"\n");
                sb.append("   - valueField 填 \"amount\"（代表价税合计金额，注意这是字段名不是数字）\n");
                sb.append("2. 贷方（主营业务收入）由系统自动生成，你不需要返回\n\n");
            } else {
                sb.append("【会计类型：一般纳税人】\n");
                sb.append("销项发票会计分录规则：\n");
                sb.append("1. 你只需返回1条借方分录：应收账款科目\n");
                sb.append("   - direction 填 \"借\"\n");
                sb.append("   - valueField 填 \"amount\"（代表价税合计金额，注意这是字段名不是数字）\n");
                sb.append("2. 贷方（收入+销项税额）由系统自动生成，你不需要返回\n\n");
            }
            sb.append("【严禁使用】应付账款、管理费用、销售费用等科目（这些只用于进项发票）\n\n");
        }

        // 科目列表：提供该账套所有末级科目（leaf=true），让 AI 从中自由选择
        List<SubjectVo> leafSubjects = subjects.stream()
                .filter(s -> Boolean.TRUE.equals(s.getLeaf()))
                .sorted(Comparator.comparing(SubjectVo::getCode))
                .collect(Collectors.toList());
        sb.append("【可用科目列表（末级科目）】subjectCode和subjectName必须从以下列表中选择，不能自己编造！\n");
        sb.append("这是该企业实际配置的全部末级科目，请根据业务性质选择最合适的科目。\n");
        sb.append("格式：科目编码 | 科目全称（含层级路径） | 科目类型 | 余额方向\n");
        leafSubjects.forEach(s -> sb.append(s.getCode())
                .append(" | ").append(StringUtils.defaultIfBlank(s.getFullName(), s.getName()))
                .append(" | ").append(s.getType())
                .append(" | ").append(StringUtils.defaultString(s.getBalanceDirection()))
                .append("\n"));

        // 输出格式
        sb.append("\n========== 必须严格按照以下JSON格式返回 ==========\n");
        sb.append("只返回纯JSON，不要包含markdown代码块（如```json```），不要包含注释或说明文字。\n\n");

        if (type == InvoiceType.IN && !isSmall) {
            // 一般纳税人进项 — 2条分录示例
            sb.append("{\n");
            sb.append("  \"businessTypeName\": \"业务类型名称（如：办公用品采购、固定资产采购、技术服务费等）\",\n");
            sb.append("  \"accountingEntries\": [\n");
            sb.append("    {\n");
            sb.append("      \"subjectCode\": \"从上方科目列表中选择的科目编码\",\n");
            sb.append("      \"subjectName\": \"对应的科目名称\",\n");
            sb.append("      \"direction\": \"借\",\n");
            sb.append("      \"valueField\": \"taxAmountExcluded\"\n");
            sb.append("    },\n");
            sb.append("    {\n");
            sb.append("      \"subjectCode\": \"应交税费科目的编码（从上方列表中找）\",\n");
            sb.append("      \"subjectName\": \"应交税费-应交增值税（进项税额）\",\n");
            sb.append("      \"direction\": \"借\",\n");
            sb.append("      \"valueField\": \"taxAmount\"\n");
            sb.append("    }\n");
            sb.append("  ]\n");
            sb.append("}\n");
        } else {
            // 小规模或销项 — 1条分录示例
            sb.append("{\n");
            sb.append("  \"businessTypeName\": \"业务类型名称\",\n");
            sb.append("  \"accountingEntries\": [\n");
            sb.append("    {\n");
            sb.append("      \"subjectCode\": \"从上方科目列表中选择的科目编码\",\n");
            sb.append("      \"subjectName\": \"对应的科目名称\",\n");
            sb.append("      \"direction\": \"借\",\n");
            sb.append("      \"valueField\": \"amount\"\n");
            sb.append("    }\n");
            sb.append("  ]\n");
            sb.append("}\n");
        }

        sb.append("========== 格式说明结束 ==========\n\n");

        // 关键约束（大字加粗重复强调）
        sb.append("【【【关键约束 - 必须严格遵守】】】\n");
        if (StringUtils.isNotBlank(accountSets.getAiProfile())) {
            sb.append("0. 【最高优先级】企业业务规则中已明确的科目归类，必须严格执行，不得覆盖。");
            sb.append("例如：规则写明\"餐饮→招待费\"，则所有餐饮类发票必须记招待费，不能改为其他科目。\n");
        }
        sb.append("1. subjectCode 和 subjectName 必须完全来自上方【可用科目列表】，不能自己编造科目编码\n");
        sb.append("2. valueField 只能填以下固定字段名之一，绝对不能填数字：\n");
        sb.append("   - \"taxAmountExcluded\" — 代表不含税金额\n");
        sb.append("   - \"taxAmount\" — 代表税额\n");
        sb.append("   - \"amount\" — 代表价税合计金额\n");
        sb.append("3. direction 只能填 \"借\"\n");
        sb.append("4. 不要返回贷方分录（系统自动生成）\n");

        return sb.toString();
    }

    // ═══════════════════════════════════════════════════════════════════
    // Qwen API 调用
    // ═══════════════════════════════════════════════════════════════════

    private String callQwen(String userContent) {
        try {
            Generation gen = new Generation();
            GenerationParam param = GenerationParam.builder()
                    .apiKey(qwenApiKey)
                    .model(QWEN_MODEL)
                    .messages(List.of(
                            Message.builder().role(Role.USER.getValue()).content(userContent).build()
                    ))
                    .temperature(0.3f)
                    .maxTokens(2000)
                    .resultFormat(GenerationParam.ResultFormat.MESSAGE)
                    .build();

            GenerationResult result = gen.call(param);
            String content = result.getOutput().getChoices().get(0).getMessage().getContent();
            if (result.getUsage() != null) {
                log.debug("Qwen AI匹配 token: prompt={}, completion={}",
                        result.getUsage().getInputTokens(), result.getUsage().getOutputTokens());
            }
            return content;
        } catch (Exception e) {
            throw new RuntimeException("调用Qwen失败: " + e.getMessage(), e);
        }
    }

    private String cleanJson(String content) {
        if (content == null) return "";
        content = content.trim();
        if (content.startsWith("```json")) content = content.substring(7);
        else if (content.startsWith("```")) content = content.substring(3);
        if (content.endsWith("```")) content = content.substring(0, content.length() - 3);
        return content.trim();
    }

    // ═══════════════════════════════════════════════════════════════════
    // 科目查找
    // ═══════════════════════════════════════════════════════════════════

    private Subject findSubject(Long accountSetId, String code, String name, List<SubjectVo> subjects) {
        // 先按编码精确匹配
        if (StringUtils.isNotBlank(code)) {
            Subject s = bqf.selectFrom(qSubject)
                    .where(qSubject.accountSetsId.eq(accountSetId).and(qSubject.code.eq(code)))
                    .fetchFirst();
            if (s != null) return s;
        }
        // 按全名匹配
        if (StringUtils.isNotBlank(name)) {
            Subject s = bqf.selectFrom(qSubject)
                    .where(qSubject.accountSetsId.eq(accountSetId).and(qSubject.fullName.eq(name)))
                    .fetchFirst();
            if (s != null) return s;
            // 按短名匹配
            s = bqf.selectFrom(qSubject)
                    .where(qSubject.accountSetsId.eq(accountSetId).and(qSubject.name.eq(name)))
                    .fetchFirst();
            if (s != null) return s;
        }
        return null;
    }

    // ═══════════════════════════════════════════════════════════════════
    // Step 3: 凭证生成
    // ═══════════════════════════════════════════════════════════════════

    private List<Invoice> queryInvoicesForVoucherGeneration(Long accountSetId, YearMonth yearMonth, InvoiceType type) {
        LocalDate start = yearMonth.atDay(1);
        LocalDate end = yearMonth.atEndOfMonth();
        List<Invoice> list = bqf.selectFrom(qInvoice)
                .where(qInvoice.accountSetsId.eq(accountSetId)
                        .and(qInvoice.type.eq(type))
                        .and(qInvoice.invoiceBusinessTypeId.isNotNull())
                        .and(qInvoice.voucherId.isNull())
                        .and(invoicePeriodCondition(start, end)))
                .fetch();
        return list != null ? list : new ArrayList<>();
    }

    private Map<Long, Subject> buildSubjectMap(Long accountSetId) {
        Map<Long, Subject> map = new HashMap<>();
        bqf.selectFrom(qSubject)
                .where(qSubject.accountSetsId.eq(accountSetId))
                .fetch()
                .forEach(s -> map.put(s.getId(), s));
        return map;
    }

    private Map<Long, InvoiceBusinessType> buildBusinessTypeMap(Long accountSetId, InvoiceType type) {
        Map<Long, InvoiceBusinessType> map = new HashMap<>();
        bqf.selectFrom(qInvoiceBusinessType)
                .where(qInvoiceBusinessType.accountSetsId.eq(accountSetId)
                        .and(qInvoiceBusinessType.invoiceType.eq(type)))
                .fetch()
                .forEach(bt -> map.put(bt.getId(), bt));
        return map;
    }

    private String resolveVoucherWord(Long accountSetId, Long voucherWordId) {
        if (voucherWordId == null) return "记";
        VoucherWord vw = bqf.selectFrom(QVoucherWord.voucherWord)
                .where(QVoucherWord.voucherWord.id.eq(voucherWordId)
                        .and(QVoucherWord.voucherWord.accountSetsId.eq(accountSetId)))
                .fetchFirst();
        return vw != null ? vw.getWord() : "记";
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public boolean generateVoucherForInvoice(Invoice invoice, InvoiceBusinessType businessType,
                                             Map<Long, Subject> subjectMap, CurrencyDto localCurrency,
                                             String voucherWord, Integer voucherCode, LocalDate voucherDate,
                                             AccountSets accountSets, Long accountSetId) {
        VoucherDto voucherDto = buildVoucherDto(invoice, businessType, subjectMap,
                localCurrency, voucherWord, voucherCode, voucherDate, accountSetId, accountSets);
        if (voucherDto == null) {
            log.warn("凭证构建失败: invoiceId={} businessType={}", invoice.getId(), businessType.getTitle());
            return false;
        }

        // 打印生成的凭证分录
        StringBuilder vLog = new StringBuilder();
        vLog.append(String.format("生成凭证: %s-%d 日期=%s invoiceId=%s businessType=%s customer=%s\n",
                voucherDto.getWord(), voucherDto.getCode(), voucherDto.getVoucherDate(),
                invoice.getId(), businessType.getTitle(), invoice.getCustomerName()));
        if (voucherDto.getDetails() != null) {
            for (VoucherDetailsDto d : voucherDto.getDetails()) {
                String dir = d.getDebitAmount() != null && d.getDebitAmount().compareTo(BigDecimal.ZERO) > 0 ? "借" : "贷";
                BigDecimal amt = dir.equals("借") ? d.getDebitAmount() : d.getCreditAmount();
                vLog.append(String.format("  %s %-30s %12s  摘要=%s\n",
                        dir, d.getSubjectName(), amt, d.getSummary()));
            }
        }
        vLog.append(String.format("  借方合计=%s  贷方合计=%s", voucherDto.getDebitAmount(), voucherDto.getCreditAmount()));
        log.info("{}", vLog);

        invoiceService.generateVoucher(Collections.singletonList(voucherDto), accountSets, null, true);
        log.info("凭证保存成功: invoiceId={}", invoice.getId());
        return true;
    }

    private VoucherDto buildVoucherDto(Invoice invoice, InvoiceBusinessType businessType,
                                       Map<Long, Subject> subjectMap, CurrencyDto localCurrency,
                                       String voucherWord, Integer voucherCode, LocalDate voucherDate,
                                       Long accountSetId, AccountSets accountSets) {
        List<InvoiceBusinessType.InvoiceVoucherTemplateItems> items = businessType.getItems();
        if (CollUtil.isEmpty(items)) return null;

        List<String> transactionAccounts = Arrays.asList(
                "应收账款", "其他应收款", "预收账款", "应付账款", "其他应付款", "预付账款");

        List<VoucherDetailsDto> details = new ArrayList<>();
        BigDecimal debit = BigDecimal.ZERO, credit = BigDecimal.ZERO;
        BigDecimal taxExcluded = invoice.getTaxAmountExcluded() != null ? invoice.getTaxAmountExcluded() : BigDecimal.ZERO;
        BigDecimal taxAmount = invoice.getTaxAmount() != null ? invoice.getTaxAmount() : BigDecimal.ZERO;
        BigDecimal amount = invoice.getAmount() != null ? invoice.getAmount() : BigDecimal.ZERO;
        String customerName = invoice.getCustomerName();

        // 红冲发票（负数金额）：取绝对值并反转借贷方向
        boolean isRedInvoice = amount.compareTo(BigDecimal.ZERO) < 0;
        if (isRedInvoice) {
            taxExcluded = taxExcluded.abs();
            taxAmount = taxAmount.abs();
            amount = amount.abs();
        }

        for (InvoiceBusinessType.InvoiceVoucherTemplateItems item : items) {
            String subjectIdStr = item.getSubjectId();
            if (StringUtils.isBlank(subjectIdStr)) continue;
            Long subjectId;
            try { subjectId = Long.parseLong(subjectIdStr); } catch (NumberFormatException e) { continue; }
            Subject subject = subjectMap.get(subjectId);
            if (subject == null) continue;

            // 非末级科目自动解析
            if (!Boolean.TRUE.equals(subject.getLeaf())) {
                String fn = subject.getFullName();
                boolean isTxParent = transactionAccounts.stream().anyMatch(ta -> fn != null && fn.startsWith(ta));
                Subject resolved = null;
                if (isTxParent && StringUtils.isNotBlank(customerName)) {
                    resolved = findOrCreateChildSubject(accountSetId, accountSets, subject, customerName, subjectMap);
                }
                if (resolved == null) {
                    resolved = findFirstLeafChild(accountSetId, subject, subjectMap);
                }
                if (resolved != null) { subject = resolved; subjectId = resolved.getId(); }
                else {
                    log.warn("发票ID:{} 科目【{}-{}】非末级且无可用子科目", invoice.getId(), subject.getCode(), subject.getName());
                    return null;
                }
            }

            BigDecimal itemAmount = switch (StringUtils.defaultString(item.getValueField())) {
                case "taxAmountExcluded" -> taxExcluded;
                case "taxAmount" -> taxAmount;
                default -> amount;
            };
            if (itemAmount.compareTo(BigDecimal.ZERO) == 0) continue;

            final Subject finalSubject = subject;
            VoucherDetailsDto d = new VoucherDetailsDto();
            d.setSubjectId(subjectId);
            d.setSubjectName(finalSubject.getCode() + " " + finalSubject.getFullName());
            d.setCurrencyId(localCurrency.getId());
            d.setSummary(StringUtils.defaultString(businessType.getTitle()).replaceFirst("^【AI生成】", ""));

            boolean isTxAccount = transactionAccounts.stream()
                    .anyMatch(ta -> finalSubject.getFullName() != null && finalSubject.getFullName().startsWith(ta));
            if (isTxAccount && StringUtils.isNotBlank(customerName)
                    && !finalSubject.getFullName().startsWith("其他应付款")) {
                d.setCustomerName(customerName);
            }

            String effectiveDir = isRedInvoice
                    ? ("借".equals(item.getDirection()) ? "贷" : "借")
                    : item.getDirection();
            if ("借".equals(effectiveDir)) { d.setDebitAmount(itemAmount); debit = debit.add(itemAmount); }
            else if ("贷".equals(effectiveDir)) { d.setCreditAmount(itemAmount); credit = credit.add(itemAmount); }
            details.add(d);
        }

        if (debit.compareTo(credit) != 0) {
            log.warn("发票ID:{} 借贷不平衡 借:{} 贷:{}", invoice.getId(), debit, credit);
            return null;
        }

        VoucherDto dto = new VoucherDto();
        dto.setWord(voucherWord);
        dto.setCode(voucherCode);
        dto.setVoucherDate(voucherDate);
        dto.setDebitAmount(debit);
        dto.setCreditAmount(credit);
        dto.setInvoiceId(invoice.getId());
        dto.setDetails(details);
        return dto;
    }

    // ═══════════════════════════════════════════════════════════════════
    // 子科目查找/创建
    // ═══════════════════════════════════════════════════════════════════

    private Subject findOrCreateChildSubject(Long accountSetId, AccountSets accountSets,
                                              Subject parent, String customerName,
                                              Map<Long, Subject> subjectMap) {
        try {
            for (Subject s : subjectMap.values()) {
                if (parent.getId().equals(s.getParentId()) && customerName.equals(s.getName())
                        && Boolean.TRUE.equals(s.getLeaf())) return s;
            }
            Subject existing = bqf.selectFrom(qSubject)
                    .where(qSubject.accountSetsId.eq(accountSetId)
                            .and(qSubject.parentId.eq(parent.getId()))
                            .and(qSubject.name.eq(customerName)))
                    .fetchFirst();
            if (existing != null) { subjectMap.put(existing.getId(), existing); return existing; }

            List<Integer> encoding = accountSets.getEncoding();
            Integer encLen = encoding != null
                    ? encoding.stream().reduce(Integer::sum).orElse(0) : 0;

            // 根据编码规则生成子科目编码
            int childLevel = parent.getLevel() + 1; // level 从 1 开始
            int segLen = (encoding != null && childLevel <= encoding.size())
                    ? encoding.get(childLevel - 1) : 2; // 默认 2 位
            // 查当前父科目下已有的最大编码
            String maxCode = null;
            for (Subject s : subjectMap.values()) {
                if (parent.getId().equals(s.getParentId()) && s.getCode() != null) {
                    if (maxCode == null || s.getCode().compareTo(maxCode) > 0) maxCode = s.getCode();
                }
            }
            if (maxCode == null) {
                Subject maxChild = bqf.selectFrom(qSubject)
                        .where(qSubject.accountSetsId.eq(accountSetId)
                                .and(qSubject.parentId.eq(parent.getId())))
                        .orderBy(qSubject.code.desc())
                        .fetchFirst();
                if (maxChild != null) maxCode = maxChild.getCode();
            }
            String childCode;
            if (maxCode != null && maxCode.length() > parent.getCode().length()) {
                String lastSeg = maxCode.substring(parent.getCode().length());
                int nextNum = Integer.parseInt(lastSeg) + 1;
                childCode = parent.getCode() + StrUtil.padPre(String.valueOf(nextNum), segLen, '0');
            } else {
                childCode = parent.getCode() + StrUtil.padPre("1", segLen, '0');
            }

            Subject child = new Subject();
            child.setAccountSetsId(accountSetId);
            child.setName(customerName);
            child.setCode(childCode);
            child.setParentId(parent.getId());
            child.setLevel(childLevel);
            child.setLeaf(true);
            child.setStatus(true);
            child.setSystemDefault(false);
            child.setType(parent.getType());
            child.setBalanceDirection(parent.getBalanceDirection());
            child.setCash(false);
            Subject saved = subjectService.save(child, encLen);
            subjectMap.put(saved.getId(), saved);
            log.info("自动创建子科目: {}({}) → {}({})", parent.getCode(), parent.getName(), saved.getCode(), customerName);
            return saved;
        } catch (Exception e) {
            log.error("创建子科目失败: parent={}({}), customer={}", parent.getCode(), parent.getName(), customerName, e);
            return null;
        }
    }

    private Subject findFirstLeafChild(Long accountSetId, Subject parent, Map<Long, Subject> subjectMap) {
        for (Subject s : subjectMap.values()) {
            if (parent.getId().equals(s.getParentId()) && Boolean.TRUE.equals(s.getLeaf())) return s;
        }
        Subject child = bqf.selectFrom(qSubject)
                .where(qSubject.accountSetsId.eq(accountSetId)
                        .and(qSubject.parentId.eq(parent.getId()))
                        .and(qSubject.leaf.eq(true)).and(qSubject.status.eq(true)))
                .orderBy(qSubject.code.asc()).fetchFirst();
        if (child != null) {
            subjectMap.put(child.getId(), child);
            log.info("非末级科目降级: {}({}) → {}({})", parent.getCode(), parent.getName(), child.getCode(), child.getName());
        }
        return child;
    }

    // ═══════════════════════════════════════════════════════════════════
    // 结账状态管理
    // ═══════════════════════════════════════════════════════════════════

    private boolean isPeriodCheckedOut(Long accountSetId, YearMonth yearMonth) {
        cn.gson.financial.kernel.model.entity.QCheckout qCheckout = QCheckout.checkout;
        return bqf.selectFrom(qCheckout)
                .where(qCheckout.accountSetsId.eq(accountSetId)
                        .and(qCheckout.checkYear.eq(yearMonth.getYear()))
                        .and(qCheckout.checkMonth.eq(yearMonth.getMonthValue()))
                        .and(qCheckout.status.eq(2)))
                .fetchCount() > 0;
    }

    /** 保存业务类型并绑定到发票（独立事务，AI匹配后逐条提交） */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveBusinessTypeAndBindInvoice(InvoiceBusinessType bt, Long invoiceId) {
        invoiceBusinessTypeService.save(bt);
        jqf.update(qInvoice)
                .set(qInvoice.invoiceBusinessTypeId, bt.getId())
                .where(qInvoice.id.eq(invoiceId))
                .execute();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void setCheckoutStatus(Long accountSetId, YearMonth yearMonth, int status) {
        cn.gson.financial.kernel.model.entity.QCheckout qCheckout = QCheckout.checkout;
        jqf.update(qCheckout)
                .set(qCheckout.status, status)
                .where(qCheckout.accountSetsId.eq(accountSetId)
                        .and(qCheckout.checkYear.eq(yearMonth.getYear()))
                        .and(qCheckout.checkMonth.eq(yearMonth.getMonthValue())))
                .execute();
    }
}
