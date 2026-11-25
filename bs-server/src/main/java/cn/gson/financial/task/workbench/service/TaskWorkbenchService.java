package cn.gson.financial.task.workbench.service;

import cn.gson.financial.kernel.model.dto.InvoiceDto;
import cn.gson.financial.kernel.model.entity.*;
import java.math.BigDecimal;
import cn.gson.financial.kernel.model.enums.InvoiceState;
import cn.gson.financial.kernel.model.enums.InvoiceType;
import cn.gson.financial.kernel.model.enums.VatType;
import cn.gson.financial.kernel.model.hibernate.BlazeJPAQueryFactory;
import cn.gson.financial.kernel.model.repo.*;
import cn.gson.financial.kernel.service.CheckInvoiceService;
import cn.gson.financial.kernel.service.CheckoutService;
import cn.gson.financial.kernel.service.FileInfoService;
import cn.gson.financial.kernel.service.InvoiceImportService;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpUtil;
import com.alibaba.fastjson2.JSON;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
import java.util.Objects;

@Slf4j
@Service
@RequiredArgsConstructor
public class TaskWorkbenchService {

    private final AccountSetsRepository accountSetsRepository;
    private final TaskPeriodRepository taskPeriodRepository;
    private final TaskDataSubRepository taskDataSubRepository;
    private final OcTaskRepository ocTaskRepository;
    private final TaxOfficerRepository taxOfficerRepository;
    private final TaskBankParseService taskBankParseService;
    private final TaskSalaryService taskSalaryService;
    private final TaskBookkeepService taskBookkeepService;
    private final CheckoutService checkoutService;
    private final InvoiceImportService invoiceImportService;
    private final TaskVerifyResultRepository taskVerifyResultRepository;
    private final TaskTaxItemRepository taskTaxItemRepository;
    private final TaskPaymentItemRepository taskPaymentItemRepository;
    private final TaskIitItemRepository taskIitItemRepository;
    private final TaxFilingStatusRepository taxFilingStatusRepository;
    private final TaskTaxAnnualItemRepository taskTaxAnnualItemRepository;
    private final CheckoutRepository checkoutRepository;
    private final TaskBookkeepSubRepository taskBookkeepSubRepository;
    private final SalaryRepository salaryRepository;
    private final SalaryFieldValueRepository salaryFieldValueRepository;
    private final EmployeeRepository employeeRepository;
    private final TaskInitResultRepository taskInitResultRepository;
    private final CashierJournalRepository cashierJournalRepository;
    private final CashierIoTypeRepository cashierIoTypeRepository;
    private final SubjectRepository subjectRepository;
    private final CashierIoTypeVoucherTemplateRepository cashierIoTypeVoucherTemplateRepository;
    private final InvoiceRepository invoiceRepository;
    private final CheckInvoiceService checkInvoiceService;
    private final TaskUncheckedInvoiceRepository taskUncheckedInvoiceRepository;
    private final TaskInitImportService taskInitImportService;
    private final FileInfoService fileInfoService;
    private final UserAccountSetsRepository userAccountSetsRepository;
    private final cn.gson.financial.kernel.model.repo.UserRepository userRepository;
    private final OcOperationLogRepository ocOperationLogRepository;
    private final BlazeJPAQueryFactory bqf;
    private final cn.gson.financial.kernel.service.BalanceService balanceService;
    private final cn.gson.financial.kernel.tool.RedisUtil redisUtil;
    private final cn.gson.financial.kernel.model.repo.TaskVatPreRepository taskVatPreRepository;
    // FN-TAX-VERIFY-PROFILE-WIRE: 核验结果并入账套画像（已落库，直接复用）
    private final cn.gson.financial.kernel.model.repo.AccountSetProfileRepository accountSetProfileRepository;
    private final cn.gson.financial.kernel.model.repo.OcCaptchaRequestRepository ocCaptchaRequestRepository;

    // ── running 超时回收阈值（分钟）：领走后超过阈值仍未回调视为卡死，自动标记失败 ──
    // 计时基准是 oc_task.started_at（真正进入 running 的时刻），不含排队等待；可按需在配置里调。
    /** 做账类（bookkeep_*）超时阈值，沿用历史 30 分钟 */
    @org.springframework.beans.factory.annotation.Value("${oc.task.bookkeep-timeout-min:30}")
    int bookkeepTimeoutMin;
    /** 个税申报（iit_declare）超时阈值；个税较慢 + 有验证码，给足 60 分钟 */
    @org.springframework.beans.factory.annotation.Value("${oc.task.iit-timeout-min:60}")
    int iitTimeoutMin;
    /** 国税侧（核验/发票下载/国税申报/缴费等）超时阈值，60 分钟 */
    @org.springframework.beans.factory.annotation.Value("${oc.task.national-timeout-min:60}")
    int nationalTimeoutMin;
    /** 获取数据类（vat_declare_pre / download_invoice / check_invoice）超时阈值，60 分钟 */
    @org.springframework.beans.factory.annotation.Value("${oc.task.data-timeout-min:60}")
    int dataTimeoutMin;
    /** 防误杀窗口：该任务近 N 分钟内有验证码交互则本轮不回收（OC 还活着在等人工填码） */
    @org.springframework.beans.factory.annotation.Value("${oc.task.captcha-alive-min:10}")
    int captchaAliveMin;

    /** 自注入，用于在独立事务（REQUIRES_NEW）中执行"不影响主流程"的辅助写操作 */
    @Autowired
    @Lazy
    private TaskWorkbenchService self;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    /** 发布步骤完成事件，由 @TransactionalEventListener(AFTER_COMMIT) 异步触发后置任务，与当前事务完全解耦 */
    private void publishStepCompleted(Long accountSetId, String period, String completedStep) {
        eventPublisher.publishEvent(new StepCompletedEvent(accountSetId, period, completedStep));
    }

    private static final DateTimeFormatter PERIOD_FMT = DateTimeFormatter.ofPattern("yyyy-MM");

    /**
     * 步骤执行模式默认配置（主步骤 + 子任务级别）。
     * 数据库中无记录时使用此值；如需修改默认行为，只改这里即可。
     * auto   = 自动执行
     * manual = 暂停等待人工确认
     */
    private static final Map<String, String> DEFAULT_STEP_CONFIG;
    static {
        Map<String, String> m = new LinkedHashMap<>();
        // 主步骤
        m.put("verify",   "auto");
        m.put("init",     "auto");
        m.put("data",     "auto");
        m.put("bookkeep", "auto");
        m.put("vat",      "auto");
        m.put("iit",      "auto");
        m.put("payment",  "auto");
        // 获取数据子任务
        m.put("data.invoice",           "auto");
        m.put("data.bank",              "auto");
        m.put("data.salary",            "auto");
        m.put("data.vat_declare_pre",   "auto");
        // 做账子任务
        m.put("bookkeep.asset",         "auto");
        m.put("bookkeep.invoice",       "auto");
        m.put("bookkeep.salary",        "auto");
        m.put("bookkeep.bank",          "auto");
        m.put("bookkeep.carry_forward", "auto");
        DEFAULT_STEP_CONFIG = java.util.Collections.unmodifiableMap(m);
    }

    // ─────────────────────────────────────────────────────────────────────
    // 任务列表 & 统计
    // ─────────────────────────────────────────────────────────────────────

    /**
     * 获取任务列表（含统计）
     */
    public Map<String, Object> getTasks(String period, String status, String vat,
                                        String province, String keyword, int page, int pageSize) {
        return getTasks(period, status, null, vat, null, province, keyword, page, pageSize, (List<Long>) null);
    }

    public Map<String, Object> getTasks(String period, String status, String vat,
                                        String province, String keyword, int page, int pageSize, Long userId) {
        return getTasks(period, status, null, vat, null, province, keyword, page, pageSize,
                userId != null ? Collections.singletonList(userId) : null);
    }

    public Map<String, Object> getTasks(String period, String status, String stepField, String vat,
                                        String province, String keyword, int page, int pageSize, Long userId) {
        return getTasks(period, status, stepField, vat, null, province, keyword, page, pageSize,
                userId != null ? Collections.singletonList(userId) : null);
    }

    public Map<String, Object> getTasks(String period, String status, String stepField, String vat,
                                        String accountPeriod, String province, String keyword, int page, int pageSize, Long userId) {
        return getTasks(period, status, stepField, vat, accountPeriod, province, keyword, page, pageSize,
                userId != null ? Collections.singletonList(userId) : null);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getTasks(String period, String status, String stepField, String vat,
                                        String accountPeriod, String province, String keyword, int page, int pageSize, List<Long> userIds) {
        return getTasks(period, status, stepField, vat, accountPeriod, province, keyword, page, pageSize, userIds, null, null);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getTasks(String period, String status, String stepField, String vat,
                                        String accountPeriod, String province, String keyword, int page, int pageSize, List<Long> userIds, String declareFilter) {
        return getTasks(period, status, stepField, vat, accountPeriod, province, keyword, page, pageSize, userIds, declareFilter, null);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getTasks(String period, String status, String stepField, String vat,
                                        String accountPeriod, String province, String keyword, int page, int pageSize, List<Long> userIds, String declareFilter, String iitFilter) {
        String p = resolvePeriod(period);

        // ── 将 keyword / vat / province 过滤下推到 SQL，同时用子查询关联 user_account_sets ──
        VatType vatType = "small".equals(vat) ? VatType.SMALL_SCALE
                        : "general".equals(vat) ? VatType.COMMONLY : null;
        String kw = StrUtil.isBlank(keyword) ? null : keyword;
        String prov = StrUtil.isBlank(province) ? null : province;

        // 1. 单条 SQL：JOIN user_account_sets + 过滤 keyword/vat/province（替代原来的 2 次 IN 查询 + Java 内存过滤）
        List<AccountSets> allSets;
        if (userIds != null && !userIds.isEmpty()) {
            if (userIds.size() == 1) {
                allSets = accountSetsRepository.findActiveByUserAndFilters(userIds.get(0), kw, vatType, prov);
            } else {
                allSets = accountSetsRepository.findActiveByUserIdsAndFilters(userIds, kw, vatType, prov);
            }
        } else {
            allSets = accountSetsRepository.findActiveByFilters(kw, vatType, prov);
        }

        if (allSets.isEmpty()) {
            return buildEmptyResult(p, page, pageSize);
        }

        List<Long> allIds = allSets.stream().map(AccountSets::getId).collect(Collectors.toList());

        // 2. 查询当期 TaskPeriod + 曾导过期初标记（1条SQL合并两次查询）
        Map<Long, TaskPeriod> periodMap = new LinkedHashMap<>();
        Set<Long> everDoneInitIds = new HashSet<>();
        for (Object[] r2 : taskPeriodRepository.findByPeriodWithEverDoneInit(p, allIds)) {
            TaskPeriod tp = (TaskPeriod) r2[0];
            periodMap.put(tp.getAccountSetId(), tp);
            if (Boolean.TRUE.equals(r2[1])) everDoneInitIds.add(tp.getAccountSetId());
        }

        // 3. 批量查出子任务状态，按 taskPeriodId 分组
        List<Long> tpIds = periodMap.values().stream().map(TaskPeriod::getId).collect(Collectors.toList());
        Map<Long, List<TaskDataSub>> subsByTpId = tpIds.isEmpty()
                ? Collections.emptyMap()
                : taskDataSubRepository.findByTaskPeriodIdIn(tpIds).stream()
                        .collect(Collectors.groupingBy(TaskDataSub::getTaskPeriodId));

        // 4b. 批量查出做账子任务，按 taskPeriodId 分组
        Map<Long, List<TaskBookkeepSub>> bkSubsByTpId = tpIds.isEmpty()
                ? Collections.emptyMap()
                : taskBookkeepSubRepository.findByTaskPeriodIdIn(tpIds).stream()
                        .collect(Collectors.groupingBy(TaskBookkeepSub::getTaskPeriodId));

        // 4c. 批量查出个税申报明细，按 taskPeriodId 分组（消除 buildRow 的 N+1 查询）
        Map<Long, List<TaskIitItem>> iitItemsByTpId = tpIds.isEmpty()
                ? Collections.emptyMap()
                : taskIitItemRepository.findByTaskPeriodIdIn(tpIds).stream()
                        .collect(Collectors.groupingBy(TaskIitItem::getTaskPeriodId));

        // 4c2. 批量查出税务年报明细，按 accountSetId 分组（年报跨月共享，不按 taskPeriodId）
        String taxYear = String.valueOf(Integer.parseInt(p.substring(0, 4)) - 1);
        Map<Long, List<TaskTaxAnnualItem>> taxAnnualItemsByAsId = allIds.isEmpty()
                ? Collections.emptyMap()
                : taskTaxAnnualItemRepository.findByAccountSetIdInAndTaxYear(allIds, taxYear).stream()
                        .collect(Collectors.groupingBy(TaskTaxAnnualItem::getAccountSetId));

        // 4d. 批量查出缴费明细，按 taskPeriodId 分组（列表行内展示税额汇总）
        Map<Long, List<TaskPaymentItem>> paymentItemsByTpId = tpIds.isEmpty()
                ? Collections.emptyMap()
                : taskPaymentItemRepository.findByTaskPeriodIdIn(tpIds).stream()
                        .collect(Collectors.groupingBy(TaskPaymentItem::getTaskPeriodId));

        // 4e. 批量从发票表聚合发票统计（张数/金额/税额），按 accountSetId 分组
        //     维度：type(IN进项/OUT销项) + showStatus(null普票/unchecked未勾选/checked已勾选)
        java.time.YearMonth ym = java.time.YearMonth.parse(p);
        LocalDate invoiceStart = ym.atDay(1);
        LocalDate invoiceEnd = ym.plusMonths(1).atDay(1);
        Map<Long, Map<String, Object>> invoiceSummaryByAsId = new HashMap<>();
        if (!allIds.isEmpty()) {
            List<Object[]> invoiceAgg = invoiceRepository.summarizeByAccountSetIdsAndDateRange(allIds, invoiceStart, invoiceEnd);
            for (Object[] r : invoiceAgg) {
                Long asId = ((Number) r[0]).longValue();
                Object typeVal = r[1];
                String showStatus = r[2] != null ? r[2].toString() : null;
                long cnt = ((Number) r[3]).longValue();
                BigDecimal amt = (BigDecimal) r[4];
                BigDecimal tax = (BigDecimal) r[5];

                // 判断进项/销项
                boolean isIn = typeVal instanceof InvoiceType
                        ? typeVal == InvoiceType.IN
                        : (typeVal instanceof Number && ((Number) typeVal).intValue() == 1);
                boolean isOut = typeVal instanceof InvoiceType
                        ? typeVal == InvoiceType.OUT
                        : (typeVal instanceof Number && ((Number) typeVal).intValue() == 0);

                Map<String, Object> summary = invoiceSummaryByAsId.computeIfAbsent(asId, k -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("totalCount", 0L);
                    m.put("totalAmount", BigDecimal.ZERO);
                    m.put("totalTax", BigDecimal.ZERO);
                    // 按 type 分类
                    m.put("inputCount", 0L);
                    m.put("inputAmount", BigDecimal.ZERO);
                    m.put("inputTax", BigDecimal.ZERO);
                    m.put("outputCount", 0L);
                    m.put("outputAmount", BigDecimal.ZERO);
                    m.put("outputTax", BigDecimal.ZERO);
                    // 按 showStatus 分类
                    m.put("checkedCount", 0L);
                    m.put("checkedAmount", BigDecimal.ZERO);
                    m.put("checkedTax", BigDecimal.ZERO);
                    m.put("uncheckedCount", 0L);
                    m.put("uncheckedAmount", BigDecimal.ZERO);
                    m.put("uncheckedTax", BigDecimal.ZERO);
                    m.put("normalCount", 0L);
                    m.put("normalAmount", BigDecimal.ZERO);
                    m.put("normalTax", BigDecimal.ZERO);
                    return m;
                });
                summary.put("totalCount", ((Number) summary.get("totalCount")).longValue() + cnt);
                summary.put("totalAmount", ((BigDecimal) summary.get("totalAmount")).add(amt));
                summary.put("totalTax", ((BigDecimal) summary.get("totalTax")).add(tax));
                if (isIn) {
                    summary.put("inputCount", ((Number) summary.get("inputCount")).longValue() + cnt);
                    summary.put("inputAmount", ((BigDecimal) summary.get("inputAmount")).add(amt));
                    summary.put("inputTax", ((BigDecimal) summary.get("inputTax")).add(tax));
                } else if (isOut) {
                    summary.put("outputCount", ((Number) summary.get("outputCount")).longValue() + cnt);
                    summary.put("outputAmount", ((BigDecimal) summary.get("outputAmount")).add(amt));
                    summary.put("outputTax", ((BigDecimal) summary.get("outputTax")).add(tax));
                }
                // 按 showStatus 归类（仅进项拆分：已勾选/未勾选/普票，销项不拆）
                if (isIn) {
                    if ("已勾选".equals(showStatus)) {
                        summary.put("checkedCount", ((Number) summary.get("checkedCount")).longValue() + cnt);
                        summary.put("checkedAmount", ((BigDecimal) summary.get("checkedAmount")).add(amt));
                        summary.put("checkedTax", ((BigDecimal) summary.get("checkedTax")).add(tax));
                    } else if ("未勾选".equals(showStatus)) {
                        summary.put("uncheckedCount", ((Number) summary.get("uncheckedCount")).longValue() + cnt);
                        summary.put("uncheckedAmount", ((BigDecimal) summary.get("uncheckedAmount")).add(amt));
                        summary.put("uncheckedTax", ((BigDecimal) summary.get("uncheckedTax")).add(tax));
                    } else {
                        // "普票" / null / 其他 → 普票
                        summary.put("normalCount", ((Number) summary.get("normalCount")).longValue() + cnt);
                        summary.put("normalAmount", ((BigDecimal) summary.get("normalAmount")).add(amt));
                        summary.put("normalTax", ((BigDecimal) summary.get("normalTax")).add(tax));
                    }
                }
            }
        }

        // 4f. 批量查出当期有已复核工资的账套ID（个税筛选用）
        java.time.YearMonth ymSalary = java.time.YearMonth.parse(p);
        Set<Long> hasSalaryIds = allIds.isEmpty()
                ? Collections.emptySet()
                : salaryRepository.findAccountSetIdsWithReviewedSalary(ymSalary.getYear(), ymSalary.getMonthValue(), allIds);

        // 5. 组装原始 DTO 列表
        List<Map<String, Object>> rows = new ArrayList<>();
        for (AccountSets as : allSets) {
            TaskPeriod tp = periodMap.get(as.getId());
            Map<String, Object> row = buildRow(as, tp, p, everDoneInitIds);
            row.put("hasSalary", hasSalaryIds.contains(as.getId()));
            // 附带子任务状态摘要
            if (tp != null) {
                // 应用批量预加载的个税明细（覆盖 buildRow 中的 null 占位，消除 N+1）
                row.put("iitResultDetail", buildIitResultDetailFromList(
                        iitItemsByTpId.getOrDefault(tp.getId(), Collections.emptyList())));

                // 应用批量预加载的缴费明细（列表行显示税额汇总）
                row.put("paymentSummary", buildPaymentSummary(
                        paymentItemsByTpId.getOrDefault(tp.getId(), Collections.emptyList())));

                // 应用批量预加载的税务年报明细（按 accountSetId 跨月查找）
                List<TaskTaxAnnualItem> annualItems = taxAnnualItemsByAsId.getOrDefault(as.getId(), Collections.emptyList());
                row.put("taxAnnualResultDetail", buildTaxAnnualResultDetail(annualItems));
                // 用实际年报状态覆盖当前月 TaskPeriod 上的 stepTaxAnnual（年报跨月共享）
                if (!annualItems.isEmpty()) {
                    row.put("stepTaxAnnual", resolveTaxAnnualStatus(annualItems));
                    // overallStatus 需要重算（buildRow 中先于此处计算，stepTaxAnnual 已被覆盖）
                    row.put("overallStatus", calcOverallStatus(row));
                }

                List<TaskDataSub> subs = subsByTpId.getOrDefault(tp.getId(), Collections.emptyList());
                for (TaskDataSub sub : subs) {
                    row.put("sub_" + sub.getSubType(), sub.getStatus());
                }
                // 聚合"发票下载"状态：小规模 [in, out]；一般纳税人 [in, out, checked, unchecked]（不含勾选动作）
                row.put("sub_invoice", aggregateInvoiceStatus(subs, as));

                // 获取数据子任务进度（X/Y 显示用）：勾选发票仅一般纳税人加入分母
                boolean isGeneral = as.getVatType() == VatType.COMMONLY;
                List<String> dataSubFields = isGeneral
                        ? java.util.Arrays.asList("sub_invoice", "sub_invoice_check", "sub_bank", "sub_salary", "sub_vat_declare_pre")
                        : java.util.Arrays.asList("sub_invoice", "sub_bank", "sub_salary", "sub_vat_declare_pre");
                List<String> presentDataFields = dataSubFields.stream()
                        .filter(f -> {
                            Object v = row.get(f);
                            return v != null && !"skipped".equals(v) && !"na".equals(v);
                        })
                        .collect(Collectors.toList());
                if (!presentDataFields.isEmpty()) {
                    long dataDone = presentDataFields.stream()
                            .filter(f -> "done".equals(row.get(f))).count();
                    Map<String, Object> dp = new LinkedHashMap<>();
                    dp.put("done", (int) dataDone);
                    dp.put("total", presentDataFields.size());
                    row.put("stepDataSubProgress", dp);
                }

                // 做账子任务进度（X/Y 显示用）：有记录就展示，skipped 算作完成
                List<TaskBookkeepSub> bkSubs = bkSubsByTpId.getOrDefault(tp.getId(), Collections.emptyList());
                if (!bkSubs.isEmpty()) {
                    long bkDone = bkSubs.stream()
                            .filter(s -> "done".equals(s.getStatus()) || "skipped".equals(s.getStatus())).count();
                    Map<String, Object> bp = new LinkedHashMap<>();
                    bp.put("done", (int) bkDone);
                    bp.put("total", bkSubs.size());
                    row.put("stepBookkeepSubProgress", bp);
                }
            } else {
                // 无 TaskPeriod 时也查年报状态（年报跨月共享）
                List<TaskTaxAnnualItem> annualItems = taxAnnualItemsByAsId.getOrDefault(as.getId(), Collections.emptyList());
                if (!annualItems.isEmpty()) {
                    row.put("taxAnnualResultDetail", buildTaxAnnualResultDetail(annualItems));
                    row.put("stepTaxAnnual", resolveTaxAnnualStatus(annualItems));
                    row.put("overallStatus", calcOverallStatus(row));
                }
            }
            // 发票统计汇总（从发票表聚合，不依赖 task_data_sub）
            Map<String, Object> invSummary = invoiceSummaryByAsId.get(as.getId());
            if (invSummary != null) {
                row.put("invoiceSummary", invSummary);
            }
            rows.add(row);
        }

        // 孤立 running 修复：批量检测 running 步骤无对应活跃 oc_task 的情况并自动重置为 error
        self.healOrphanedRunningSteps(rows, p);

        // 5. 本地过滤（keyword/vat/province 已在 SQL 层完成，这里做 status + accountPeriod + declareFilter + iitFilter 过滤）
        List<Map<String, Object>> filtered = rows.stream()
                .filter(r -> matchStatus(r, status, stepField))
                .filter(r -> matchAccountPeriod(r, accountPeriod))
                .filter(r -> matchDeclareFilter(r, declareFilter))
                .filter(r -> matchIitFilter(r, iitFilter))
                .collect(Collectors.toList());

        // 6. 排序（SQL 已按 star_rating DESC, company_name ASC 排序，
        //    但 status 过滤后可能打乱顺序，这里保持兜底排序）
        filtered.sort((a, b) -> {
            int sa = a.get("starRating") != null ? (int) a.get("starRating") : 0;
            int sb = b.get("starRating") != null ? (int) b.get("starRating") : 0;
            if (sa != sb) return sb - sa; // 星级降序
            String na = (String) a.getOrDefault("companyName", "");
            String nb = (String) b.getOrDefault("companyName", "");
            return na.compareTo(nb);
        });

        // 7. 统计
        Map<String, Object> stats = calcStats(rows);

        // 7b. 当前过滤视图下各步骤的状态分布（单次遍历累积，替代原 7×9 次流操作）
        List<String> sfList = List.of("stepVerify", "stepInit", "stepData",
                "stepBookkeep", "stepTaxNational", "stepTaxIit", "stepPayment", "stepTaxAnnual");
        Map<String, Map<String, Long>> rawBreakdown = new LinkedHashMap<>();
        for (String sf : sfList) rawBreakdown.put(sf, new LinkedHashMap<>());
        for (Map<String, Object> r : filtered) {
            for (String sf : sfList) {
                String st = (String) r.get(sf);
                if (st != null) rawBreakdown.get(sf).merge(st, 1L, Long::sum);
            }
        }
        Map<String, Object> stepBreakdown = new LinkedHashMap<>(rawBreakdown);
        stats.put("stepBreakdown", stepBreakdown);

        // 8. 分页
        int total = filtered.size();
        int fromIdx = Math.min((page - 1) * pageSize, total);
        int toIdx = Math.min(fromIdx + pageSize, total);
        List<Map<String, Object>> pageData = filtered.subList(fromIdx, toIdx);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("stats", stats);
        result.put("tasks", pageData);
        result.put("total", total);
        result.put("page", page);
        result.put("pageSize", pageSize);
        result.put("period", p);
        return result;
    }

    /**
     * 获取所有账号列表（用于任务控制台账号筛选）
     */
    public List<Map<String, Object>> getFinancialUsers() {
        return userRepository.findAll().stream()
                .map(u -> {
                    Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("id", u.getId());
                    String name = u.getRealName() != null ? u.getRealName() : u.getNickname();
                    m.put("name", name != null ? name : u.getMobile());
                    m.put("mobile", u.getMobile());
                    return m;
                })
                .sorted(java.util.Comparator.comparing(m -> String.valueOf(m.get("mobile"))))
                .collect(Collectors.toList());
    }

    /**
     * 根据税号列表匹配账套，返回匹配的 accountSetId 列表和统计
     */
    @Transactional(readOnly = true)
    public Map<String, Object> matchCreditCodes(List<String> creditCodes, List<Long> userIds) {
        if (creditCodes == null || creditCodes.isEmpty()) {
            return Map.of("matchedIds", List.of(), "matchedCount", 0,
                          "totalInput", 0, "unmatchedCodes", List.of());
        }
        // 去重 + 去空
        List<String> codes = creditCodes.stream()
                .filter(c -> c != null && !c.isBlank())
                .map(String::trim)
                .distinct()
                .collect(Collectors.toList());

        List<AccountSets> matched;
        if (userIds != null && !userIds.isEmpty()) {
            matched = accountSetsRepository.findActiveByCreditCodesAndUserIds(codes, userIds);
        } else {
            matched = accountSetsRepository.findActiveByCreditCodes(codes);
        }

        Set<String> matchedCodeSet = matched.stream()
                .map(AccountSets::getCreditCode)
                .collect(Collectors.toSet());
        List<String> unmatchedCodes = codes.stream()
                .filter(c -> !matchedCodeSet.contains(c))
                .collect(Collectors.toList());
        List<Long> matchedIds = matched.stream()
                .map(AccountSets::getId)
                .collect(Collectors.toList());
        List<Map<String, Object>> matchedList = matched.stream()
                .map(a -> {
                    Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("accountSetId", a.getId());
                    m.put("companyName", a.getCompanyName());
                    m.put("creditCode", a.getCreditCode());
                    return m;
                })
                .collect(Collectors.toList());

        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("matchedIds", matchedIds);
        result.put("matchedList", matchedList);
        result.put("matchedCount", matchedIds.size());
        result.put("totalInput", codes.size());
        result.put("unmatchedCodes", unmatchedCodes);
        return result;
    }

    /**
     * 获取实时进度：总体统计 + 每步统计 + 事件流（正在执行 + 最近完成/失败）
     */
    public Map<String, Object> getProgress(String period, Long userId) {
        String p = resolvePeriod(period);

        // 1. 获取用户关联的未停用账套
        List<AccountSets> allSets;
        if (userId != null) {
            allSets = accountSetsRepository.findActiveByUserAndFilters(userId, null, null, null);
        } else {
            allSets = accountSetsRepository.findActiveByFilters(null, null, null);
        }
        if (allSets.isEmpty()) return Map.of("stats", Map.of(), "events", List.of());

        Map<Long, AccountSets> asMap = allSets.stream().collect(Collectors.toMap(AccountSets::getId, a -> a));
        List<Long> allIds = new ArrayList<>(asMap.keySet());

        // 2. 查询当期 TaskPeriod，计算 stats
        Map<Long, TaskPeriod> periodMap = taskPeriodRepository
                .findByPeriodAndAccountSetIdIn(p, allIds)
                .stream().collect(Collectors.toMap(TaskPeriod::getAccountSetId, tp -> tp));
        Set<Long> everDoneInitIds = new HashSet<>(
                taskPeriodRepository.findAccountSetIdsByStepInitAndAccountSetIdIn("done", allIds));

        List<Map<String, Object>> rows = new ArrayList<>();
        for (AccountSets as : allSets) {
            rows.add(buildRow(as, periodMap.get(as.getId()), p, everDoneInitIds));
        }
        Map<String, Object> stats = calcStats(rows);

        // 3. 查询 OcTask 事件流（running + 最近完成/失败）
        List<OcTask> runningTasks = ocTaskRepository.findByPeriodAndStatusInOrderByCreatedAtDesc(
                p, List.of("running", "queued"));
        List<OcTask> recentDone = ocTaskRepository.findRecentFinished(p, List.of("done", "failed"));

        // 仅保留当前用户账套的事件
        Set<Long> idSet = new HashSet<>(allIds);
        runningTasks = runningTasks.stream().filter(o -> idSet.contains(o.getAccountSetId())).collect(Collectors.toList());
        recentDone = recentDone.stream().filter(o -> idSet.contains(o.getAccountSetId())).collect(Collectors.toList());

        // 4. 组装事件列表
        List<Map<String, Object>> events = new ArrayList<>();
        for (OcTask oc : runningTasks) {
            AccountSets as = asMap.get(oc.getAccountSetId());
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("type", "running".equals(oc.getStatus()) ? "running" : "queued");
            ev.put("companyName", as != null ? as.getCompanyName() : "未知");
            ev.put("taskType", oc.getTaskType());
            ev.put("time", oc.getCreatedAt());
            ev.put("errorMsg", null);
            events.add(ev);
        }
        int recentLimit = Math.min(recentDone.size(), 50);
        for (int i = 0; i < recentLimit; i++) {
            OcTask oc = recentDone.get(i);
            AccountSets as = asMap.get(oc.getAccountSetId());
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("type", "done".equals(oc.getStatus()) ? "done" : "failed");
            ev.put("companyName", as != null ? as.getCompanyName() : "未知");
            ev.put("taskType", oc.getTaskType());
            ev.put("time", oc.getFinishedAt());
            // 失败时尝试从 resultJson 提取 error_msg
            String errMsg = null;
            if ("failed".equals(oc.getStatus()) && StrUtil.isNotBlank(oc.getResultJson())) {
                try {
                    Map<?, ?> rj = JSON.parseObject(oc.getResultJson(), Map.class);
                    errMsg = rj.get("error_msg") != null ? rj.get("error_msg").toString() : null;
                } catch (Exception ignored) {}
            }
            ev.put("errorMsg", errMsg);
            events.add(ev);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("stats", stats);
        result.put("events", events);
        result.put("period", p);
        return result;
    }

    /**
     * 获取单客户详情（含子任务）
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getDetail(Long accountSetId, String period) {
        String p = resolvePeriod(period);

        AccountSets as = accountSetsRepository.findById(accountSetId)
                .orElseThrow(() -> new IllegalArgumentException("账套不存在：" + accountSetId));

        TaskPeriod tp = taskPeriodRepository.findByAccountSetIdAndPeriod(accountSetId, p)
                .orElse(null);

        // 单账套详情：只需查这一个账套是否曾导过期初
        Set<Long> everDoneInitIds = new HashSet<>(
                taskPeriodRepository.findAccountSetIdsByStepInitAndAccountSetIdIn("done", List.of(accountSetId)));
        Map<String, Object> row = buildRow(as, tp, p, everDoneInitIds);

        // 子任务 & 各步骤结果明细
        if (tp != null) {
            Long tpId = tp.getId();

            List<TaskDataSub> subs = taskDataSubRepository.findByTaskPeriodId(tpId);
            List<String> subTypeOrder = List.of(
                    "invoice_in", "invoice_out", "invoice_check",
                    "invoice_checked", "invoice_unchecked",
                    "bank", "salary", "tax_items", "invoice");
            List<Map<String, Object>> subList = subs.stream()
                    .sorted(Comparator.comparingInt(s -> {
                        int idx = subTypeOrder.indexOf(s.getSubType());
                        return idx < 0 ? Integer.MAX_VALUE : idx;
                    }))
                    .map(this::buildSubRow)
                    .collect(Collectors.toList());
            row.put("subTasks", subList);

            // 近期 OC 任务日志
            List<OcTask> ocTasks = ocTaskRepository.findByAccountSetIdAndPeriod(accountSetId, p);
            row.put("ocTasks", ocTasks.stream().map(this::buildOcTaskRow).collect(Collectors.toList()));

            // ① 核验税务结果
            taskVerifyResultRepository.findByTaskPeriodId(tpId).ifPresent(v -> row.put("verifyResult", buildVerifyResultDto(v)));

            // ② 导入期初结果
            taskInitResultRepository.findByTaskPeriodId(tpId).ifPresent(v -> row.put("initResult", v));

            // ④ 做账子任务
            List<TaskBookkeepSub> bookkeepSubs = taskBookkeepSubRepository.findByTaskPeriodId(tpId);
            if (!bookkeepSubs.isEmpty()) row.put("bookkeepSubs", bookkeepSubs);

            // ⑤ 国税申报税目：从最近一条 vat_declare oc_task result_json 解析
            ocTaskRepository.findTopByAccountSetIdAndTaskTypeAndPeriodOrderByCreatedAtDesc(accountSetId, "vat_declare_post", p)
                    .filter(oc -> StrUtil.isNotBlank(oc.getResultJson()))
                    .ifPresent(oc -> {
                        try {
                            Map<String, Object> res = JSON.parseObject(oc.getResultJson(),
                                    new com.alibaba.fastjson2.TypeReference<Map<String, Object>>() {});
                            // OC 回调将实际数据包在 "result" 子对象中，兼容直接在顶层的旧格式
                            @SuppressWarnings("unchecked")
                            Map<String, Object> data = (res.get("result") instanceof Map)
                                    ? (Map<String, Object>) res.get("result") : res;
                            Object resultsObj = data.get("results");
                            if (!(resultsObj instanceof Map)) return;
                            @SuppressWarnings("unchecked")
                            Map<String, Object> resultsMap = (Map<String, Object>) resultsObj;
                            String taxPeriod = (String) data.get("tax_period");
                            List<Map<String, Object>> taxItems = new ArrayList<>();
                            for (Map.Entry<String, Object> e : resultsMap.entrySet()) {
                                if (!(e.getValue() instanceof Map)) continue;
                                @SuppressWarnings("unchecked")
                                Map<String, Object> item = (Map<String, Object>) e.getValue();
                                Map<String, Object> ti = new LinkedHashMap<>();
                                ti.put("taxName", e.getKey());
                                // 兼容两种格式：status:"done"/"failed"/"skipped"（新） 或 success:true/false（旧）
                                Object statusVal = item.get("status");
                                String declareStatus;
                                if ("done".equals(statusVal) || Boolean.TRUE.equals(item.get("success"))) {
                                    declareStatus = "done";
                                } else if ("skipped".equals(statusVal)) {
                                    declareStatus = "skipped";
                                } else {
                                    declareStatus = "failed";
                                }
                                ti.put("declareStatus", declareStatus);
                                ti.put("declarePeriod", taxPeriod);
                                // error 字段（新协议）或 failure_reason（旧协议），skipped 时无错误信息
                                ti.put("errorMsg", "done".equals(declareStatus) || "skipped".equals(declareStatus)
                                        ? null : (item.get("error") != null ? item.get("error") : item.get("failure_reason")));
                                // screenshots 字段（文件名列表，新协议）或 img_urls（旧协议）
                                Object screenshotsVal = item.get("screenshots") instanceof List ? item.get("screenshots") : item.get("img_urls");
                                if (screenshotsVal instanceof List && !((List<?>) screenshotsVal).isEmpty()) {
                                    List<String> urls = ((List<?>) screenshotsVal).stream()
                                            .map(Object::toString)
                                            .filter(s -> !s.isBlank())
                                            .map(s -> (s.startsWith("http://") || s.startsWith("https://"))
                                                    ? s : "/oc-screenshots/" + s)
                                            .toList();
                                    if (!urls.isEmpty()) ti.put("screenshotUrls", JSON.toJSONString(urls));
                                }
                                taxItems.add(ti);
                            }
                            if (!taxItems.isEmpty()) row.put("taxItems", taxItems);
                        } catch (Exception ex) {
                            log.warn("解析 vat_declare result_json 失败: ocTaskId={}", oc.getId(), ex);
                        }
                    });

            // ⑥ 个税申报
            List<TaskIitItem> iitItems = taskIitItemRepository.findByTaskPeriodId(tpId);
            if (!iitItems.isEmpty()) row.put("iitItems", iitItems);

            // ⑧ 税务年报（按 accountSetId + taxYear 跨月查找，不局限于当前月的 TaskPeriod）
            String detailTaxYear = String.valueOf(Integer.parseInt(p.substring(0, 4)) - 1);
            taskTaxAnnualItemRepository.findByAccountSetIdAndTaxYear(accountSetId, detailTaxYear)
                    .ifPresent(item -> {
                        row.put("taxAnnualItems", List.of(item));
                        row.put("stepTaxAnnual", item.getDeclareStatus());
                        row.put("overallStatus", calcOverallStatus(row));
                    });

            // ⑦ 缴费明细（始终放入，空时为空列表）
            List<TaskPaymentItem> paymentItems = taskPaymentItemRepository.findByTaskPeriodId(tpId);
            row.put("paymentItems", paymentItems);

            // ⑦+ 未申报税种 & 缴费截图（从 query_payment_national 的 result_json 提取，空时也放入）
            ocTaskRepository.findTopByAccountSetIdAndTaskTypeAndPeriodOrderByCreatedAtDesc(
                    accountSetId, "query_payment_national", p)
                .filter(oc -> StrUtil.isNotBlank(oc.getResultJson()))
                .ifPresent(oc -> {
                    try {
                        Map<String, Object> res = JSON.parseObject(oc.getResultJson(),
                                new com.alibaba.fastjson2.TypeReference<Map<String, Object>>() {});
                        @SuppressWarnings("unchecked")
                        Map<String, Object> data = (res.get("result") instanceof Map)
                                ? (Map<String, Object>) res.get("result") : res;
                        // 未申报税种（空数组也放入）
                        Object unreportedObj = data.get("unreported_taxes");
                        row.put("unreportedTaxes", unreportedObj instanceof List ? unreportedObj : List.of());
                        // 缴费合计（null 时放入空字符串）
                        Object totalTaxes = data.get("total_taxes");
                        row.put("totalTaxes", totalTaxes != null ? totalTaxes.toString() : "");
                        // 首页本期应申报（空数组也放入）
                        Object homeUnreportedObj = data.get("home_unreported");
                        row.put("homeUnreported", homeUnreportedObj instanceof List ? homeUnreportedObj : List.of());
                        // 顶层截图
                        Object screenshotsObj = res.get("screenshot_urls");
                        if (screenshotsObj instanceof List && !((List<?>) screenshotsObj).isEmpty()) {
                            List<String> urls = ((List<?>) screenshotsObj).stream()
                                    .map(Object::toString)
                                    .filter(s -> !s.isBlank())
                                    .map(s -> (s.startsWith("http://") || s.startsWith("https://"))
                                            ? s : "/oc-screenshots/" + s)
                                    .toList();
                            if (!urls.isEmpty()) row.put("paymentScreenshots", urls);
                        }
                    } catch (Exception ex) {
                        log.warn("解析 query_payment_national result_json 失败: ocTaskId={}", oc.getId(), ex);
                    }
                });

            // ⑨ 工资明细（已复核的当期工资）
            {
                YearMonth ymSalary = YearMonth.parse(p, PERIOD_FMT);
                List<Salary> salaries = salaryRepository.findByAccountSetsIdAndYearAndMonth(
                        accountSetId, ymSalary.getYear(), ymSalary.getMonthValue());
                salaries.stream()
                        .filter(s -> Boolean.TRUE.equals(s.getState()))
                        .findFirst()
                        .ifPresent(salary -> {
                            List<cn.gson.financial.kernel.model.entity.SalaryFieldValue> fvList =
                                    salaryFieldValueRepository.findBySalaryId(salary.getId());
                            // 批量加载员工姓名
                            Map<Long, String> empNames = new HashMap<>();
                            fvList.forEach(fv -> {
                                if (fv.getEmpId() != null && !empNames.containsKey(fv.getEmpId())) {
                                    employeeRepository.findById(
                                            new cn.gson.financial.kernel.model.hibernate.PrimaryKey(fv.getEmpId(), accountSetId))
                                            .ifPresent(e -> empNames.put(fv.getEmpId(), e.getName()));
                                }
                            });
                            List<Map<String, Object>> salaryRows = fvList.stream().map(fv -> {
                                Map<String, Object> r = new LinkedHashMap<>();
                                r.put("empId", fv.getEmpId());
                                r.put("name", empNames.getOrDefault(fv.getEmpId(), ""));
                                // fieldValue 是 FieldValue(HashMap<String,Value>)，直接放入即可由 JSON 序列化
                                r.put("fields", fv.getFieldValue());
                                return r;
                            }).collect(Collectors.toList());
                            Map<String, Object> salaryDetail = new LinkedHashMap<>();
                            salaryDetail.put("reviewed", true);
                            salaryDetail.put("employeeCount", fvList.size());
                            salaryDetail.put("rows", salaryRows);
                            row.put("salaryDetail", salaryDetail);
                        });
            }

            // ⑧ 银行流水明细（从 CashierJournal 读当期银行存款记录）
            YearMonth ym = YearMonth.parse(p, PERIOD_FMT);
            LocalDate periodStart = ym.atDay(1);
            LocalDate periodEnd = ym.atEndOfMonth();
            QCashierJournal qj = QCashierJournal.cashierJournal;
            List<CashierJournal> journals = (List<CashierJournal>) cashierJournalRepository.findAll(
                    qj.accountSetsId.eq(accountSetId)
                            .and(qj.type.eq(CashierAccount.CashierAccountType.银行存款))
                            .and(qj.billDate.goe(periodStart))
                            .and(qj.billDate.loe(periodEnd)),
                    qj.billDate.asc()
            );
            if (!journals.isEmpty()) {
                // 批量预加载 ioType 和 subject，避免 N+1
                Map<Long, String> ioTypeNames = new HashMap<>();
                Map<Long, String> subjectNames = new HashMap<>();
                Map<Long, String> subjectCodes = new HashMap<>();
                // 预加载凭证模板默认科目：key = "ioTypeId_direction"(贷/借) → subjectId
                Map<String, Long> tplSubjectIds = new HashMap<>();
                journals.forEach(j -> {
                    if (j.getIoTypeId() != null && !ioTypeNames.containsKey(j.getIoTypeId())) {
                        cashierIoTypeRepository.findById(new cn.gson.financial.kernel.model.hibernate.PrimaryKey(j.getIoTypeId(), accountSetId))
                                .ifPresent(t -> ioTypeNames.put(j.getIoTypeId(), t.getName()));
                    }
                    if (j.getSubjectId() != null && !subjectNames.containsKey(j.getSubjectId())) {
                        subjectRepository.findById(new cn.gson.financial.kernel.model.hibernate.PrimaryKey(j.getSubjectId(), accountSetId))
                                .ifPresent(s -> {
                                    // 优先使用科目全称（含父级），如"应收账款/XX公司"
                                    subjectNames.put(j.getSubjectId(),
                                            cn.hutool.core.util.StrUtil.isNotBlank(s.getFullName()) ? s.getFullName() : s.getName());
                                    subjectCodes.put(j.getSubjectId(), s.getCode());
                                });
                    }
                    // 预加载凭证模板：ioTypeId + direction(来账→贷, 往账→借)
                    if (j.getIoTypeId() != null) {
                        CashierIoTypeVoucherTemplate.Direction dir = j.getDebit() != null
                                ? CashierIoTypeVoucherTemplate.Direction.贷
                                : CashierIoTypeVoucherTemplate.Direction.借;
                        String tplKey = j.getIoTypeId() + "_" + dir.name();
                        if (!tplSubjectIds.containsKey(tplKey)) {
                            cashierIoTypeVoucherTemplateRepository
                                    .findByAccountSetsIdAndIoTypeId(accountSetId, j.getIoTypeId())
                                    .stream()
                                    .filter(t -> t.getDirection() == dir
                                            && t.getJournalType() == CashierAccount.CashierAccountType.银行存款
                                            && t.getSubjectId() != null)
                                    .findFirst()
                                    .ifPresent(t -> tplSubjectIds.put(tplKey, t.getSubjectId()));
                        }
                    }
                });
                // 确保凭证模板默认科目的名称/编码也被预加载
                tplSubjectIds.values().forEach(sid -> {
                    if (!subjectNames.containsKey(sid)) {
                        subjectRepository.findById(new cn.gson.financial.kernel.model.hibernate.PrimaryKey(sid, accountSetId))
                                .ifPresent(s -> {
                                    subjectNames.put(sid,
                                            cn.hutool.core.util.StrUtil.isNotBlank(s.getFullName()) ? s.getFullName() : s.getName());
                                    subjectCodes.put(sid, s.getCode());
                                });
                    }
                });

                List<Map<String, Object>> bankDetails = new ArrayList<>();
                for (CashierJournal j : journals) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", j.getId());
                    item.put("tradeDate", j.getBillDate() != null ? j.getBillDate().toString() : null);
                    item.put("transactionType", j.getDebit() != null ? "来账" : "往账");
                    item.put("tradeAmount", j.getDebit() != null ? j.getDebit() : j.getCredit());
                    item.put("counterparty", j.getOppositeAccountName());
                    item.put("remarks", j.getDigest());
                    item.put("accountId", j.getAccountId());
                    item.put("billDate", j.getBillDate() != null ? j.getBillDate().toString() : null);
                    item.put("ioTypeId", j.getIoTypeId());
                    item.put("ioTypeName", j.getIoTypeId() != null ? ioTypeNames.get(j.getIoTypeId()) : null);
                    // 优先用日记账自身的科目，若没有则用凭证模板配置的默认科目
                    Long effectiveSubjectId = j.getSubjectId();
                    if (effectiveSubjectId == null && j.getIoTypeId() != null) {
                        CashierIoTypeVoucherTemplate.Direction dir = j.getDebit() != null
                                ? CashierIoTypeVoucherTemplate.Direction.贷
                                : CashierIoTypeVoucherTemplate.Direction.借;
                        effectiveSubjectId = tplSubjectIds.get(j.getIoTypeId() + "_" + dir.name());
                    }
                    item.put("subjectId", effectiveSubjectId);
                    item.put("subjectName", effectiveSubjectId != null ? subjectNames.get(effectiveSubjectId) : null);
                    item.put("subjectCode", effectiveSubjectId != null ? subjectCodes.get(effectiveSubjectId) : null);
                    bankDetails.add(item);
                }
                row.put("bankDetails", bankDetails);
            }

            // ⑩ 发票明细
            {
                QInvoice qInv = QInvoice.invoice;
                QInvoiceBusinessType qBt = QInvoiceBusinessType.invoiceBusinessType;
                List<Invoice> invoices = bqf.selectFrom(qInv)
                        .where(qInv.accountSetsId.eq(accountSetId)
                                .and(qInv.assignmentDate.between(periodStart, periodEnd)
                                        .or(qInv.assignmentDate.isNull()
                                                .and(qInv.tradeDate.between(periodStart, periodEnd)))))
                        .orderBy(qInv.tradeDate.asc())
                        .fetch();
                if (!invoices.isEmpty()) {
                    // 预加载业务类型名称
                    Set<Long> btIds = invoices.stream()
                            .map(Invoice::getInvoiceBusinessTypeId)
                            .filter(Objects::nonNull)
                            .collect(Collectors.toSet());
                    Map<Long, String> btNames = btIds.isEmpty() ? Collections.emptyMap()
                            : bqf.selectFrom(qBt)
                                .where(qBt.accountSetsId.eq(accountSetId).and(qBt.id.in(btIds)))
                                .fetch().stream()
                                .collect(Collectors.toMap(InvoiceBusinessType::getId, InvoiceBusinessType::getTitle, (a, b) -> a));

                    List<Map<String, Object>> invoiceDetails = new ArrayList<>();
                    for (Invoice inv : invoices) {
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("id", inv.getId());
                        item.put("type", inv.getType() != null ? inv.getType().name() : null);
                        item.put("tradeDate", inv.getTradeDate() != null ? inv.getTradeDate().toString() : null);
                        item.put("invoiceNumber", inv.getInvoiceNumber());
                        item.put("customerName", inv.getCustomerName());
                        item.put("amount", inv.getAmount());
                        item.put("taxAmount", inv.getTaxAmount());
                        item.put("businessTypeName", inv.getInvoiceBusinessTypeId() != null
                                ? btNames.get(inv.getInvoiceBusinessTypeId()) : null);
                        item.put("state", inv.getState() != null ? inv.getState().name() : null);
                        item.put("showStatus", inv.getShowStatus());
                        item.put("newCategory", inv.getNewCategory());
                        invoiceDetails.add(item);
                    }
                    row.put("invoiceDetails", invoiceDetails);
                }

                // ── 税务提醒汇总 ──
                boolean isSmallScale = as.getVatType() == VatType.SMALL_SCALE;
                QInvoice qInv2 = QInvoice.invoice;
                Map<String, Object> taxTip = new LinkedHashMap<>();
                taxTip.put("vatType", isSmallScale ? "small" : "general");

                if (isSmallScale) {
                    // 小规模：季度免税额度30万（不含税销售额）
                    int monthVal = ym.getMonthValue();
                    int q = (monthVal - 1) / 3; // 0=Q1, 1=Q2, 2=Q3, 3=Q4
                    LocalDate qStart = LocalDate.of(ym.getYear(), q * 3 + 1, 1);
                    LocalDate qEnd = YearMonth.of(ym.getYear(), q * 3 + 3).atEndOfMonth();
                    // 查询本季度所有销项发票不含税额合计
                    List<Invoice> qOutInvoices = bqf.selectFrom(qInv2)
                            .where(qInv2.accountSetsId.eq(accountSetId)
                                    .and(qInv2.type.eq(InvoiceType.OUT))
                                    .and(qInv2.assignmentDate.between(qStart, qEnd)
                                            .or(qInv2.assignmentDate.isNull()
                                                    .and(qInv2.tradeDate.between(qStart, qEnd)))))
                            .fetch();
                    java.math.BigDecimal qtySales = qOutInvoices.stream()
                            .map(Invoice::getTaxAmountExcluded)
                            .filter(Objects::nonNull)
                            .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
                    java.math.BigDecimal limit = new java.math.BigDecimal("300000");
                    java.math.BigDecimal remaining = limit.subtract(qtySales);
                    taxTip.put("quarterLabel", "Q" + (q + 1));
                    taxTip.put("quarterStart", qStart.toString());
                    taxTip.put("quarterEnd", qEnd.toString());
                    taxTip.put("quarterlySales", qtySales);
                    taxTip.put("quarterlyLimit", limit);
                    taxTip.put("quarterlyRemaining", remaining);
                    taxTip.put("exceeded", qtySales.compareTo(limit) > 0);
                    if (qtySales.compareTo(limit) > 0) {
                        taxTip.put("tip", "本季度不含税销售额已超过30万免税额度，需全额缴纳增值税");
                    } else if (qtySales.compareTo(new java.math.BigDecimal("270000")) > 0) {
                        taxTip.put("tip", "本季度不含税销售额已接近30万免税额度（剩余" + remaining.setScale(2, java.math.RoundingMode.HALF_UP) + "元），注意控制开票金额");
                    }
                } else {
                    // 一般纳税人：进项税 vs 销项税
                    // 当月数据已在 invoices 变量中（如果前面查过），否则重新查
                    java.math.BigDecimal inputTax = java.math.BigDecimal.ZERO;
                    java.math.BigDecimal outputTax = java.math.BigDecimal.ZERO;
                    List<Invoice> allInv = bqf.selectFrom(qInv2)
                            .where(qInv2.accountSetsId.eq(accountSetId)
                                    .and(qInv2.assignmentDate.between(periodStart, periodEnd)
                                            .or(qInv2.assignmentDate.isNull()
                                                    .and(qInv2.tradeDate.between(periodStart, periodEnd)))))
                            .fetch();
                    for (Invoice inv : allInv) {
                        if (inv.getType() == InvoiceType.IN && inv.getTaxAmount() != null) {
                            inputTax = inputTax.add(inv.getTaxAmount());
                        } else if (inv.getType() == InvoiceType.OUT && inv.getTaxAmount() != null) {
                            outputTax = outputTax.add(inv.getTaxAmount());
                        }
                    }
                    java.math.BigDecimal vatPayable = outputTax.subtract(inputTax);
                    taxTip.put("inputTax", inputTax);
                    taxTip.put("outputTax", outputTax);
                    taxTip.put("vatPayable", vatPayable);
                    if (vatPayable.signum() > 0) {
                        taxTip.put("tip", "销项税额大于进项税额，本期预计应缴增值税 " + vatPayable.setScale(2, java.math.RoundingMode.HALF_UP) + " 元");
                    } else if (vatPayable.signum() < 0) {
                        taxTip.put("tip", "进项税额大于销项税额，本期有留抵税额 " + vatPayable.abs().setScale(2, java.math.RoundingMode.HALF_UP) + " 元");
                    }
                    // 进项不足提醒
                    if (outputTax.signum() > 0 && inputTax.signum() == 0) {
                        taxTip.put("warning", "本期无进项发票，建议及时补充进项票据以抵扣税额");
                    }
                }
                row.put("taxTip", taxTip);
            }

            // ⑪ 做账前申报税费明细（vat_declare_pre）
            List<cn.gson.financial.kernel.model.entity.TaskVatPre> vatPreList =
                    taskVatPreRepository.findByTaskPeriodIdAndStatus(tpId, "done");
            if (!vatPreList.isEmpty()) {
                List<Map<String, Object>> vatPreMaps = vatPreList.stream().map(v -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("seqNo", v.getSeqNo());
                    m.put("taxName", v.getTaxName());
                    m.put("taxItem", v.getTaxItem());
                    m.put("taxPeriodStart", v.getTaxPeriodStart());
                    m.put("taxPeriodEnd", v.getTaxPeriodEnd());
                    m.put("declareDate", v.getDeclareDate());
                    m.put("paymentDeadline", v.getPaymentDeadline());
                    m.put("taxPayable", v.getTaxPayable());
                    m.put("taxPaid", v.getTaxPaid());
                    m.put("surcharge", v.getSurcharge());
                    return m;
                }).collect(Collectors.toList());
                row.put("vatPreItems", vatPreMaps);
            }
            // 做账前申报结果（result.results）+ 截图，从最近一次 vat_declare_pre OC 任务提取
            ocTaskRepository.findTopByAccountSetIdAndTaskTypeAndPeriodOrderByCreatedAtDesc(
                    accountSetId, "vat_declare_pre", p)
                    .filter(oc -> StrUtil.isNotBlank(oc.getResultJson()))
                    .ifPresent(oc -> {
                        try {
                            Map<String, Object> res = JSON.parseObject(oc.getResultJson(),
                                    new com.alibaba.fastjson2.TypeReference<Map<String, Object>>() {});
                            @SuppressWarnings("unchecked")
                            Map<String, Object> data = (res.get("result") instanceof Map)
                                    ? (Map<String, Object>) res.get("result") : res;
                            // ① 申报结果明细（result.results）
                            Object resultsObj = data.get("results");
                            if (resultsObj instanceof Map) {
                                @SuppressWarnings("unchecked")
                                Map<String, Object> resultsMap = (Map<String, Object>) resultsObj;
                                List<Map<String, Object>> declareResults = new ArrayList<>();
                                for (Map.Entry<String, Object> e : resultsMap.entrySet()) {
                                    if (!(e.getValue() instanceof Map)) continue;
                                    @SuppressWarnings("unchecked")
                                    Map<String, Object> item = (Map<String, Object>) e.getValue();
                                    Map<String, Object> ti = new LinkedHashMap<>();
                                    ti.put("taxName", e.getKey());
                                    Object statusVal = item.get("status");
                                    String ds;
                                    if ("done".equals(statusVal) || Boolean.TRUE.equals(item.get("success"))) {
                                        ds = "done";
                                    } else if ("skipped".equals(statusVal)) {
                                        ds = "skipped";
                                    } else {
                                        ds = "failed";
                                    }
                                    ti.put("declareStatus", ds);
                                    ti.put("errorMsg", "done".equals(ds) || "skipped".equals(ds)
                                            ? null : (item.get("error") != null ? item.get("error") : item.get("failure_reason")));
                                    Object shots = item.get("screenshots") instanceof List ? item.get("screenshots") : item.get("img_urls");
                                    if (shots instanceof List && !((List<?>) shots).isEmpty()) {
                                        List<String> urls = ((List<?>) shots).stream()
                                                .map(Object::toString).filter(s -> !s.isBlank())
                                                .map(s -> s.startsWith("http://") || s.startsWith("https://") ? s : "/oc-screenshots/" + s)
                                                .toList();
                                        if (!urls.isEmpty()) ti.put("screenshotUrls", JSON.toJSONString(urls));
                                    }
                                    declareResults.add(ti);
                                }
                                if (!declareResults.isEmpty()) row.put("vatPreDeclareResults", declareResults);
                            }
                            // ② 顶层截图
                            Object screenshotsObj = res.get("screenshot_urls");
                            if (screenshotsObj instanceof List && !((List<?>) screenshotsObj).isEmpty()) {
                                List<String> urls = ((List<?>) screenshotsObj).stream()
                                        .map(Object::toString).filter(s -> !s.isBlank())
                                        .map(s -> s.startsWith("http://") || s.startsWith("https://") ? s : "/oc-screenshots/" + s)
                                        .toList();
                                if (!urls.isEmpty()) row.put("vatPreScreenshots", urls);
                            }
                        } catch (Exception ex) {
                            log.warn("解析 vat_declare_pre result_json 失败: ocTaskId={}", oc.getId(), ex);
                        }
                    });

        } else {
            row.put("subTasks", Collections.emptyList());
            row.put("ocTasks", Collections.emptyList());
        }

        // FN-BOOKKEEP-SUMMARY-AGGREGATE：做账阶段聚合（凭证数/存疑笔数/跳过类型），供 BT 做账节点抽屉
        row.put("bookkeepSummary", buildBookkeepSummary(accountSetId, as, p, tp));

        // FN-PREDECLARE-CONFIRM-FLAG：暴露「预申报财务已确认」状态（向后兼容，无 tp 时返 false）
        row.put("preDeclareConfirmed", tp != null && Boolean.TRUE.equals(tp.getPreDeclareConfirmed()));
        row.put("preDeclareConfirmedAt", tp != null && tp.getPreDeclareConfirmedAt() != null
                ? tp.getPreDeclareConfirmedAt().toString() : null);
        row.put("preDeclareConfirmedBy", tp != null ? tp.getPreDeclareConfirmedBy() : null);

        return row;
    }

    /**
     * FN-BOOKKEEP-SUMMARY-AGGREGATE：做账阶段聚合，供 BT「账套流程图右侧抽屉」做账节点渲染。
     * <p><b>纯读聚合，不动做账业务逻辑</b>。
     * <ul>
     *   <li>{@code voucherCount}：本期已生成凭证数，按 accountSetId + 账期(year+month) count；
     *       小规模归一到季末月（与采集/入库 assignmentDate 落季末同口径，不串季）。<b>核心字段</b>。</li>
     *   <li>{@code uncertainCount}：存疑/待确认笔数。当前做账链路<b>无结构化"存疑"标记</b>，按工单 §2(b)
     *       返 0（不伪造），待后续评估是否落库标记。</li>
     *   <li>{@code skippedTypes}：做账中跳过的数据子类型（{@code TaskDataSub.status = skipped}），无则省略。</li>
     * </ul>
     */
    /** 小规模做账聚合的季末月（Q1→3 / Q2→6 / Q3→9 / Q4→12），与采集/入库季末口径一致。 */
    static int quarterEndMonth(int month) {
        int q = (month - 1) / 3;   // 0=Q1 … 3=Q4
        return q * 3 + 3;
    }

    // FN-VERIFY-READ-MONTHLY：原 normalizeVerifyPeriod（核验小规模季末月归一）已废弃删除——
    // jianye 定调核验改按月，读写均按原始月，不再归一。（quarterEndMonth 仍供做账/采集季末口径用，保留。）

    private Map<String, Object> buildBookkeepSummary(Long accountSetId, AccountSets as, String period, TaskPeriod tp) {
        Map<String, Object> summary = new LinkedHashMap<>();

        YearMonth ym = YearMonth.parse(period, PERIOD_FMT);
        int effYear = ym.getYear();
        int effMonth = ym.getMonthValue();
        if (as != null && as.getVatType() == VatType.SMALL_SCALE) {
            // 小规模季末月口径：与采集/入库（assignmentDate 落季末）一致，避免季中月查显示 0
            effMonth = quarterEndMonth(ym.getMonthValue());
        }

        cn.gson.financial.kernel.model.entity.QVoucher qv = cn.gson.financial.kernel.model.entity.QVoucher.voucher;
        long voucherCount = bqf.selectFrom(qv)
                .where(qv.accountSetsId.eq(accountSetId)
                        .and(qv.year.eq(effYear))
                        .and(qv.month.eq(effMonth)))
                .fetchCount();
        summary.put("voucherCount", voucherCount);

        // 存疑/待确认笔数：做账链路暂无结构化标记（工单 §2(b)）→ 返 0 待跟进，不伪造
        summary.put("uncertainCount", 0);

        // 做账中跳过的数据子类型
        if (tp != null) {
            List<String> skippedTypes = taskDataSubRepository.findByTaskPeriodId(tp.getId()).stream()
                    .filter(s -> "skipped".equals(s.getStatus()))
                    .map(TaskDataSub::getSubType)
                    .filter(Objects::nonNull)
                    .distinct()
                    .collect(Collectors.toList());
            if (!skippedTypes.isEmpty()) summary.put("skippedTypes", skippedTypes);
        }
        return summary;
    }

    // ─────────────────────────────────────────────────────────────────────
    // FN-PREDECLARE-CONFIRM-FLAG：预申报财务已确认（置位 / 撤销 / 状态）
    // ─────────────────────────────────────────────────────────────────────

    /**
     * 置位「预申报财务已确认」（报税前 gate 落库）。幂等：重复 confirm 不报错，刷新确认时间/操作人为最新。
     * 不改 declare-data / 报税逻辑；报税硬校验为可选开关、默认关闭（见工单 §2.4/§6.2，本服务不在此处卡报税）。
     */
    @Transactional
    public Map<String, Object> confirmPreDeclare(Long accountSetsId, String period, String confirmedBy) {
        AccountSets as = accountSetsRepository.findById(accountSetsId)
                .orElseThrow(() -> new IllegalArgumentException("账套不存在：" + accountSetsId));
        TaskPeriod tp = getOrCreateTaskPeriod(accountSetsId, period, as);
        tp.setPreDeclareConfirmed(true);
        tp.setPreDeclareConfirmedAt(LocalDateTime.now());
        tp.setPreDeclareConfirmedBy(StrUtil.isBlank(confirmedBy) ? "system" : confirmedBy.trim());
        taskPeriodRepository.save(tp);
        return preDeclareStatus(accountSetsId, period, tp);
    }

    /**
     * 撤销「预申报财务已确认」（数据有误退回重做）。幂等：未确认时调用仍安全回 0。
     */
    @Transactional
    public Map<String, Object> cancelPreDeclare(Long accountSetsId, String period) {
        AccountSets as = accountSetsRepository.findById(accountSetsId)
                .orElseThrow(() -> new IllegalArgumentException("账套不存在：" + accountSetsId));
        TaskPeriod tp = getOrCreateTaskPeriod(accountSetsId, period, as);
        tp.setPreDeclareConfirmed(false);
        tp.setPreDeclareConfirmedAt(null);
        tp.setPreDeclareConfirmedBy(null);
        taskPeriodRepository.save(tp);
        return preDeclareStatus(accountSetsId, period, tp);
    }

    private Map<String, Object> preDeclareStatus(Long accountSetsId, String period, TaskPeriod tp) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("accountSetsId", accountSetsId);
        m.put("period", period);
        m.put("taskPeriodId", tp.getId());
        m.put("preDeclareConfirmed", Boolean.TRUE.equals(tp.getPreDeclareConfirmed()));
        m.put("preDeclareConfirmedAt", tp.getPreDeclareConfirmedAt() != null ? tp.getPreDeclareConfirmedAt().toString() : null);
        m.put("preDeclareConfirmedBy", tp.getPreDeclareConfirmedBy());
        return m;
    }

    // ─────────────────────────────────────────────────────────────────────
    // STAGE-STATUS-PERSIST：阶段状态显式落库（TaskPeriod 单一真相源）
    // ─────────────────────────────────────────────────────────────────────

    /** 通用写端点的 step 名 → TaskPeriod 实体字段名 白名单。 */
    public static final Map<String, String> STEP_FIELD_WHITELIST = Map.ofEntries(
            Map.entry("verify", "stepVerify"),
            Map.entry("init", "stepInit"),
            Map.entry("data", "stepData"),
            Map.entry("bookkeep", "stepBookkeep"),
            Map.entry("carry", "stepCarry"),
            Map.entry("tax_national", "stepTaxNational"),
            Map.entry("tax_iit", "stepTaxIit"),
            Map.entry("payment", "stepPayment"),
            Map.entry("tax_annual", "stepTaxAnnual"),
            Map.entry("archive", "stepArchive")
    );

    /**
     * 通用写端点（BT 回写）允许的状态值——operational 子集，BT 只该写这 5 个。
     * 注意：这是<b>对外端点</b>的更紧白名单，⊂ {@link #VALID_STEP_STATUSES}。
     */
    public static final Set<String> STEP_STATUS_WHITELIST = Set.of(
            "pending", "running", "done", "error", "skipped");

    /**
     * FN-TASK-PERIOD-SCHEMA-HARDEN：step_* 列的<b>规范状态集</b>（应用层校验，不上 DB ENUM，词表可演进）。
     * <p>按代码实证固化（{@code calcStats}/{@code COMPLETED_STATUSES} + 各阶段实际写值）：
     * 核心 {@code pending/running/done/error/skipped/na} + FN 内部态 {@code warn}(有警告,如欠税/未申报)、
     * {@code waiting}(人工确认模式待确认)、{@code blocked}(前置未完成被挡)。
     * <p>低层写入（{@link #setStepStatusByField}）统一过此集；对外端点另用更紧的 {@link #STEP_STATUS_WHITELIST}。
     * <p><b>⚠ 上线前须 {@code SELECT DISTINCT step_*} 全表盘点实际值核对此集</b>（见工单 §5.2），
     * 若线上存在此处未列入的历史态须补入，避免合法历史态被写校验误拒。
     */
    public static final Set<String> VALID_STEP_STATUSES = Set.of(
            "pending", "running", "done", "error", "skipped", "na", "warn", "waiting", "blocked");

    /** 校验状态值 ∈ 规范状态集，否则抛出（控制器映射为参数错误）。 */
    static void assertValidStatus(String status) {
        if (!VALID_STEP_STATUSES.contains(status)) {
            throw new IllegalArgumentException("status 取值非法：" + status + "，允许：" + VALID_STEP_STATUSES);
        }
    }

    /**
     * 状态机「不许跳级」前置链：写某 step=done 前，其前置 step 必须 done/skipped（否则拒绝）。
     * 仅约束做账主链 bookkeep→carry→报税→archive；verify/init/data/tax_annual 不门控。
     * <p><b>刻意不设 bookkeep←data</b>：采集 step_data 与做账 step_bookkeep 是<b>异源异步</b>回写
     * （数据采集 vs xiaowei/BT 做账，完成时序无强先后），硬卡 data 先于 bookkeep 会误触发 4490。
     * 设 done 以外状态（running/error/pending/skipped）不门控（可随时标记失败/进行中）。
     */
    static final Map<String, String> STEP_PREREQ = Map.ofEntries(
            Map.entry("carry", "bookkeep"),
            Map.entry("tax_national", "carry"),
            Map.entry("tax_iit", "carry"),
            Map.entry("payment", "tax_national"),
            Map.entry("archive", "payment")
    );

    private static boolean isDoneLike(String status) {
        return "done".equals(status) || "skipped".equals(status) || "na".equals(status);
    }

    /**
     * 状态机「不许跳级」判定（纯逻辑，可测）：写 {@code step=status} 是否应被拒。
     * 仅 {@code status=done} 且存在前置 step 且前置非 done/skipped/na 时拒（返回原因文案）；否则放行（返回 null）。
     * @param prereqStatusOf 给前置 step 名返回其当前状态值
     */
    static String stepSkipLevelReject(String step, String status,
                                      java.util.function.Function<String, String> prereqStatusOf) {
        if (!"done".equals(status)) return null;
        String prereq = STEP_PREREQ.get(step);
        if (prereq == null) return null;
        String prereqVal = prereqStatusOf.apply(prereq);
        if (isDoneLike(prereqVal)) return null;
        return "状态机校验失败：前置步骤 " + prereq + " 未完成（当前=" + prereqVal
                + "），不允许跳级写 " + step + "=done";
    }

    /**
     * 直接写某 step 状态（<b>不做状态机校验</b>），<b>独立事务 best-effort</b>，供"状态写失败也不该拖垮主流程"
     * 的<b>次要</b>回写用（如关账<b>失败分支</b> 4402/4403 的 step_carry=error/done 信息性回写）。
     * <p>用 {@code REQUIRES_NEW}：状态落库失败/回滚不污染外层事务，调用方可安全 try/catch 吞异常。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public TaskPeriod writeStepStatusDirect(Long accountSetId, String period, String stepField, String status) {
        AccountSets as = accountSetsRepository.findById(accountSetId)
                .orElseThrow(() -> new IllegalArgumentException("账套不存在：" + accountSetId));
        TaskPeriod tp = getOrCreateTaskPeriod(accountSetId, period, as);
        setStepStatusByField(tp, stepField, status);
        return taskPeriodRepository.save(tp);
    }

    /**
     * 直接写某 step 状态，<b>同事务（REQUIRED）原子</b>，供「完成即写」的<b>主成功路径</b>用
     * （如关账<b>成功</b>内联写 step_carry=done）。
     * <p>STAGE-STATUS-PERSIST §8（纯落库展示）可靠性：与调用方主操作<b>同事务提交/回滚</b>——
     * 主操作成功 ⟺ 状态已落库，<b>不存在"成功了但状态没写"的漂移窗口</b>；状态写失败则主操作一并回滚（漏写=显错，调用方重试）。
     */
    @Transactional
    public TaskPeriod writeStepStatusTx(Long accountSetId, String period, String stepField, String status) {
        AccountSets as = accountSetsRepository.findById(accountSetId)
                .orElseThrow(() -> new IllegalArgumentException("账套不存在：" + accountSetId));
        TaskPeriod tp = getOrCreateTaskPeriod(accountSetId, period, as);
        setStepStatusByField(tp, stepField, status);
        return taskPeriodRepository.save(tp);
    }

    /**
     * STAGE-STATUS-PERSIST §8：批量回填一个账套×账期的多列 step_*（存量回填 / 对账修复共用）。
     * 一个事务内 getOrCreateTaskPeriod + 逐列 set + save。账套不存在则静默跳过（回填全量时容错）。
     */
    @Transactional
    public void backfillStepColumns(Long accountId, String period, Map<String, String> fieldStatus) {
        AccountSets as = accountSetsRepository.findById(accountId).orElse(null);
        if (as == null) return;
        TaskPeriod tp = getOrCreateTaskPeriod(accountId, period, as);
        fieldStatus.forEach((field, status) -> setStepStatusByField(tp, field, status));
        taskPeriodRepository.save(tp);
    }

    /**
     * 通用写端点入口：step/status 走白名单 + done 走状态机校验（不许跳级），再落库。
     * 供 BT 原生链（采集/做账/报税完成）回写。越权防护：account 存在性 + 白名单 + 状态机。
     *
     * @throws IllegalArgumentException step/status 非法或账套不存在（控制器映射为参数错误）
     * @throws IllegalStateException    状态机校验失败（控制器映射为 4490）
     */
    @Transactional
    public Map<String, Object> writeStepStatusChecked(Long accountSetId, String period, String step, String status) {
        String field = STEP_FIELD_WHITELIST.get(step);
        if (field == null) {
            throw new IllegalArgumentException("step 取值非法：" + step + "，允许：" + STEP_FIELD_WHITELIST.keySet());
        }
        if (!STEP_STATUS_WHITELIST.contains(status)) {
            throw new IllegalArgumentException("status 取值非法：" + status + "，允许：" + STEP_STATUS_WHITELIST);
        }
        AccountSets as = accountSetsRepository.findById(accountSetId)
                .orElseThrow(() -> new IllegalArgumentException("账套不存在：" + accountSetId));
        TaskPeriod tp = getOrCreateTaskPeriod(accountSetId, period, as);

        // 状态机：done 不许跳级（前置 step 必须 done/skipped）
        String reject = stepSkipLevelReject(step, status,
                ps -> getStepStatusByField(tp, STEP_FIELD_WHITELIST.get(ps)));
        if (reject != null) {
            throw new IllegalStateException(reject);
        }

        setStepStatusByField(tp, field, status);
        taskPeriodRepository.save(tp);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("accountSetsId", accountSetId);
        m.put("period", period);
        m.put("taskPeriodId", tp.getId());
        m.put("step", step);
        m.put("field", field);
        m.put("status", status);
        return m;
    }

    // ─────────────────────────────────────────────────────────────────────
    // 下发 & 重置
    // ─────────────────────────────────────────────────────────────────────

    /**
     * 下发单步骤任务
     */
    @Transactional
    public Map<String, Object> dispatch(Long accountSetId, String period, String step, boolean force) {
        return dispatch(accountSetId, period, step, force, null, null);
    }

    /**
     * 下发单步骤任务（可指定 OpenClaw 实例编号）
     * @param ocInstanceId 目标 OC 实例编号，为 null 时由任意可用实例处理
     */
    @Transactional
    public Map<String, Object> dispatch(Long accountSetId, String period, String step, boolean force, String ocInstanceId) {
        return dispatch(accountSetId, period, step, force, ocInstanceId, null);
    }

    /**
     * 下发单步骤任务（完整参数版）
     * @param ocInstanceId 目标 OC 实例编号，为 null 时由任意可用实例处理
     * @param subType      数据采集子任务类型（data 步骤专用）：
     *                     invoice_in / invoice_out / invoice_checked / invoice_unchecked /
     *                     bank / salary；为 null 时全量下发所有数据子任务
     */
    @Transactional
    public Map<String, Object> dispatch(Long accountSetId, String period, String step, boolean force, String ocInstanceId, String subType) {
        String p = resolvePeriod(period);
        AccountSets as = accountSetsRepository.findById(accountSetId)
                .orElseThrow(() -> new IllegalArgumentException("账套不存在：" + accountSetId));

        // 未配置 OC 实例的账套不允许下发任务
        if (StrUtil.isBlank(ocInstanceId) && StrUtil.isBlank(as.getOcInstanceId())) {
            return Map.of("success", false, "msg", "该账套未配置 OC 实例，请先绑定后再下发任务");
        }

        TaskPeriod tp = getOrCreateTaskPeriod(accountSetId, p, as);

        // 检查状态，非 force 时不允许重复下发
        String currentStatus = getStepStatus(tp, step);
        boolean isSubTask = ("bookkeep".equals(step) || "data".equals(step)) && StrUtil.isNotBlank(subType);
        // tax_annual 跨月共享，tp 上的状态可能过时，跳过此处检查，由 dispatchTaxAnnualStep 内部去重
        boolean isTaxAnnual = "tax_annual".equals(step);

        // ── 子任务级别去重：避免重复下发产生多份 OcTask 浪费资源 / 引起数据竞争 ──
        // 用子任务自身的 status（而非 step 整体状态）判断，保证不同子任务可独立并发执行
        if (isSubTask) {
            String subStatus = "data".equals(step)
                    ? taskDataSubRepository.findByTaskPeriodIdAndSubType(tp.getId(), subType)
                            .map(TaskDataSub::getStatus).orElse("pending")
                    : taskBookkeepSubRepository.findByTaskPeriodIdAndSubType(tp.getId(), subType)
                            .map(TaskBookkeepSub::getStatus).orElse("pending");
            if ("running".equals(subStatus)) {
                return Map.of("success", false, "msg", "该子任务正在执行中，请稍候");
            }
            if ("waiting".equals(subStatus) && !force) {
                return Map.of("success", true, "waiting", true,
                        "msg", "该子任务已执行完毕，请核查结果后点击确认完成");
            }
            if ("done".equals(subStatus) && !force) {
                return Map.of("success", false, "msg", "该子任务已完成，如需重新执行请先重置");
            }
            if ("skipped".equals(subStatus) && !force) {
                // 重新下发跳过的子任务：账期仍结账则维持跳过，否则放行重新执行（无需先重置）
                if (isPeriodCheckedOut(accountSetId, p)) {
                    return Map.of("success", true, "skipped", true, "msg", "该子任务已跳过");
                }
                log.info("重新下发跳过子任务：账期未结账，放行重新执行: accountSetId={}, period={}, subType={}",
                        accountSetId, p, subType);
            }
            // pending / error / 已反结账的 skipped 放行继续走下发流程
        }

        if ("running".equals(currentStatus) && !isSubTask && !isTaxAnnual) {
            return Map.of("success", false, "msg", "该步骤正在执行中，请稍候");
        }
        if ("done".equals(currentStatus) && !force && !isSubTask && !isTaxAnnual) {
            return Map.of("success", false, "msg", "该步骤已完成，如需重新执行请使用重置");
        }
        // 账期已结账导致的跳过状态：重新下发时复核结账状态，无需先重置
        // - 账期仍处于结账状态：维持跳过，直接返回
        // - 账期已反结账（不再满足跳过条件）：清除过时的跳过状态，继续走正常下发流程
        // 例外：做账前申报（vat_declare_pre）即使账期已结账也允许单独下发，用于补全做账前税款申报数据
        if ("skipped".equals(currentStatus) && !force
                && !"vat_declare_pre".equals(subType)
                && (List.of("verify", "init", "data", "bookkeep").contains(step))) {
            if (isPeriodCheckedOut(accountSetId, p)) {
                return Map.of("success", true, "skipped", true, "msg", "该账期已结账，该步骤已自动跳过");
            }
            // 账期已反结账，清除过时的跳过状态后继续下发
            setStepStatus(tp, step, "pending");
            currentStatus = "pending";
            log.info("重新下发跳过步骤：账期已反结账，清除跳过状态继续下发: accountSetId={}, period={}, step={}",
                    accountSetId, p, step);
        }
        // 已在等待前置任务队列中，检查前置条件是否已满足，满足则放行继续执行。
        // isSubTask=true（如 bookkeep.invoice）时跳过此检查，让代码继续走到子任务创建逻辑
        if ("blocked".equals(currentStatus) && !force && !isSubTask) {
            boolean canProceed = switch (step) {
                case "bookkeep" -> isStepPassed(tp.getStepData());
                case "vat"      -> isStepPassed(tp.getStepBookkeep());
                case "payment"  -> { String vs = tp.getStepTaxNational(); yield isTaxNationalPassed(vs) || "error".equals(vs) || "failed".equals(vs); }
                default         -> false; // iit 无前置依赖，不会进入 blocked 状态
            };
            if (!canProceed) {
                return Map.of("success", true, "blocked", true, "msg", "前置任务尚未完成，该步骤已进入等待队列，待前置完成后自动执行");
            }
        }
        // 已执行完毕等待人工确认，不重复触发
        if ("waiting".equals(currentStatus) && !force) {
            return Map.of("success", true, "waiting", true, "msg", "该步骤已执行完毕，请核查结果后点击确认完成");
        }

        // 核验税务前置校验：必须配置至少一个办税员
        if ("verify".equals(step)) {
            if (!taxOfficerRepository.existsByAccountSetId(accountSetId)) {
                return Map.of("success", false, "msg", "请先为该账套配置办税员，再下发核验税务任务");
            }
        }

        // 确定本次使用的 OC 实例：优先使用参数传入值，其次用 task_period 上已绑定的值，最后兜底到账套配置
        String resolvedOcId = StrUtil.isNotBlank(ocInstanceId) ? ocInstanceId
                : StrUtil.isNotBlank(tp.getOcInstanceId()) ? tp.getOcInstanceId()
                : as.getOcInstanceId();
        if (StrUtil.isNotBlank(resolvedOcId)) {
            tp.setOcInstanceId(resolvedOcId);
        }

        // ── 数据采集步骤（无前置条件）──────────────────────────────────────
        if ("data".equals(step) || "invoice".equals(step) || "invoice_in".equals(step)
                || "invoice_out".equals(step) || "invoice_check".equals(step)
                || "invoice_checked".equals(step) || "invoice_unchecked".equals(step)
                || "bank".equals(step) || "salary".equals(step) || "vat_declare_pre".equals(step)) {
            // 旧 step 名（bank/salary/invoice_* / vat_declare_pre 等）映射为 subType
            String resolvedSubType = StrUtil.isNotBlank(subType) ? subType
                    : switch (step) {
                        case "bank", "salary",
                                "invoice_in", "invoice_out", "invoice_check",
                                "invoice_checked", "invoice_unchecked",
                                "vat_declare_pre" -> step;
                        default -> null; // "data" → 全量下发
                    };
            // 小规模纳税人无需勾选/已勾/未勾发票流程
            if ("invoice_check".equals(resolvedSubType)
                    || "invoice_checked".equals(resolvedSubType)
                    || "invoice_unchecked".equals(resolvedSubType)) {
                if (as.getVatType() == VatType.SMALL_SCALE) {
                    return Map.of("success", false, "msg", "小规模纳税人无需勾选/下载已勾未勾发票");
                }
            }
            if ("invoice".equals(step)) {
                // 只下发 4 个发票子任务
                return dispatchInvoiceSubTasks(tp, as, p, resolvedOcId);
            } else if (StrUtil.isNotBlank(resolvedSubType)) {
                return dispatchDataSubTask(tp, as, resolvedSubType, p, resolvedOcId);
            } else {
                return dispatchAllDataSubTasks(tp, as, p, resolvedOcId);
            }
        }

        // ── 国税申报步骤 ─────────────────────────────────────────────────
        if ("vat".equals(step)) {
            String bookkeep = tp.getStepBookkeep();
            if (!isStepPassed(bookkeep)) {
                // 前置未完成 → 创建 blocked 的 oc_task，前置完成后自动解锁为 queued
                createBlockedOcTask(tp, accountSetId, p, "vat_declare_post", resolvedOcId,
                        buildPayload(as, p, resolvedOcId, "vat_declare_post"));
                tp.setStepTaxNational("blocked");
                taskPeriodRepository.save(tp);
                log.info("国税申报任务已创建(blocked)，等待做账完成: accountSetId={}", accountSetId);
                return Map.of("success", true, "blocked", true, "msg", "做账步骤尚未完成，国税申报任务已创建并等待解锁");
            }
            // 已结账：data 步骤被跳过，vat_declare_pre 可能未执行，一并下发以补全税款申报
            boolean checkedOut = "skipped".equals(tp.getStepData()) && "done".equals(tp.getStepBookkeep());
            return dispatchVatStep(tp, as, p, resolvedOcId, checkedOut);
        }

        // ── 做账步骤（内部任务，4个子任务各插 oc_task 记录）───────────
        if ("bookkeep".equals(step)) {
            String data = tp.getStepData();
            if (!isStepPassed(data)) {
                // 前置未完成 → 创建 blocked 的 oc_task，数据完成后自动解锁
                List<String> bkTypes = (subType == null || subType.isEmpty())
                        ? List.of("asset", "invoice", "salary", "bank", "tax", "carry_forward")
                        : List.of(subType);
                for (String bkType : bkTypes) {
                    createBlockedOcTask(tp, accountSetId, p, "bookkeep_" + bkType, resolvedOcId, null);
                }
                tp.setStepBookkeep("blocked");
                taskPeriodRepository.save(tp);
                log.info("做账任务已创建(blocked)，等待数据完成: accountSetId={}", accountSetId);
                return Map.of("success", true, "blocked", true, "msg", "获取数据步骤尚未完成，做账任务已创建并等待解锁");
            }
            tp.setStepBookkeep("running");
            taskPeriodRepository.save(tp);

            // 仅为实际要执行的子任务创建 oc_task（subType=null 时全部，否则只创建指定的）
            List<String> bkTypes = (subType == null || subType.isEmpty())
                    ? List.of("asset", "invoice", "salary", "bank", "tax", "carry_forward")
                    : List.of(subType);
            Map<String, OcTask> bookkeepOcTasks = new LinkedHashMap<>();
            for (String bkType : bkTypes) {
                // 复用已有的 blocked oc_task，或创建新的
                String taskType = "bookkeep_" + bkType;
                OcTask ocTask = ocTaskRepository.findFirstByTaskPeriodIdAndTaskTypeAndStatus(
                        tp.getId(), taskType, "blocked").orElse(null);
                if (ocTask != null) {
                    ocTask.setStatus("running");
                    ocTaskRepository.save(ocTask);
                } else {
                    ocTask = new OcTask();
                    ocTask.setAccountSetId(accountSetId);
                    ocTask.setPeriod(p);
                    ocTask.setTaskType(taskType);
                    ocTask.setStatus("running");
                    ocTask.setTaskPeriodId(tp.getId());
                    ocTask.setOcInstanceId(resolvedOcId);
                    ocTaskRepository.save(ocTask);
                }
                bookkeepOcTasks.put(bkType, ocTask);
            }

            // dispatch 事务到此结束（创建 oc_task + 设 step=running），
            // 做账执行在事务外进行，避免 AI 调用长时间占用数据库连接
            log.info("做账任务已创建，准备执行: accountSetId={}, period={}", accountSetId, p);
            Long tpId = tp.getId();
            Map<String, Long> ocTaskIds = new LinkedHashMap<>();
            bookkeepOcTasks.forEach((k, v) -> ocTaskIds.put(k, v.getId()));
            // 返回标记，由调用方在事务外执行做账
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("executeBookkeep", true);
            result.put("taskPeriodId", tpId);
            result.put("ocTaskIds", ocTaskIds);
            result.put("accountSetId", accountSetId);
            result.put("period", p);
            result.put("subType", subType);
            result.put("force", force);
            return result;
        }

        // ── verify 步骤：4个并行子任务 ─────────────────────────────────
        if ("verify".equals(step)) {
            return dispatchVerifyStep(tp, as, p, resolvedOcId);
        }

        // ── iit 步骤：无 bookkeep 前置依赖，只需个税密码+人员+工资信息 ──
        if ("iit".equals(step)) {
            return dispatchIitStep(tp, as, p, resolvedOcId);
        }

        // ── tax_annual 步骤：税务年报（财务报表年报 + 企业所得税年报）──
        if ("tax_annual".equals(step)) {
            return dispatchTaxAnnualStep(tp, as, p, resolvedOcId, subType);
        }

        // ── payment 步骤：拆分为 national + iit 两条 oc_task ────────────
        if ("payment".equals(step)) {
            String taxNat = tp.getStepTaxNational();
            boolean vatFinished = isTaxNationalPassed(taxNat) || "error".equals(taxNat) || "failed".equals(taxNat);
            if (!vatFinished) {
                // 前置未完成 → 创建 blocked 的 oc_task（携带 payload，解锁后可直接执行）
                createBlockedOcTask(tp, accountSetId, p, "query_payment_national", resolvedOcId,
                        buildPayload(as, p, resolvedOcId, "query_payment_national"));
                tp.setStepPayment("blocked");
                taskPeriodRepository.save(tp);
                log.info("缴费任务已创建(blocked)，等待国税申报完成: accountSetId={}", accountSetId);
                return Map.of("success", true, "blocked", true, "msg", "国税申报步骤尚未完成，缴费任务已创建并等待解锁");
            }
            return dispatchPaymentStep(tp, as, p, resolvedOcId);
        }

        // ── OC 任务（其他）──────────────────────────────────────────────
        String ocTaskType = stepToOcTaskType(step);
        if (ocTaskType != null) {
            OcTask ocTask = new OcTask();
            ocTask.setAccountSetId(accountSetId);
            ocTask.setPeriod(p);
            ocTask.setTaskType(ocTaskType);
            ocTask.setStatus("queued");
            ocTask.setTaskPeriodId(tp.getId());
            ocTask.setOcInstanceId(resolvedOcId);
            ocTask.setPayloadJson(buildPayload(as, p, resolvedOcId, ocTaskType));
            ocTaskRepository.save(ocTask);

            setStepStatus(tp, step, "running");
            taskPeriodRepository.save(tp);
            log.info("下发OC任务: accountSetId={}, period={}, step={}, ocTaskType={}", accountSetId, p, step, ocTaskType);
            return Map.of("success", true, "msg", "已下发到 OpenClaw 执行");
        }

        // ── init 步骤：检查外部系统是否有期初文件，有则自动导入 ─────────
        if ("init".equals(step)) {
            OcTask ocTask = new OcTask();
            ocTask.setAccountSetId(accountSetId);
            ocTask.setPeriod(p);
            ocTask.setTaskType("init_import");
            ocTask.setStatus("queued");
            ocTask.setTaskPeriodId(tp.getId());
            ocTask.setOcInstanceId(resolvedOcId);
            ocTask.setPayloadJson(JSON.toJSONString(Map.of("account_set_id", accountSetId, "period", p)));
            ocTaskRepository.save(ocTask);

            tp.setStepInit("running");
            taskPeriodRepository.save(tp);
            log.info("下发期初导入任务: accountSetId={}, period={}", accountSetId, p);
            Long ocTaskId = ocTask.getId();

            // HTTP 下载和 AI 导入在事务外执行（dispatch 事务先提交释放连接）
            // 通过返回标记，由 controller 层或后续逻辑异步处理
            return Map.of("success", true, "msg", "期初导入任务已下发，等待手动上传或自动处理",
                    "initAutoImport", true, "ocTaskId", ocTaskId);
        }

        // ── 其余内部步骤（fallback）────────────────────────────────────
        setStepStatus(tp, step, "pending");
        taskPeriodRepository.save(tp);
        log.info("内部任务标记pending: accountSetId={}, period={}, step={}", accountSetId, p, step);
        return Map.of("success", true, "msg", "已标记为待处理");
    }

    /** 下发单个数据采集子任务 */
    private Map<String, Object> dispatchDataSubTask(TaskPeriod tp, AccountSets as, String subType, String period, String ocInstanceId) {
        Long accountSetId = as.getId();

        updateSubTaskStatus(tp.getId(), subType, "running");
        tp.setStepData("running");
        taskPeriodRepository.save(tp);

        String ocTaskType = dataSubTypeToOcTaskType(subType);
        if (ocTaskType != null) {
            // OC 任务（invoice_in / invoice_out / invoice_checked / invoice_unchecked / tax_items）
            OcTask ocTask = new OcTask();
            ocTask.setAccountSetId(accountSetId);
            ocTask.setPeriod(period);
            ocTask.setTaskType(ocTaskType);
            ocTask.setStatus("queued");
            ocTask.setTaskPeriodId(tp.getId());
            ocTask.setOcInstanceId(ocInstanceId);

            // 构建 payload，发票任务附带下载起止日期
            String basePayload = buildPayload(as, period, ocInstanceId, ocTaskType);
            @SuppressWarnings("unchecked")
            Map<String, Object> payloadMap = JSON.parseObject(basePayload, java.util.LinkedHashMap.class);
            if (ocTaskType.startsWith("download_invoice")) {
                YearMonth ym = YearMonth.parse(period, PERIOD_FMT);
                payloadMap.put("start_date", ym.atDay(1).toString());
                payloadMap.put("end_date", ym.atEndOfMonth().toString());
            }
            ocTask.setPayloadJson(JSON.toJSONString(payloadMap));

            ocTaskRepository.save(ocTask);
            log.info("下发数据采集OC子任务: accountSetId={}, subType={}, ocTaskType={}", accountSetId, subType, ocTaskType);
            return Map.of("success", true, "msg", "已下发到 OpenClaw 执行（" + subType + "）");
        } else if ("bank".equals(subType)) {
            // 下发阶段仅创建 pending 任务，实际执行延迟到 OC 发票任务全部完成后自动触发
            OcTask ocTask = new OcTask();
            ocTask.setAccountSetId(accountSetId);
            ocTask.setPeriod(period);
            ocTask.setTaskType("parse_bank");
            ocTask.setStatus("pending");
            ocTask.setTaskPeriodId(tp.getId());
            ocTask.setOcInstanceId(ocInstanceId);
            ocTaskRepository.save(ocTask);

            updateSubTaskStatusWithOcTask(tp.getId(), "bank", "pending", null, ocTask.getId());
            recalcStepData(tp);
            taskPeriodRepository.save(tp);
            // 若 OC 子任务已全部终态（包括重新下发后的场景），触发立即执行
            publishStepCompleted(tp.getAccountSetId(), period, "data_local");
            log.info("银行流水任务已创建(pending)，待OC数据任务完成后自动执行: accountSetId={}", accountSetId);
            return Map.of("success", true, "status", "pending", "msg", "银行流水任务已创建，待数据采集完成后自动执行");
        } else if ("salary".equals(subType)) {
            // 下发阶段仅创建 pending 任务，实际执行延迟到 OC 发票任务全部完成后自动触发
            OcTask ocTask = new OcTask();
            ocTask.setAccountSetId(accountSetId);
            ocTask.setPeriod(period);
            ocTask.setTaskType("copy_salary");
            ocTask.setStatus("pending");
            ocTask.setTaskPeriodId(tp.getId());
            ocTask.setOcInstanceId(ocInstanceId);
            ocTaskRepository.save(ocTask);

            updateSubTaskStatusWithOcTask(tp.getId(), "salary", "pending", null, ocTask.getId());
            recalcStepData(tp);
            taskPeriodRepository.save(tp);
            // 若 OC 子任务已全部终态（包括重新下发后的场景），触发立即执行
            publishStepCompleted(tp.getAccountSetId(), period, "data_local");
            log.info("工资任务已创建(pending)，待OC数据任务完成后自动执行: accountSetId={}", accountSetId);
            return Map.of("success", true, "status", "pending", "msg", "工资任务已创建，待数据采集完成后自动执行");
        }
        return Map.of("success", false, "msg", "未知的数据子任务类型：" + subType);
    }

    /** 全量下发所有数据采集子任务：发票下载 + 勾选发票（仅一般）+ 做账前申报 + 银行 + 工资 */
    private Map<String, Object> dispatchAllDataSubTasks(TaskPeriod tp, AccountSets as, String period, String ocInstanceId) {
        tp.setStepData("running");
        taskPeriodRepository.save(tp);

        List<String> dispatched = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        // 1. 先下发发票下载任务（in/out/checked/unchecked，按企业类型区分）
        try {
            Map<String, Object> invoiceResult = dispatchInvoiceSubTasks(tp, as, period, ocInstanceId);
            @SuppressWarnings("unchecked")
            List<String> invoiceDispatched = (List<String>) invoiceResult.get("dispatched");
            if (invoiceDispatched != null) dispatched.addAll(invoiceDispatched);
        } catch (Exception e) {
            errors.add("invoice: " + e.getMessage());
            log.warn("下发发票子任务失败", e);
        }

        // 2. 仅一般纳税人：下发勾选发票任务（独立子任务）
        if (as.getVatType() == VatType.COMMONLY) {
            try {
                Map<String, Object> r = dispatchDataSubTask(tp, as, "invoice_check", period, ocInstanceId);
                if (Boolean.TRUE.equals(r.get("success"))) dispatched.add("invoice_check");
                else errors.add("invoice_check: " + r.get("msg"));
            } catch (Exception e) {
                errors.add("invoice_check: " + e.getMessage());
                log.warn("下发勾选发票失败", e);
            }
        }

        // 3. 下发做账前申报（与发票并行，OC 任务）
        try {
            Map<String, Object> r = dispatchDataSubTask(tp, as, "vat_declare_pre", period, ocInstanceId);
            if (Boolean.TRUE.equals(r.get("success"))) dispatched.add("vat_declare_pre");
            else errors.add("vat_declare_pre: " + r.get("msg"));
        } catch (Exception e) {
            errors.add("vat_declare_pre: " + e.getMessage());
            log.warn("下发做账前申报失败", e);
        }

        // 4. 下发银行、工资
        for (String st : List.of("bank", "salary")) {
            try {
                Map<String, Object> r = dispatchDataSubTask(tp, as, st, period, ocInstanceId);
                if (Boolean.TRUE.equals(r.get("success"))) dispatched.add(st);
                else errors.add(st + ": " + r.get("msg"));
            } catch (Exception e) {
                errors.add(st + ": " + e.getMessage());
                log.warn("下发数据子任务失败: subType={}", st, e);
            }
        }
        // 全部子任务均下发失败：将步骤状态恢复为 error，避免 step_data="running" 但无 oc_task 的孤立状态
        if (dispatched.isEmpty()) {
            tp.setStepData("error");
            tp.setErrorStep("step_data");
            tp.setErrorMsg("所有子任务下发失败：" + String.join("；", errors));
            taskPeriodRepository.save(tp);
            log.warn("全量数据采集下发全部失败，步骤已重置为 error: accountSetId={}, errors={}", as.getId(), errors);
            return Map.of("success", false, "msg", "下发失败：" + String.join("；", errors));
        }

        String msg = "已下发 " + dispatched.size() + " 个数据子任务";
        if (!errors.isEmpty()) msg += "，失败: " + errors;
        log.info("全量数据采集下发完成: accountSetId={}, dispatched={}", as.getId(), dispatched);
        return Map.of("success", true, "msg", msg, "dispatched", dispatched, "errors", errors);
    }

    /**
     * 按企业类型下发「下载发票」子任务（不包含勾选动作 invoice_check，它是独立步骤需单独下发）。
     * <p>小规模纳税人：进项 + 销项（2 个），按季度下发，发票导入到季度最后一个月。
     * <p>一般纳税人：进项(in) → 销项(out) → 已勾选下载(checked) → 未勾选下载(unchecked)，4 个下载子任务，按月下发。
     * <p>checked / unchecked 在 OC 端会等 invoice_check 完成（前置依赖检查），未勾选则保持 blocked。
     */
    private Map<String, Object> dispatchInvoiceSubTasks(TaskPeriod tp, AccountSets as, String period, String ocInstanceId) {
        boolean isSmallScale = as.getVatType() == VatType.SMALL_SCALE;

        // 根据纳税人类型决定下发的发票下载子任务（不含 invoice_check 勾选动作）
        List<String> invoiceSubTypes;
        if (isSmallScale) {
            invoiceSubTypes = List.of("invoice_in", "invoice_out");
        } else {
            // 一般纳税人：进项 → 销项 → 已勾下载 → 未勾下载
            invoiceSubTypes = List.of("invoice_in", "invoice_out",
                    "invoice_checked", "invoice_unchecked");
        }

        // 小规模按季度下发：计算季度的起止月份
        String dispatchPeriod = period;
        String startPeriod = null;
        if (isSmallScale) {
            YearMonth ym = YearMonth.parse(period, PERIOD_FMT);
            int quarter = (ym.getMonthValue() - 1) / 3; // 0=Q1, 1=Q2, 2=Q3, 3=Q4
            YearMonth quarterStart = YearMonth.of(ym.getYear(), quarter * 3 + 1);
            YearMonth quarterEnd = quarterStart.plusMonths(2);
            startPeriod = quarterStart.format(PERIOD_FMT);
            dispatchPeriod = quarterEnd.format(PERIOD_FMT); // 发票导入到季度最后一个月
        }

        List<String> dispatched = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        for (String st : invoiceSubTypes) {
            try {
                // 小规模需要额外传递季度起止月份信息
                if (isSmallScale) {
                    Map<String, Object> r = dispatchInvoiceSubTaskWithDateRange(tp, as, st, dispatchPeriod, startPeriod, ocInstanceId);
                    if (Boolean.TRUE.equals(r.get("success"))) dispatched.add(st);
                    else errors.add(st + ": " + r.get("msg"));
                } else {
                    Map<String, Object> r = dispatchDataSubTask(tp, as, st, period, ocInstanceId);
                    if (Boolean.TRUE.equals(r.get("success"))) dispatched.add(st);
                    else errors.add(st + ": " + r.get("msg"));
                }
            } catch (Exception e) {
                errors.add(st + ": " + e.getMessage());
                log.warn("下发发票子任务失败: subType={}", st, e);
            }
        }
        recalcStepData(tp);
        taskPeriodRepository.save(tp);
        String typeLabel = isSmallScale ? "小规模" : "一般纳税人";
        String msg = "已下发 " + dispatched.size() + " 个发票子任务（" + typeLabel + "）";
        if (!errors.isEmpty()) msg += "，失败: " + errors;
        log.info("发票子任务下发完成: accountSetId={}, type={}, dispatched={}", as.getId(), typeLabel, dispatched);
        return Map.of("success", true, "msg", msg, "dispatched", dispatched, "errors", errors);
    }

    /** 小规模发票任务：在 payload 中附带季度起止月份，发票导入到季度最后一个月 */
    private Map<String, Object> dispatchInvoiceSubTaskWithDateRange(TaskPeriod tp, AccountSets as,
            String subType, String period, String startPeriod, String ocInstanceId) {
        Long accountSetId = as.getId();
        updateSubTaskStatus(tp.getId(), subType, "running");
        tp.setStepData("running");
        taskPeriodRepository.save(tp);

        String ocTaskType = dataSubTypeToOcTaskType(subType);
        OcTask ocTask = new OcTask();
        ocTask.setAccountSetId(accountSetId);
        ocTask.setPeriod(period);
        ocTask.setTaskType(ocTaskType);
        ocTask.setStatus("queued");
        ocTask.setTaskPeriodId(tp.getId());
        ocTask.setOcInstanceId(ocInstanceId);

        // 构建 payload，附带季度起止月份
        String basePayload = buildPayload(as, period, ocInstanceId, ocTaskType);
        @SuppressWarnings("unchecked")
        Map<String, Object> payloadMap = JSON.parseObject(basePayload, java.util.LinkedHashMap.class);
        YearMonth qStart = YearMonth.parse(startPeriod, PERIOD_FMT);
        YearMonth qEnd = YearMonth.parse(period, PERIOD_FMT);
        payloadMap.put("start_period", startPeriod);
        payloadMap.put("end_period", period);
        payloadMap.put("start_date", qStart.atDay(1).toString());
        payloadMap.put("end_date", qEnd.atEndOfMonth().toString());
        payloadMap.put("quarter_mode", true);
        ocTask.setPayloadJson(JSON.toJSONString(payloadMap));

        ocTaskRepository.save(ocTask);
        log.info("下发小规模发票任务: accountSetId={}, subType={}, startPeriod={}, endPeriod={}", accountSetId, subType, startPeriod, period);
        return Map.of("success", true, "msg", "已下发到 OpenClaw 执行（" + subType + "，季度模式）");
    }

    /** 数据采集子任务类型 → OC 任务类型（null 表示内部同步任务） */
    private String dataSubTypeToOcTaskType(String subType) {
        return switch (subType) {
            case "invoice_in"        -> "download_invoice_in";
            case "invoice_out"       -> "download_invoice_out";
            case "invoice_check"     -> "check_invoice";
            case "invoice_checked"   -> "download_invoice_checked";
            case "invoice_unchecked" -> "download_invoice_unchecked";
            case "vat_declare_pre"   -> "vat_declare_pre";
            default -> null; // bank / salary：内部同步任务
        };
    }

    /**
     * 下发核验税务步骤：创建一条 verify_tax 类型的 oc_task。
     */
    private Map<String, Object> dispatchVerifyStep(TaskPeriod tp, AccountSets as, String period, String ocInstanceId) {
        Long accountSetId = as.getId();

        OcTask ocTask = new OcTask();
        ocTask.setAccountSetId(accountSetId);
        ocTask.setPeriod(period);
        ocTask.setTaskType("verify_tax");
        ocTask.setStatus("queued");
        ocTask.setTaskPeriodId(tp.getId());
        ocTask.setOcInstanceId(ocInstanceId);
        ocTask.setPayloadJson(buildPayload(as, period, ocInstanceId, "verify_tax"));
        ocTaskRepository.save(ocTask);

        tp.setStepVerify("running");
        taskPeriodRepository.save(tp);
        log.info("下发核验税务任务: accountSetId={}, period={}", accountSetId, period);
        return Map.of("success", true, "msg", "已下发核验税务任务到 OpenClaw 执行");
    }

    /**
     * 下发个税申报步骤：直接创建一条 iit_declare 类型的 oc_task。
     * 前置条件由 checkOcTaskPrerequisite 检查（对应月份工资已复核）。
     */
    private Map<String, Object> dispatchIitStep(TaskPeriod tp, AccountSets as, String period, String ocInstanceId) {
        Long accountSetId = as.getId();

        // ── 1. 检查个税密码，未配置则跳过 ──
        if (StrUtil.isBlank(as.getIndividualTaxCode())) {
            tp.setStepTaxIit("skipped");
            taskPeriodRepository.save(tp);
            log.info("个税申报跳过（未配置个税密码）: accountSetId={}", accountSetId);
            return Map.of("success", true, "skipped", true, "msg", "未配置个税密码，跳过个税申报");
        }

        // ── 2. 检查是否有员工，没有员工则跳过个税 ──
        if (!taskSalaryService.hasEmployees(accountSetId)) {
            tp.setStepTaxIit("skipped");
            taskPeriodRepository.save(tp);
            log.info("个税申报跳过（无在职员工）: accountSetId={}", accountSetId);
            return Map.of("success", true, "skipped", true, "msg", "无在职员工，跳过个税申报");
        }

        tp.setStepTaxIit("running");
        // 重新下发时若全局错误指向本步骤，清掉过时的错误标记
        if ("step_tax_iit".equals(tp.getErrorStep())) {
            tp.setErrorStep(null);
            tp.setErrorMsg(null);
        }
        taskPeriodRepository.save(tp);

        // ── 3. 工资未复核 → 自动复制上期工资（无工资员工生成0工资）并复核 ──
        if (!isSalaryReviewed(accountSetId, period)) {
            Map<String, Object> salaryResult = taskSalaryService.execute(accountSetId, period);
            String salaryStatus = (String) salaryResult.get("status");

            if ("error".equals(salaryStatus) || "failed".equals(salaryStatus)) {
                tp.setStepTaxIit("error");
                tp.setErrorStep("step_tax_iit");
                tp.setErrorMsg("工资复制失败：" + salaryResult.get("msg"));
                taskPeriodRepository.save(tp);
                log.warn("个税申报前置工资复制失败: accountSetId={}, msg={}", accountSetId, salaryResult.get("msg"));
                return Map.of("success", false, "msg", "工资复制失败：" + salaryResult.get("msg"));
            }
            log.info("个税申报前置：工资自动复制并复核完成: accountSetId={}, msg={}", accountSetId, salaryResult.get("msg"));
        } else {
            log.info("个税申报前置：工资已复核，跳过: accountSetId={}", accountSetId);
        }

        // ── 4. 下发个税申报 OC 任务 ──
        String iitType = "salary";
        TaskIitItem item = taskIitItemRepository
                .findByTaskPeriodIdAndIitType(tp.getId(), iitType)
                .orElseGet(() -> {
                    TaskIitItem ni = new TaskIitItem();
                    ni.setTaskPeriodId(tp.getId());
                    ni.setIitType(iitType);
                    return ni;
                });

        String basePayload = buildPayload(as, period, ocInstanceId, "iit_declare");
        @SuppressWarnings("unchecked")
        Map<String, Object> payloadMap = JSON.parseObject(basePayload, java.util.LinkedHashMap.class);
        payloadMap.put("iit_type", iitType);

        // 幂等复用已有的「未完成」oc_task（blocked/queued/running），避免重复下发产生两条同样的任务。
        // 注意：不能只找 blocked——若已存在 queued/running 的 iit_declare，旧逻辑会再新建一条，
        // 导致同一家被申报两遍（已确认线上发生过）。
        OcTask ocTask = ocTaskRepository
                .findFirstByTaskPeriodIdAndTaskTypeAndStatusInOrderByCreatedAtAsc(
                        tp.getId(), "iit_declare", List.of("blocked", "queued", "running"))
                .orElse(null);
        if (ocTask != null) {
            // blocked → 解锁为 queued 让其可被领取；queued 保持；running 不动（正被 OC 处理，避免被二次领取）
            if ("blocked".equals(ocTask.getStatus())) {
                ocTask.setStatus("queued");
            }
            ocTask.setPayloadJson(JSON.toJSONString(payloadMap));
            ocTask.setOcInstanceId(ocInstanceId);
            ocTaskRepository.save(ocTask);
        } else {
            ocTask = new OcTask();
            ocTask.setAccountSetId(accountSetId);
            ocTask.setPeriod(period);
            ocTask.setTaskType("iit_declare");
            ocTask.setStatus("queued");
            ocTask.setTaskPeriodId(tp.getId());
            ocTask.setOcInstanceId(ocInstanceId);
            ocTask.setPayloadJson(JSON.toJSONString(payloadMap));
            ocTaskRepository.save(ocTask);
        }

        item.setDeclareStatus("running");
        item.setOcTaskId(ocTask.getId());
        // 重新下发时清空上一次的结果，避免前端继续显示旧的错误信息
        item.setDeclareResult(null);
        item.setErrorMsg(null);
        item.setScreenshotUrls(null);
        item.setExecutedAt(null);
        taskIitItemRepository.save(item);

        taskPeriodRepository.save(tp);
        log.info("下发个税申报任务: accountSetId={}, period={}", accountSetId, period);
        return Map.of("success", true, "msg", "已下发个税申报任务");
    }

    /**
     * 下发税务年报步骤：为当前账套创建财务报表年报 + 企业所得税年报两条子任务。
     * 税务年报以自然年为单位申报（报上一年），任意月份均可下发。
     *
     * @param subType 可选，指定只下发某个类型（financial_statement / enterprise_income_tax），为空则全部下发
     */
    private Map<String, Object> dispatchTaxAnnualStep(TaskPeriod tp, AccountSets as, String period,
                                                       String ocInstanceId, String subType) {
        Long accountSetId = as.getId();

        // 推算税年：统一取 period 年份 - 1（年报报上一年度）
        String taxYear = String.valueOf(Integer.parseInt(period.substring(0, 4)) - 1);

        // 去重检查：同一账套同一税年是否已存在
        if (taskTaxAnnualItemRepository.existsByAccountSetIdAndTaxYear(accountSetId, taxYear)) {
            return Map.of("success", false, "msg", taxYear + "年度税务年报已存在，请先重置再下发");
        }

        tp.setStepTaxAnnual("running");
        taskPeriodRepository.save(tp);

        // 创建单条 OcTask
        String basePayload = buildPayload(as, period, ocInstanceId, "tax_annual");
        @SuppressWarnings("unchecked")
        Map<String, Object> payloadMap = JSON.parseObject(basePayload, java.util.LinkedHashMap.class);
        payloadMap.put("tax_year", taxYear);

        OcTask ocTask = new OcTask();
        ocTask.setAccountSetId(accountSetId);
        ocTask.setPeriod(period);
        ocTask.setTaskType("tax_annual");
        ocTask.setStatus("queued");
        ocTask.setTaskPeriodId(tp.getId());
        ocTask.setOcInstanceId(ocInstanceId);
        ocTask.setPayloadJson(JSON.toJSONString(payloadMap));
        ocTaskRepository.save(ocTask);

        // 创建单条 TaskTaxAnnualItem
        TaskTaxAnnualItem item = new TaskTaxAnnualItem();
        item.setTaskPeriodId(tp.getId());
        item.setAccountSetId(accountSetId);
        item.setTaxYear(taxYear);
        item.setDeclareStatus("running");
        item.setOcTaskId(ocTask.getId());
        taskTaxAnnualItemRepository.save(item);

        log.info("下发税务年报任务: accountSetId={}, period={}, taxYear={}", accountSetId, period, taxYear);
        return Map.of("success", true, "msg", "已下发" + taxYear + "年度税务年报任务");
    }

    /** 从 TaskTaxAnnualItem 取状态（单条记录，直接返回） */
    private String resolveTaxAnnualStatus(List<TaskTaxAnnualItem> items) {
        if (items.isEmpty()) return "pending";
        return items.get(0).getDeclareStatus();
    }

    /**
     * 处理税务年报 OC 回调（task_type = tax_annual）。
     *
     * <p>回调 body 格式：
     * <pre>
     * {
     *   "task_id": "...", "task_type": "tax_annual", "status": "done",
     *   "period": "2025", "account_set_id": 88,
     *   "result": {
     *     "success_count": 2, "fail_count": 0,
     *     "results": {
     *       "财务报表报送与信息采集（小企业会计准则）年报": {"status": "done", "screenshots": [...]},
     *       "居民企业（查账征收）企业所得税年度申报":     {"status": "done", "screenshots": [...]}
     *     },
     *     "tax_items": [{"税种名称": "企业所得税", "应纳税额": "12345.00", "滞纳金": "0.00"}]
     *   },
     *   "screenshot_urls": [...],
     *   "error_msg": null
     * }
     * </pre>
     */
    public void handleTaxAnnualCallback(Map<String, Object> body) {
        Long taskId = toLong(body.get("task_id"));
        String status = (String) body.get("status");

        // 提取 result 子对象
        @SuppressWarnings("unchecked")
        Map<String, Object> resultData = (body.get("result") instanceof Map)
                ? (Map<String, Object>) body.get("result") : Collections.emptyMap();

        // 若子项有失败（fail_count > 0）则整体标记为 error
        Object failCountObj = resultData.get("fail_count");
        int failCount = failCountObj != null ? Integer.parseInt(failCountObj.toString()) : 0;
        if ("done".equals(status) && failCount > 0) {
            status = "error";
            body.put("status", "error");
            if (body.get("error_msg") == null) {
                body.put("error_msg", "年报申报部分失败（fail_count=" + failCount + "）");
            }
        }

        updateOcTask(taskId, status, body);

        OcTask ocTask = ocTaskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("OcTask 不存在：" + taskId));
        TaskPeriod tp = taskPeriodRepository.findById(ocTask.getTaskPeriodId())
                .orElseThrow(() -> new IllegalArgumentException("TaskPeriod 不存在：" + ocTask.getTaskPeriodId()));

        // 更新 task_tax_annual_item
        try {
            TaskTaxAnnualItem item = taskTaxAnnualItemRepository.findByOcTaskId(taskId).orElse(null);
            if (item != null) {
                String itemStatus = "done".equals(status) ? "done" : "error";
                item.setDeclareStatus(itemStatus);
                item.setDeclareResult(JSON.toJSONString(body));
                item.setErrorMsg((String) body.get("error_msg"));
                item.setExecutedAt(LocalDateTime.now());

                // screenshot_urls：优先使用顶层字段（已由 multipart 处理层注入 HTTP URL）
                Object urls = body.get("screenshot_urls");
                if (urls instanceof List) {
                    item.setScreenshotUrls(JSON.toJSONString(urls));
                } else if (body.get("screenshot_url") != null) {
                    item.setScreenshotUrls(JSON.toJSONString(List.of(body.get("screenshot_url"))));
                }

                // tax_items：存 result.tax_items（企业所得税应纳税额等）
                Object taxItemsObj = resultData.get("tax_items");
                if (taxItemsObj instanceof List) {
                    item.setTaxItems(JSON.toJSONString(taxItemsObj));
                }

                taskTaxAnnualItemRepository.save(item);

                tp.setStepTaxAnnual(itemStatus);
                if ("error".equals(itemStatus)) {
                    tp.setErrorStep("step_tax_annual");
                    tp.setErrorMsg((String) body.getOrDefault("error_msg", "税务年报申报失败"));
                }
            }
        } catch (Exception e) {
            log.warn("保存税务年报明细失败（不影响主流程）: taskId={}", taskId, e);
        }
        taskPeriodRepository.save(tp);
    }

    @Transactional
    public void handleTaxAnnualVerifyCallback(Map<String, Object> body) {
        Long taskId = toLong(body.get("task_id"));
        String status = (String) body.get("status");
        String errorMsg = (String) body.get("error_msg");
        log.info("[tax_annual_verify] taskId={} status={}", taskId, status);
        if (taskId == null) return;
        OcTask oc = ocTaskRepository.findById(taskId).orElse(null);
        if (oc == null) { log.warn("[tax_annual_verify] OcTask not found: {}", taskId); return; }
        oc.setStatus("done".equals(status) ? "done" : "failed");
        if (errorMsg != null) oc.setResultJson("{\"error_msg\":\"" + errorMsg.replace("\"", "\\\"") + "\"}");
        ocTaskRepository.save(oc);
    }

    /** 聚合 task_iit_item 所有状态 → 整体 step_tax_iit */
    private String aggregateIitStatus(List<TaskIitItem> items) {
        if (items.isEmpty()) return "pending";
        if (items.stream().anyMatch(i -> "error".equals(i.getDeclareStatus()))) return "error";
        if (items.stream().anyMatch(i -> "running".equals(i.getDeclareStatus()) || "pending".equals(i.getDeclareStatus()))) return "running";
        if (items.stream().allMatch(i -> "skipped".equals(i.getDeclareStatus()) || "na".equals(i.getDeclareStatus()))) return "skipped";
        if (items.stream().anyMatch(i -> "warn".equals(i.getDeclareStatus()))) return "warn";
        return "done";
    }

    /**
     * 下发国税申报步骤：插入一条 vat_declare oc_task。
     * OC 负责查询本期应申报税目、逐个申报，完成后统一回调 handleVatDeclareCallback。
     */
    private Map<String, Object> dispatchVatStep(TaskPeriod tp, AccountSets as, String period, String ocInstanceId) {
        return dispatchVatStep(tp, as, period, ocInstanceId, false);
    }

    /**
     * 下发国税申报步骤。
     *
     * @param alsoDispatchPre 账期已结账时为 true：data 步骤被跳过，vat_declare_pre 可能未执行，
     *                        需同步下发以补全做账前税款申报数据。
     */
    private Map<String, Object> dispatchVatStep(TaskPeriod tp, AccountSets as, String period, String ocInstanceId, boolean alsoDispatchPre) {
        Long accountSetId = as.getId();

        // 若账期已结账，同步下发 vat_declare_pre（补全做账前税款申报）
        if (alsoDispatchPre) {
            OcTask preTask = new OcTask();
            preTask.setAccountSetId(accountSetId);
            preTask.setPeriod(period);
            preTask.setTaskType("vat_declare_pre");
            preTask.setStatus("queued");
            preTask.setTaskPeriodId(tp.getId());
            preTask.setOcInstanceId(ocInstanceId);
            preTask.setPayloadJson(buildPayload(as, period, ocInstanceId, "vat_declare_pre"));
            ocTaskRepository.save(preTask);
            log.info("账期已结账，同步下发做账前申报: accountSetId={}, period={}", accountSetId, period);
        }

        // 复用已有的 blocked oc_task，或创建新的
        OcTask ocTask = unlockBlockedOcTask(tp.getId(), "vat_declare_post");
        if (ocTask != null) {
            ocTask.setPayloadJson(buildPayload(as, period, ocInstanceId, "vat_declare_post"));
            ocTask.setOcInstanceId(ocInstanceId);
            ocTaskRepository.save(ocTask);
        } else {
            ocTask = new OcTask();
            ocTask.setAccountSetId(accountSetId);
            ocTask.setPeriod(period);
            ocTask.setTaskType("vat_declare_post");
            ocTask.setStatus("queued");
            ocTask.setTaskPeriodId(tp.getId());
            ocTask.setOcInstanceId(ocInstanceId);
            ocTask.setPayloadJson(buildPayload(as, period, ocInstanceId, "vat_declare_post"));
            ocTaskRepository.save(ocTask);
        }

        tp.setStepTaxNational("running");
        taskPeriodRepository.save(tp);
        log.info("国税申报已下发: accountSetId={}, period={}, alsoDispatchPre={}", accountSetId, period, alsoDispatchPre);
        return Map.of("success", true, "msg", alsoDispatchPre
                ? "已下发国税申报任务及做账前申报到 OpenClaw 执行"
                : "已下发国税申报任务到 OpenClaw 执行");
    }

    // ═══════════════════════════════════════════════════════════════
    // FN-NEW-DECLARE-TRIGGER-API：BT 一键报税 → 申报类 OcTask → tax-orchestrator(OC)
    // ═══════════════════════════════════════════════════════════════

    /** 申报触发白名单 taskType（与 OC 端申报任务类型对齐）。 */
    public static final List<String> DECLARE_TASK_TYPES = List.of(
            "current_declaration_query", "vat_declare", "vat_declare_pre", "vat_declare_post");

    /** 进行中状态（防重复触发）。 */
    private static final List<String> DECLARE_ACTIVE_STATUSES = List.of("queued", "running", "blocked");

    /**
     * 下发一条申报类 OcTask 交 OpenClaw(tax-orchestrator) 执行。<b>薄包，复用现有能力</b>：
     * payload 走 {@link #buildPayloadMap}（province/tax_no(=credit_code)/period/vat_type + 默认办税员登录凭证，
     * 与 OC {@code fetch_task} 契约一致）；taskPeriodId 走 {@link #getOrCreateTaskPeriod}（vat_declare 回调需反查 task_period）。
     * <p>防重复：同账套同期同 taskType 存在 queued/running/blocked 任务则拒绝。
     *
     * @param taxTypes 可选税种清单，非空则并入 payload.tax_types（OC 据此逐税种申报）
     * @return {@code {ocTaskId, taskType, period, taskPeriodId}}
     * @throws IllegalArgumentException 入参非法 / 账套不存在
     * @throws IllegalStateException    凭证缺失 / 重复触发
     */
    @Transactional
    public Map<String, Object> dispatchDeclareTask(Long accountSetId, String period, String taskType,
                                                   List<String> taxTypes, String ocInstanceId) {
        if (accountSetId == null) throw new IllegalArgumentException("accountSetsId 不能为空");
        if (StrUtil.isBlank(period)) throw new IllegalArgumentException("period 不能为空（格式 YYYY-MM）");
        if (StrUtil.isBlank(taskType) || !DECLARE_TASK_TYPES.contains(taskType)) {
            throw new IllegalArgumentException(
                    "taskType 非法（合法值：" + String.join("|", DECLARE_TASK_TYPES) + "）：" + taskType);
        }
        AccountSets as = accountSetsRepository.findById(accountSetId)
                .orElseThrow(() -> new IllegalArgumentException("账套不存在：" + accountSetId));

        // 校验登录凭证：payload 的登录凭证来自默认办税员；缺失则 OC 无法登录税局（ca 渠道凭证由 OC 本地加载，放行）
        String accountLoginType = StrUtil.isBlank(as.getLoginType()) ? "officer" : as.getLoginType();
        boolean hasOfficer = taxOfficerRepository.findByAccountSetIdAndDefaultOfficerTrue(accountSetId).isPresent();
        if (!hasOfficer && !"ca".equals(accountLoginType)) {
            throw new IllegalStateException("账套未配置默认办税员凭证，无法下发申报任务：accountSetsId=" + accountSetId);
        }

        // 防重复触发：同账套同期同 taskType 已有进行中任务
        OcTask active = ocTaskRepository
                .findByAccountSetIdAndPeriodAndTaskTypeIn(accountSetId, period, List.of(taskType))
                .stream().filter(t -> DECLARE_ACTIVE_STATUSES.contains(t.getStatus()))
                .findFirst().orElse(null);
        if (active != null) {
            throw new IllegalStateException("同账套同期同类型申报任务进行中（ocTaskId=" + active.getId()
                    + ", status=" + active.getStatus() + "），请勿重复触发");
        }

        TaskPeriod tp = getOrCreateTaskPeriod(accountSetId, period, as);

        Map<String, Object> payload = buildPayloadMap(as, period, ocInstanceId, taskType);
        if (taxTypes != null && !taxTypes.isEmpty()) {
            payload.put("tax_types", taxTypes);
        }

        OcTask ocTask = new OcTask();
        ocTask.setAccountSetId(accountSetId);
        ocTask.setPeriod(period);
        ocTask.setTaskType(taskType);
        ocTask.setStatus("queued");
        ocTask.setTaskPeriodId(tp.getId());
        ocTask.setOcInstanceId(ocInstanceId);
        ocTask.setPayloadJson(JSON.toJSONString(payload));
        ocTaskRepository.save(ocTask);

        log.info("[FN-NEW-DECLARE-TRIGGER] 已下发申报任务 accountSetId={} period={} taskType={} ocTaskId={}",
                accountSetId, period, taskType, ocTask.getId());

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ocTaskId", ocTask.getId());
        r.put("taskType", taskType);
        r.put("period", period);
        r.put("taskPeriodId", tp.getId());
        return r;
    }

    /**
     * {@code current_declaration_query} 回调 —— 通用落库（无领域处理）。
     * OC 查询当期应申报信息后回调，结果整包落 {@code oc_task.result_json} + 截图 + 状态/完成时间。
     * 其余申报类型（vat_declare/_pre/_post）走各自既有 handler。
     */
    @Transactional
    public void handleDeclarationQueryCallback(Map<String, Object> body) {
        Long taskId = toLong(body.get("task_id"));
        OcTask ocTask = ocTaskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("OcTask 不存在：" + taskId));
        String status = (String) body.get("status");
        ocTask.setStatus("failed".equals(status) ? "failed" : "done");
        ocTask.setResultJson(JSON.toJSONString(body));
        String shot = extractFirstScreenshotUrl(body);
        if (shot != null) ocTask.setScreenshotUrl(shot);
        ocTask.setFinishedAt(LocalDateTime.now());
        ocTaskRepository.save(ocTask);
        log.info("[FN-NEW-DECLARE-TRIGGER] current_declaration_query 回调落库 ocTaskId={} status={}",
                taskId, ocTask.getStatus());
    }

    /** 从回调 body 取首张截图 URL（screenshot_urls 列表优先，兼容 screenshot_url 单值）；无则 null。 */
    private String extractFirstScreenshotUrl(Map<String, Object> body) {
        Object urls = body.get("screenshot_urls");
        if (urls instanceof List<?> l && !l.isEmpty() && l.get(0) != null) {
            String s = l.get(0).toString();
            return StrUtil.isNotBlank(s) ? s : null;
        }
        Object single = body.get("screenshot_url");
        return single != null && StrUtil.isNotBlank(single.toString()) ? single.toString() : null;
    }

    /**
     * 批量下发
     */
    public Map<String, Object> batchDispatch(List<Long> accountSetIds, String period, String step) {
        String p = resolvePeriod(period);

        // 批量预加载 TaskPeriod，用于跳过已完成步骤
        Set<String> COMPLETED_STATUSES = Set.of("done", "skipped", "na", "waiting");
        Map<Long, TaskPeriod> tpMap = taskPeriodRepository
                .findByPeriodAndAccountSetIdIn(p, accountSetIds)
                .stream().collect(Collectors.toMap(TaskPeriod::getAccountSetId, t -> t));

        int successCount = 0;
        int skippedCount = 0;
        List<String> errors = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (Long id : accountSetIds) {
            TaskPeriod tp = tpMap.get(id);
            if (tp != null && COMPLETED_STATUSES.contains(getStepStatus(tp, step))) {
                skippedCount++;
                skipped.add("账套" + id + "：" + stepStatusLabel(getStepStatus(tp, step)));
                continue;
            }
            try {
                Map<String, Object> r = self.dispatch(id, p, step, false);
                // 实际下发：success=true 且非 skipped/blocked/waiting；否则记为跳过并保留原因
                boolean realDispatch = r != null
                        && Boolean.TRUE.equals(r.get("success"))
                        && !Boolean.TRUE.equals(r.get("skipped"))
                        && !Boolean.TRUE.equals(r.get("blocked"))
                        && !Boolean.TRUE.equals(r.get("waiting"));
                if (realDispatch) {
                    successCount++;
                } else {
                    skippedCount++;
                    String msg = r != null ? (String) r.get("msg") : null;
                    skipped.add("账套" + id + "：" + (StrUtil.isNotBlank(msg) ? msg : "已跳过"));
                }
            } catch (Exception e) {
                errors.add("账套" + id + ": " + e.getMessage());
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("successCount", successCount);
        result.put("skippedCount", skippedCount);
        result.put("skipped", skipped);
        result.put("failCount", errors.size());
        result.put("errors", errors);
        return result;
    }

    /**
     * 批量下发弹窗 - 查询可用凭证列表。
     * <p>
     * 凭证来源：系统内<b>所有</b>账套中该登录渠道的办税员记录，按凭证 key 去重后返回。
     * matchCount：已选账套（accountSetIds）中有多少个持有该凭证，帮助用户判断覆盖范围。
     * <ul>
     *   <li>officer / proxy：按 phone 去重</li>
     *   <li>legal_person：按 idCard 去重</li>
     * </ul>
     *
     * @param accountSetIds 已选账套ID列表（用于计算 matchCount）
     * @param loginType     登录渠道（officer / proxy / legal_person）
     * @return { loginType, loginTypeDisplay, credentials: [{id, name, phone/idCard, ..., matchCount}] }
     */
    public Map<String, Object> batchQueryOfficersByType(List<Long> accountSetIds, String loginType) {
        boolean byIdCard = "legal_person".equals(loginType);

        // 只查已选账套中的凭证，同时用于去重展示和计算 matchCount
        List<TaxOfficer> selectedOfficers = taxOfficerRepository
                .findByAccountSetIdInAndLoginType(accountSetIds, loginType);

        // credentialKey → 代表该凭证的第一条记录（展示用）
        Map<String, TaxOfficer> representativeByKey = new LinkedHashMap<>();
        Map<String, Long> matchCountByKey = new LinkedHashMap<>();
        for (TaxOfficer o : selectedOfficers) {
            String key = byIdCard ? o.getIdCard() : o.getPhone();
            if (StrUtil.isBlank(key)) continue;
            representativeByKey.putIfAbsent(key, o);
            matchCountByKey.merge(key, 1L, Long::sum);
        }

        List<Map<String, Object>> credentials = new ArrayList<>();
        for (Map.Entry<String, TaxOfficer> entry : representativeByKey.entrySet()) {
            long matchCount = matchCountByKey.getOrDefault(entry.getKey(), 0L);
            if (matchCount == 0) continue; // 已选账套中无此凭证，不展示
            Map<String, Object> row = buildOfficerRow(entry.getValue());
            row.put("matchCount", matchCount);
            credentials.add(row);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("loginType", loginType);
        result.put("loginTypeDisplay", resolveLoginTypeDisplay(loginType));
        result.put("credentials", credentials);
        return result;
    }

    /**
     * 批量下发弹窗确认 - 按一个凭证批量设置默认办税员。
     * <p>
     * 用户在弹窗中选定一个凭证（officer_id 为该凭证在任意账套的代表记录 ID），
     * 系统在已选账套（account_set_ids）中找出所有持有相同凭证的办税员记录，
     * 逐一设为默认并更新账套 login_type。
     * 没有该凭证的账套记入 skipped，后续 batchDispatchAll 会自动跳过。
     *
     * @param accountSetIds    已选账套ID列表
     * @param referenceOfficerId 用户选定凭证的代表记录 ID（由 batchQueryOfficersByType 返回）
     * @return { updatedCount, skippedCount, updatedAccountSetIds, skippedAccountSetIds }
     */
    public Map<String, Object> batchSetDefaultOfficer(List<Long> accountSetIds, Long referenceOfficerId) {
        TaxOfficer ref = taxOfficerRepository.findById(referenceOfficerId)
                .orElseThrow(() -> new IllegalArgumentException("凭证不存在: " + referenceOfficerId));
        String loginType = ref.getLoginType();
        boolean byIdCard = "legal_person".equals(loginType);
        String credentialKey = byIdCard ? ref.getIdCard() : ref.getPhone();
        if (StrUtil.isBlank(credentialKey)) {
            throw new IllegalArgumentException("所选凭证的关键字段（手机号/身份证）为空");
        }

        List<TaxOfficer> candidates = taxOfficerRepository
                .findByAccountSetIdInAndLoginType(accountSetIds, loginType)
                .stream()
                .filter(o -> credentialKey.equals(byIdCard ? o.getIdCard() : o.getPhone()))
                .toList();

        Set<Long> updatedIds = new HashSet<>();
        for (TaxOfficer officer : candidates) {
            try {
                self.setDefaultOfficerForAccount(officer.getAccountSetId(), officer.getId(), loginType);
                updatedIds.add(officer.getAccountSetId());
            } catch (Exception e) {
                log.warn("设置默认办税员失败: accountSetId={}, error={}", officer.getAccountSetId(), e.getMessage());
            }
        }

        List<Long> skippedIds = accountSetIds.stream().filter(id -> !updatedIds.contains(id)).toList();

        // 跳过原因：这些账套下没有持有所选凭证的办税员（带公司名，供批量下发结果页展示）
        List<String> skippedReasons = new ArrayList<>();
        if (!skippedIds.isEmpty()) {
            Map<Long, String> nameMap = new HashMap<>();
            accountSetsRepository.findAllById(skippedIds).forEach(a ->
                    nameMap.put(a.getId(), StrUtil.isNotBlank(a.getCompanyName()) ? a.getCompanyName() : ("账套" + a.getId())));
            for (Long id : skippedIds) {
                skippedReasons.add(nameMap.getOrDefault(id, "账套" + id) + "：未匹配到所选凭证的办税员");
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("updatedCount", updatedIds.size());
        result.put("skippedCount", skippedIds.size());
        result.put("updatedAccountSetIds", new ArrayList<>(updatedIds));
        result.put("skippedAccountSetIds", skippedIds);
        result.put("skipped", skippedReasons);
        return result;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void setDefaultOfficerForAccount(Long accountSetId, Long officerId, String loginType) {
        taxOfficerRepository.clearDefaultByAccountSetId(accountSetId);
        TaxOfficer freshOfficer = taxOfficerRepository.findById(officerId).orElse(null);
        if (freshOfficer != null) {
            freshOfficer.setDefaultOfficer(true);
            taxOfficerRepository.save(freshOfficer);
        }
        accountSetsRepository.findById(accountSetId).ifPresent(as -> {
            as.setLoginType(loginType);
            accountSetsRepository.save(as);
        });
    }

    /**
     * 批量全量下发（一键下发所有任务步骤）。
     * <p>
     * 对每个账套依次下发：核验税务、获取数据（一般纳税人额外包含发票子任务）、
     * 国税申报、个税申报、缴费申报。
     * 没有配置默认办税员的账套直接跳过（记入 skipped）。
     *
     * @param accountSetIds 要下发的账套ID列表
     * @param period        账期
     * @return { successCount, failCount, skippedCount, errors }
     */
    public Map<String, Object> batchDispatchAll(List<Long> accountSetIds, String period) {
        String p = resolvePeriod(period);
        int successCount = 0;
        int skippedCount = 0;
        List<String> errors = new ArrayList<>();

        for (Long id : accountSetIds) {
            try {
                Map<String, Object> r = dispatchAll(id, p);
                @SuppressWarnings("unchecked")
                List<String> stepErrors = (List<String>) r.getOrDefault("errors", List.of());
                if (!stepErrors.isEmpty()) {
                    errors.addAll(stepErrors.stream().map(e -> "账套" + id + " " + e).toList());
                }
                successCount++;
            } catch (Exception e) {
                errors.add("账套" + id + ": " + e.getMessage());
                skippedCount++;
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("successCount", successCount);
        result.put("skippedCount", skippedCount);
        result.put("failCount", errors.size());
        result.put("errors", errors);
        return result;
    }

    /**
     * 批量下发发票子任务（对应"批量下发取票"按钮）。
     * 无前置任务限制，仅下发下载发票相关子任务。
     */
    public Map<String, Object> batchDispatchInvoice(List<Long> accountSetIds, String period) {
        String p = resolvePeriod(period);
        int successCount = 0;
        int skippedCount = 0;
        List<String> errors = new ArrayList<>();

        Set<Long> hasDefaultOfficer = taxOfficerRepository.findByAccountSetIdInAndDefaultOfficerTrue(accountSetIds)
                .stream()
                .map(TaxOfficer::getAccountSetId)
                .collect(Collectors.toSet());

        for (Long id : accountSetIds) {
            if (!hasDefaultOfficer.contains(id)) {
                skippedCount++;
                continue;
            }
            try {
                self.dispatch(id, p, "invoice", false);
                successCount++;
            } catch (Exception e) {
                errors.add("账套" + id + "[invoice]: " + e.getMessage());
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("successCount", successCount);
        result.put("skippedCount", skippedCount);
        result.put("failCount", errors.size());
        result.put("errors", errors);
        return result;
    }

    /** 下发发票子任务：一般纳税人下发 invoice_in/out/checked；小规模下发 invoice */
    private void dispatchInvoiceSubTasks(Long accountSetId, String period,
                                         boolean isGeneralTaxpayer, List<String> errors) {
        // 委托给 TaskPeriod 版本，该版本已正确处理小规模季度日期范围
        try {
            AccountSets as = accountSetsRepository.findById(accountSetId).orElse(null);
            if (as == null) { errors.add("账套" + accountSetId + ": 账套不存在"); return; }
            String p = resolvePeriod(period);
            TaskPeriod tp = getOrCreateTaskPeriod(accountSetId, p, as);
            String resolvedOcId = tp.getOcInstanceId();
            Map<String, Object> result = dispatchInvoiceSubTasks(tp, as, p, resolvedOcId);
            @SuppressWarnings("unchecked")
            List<String> errs = (List<String>) result.getOrDefault("errors", List.of());
            errors.addAll(errs);
        } catch (Exception e) {
            errors.add("账套" + accountSetId + "[invoice]: " + e.getMessage());
        }
    }

    /** 需要登录凭证（OC/RPA）的步骤 */
    private static final Set<String> CREDENTIAL_REQUIRED_STEPS =
            Set.of("verify", "invoice", "tax_national", "tax_iit", "payment");

    /**
     * 批量下发指定步骤（可多选）。
     * <p>
     * steps 可包含：verify / invoice / bank / salary / bookkeep / tax_national / tax_iit / payment
     * <ul>
     *   <li>需要登录凭证的步骤（verify/invoice/tax_national/tax_iit/payment）：无默认办税员则跳过该步骤</li>
     *   <li>不需要凭证的步骤（bank/salary/bookkeep）：所有账套均执行</li>
     * </ul>
     * 各步骤按标准顺序处理，dispatch() 内部会进行前置条件检测。
     */
    public Map<String, Object> batchDispatchSteps(List<Long> accountSetIds, String period, List<String> steps) {
        // 标准顺序（与前端 BATCH_STEP_DEFS_TREE 一致）
        List<String> STEP_ORDER = List.of(
                "verify", "init",
                "data", "data.invoice", "data.invoice_check", "data.bank", "data.salary", "data.vat_declare_pre",
                "bookkeep", "bookkeep.asset", "bookkeep.invoice", "bookkeep.salary", "bookkeep.bank", "bookkeep.tax", "bookkeep.carry_forward",
                "vat", "iit", "payment", "tax_annual");
        List<String> orderedSteps = STEP_ORDER.stream().filter(steps::contains).toList();

        // 需要登录凭证的步骤 key（含子任务粒度）
        // iit 不需要办税员凭证，使用账套配置的个税密码
        Set<String> CRED_STEPS = Set.of(
                "verify", "data", "data.invoice", "data.invoice_check", "data.vat_declare_pre", "vat", "payment", "tax_annual");

        String p = resolvePeriod(period);
        int successCount = 0;
        int skippedCount = 0;
        List<String> errors = new ArrayList<>();
        // 跳过原因明细：每个被整体跳过的账套记一条「公司名：原因1；原因2」，供前端展示
        List<String> skipped = new ArrayList<>();

        List<AccountSets> accountSetsList = accountSetsRepository.findAllById(accountSetIds);
        Map<Long, AccountSets> accountSetsMap = accountSetsList.stream()
                .collect(Collectors.toMap(AccountSets::getId, a -> a));

        List<TaxOfficer> defaultOfficers = taxOfficerRepository.findByAccountSetIdInAndDefaultOfficerTrue(accountSetIds);
        Set<Long> hasDefaultOfficer = defaultOfficers.stream()
                .map(TaxOfficer::getAccountSetId)
                .collect(Collectors.toSet());

        // 预加载当期 TaskPeriod，用于跳过已完成步骤
        Set<String> COMPLETED_STATUSES = Set.of("done", "skipped", "na", "waiting");
        Map<Long, TaskPeriod> tpMap = taskPeriodRepository
                .findByPeriodAndAccountSetIdIn(p, accountSetIds)
                .stream().collect(Collectors.toMap(TaskPeriod::getAccountSetId, t -> t));

        for (Long id : accountSetIds) {
            AccountSets as = accountSetsMap.get(id);
            String asLabel = (as != null && StrUtil.isNotBlank(as.getCompanyName()))
                    ? as.getCompanyName() : ("账套" + id);

            // 未配置 OC 实例 → 整个账套跳过
            if (as == null || StrUtil.isBlank(as.getOcInstanceId())) {
                errors.add(asLabel + "：未配置 OC 实例，请在账套设置中绑定后再下发");
                skipped.add(asLabel + "：未配置 OC 实例");
                skippedCount++;
                continue;
            }

            boolean officerOk = hasDefaultOfficer.contains(id);
            boolean isGeneralTaxpayer = cn.gson.financial.kernel.model.enums.VatType.COMMONLY.equals(as.getVatType());
            TaskPeriod existingTp = tpMap.get(id);

            boolean anyDispatched = false;
            // 本账套各步骤被跳过的原因（仅当整个账套无任何实际下发时汇总展示）
            List<String> stepSkipReasons = new ArrayList<>();
            for (String step : orderedSteps) {
                // 步骤已完成（done/skipped/na/waiting）→ 跳过，不重复下发
                // 例外：做账前申报（data.vat_declare_pre）按子任务粒度判断，允许在「获取数据」整体已完成后单独补发
                if (existingTp != null && !"data.vat_declare_pre".equals(step)) {
                    String stepKey = step.contains(".") ? step.substring(0, step.indexOf('.')) : step;
                    String st = getStepStatus(existingTp, stepKey);
                    if (COMPLETED_STATUSES.contains(st)) {
                        String label = stepStatusLabel(st);
                        // 个税已跳过时补出具体原因（个税密码为空 / 无在职员工），与 dispatch 判定一致，便于排查
                        if ("skipped".equals(st) && ("iit".equals(stepKey) || "tax_iit".equals(stepKey))) {
                            label = iitSkipReasonLabel(as, id);
                        }
                        stepSkipReasons.add("【" + step + "】" + label);
                        continue;
                    }
                }
                // 需要凭证但无默认办税员 → 跳过
                if (CRED_STEPS.contains(step) && !officerOk) {
                    errors.add(asLabel + "：未配置默认办税员，无法下发【" + step + "】步骤");
                    stepSkipReasons.add("【" + step + "】未配置默认办税员");
                    continue;
                }
                // 个税：本账期已在「个税截图看板」标记"已发送客户" → 批量下发时排除（手动单独触发不受此限）
                if (("iit".equals(step) || "tax_iit".equals(step)) && iitSentToClient(id, p)) {
                    stepSkipReasons.add("【" + step + "】已发送客户，已排除");
                    continue;
                }
                try {
                    List<Map<String, Object>> rs = new ArrayList<>();
                    switch (step) {
                        // ── 获取数据：全量（含发票+银行+工资）
                        case "data" -> {
                            rs.add(self.dispatch(id, p, "invoice", false));
                            rs.add(self.dispatch(id, p, "data", false, null, "bank"));
                            rs.add(self.dispatch(id, p, "data", false, null, "salary"));
                        }
                        // ── 获取数据：子任务粒度
                        case "data.invoice"           -> rs.add(self.dispatch(id, p, "invoice", false));
                        case "data.invoice_check"     -> rs.add(self.dispatch(id, p, "data", false, null, "invoice_check"));
                        case "data.bank"              -> rs.add(self.dispatch(id, p, "data", false, null, "bank"));
                        case "data.salary"            -> rs.add(self.dispatch(id, p, "data", false, null, "salary"));
                        case "data.vat_declare_pre"   -> rs.add(self.dispatch(id, p, "data", false, null, "vat_declare_pre"));
                        // ── 做账：全量或子任务粒度
                        case "bookkeep"               -> rs.add(executeBookkeepAfterDispatch(self.dispatch(id, p, "bookkeep", false)));
                        case "bookkeep.asset"         -> rs.add(executeBookkeepAfterDispatch(self.dispatch(id, p, "bookkeep", false, null, "asset")));
                        case "bookkeep.invoice"       -> rs.add(executeBookkeepAfterDispatch(self.dispatch(id, p, "bookkeep", false, null, "invoice")));
                        case "bookkeep.salary"        -> rs.add(executeBookkeepAfterDispatch(self.dispatch(id, p, "bookkeep", false, null, "salary")));
                        case "bookkeep.bank"          -> rs.add(executeBookkeepAfterDispatch(self.dispatch(id, p, "bookkeep", false, null, "bank")));
                        case "bookkeep.tax"           -> rs.add(executeBookkeepAfterDispatch(self.dispatch(id, p, "bookkeep", false, null, "tax")));
                        case "bookkeep.carry_forward" -> rs.add(executeBookkeepAfterDispatch(self.dispatch(id, p, "bookkeep", false, null, "carry_forward")));
                        // ── 其余步骤直接透传
                        default -> rs.add(self.dispatch(id, p, step, false));
                    }
                    // 判断本步骤是否产生了实际下发：success=true 且非 skipped/blocked/waiting
                    boolean realDispatch = rs.stream().anyMatch(r -> r != null
                            && Boolean.TRUE.equals(r.get("success"))
                            && !Boolean.TRUE.equals(r.get("skipped"))
                            && !Boolean.TRUE.equals(r.get("blocked"))
                            && !Boolean.TRUE.equals(r.get("waiting")));
                    if (realDispatch) {
                        anyDispatched = true;
                    } else {
                        // 未实际下发：取后端返回的 msg 作为跳过原因
                        String reason = rs.stream()
                                .filter(r -> r != null && StrUtil.isNotBlank((String) r.get("msg")))
                                .map(r -> (String) r.get("msg"))
                                .findFirst().orElse("已跳过");
                        stepSkipReasons.add("【" + step + "】" + reason);
                    }
                } catch (Exception e) {
                    errors.add(asLabel + "【" + step + "】：" + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
                }
            }
            if (anyDispatched) {
                successCount++;
            } else {
                skippedCount++;
                skipped.add(asLabel + "：" + (stepSkipReasons.isEmpty()
                        ? "无可下发步骤" : String.join("；", stepSkipReasons)));
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("successCount", successCount);
        result.put("skippedCount", skippedCount);
        result.put("skipped", skipped);
        result.put("failCount", errors.size());
        result.put("errors", errors);
        return result;
    }

    /** 个税步骤已跳过的具体原因标签（与 dispatch 判定一致：个税密码为空 / 无在职员工）。 */
    private String iitSkipReasonLabel(AccountSets as, Long accountSetId) {
        if (as == null) return "已跳过";
        if (StrUtil.isBlank(as.getIndividualTaxCode())) return "已跳过（未配置个税密码）";
        if (!taskSalaryService.hasEmployees(accountSetId)) return "已跳过（无在职员工）";
        return "已跳过";
    }

    /** 该账套该账期是否已在「个税截图看板」标记为"已发送客户"（批量下发个税时据此排除；手动单独触发不查此项）。 */
    private boolean iitSentToClient(Long accountSetId, String period) {
        return taxFilingStatusRepository.findByAccountSetIdAndTaxPeriod(accountSetId, period)
                .map(s -> Boolean.TRUE.equals(s.getIitScreenshotSent()))
                .orElse(false);
    }

    /** 步骤状态中文标签（用于批量下发跳过原因展示）。 */
    private String stepStatusLabel(String status) {
        return switch (status == null ? "" : status) {
            case "done"    -> "已完成";
            case "skipped" -> "已跳过";
            case "waiting" -> "待确认";
            case "na"      -> "无需处理";
            case "running" -> "执行中";
            case "blocked" -> "等待前置任务";
            case "error"   -> "失败";
            case "pending" -> "待下发";
            default        -> status;
        };
    }

    /**
     * 重置任务
     */
    @Transactional
    public Map<String, Object> reset(Long accountSetId, String period, String step) {
        String p = resolvePeriod(period);
        TaskPeriod tp = taskPeriodRepository.findByAccountSetIdAndPeriod(accountSetId, p)
                .orElse(null);
        if (tp == null) {
            return Map.of("success", true, "msg", "该账期任务不存在，无需重置");
        }

        if (StrUtil.isBlank(step)) {
            // 重置所有步骤
            tp.setStepVerify("pending");
            tp.setStepInit("pending");
            tp.setStepData("pending");
            tp.setStepBookkeep("pending");
            tp.setStepTaxNational("pending");
            tp.setStepTaxIit("pending");
            tp.setStepPayment("pending");
            tp.setStepTaxAnnual("pending");
            // 数据被重新执行，清空所有"已核查"标记
            tp.setStepVerifyChecked(false);
            tp.setStepInitChecked(false);
            tp.setStepDataChecked(false);
            tp.setStepBookkeepChecked(false);
            tp.setStepTaxNationalChecked(false);
            tp.setStepTaxIitChecked(false);
            tp.setStepPaymentChecked(false);
            tp.setStepTaxAnnualChecked(false);
            tp.setErrorStep(null);
            tp.setErrorMsg(null);
            // 清空各步骤子表
            taskVerifyResultRepository.deleteByTaskPeriodId(tp.getId());
            taskDataSubRepository.findByTaskPeriodId(tp.getId()).forEach(sub -> {
                sub.setStatus("pending");
                sub.setErrorMsg(null);
                taskDataSubRepository.save(sub);
            });
            taskBookkeepSubRepository.deleteByTaskPeriodId(tp.getId());
            taskIitItemRepository.deleteByTaskPeriodId(tp.getId());
            taskPaymentItemRepository.deleteByTaskPeriodId(tp.getId());
            // 年报跨月共享，按 accountSetId + taxYear 删除，并清理原始月份
            String fullResetTaxYear = String.valueOf(Integer.parseInt(p.substring(0, 4)) - 1);
            taskTaxAnnualItemRepository.findByAccountSetIdAndTaxYear(accountSetId, fullResetTaxYear)
                    .ifPresent(item -> {
                        if (!item.getTaskPeriodId().equals(tp.getId())) {
                            taskPeriodRepository.findById(item.getTaskPeriodId()).ifPresent(otherTp -> {
                                otherTp.setStepTaxAnnual("pending");
                                taskPeriodRepository.save(otherTp);
                            });
                        }
                    });
            taskTaxAnnualItemRepository.deleteByAccountSetIdAndTaxYear(accountSetId, fullResetTaxYear);
            // 删除所有 oc_task 记录（含可能在其他月份创建的年报任务）
            ocTaskRepository.deleteByAccountSetIdAndPeriod(accountSetId, p);
            ocTaskRepository.deleteByAccountSetIdAndTaskTypeIn(accountSetId,
                    List.of("tax_annual"));
        } else {
            setStepStatus(tp, step, "pending");
            // 单步骤重置：清掉对应主步骤的"已核查"标记（子任务名传入时 setStepChecked 会返回 false，安全无副作用）
            setStepChecked(tp, step, false);
            if ("data".equals(step)) {
                // 重置整个数据采集步骤：清空所有子任务
                for (String st : List.of("invoice_in", "invoice_out", "invoice_check",
                        "invoice_checked", "invoice_unchecked",
                        "bank", "salary", "tax_items", "invoice", "vat_declare_pre")) {
                    taskDataSubRepository.findByTaskPeriodIdAndSubType(tp.getId(), st).ifPresent(sub -> {
                        sub.setStatus("pending");
                        sub.setErrorMsg(null);
                        taskDataSubRepository.save(sub);
                    });
                }
                tp.setStepData("pending");
                // 删除数据采集相关的 oc_task
                ocTaskRepository.deleteByAccountSetIdAndPeriodAndTaskTypeIn(accountSetId, p,
                        List.of("download_invoice_in", "download_invoice_out",
                                "check_invoice",
                                "download_invoice_checked", "download_invoice_unchecked",
                                "fetch_tax_items", "parse_bank", "copy_salary",
                                "vat_declare_pre"));
            } else if ("invoice".equals(step) || "invoice_in".equals(step) || "invoice_out".equals(step)
                    || "invoice_check".equals(step)
                    || "invoice_checked".equals(step) || "invoice_unchecked".equals(step)
                    || "bank".equals(step) || "salary".equals(step) || "tax_items".equals(step)
                    || "vat_declare_pre".equals(step)) {
                updateSubTaskStatus(tp.getId(), step, "pending");
                // 删除对应子任务类型的 oc_task
                if ("invoice".equals(step)) {
                    // "invoice" 是聚合类型，需要重置所有发票子任务并删除所有发票类 OcTask
                    for (String st : List.of("invoice_in", "invoice_out", "invoice_check",
                            "invoice_checked", "invoice_unchecked")) {
                        updateSubTaskStatus(tp.getId(), st, "pending");
                    }
                    ocTaskRepository.deleteByAccountSetIdAndPeriodAndTaskTypeIn(accountSetId, p,
                            List.of("download_invoice_in", "download_invoice_out",
                                    "check_invoice",
                                    "download_invoice_checked", "download_invoice_unchecked"));
                } else {
                    String ocTaskType = dataSubTypeToOcTaskType(step);
                    if (ocTaskType != null) {
                        ocTaskRepository.deleteByAccountSetIdAndPeriodAndTaskTypeIn(accountSetId, p, List.of(ocTaskType));
                    } else if ("bank".equals(step)) {
                        ocTaskRepository.deleteByAccountSetIdAndPeriodAndTaskTypeIn(accountSetId, p, List.of("parse_bank"));
                    } else if ("salary".equals(step)) {
                        ocTaskRepository.deleteByAccountSetIdAndPeriodAndTaskTypeIn(accountSetId, p, List.of("copy_salary"));
                    }
                }
                recalcStepData(tp);
            } else if ("verify".equals(step)) {
                taskVerifyResultRepository.deleteByTaskPeriodId(tp.getId());
                ocTaskRepository.deleteByAccountSetIdAndPeriodAndTaskTypeIn(accountSetId, p, List.of("verify_tax"));
            } else if ("bookkeep".equals(step)) {
                taskBookkeepSubRepository.deleteByTaskPeriodId(tp.getId());
                ocTaskRepository.deleteByAccountSetIdAndPeriodAndTaskTypeIn(accountSetId, p,
                        List.of("bookkeep_asset", "bookkeep_invoice", "bookkeep_salary", "bookkeep_bank", "bookkeep_tax", "bookkeep_carry_forward"));
            } else if ("vat".equals(step)) {
                taskTaxItemRepository.deleteByTaskPeriodId(tp.getId());
                ocTaskRepository.deleteByAccountSetIdAndPeriodAndTaskTypeIn(accountSetId, p,
                        List.of("vat_declare_post", "vat_declare", "declare_tax_item"));
            } else if ("iit".equals(step)) {
                taskIitItemRepository.deleteByTaskPeriodId(tp.getId());
                ocTaskRepository.deleteByAccountSetIdAndPeriodAndTaskTypeIn(accountSetId, p, List.of("iit_declare"));
            } else if ("payment".equals(step)) {
                taskPaymentItemRepository.deleteByTaskPeriodId(tp.getId());
                ocTaskRepository.deleteByAccountSetIdAndPeriodAndTaskTypeIn(accountSetId, p,
                        List.of("query_payment_national", "query_payment"));
            } else if ("tax_annual".equals(step)) {
                // 年报跨月共享，按 accountSetId + taxYear 删除（不限于当前月的 TaskPeriod）
                String resetTaxYear = String.valueOf(Integer.parseInt(p.substring(0, 4)) - 1);
                // 先找到原始 TaskPeriod 并清理其 stepTaxAnnual（可能在其他月份）
                taskTaxAnnualItemRepository.findByAccountSetIdAndTaxYear(accountSetId, resetTaxYear)
                        .ifPresent(item -> {
                            if (!item.getTaskPeriodId().equals(tp.getId())) {
                                taskPeriodRepository.findById(item.getTaskPeriodId()).ifPresent(otherTp -> {
                                    otherTp.setStepTaxAnnual("pending");
                                    taskPeriodRepository.save(otherTp);
                                });
                            }
                        });
                taskTaxAnnualItemRepository.deleteByAccountSetIdAndTaxYear(accountSetId, resetTaxYear);
                // OcTask 也可能在别的月份创建，按类型全量删除
                ocTaskRepository.deleteByAccountSetIdAndTaskTypeIn(accountSetId,
                        List.of("tax_annual"));
            }
        }
        taskPeriodRepository.save(tp);
        return Map.of("success", true, "msg", "重置成功");
    }

    /**
     * 批量重置指定步骤（可多选）。
     * <p>
     * steps 可包含：verify / data / invoice / invoice_in / invoice_out / invoice_check /
     *               invoice_checked / invoice_unchecked / bank / salary / bookkeep / vat / iit / payment
     * <p>
     * 对每个账套的每个步骤调用已有的 reset() 方法，逐步骤重置对应的子表和 oc_task 记录。
     */
    public Map<String, Object> batchResetSteps(List<Long> accountSetIds, String period, List<String> steps) {
        List<String> STEP_ORDER = List.of(
                "verify", "init", "data", "invoice", "invoice_in", "invoice_out",
                "invoice_check", "invoice_checked", "invoice_unchecked",
                "bank", "salary", "tax_items", "vat_declare_pre",
                "bookkeep", "vat", "iit", "payment", "tax_annual");
        List<String> orderedSteps = STEP_ORDER.stream().filter(steps::contains).toList();

        int successCount = 0;
        int skippedCount = 0;
        List<String> errors = new ArrayList<>();

        for (Long id : accountSetIds) {
            try {
                self.resetSingleAccount(id, period, orderedSteps);
                successCount++;
            } catch (Exception e) {
                errors.add("账套" + id + ": " + e.getMessage());
                skippedCount++;
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("successCount", successCount);
        result.put("skippedCount", skippedCount);
        result.put("failCount", errors.size());
        result.put("errors", errors);
        return result;
    }

    /** 单个账套重置（独立事务，避免一个账套锁超时导致全部回滚） */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void resetSingleAccount(Long accountSetId, String period, List<String> steps) {
        for (String step : steps) {
            reset(accountSetId, period, step);
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // OpenClaw 轮询接口
    // ─────────────────────────────────────────────────────────────────────

    /**
     * OpenClaw 轮询待执行任务。
     * 只返回 status="queued" 且前置步骤已满足的任务。
     * 若前置步骤失败（error），则级联将当前任务标记为 failed，并设置 task_period 对应步骤为 error。
     * 根据 oc_instance_id 获取下一个待处理企业的登录信息。
     * <p>
     * 查找该实例下所有 queued 任务，按创建时间排序，取第一条对应的企业，
     * 返回该企业的登录凭证信息。若无待处理任务则返回 null。
     *
     * @param ocInstanceId OC 实例编号（必填）
     * @return 企业登录信息 Map，无任务时返回 null
     */
    @Transactional
    public Map<String, Object> getNextPendingAccount(List<String> ocInstanceIds, String taskType) {
        return getNextPendingAccount(ocInstanceIds, taskType, null, Set.of());
    }

    /**
     * 获取下一个待处理企业的登录信息。
     * @param province          指定省份（非空时只在该省份内查找），用于 OC 并发时锁定省份
     * @param excludeProvinces  排除集合，格式 "credential|province"（其他 Worker 已占用的凭证+省份组合）
     */
    @Transactional
    public Map<String, Object> getNextPendingAccount(List<String> ocInstanceIds, String taskType, String province, Set<String> excludeProvinces) {
        return getNextPendingAccount(ocInstanceIds, taskType, province, excludeProvinces, null, null);
    }

    @Transactional
    public Map<String, Object> getNextPendingAccount(List<String> ocInstanceIds, String taskType, String province, Set<String> excludeProvinces, Integer maxCount, Integer timeWindow) {
        return getNextPendingAccount(ocInstanceIds, taskType, province, excludeProvinces, maxCount, timeWindow, null, null);
    }

    public Map<String, Object> getNextPendingAccount(List<String> ocInstanceIds, String taskType, String province, Set<String> excludeProvinces, Integer maxCount, Integer timeWindow, String loginCredential, String loginType) {
        return getNextPendingAccount(ocInstanceIds, taskType, province, excludeProvinces, maxCount, timeWindow, loginCredential, loginType, null);
    }

    @Transactional
    public Map<String, Object> getNextPendingAccount(List<String> ocInstanceIds, String taskType, String province, Set<String> excludeProvinces, Integer maxCount, Integer timeWindow, String loginCredential, String loginType, List<String> allowedProvinces) {
        // OC 客户端可能传入带单引号的 ID（如 'OC-03'），统一去掉首尾引号后再比较
        ocInstanceIds = ocInstanceIds.stream()
                .map(id -> id.startsWith("'") && id.endsWith("'") ? id.substring(1, id.length() - 1) : id)
                .collect(Collectors.toList());

        boolean iitOnly = "iit".equals(taskType) || "iit_declare".equals(taskType);
        boolean nationalOnly = "national".equals(taskType);

        // 合并两类排除：操作日志超限 + 外部传入的 worker 占用，格式均为 "credential|province"
        Set<String> excludedCredentialProvinces = new HashSet<>(
                (maxCount != null && timeWindow != null)
                        ? ocOperationLogRepository.findExcludedCredentialProvinces(maxCount, timeWindow)
                        : List.of());
        if (excludeProvinces != null) excludedCredentialProvinces.addAll(excludeProvinces);

        List<OcTask> queued = ocTaskRepository.findByStatusOrderByProvinceAscCreatedAtAsc("queued");
        log.info("[next-account] ocInstanceIds={} queued总数={}", ocInstanceIds, queued.size());
        Map<String, TaskPeriod> tpCache = new HashMap<>();

        for (OcTask oc : queued) {
            if (!ocInstanceIds.contains(oc.getOcInstanceId())) continue;
            // 跳过本地任务，OC 不处理
            if (isLocalTask(oc.getTaskType())) { log.info("[next-account] skip local ocTaskId={}", oc.getId()); continue; }
            if (iitOnly && !isTaxIitTask(oc.getTaskType())) continue;
            if (nationalOnly && !isNationalTask(oc.getTaskType())) { log.info("[next-account] skip non-national ocTaskId={} type={}", oc.getId(), oc.getTaskType()); continue; }

            // 登录凭证过滤：只返回与当前 OC 实例所用凭证匹配的任务
            if (StrUtil.isNotBlank(loginCredential)) {
                String payloadCredential = extractLoginCredentialByType(oc.getPayloadJson(), loginType);
                if (!loginCredential.equals(payloadCredential)) { log.info("[next-account] skip credential mismatch ocTaskId={}", oc.getId()); continue; }
            }

            // 从 payload_json 提取省份（与 oc_operation_log 格式一致，均为英文）
            String payloadProvince = extractPayloadProvince(oc.getPayloadJson());

            // 省份过滤
            if (StrUtil.isNotBlank(province) && !province.equals(payloadProvince)) { log.info("[next-account] skip province mismatch ocTaskId={}", oc.getId()); continue; }

            // allowed_provinces 白名单（仅 national 时生效）：账套省份不在白名单内则跳过
            if (allowedProvinces != null && !allowedProvinces.isEmpty()) {
                String accountProvince = extractPayloadProvince(oc.getPayloadJson());
                if (!allowedProvinces.contains(accountProvince)) { log.info("[next-account] skip not-in-allowed-provinces ocTaskId={} province={}", oc.getId(), accountProvince); continue; }
            }

            // 凭证+省份组合排除（含超限 + 其他 worker 占用）
            if (!excludedCredentialProvinces.isEmpty()) {
                String credential = extractLoginCredential(oc.getPayloadJson());
                String key = credential + "|" + payloadProvince;
                if (StrUtil.isNotBlank(credential) && StrUtil.isNotBlank(payloadProvince)
                        && excludedCredentialProvinces.contains(key)) { log.info("[next-account] skip rate-limit ocTaskId={} key={}", oc.getId(), key); continue; }
            }

            // 检查前置依赖
            String tpKey = oc.getAccountSetId() + "_" + oc.getPeriod();
            TaskPeriod tp = tpCache.computeIfAbsent(tpKey,
                    k -> taskPeriodRepository.findByAccountSetIdAndPeriod(
                            oc.getAccountSetId(), oc.getPeriod()).orElse(null));
            String prereq = checkOcTaskPrerequisite(oc.getTaskType(), tp, oc.getAccountSetId(), oc.getPeriod());
            if ("failed".equals(prereq) || "blocked".equals(prereq)) { log.info("[next-account] skip prereq={} ocTaskId={}", prereq, oc.getId()); continue; }
            log.info("[next-account] matched ocTaskId={} type={} ocInstanceId={}", oc.getId(), oc.getTaskType(), oc.getOcInstanceId());

            // 找到第一个可执行任务对应的企业，构建登录信息
            Long accountSetId = oc.getAccountSetId();
            return accountSetsRepository.findById(accountSetId).map(as -> {
                Map<String, Object> info = new LinkedHashMap<>();
                info.put("account_set_id", as.getId());
                info.put("company_name", as.getCompanyName());
                info.put("credit_code", as.getCreditCode());
                info.put("individual_tax_code", as.getIndividualTaxCode());
                info.put("province", as.getProvince());
                info.put("vat_type", as.getVatType() != null ? as.getVatType().display : null);

                // 登录凭证从任务的 payload_json 读取，保证与任务下发时一致
                if (StrUtil.isNotBlank(oc.getPayloadJson())) {
                    try {
                        Map<String, Object> payload = JSON.parseObject(oc.getPayloadJson(),
                                new com.alibaba.fastjson2.TypeReference<Map<String, Object>>() {});
                        for (String key : List.of("login_type", "officer_name", "phone", "password",
                                "proxy_account", "proxy_password", "legal_name", "id_card", "iit_password")) {
                            if (payload.containsKey(key) && payload.get(key) != null) {
                                info.put(key, payload.get(key));
                            }
                        }
                    } catch (Exception e) {
                        log.warn("解析任务 payload 失败，兜底读账套凭证: ocTaskId={}", oc.getId(), e);
                        applyLoginFallback(info, as);
                    }
                } else {
                    applyLoginFallback(info, as);
                }

                return info;
            }).orElse(null);
        }
        return null; // 无待处理任务
    }

    /**
     * 获取多个不同省份的待处理企业（用于 OC 并发执行）。
     * <p>
     * 每个省份最多返回一个企业，总数不超过 count。
     * 已返回省份的企业不会重复出现，保证 OC 可以并发登录不同省份。
     *
     * @param count             期望返回的企业数量（即并发省份数）
     * @param excludeProvinces  排除集合，格式 "credential|province"（其他 Worker 已占用的凭证+省份组合）
     * @return 企业登录信息列表，可能少于 count（可用省份不足时）
     */
    @Transactional
    public List<Map<String, Object>> getNextPendingAccounts(List<String> ocInstanceIds, String taskType, int count, Set<String> excludeProvinces) {
        return getNextPendingAccounts(ocInstanceIds, taskType, count, excludeProvinces, null, null);
    }

    @Transactional
    public List<Map<String, Object>> getNextPendingAccounts(List<String> ocInstanceIds, String taskType, int count, Set<String> excludeProvinces, Integer maxCount, Integer timeWindow) {
        return getNextPendingAccounts(ocInstanceIds, taskType, count, excludeProvinces, maxCount, timeWindow, null, null);
    }

    @Transactional
    public List<Map<String, Object>> getNextPendingAccounts(List<String> ocInstanceIds, String taskType, int count, Set<String> excludeProvinces, Integer maxCount, Integer timeWindow, String loginCredential, String loginType) {
        return getNextPendingAccounts(ocInstanceIds, taskType, count, excludeProvinces, maxCount, timeWindow, loginCredential, loginType, null);
    }

    @Transactional
    public List<Map<String, Object>> getNextPendingAccounts(List<String> ocInstanceIds, String taskType, int count, Set<String> excludeProvinces, Integer maxCount, Integer timeWindow, String loginCredential, String loginType, List<String> allowedProvinces) {
        ocInstanceIds = ocInstanceIds.stream()
                .map(id -> id.startsWith("'") && id.endsWith("'") ? id.substring(1, id.length() - 1) : id)
                .collect(Collectors.toList());

        boolean iitOnly = "iit".equals(taskType) || "iit_declare".equals(taskType);
        boolean nationalOnly = "national".equals(taskType);

        // 合并两类排除：操作日志超限 + 外部传入的 worker 占用，格式均为 "credential|province"
        Set<String> excludedCredentialProvinces = new HashSet<>(
                (maxCount != null && timeWindow != null)
                        ? ocOperationLogRepository.findExcludedCredentialProvinces(maxCount, timeWindow)
                        : List.of());
        if (excludeProvinces != null) excludedCredentialProvinces.addAll(excludeProvinces);

        List<OcTask> queued = ocTaskRepository.findByStatusOrderByProvinceAscCreatedAtAsc("queued");
        Map<String, TaskPeriod> tpCache = new HashMap<>();

        List<Map<String, Object>> result = new ArrayList<>();
        Set<String> usedProvinces = new HashSet<>();
        Set<Long> usedAccountSetIds = new HashSet<>();

        for (OcTask oc : queued) {
            if (result.size() >= count) break;

            if (!ocInstanceIds.contains(oc.getOcInstanceId())) continue;
            // 跳过本地任务，OC 不处理
            if (isLocalTask(oc.getTaskType())) continue;
            if (iitOnly && !isTaxIitTask(oc.getTaskType())) continue;
            if (nationalOnly && !isNationalTask(oc.getTaskType())) continue;

            // 登录凭证过滤：只返回与当前 OC 实例所用凭证匹配的任务
            if (StrUtil.isNotBlank(loginCredential)) {
                String payloadCredential = extractLoginCredentialByType(oc.getPayloadJson(), loginType);
                if (!loginCredential.equals(payloadCredential)) continue;
            }

            // 同一账套不重复返回
            if (usedAccountSetIds.contains(oc.getAccountSetId())) continue;

            String tpKey = oc.getAccountSetId() + "_" + oc.getPeriod();
            TaskPeriod tp = tpCache.computeIfAbsent(tpKey,
                    k -> taskPeriodRepository.findByAccountSetIdAndPeriod(
                            oc.getAccountSetId(), oc.getPeriod()).orElse(null));
            String prereq = checkOcTaskPrerequisite(oc.getTaskType(), tp, oc.getAccountSetId(), oc.getPeriod());
            if ("failed".equals(prereq) || "blocked".equals(prereq)) continue;

            Long accountSetId = oc.getAccountSetId();
            AccountSets as = accountSetsRepository.findById(accountSetId).orElse(null);
            if (as == null) continue;

            // 从 payload_json 提取省份（与 oc_operation_log 格式一致，均为英文）
            String payloadProvince = extractPayloadProvince(oc.getPayloadJson());
            String provinceKey = StrUtil.isNotBlank(payloadProvince) ? payloadProvince : "__blank__";

            // allowed_provinces 白名单（仅 national 时生效）：账套省份不在白名单内则跳过
            if (allowedProvinces != null && !allowedProvinces.isEmpty()) {
                if (!allowedProvinces.contains(payloadProvince)) continue;
            }

            // 凭证+省份组合排除（含超限 + 其他 worker 占用）
            if (!excludedCredentialProvinces.isEmpty()) {
                String credential = extractLoginCredential(oc.getPayloadJson());
                if (StrUtil.isNotBlank(credential) && StrUtil.isNotBlank(payloadProvince)
                        && excludedCredentialProvinces.contains(credential + "|" + payloadProvince)) continue;
            }
            // 每个省份只取一个企业
            if (usedProvinces.contains(provinceKey)) continue;

            Map<String, Object> info = new LinkedHashMap<>();
            info.put("account_set_id", as.getId());
            info.put("company_name", as.getCompanyName());
            info.put("credit_code", as.getCreditCode());
            info.put("individual_tax_code", as.getIndividualTaxCode());
            info.put("province", payloadProvince);
            info.put("vat_type", as.getVatType() != null ? as.getVatType().display : null);

            if (StrUtil.isNotBlank(oc.getPayloadJson())) {
                try {
                    Map<String, Object> payload = JSON.parseObject(oc.getPayloadJson(),
                            new com.alibaba.fastjson2.TypeReference<Map<String, Object>>() {});
                    for (String key : List.of("login_type", "officer_name", "phone", "password",
                            "proxy_account", "proxy_password", "legal_name", "id_card", "iit_password")) {
                        if (payload.containsKey(key) && payload.get(key) != null) {
                            info.put(key, payload.get(key));
                        }
                    }
                } catch (Exception e) {
                    log.warn("解析任务 payload 失败，兜底读账套凭证: ocTaskId={}", oc.getId(), e);
                    applyLoginFallback(info, as);
                }
            } else {
                applyLoginFallback(info, as);
            }

            result.add(info);
            usedProvinces.add(provinceKey);
            usedAccountSetIds.add(accountSetId);
        }
        return result;
    }

    /**
     * 获取指定企业下的下一条可执行任务。
     * <p>
     * 返回 Map 中始终包含 {@code "status"} 字段，取值如下：
     * <ul>
     *   <li>{@code "ok"}       — 有可执行任务，{@code "task"} 字段包含任务详情</li>
     *   <li>{@code "blocked"}  — 还有排队任务但前置未完成，OpenClaw 应等待后重试</li>
     *   <li>{@code "empty"}    — 该企业已无待执行任务，OpenClaw 应切换下一个企业</li>
     * </ul>
     *
     * @param ocInstanceId OC 实例编号
     * @param accountSetId 账套ID
     */
    @Transactional
    public Map<String, Object> getNextPendingTask(List<String> ocInstanceIds, Long accountSetId, String taskType) {
        ocInstanceIds = ocInstanceIds.stream()
                .map(id -> id.startsWith("'") && id.endsWith("'") ? id.substring(1, id.length() - 1) : id)
                .collect(Collectors.toList());

        boolean iitOnly = "iit".equals(taskType) || "iit_declare".equals(taskType);
        boolean nationalOnly = "national".equals(taskType);

        // 1. 从 task_period 判断该企业是否还有需要 OC 处理且未完成的步骤
        //    查询该账套所有账期（不限当前月份），避免跨月处理时期间不匹配
        //    终态：done/error/warn/skipped/na/空 — 这些状态说明该步骤已结束，不需要 OC 再处理
        //    注意：做账(bookkeep)是本地执行、个税(iit)是独立流程，不影响 OC 等待判断
        List<TaskPeriod> tpList = taskPeriodRepository.findByAccountSetId(accountSetId);
        boolean hasUnfinishedOcSteps = false;
        if (!tpList.isEmpty()) {
            if (iitOnly) {
                hasUnfinishedOcSteps = tpList.stream().anyMatch(t ->
                        isActiveOcStatus(StrUtil.nullToEmpty(t.getStepTaxIit())));
            } else {
                hasUnfinishedOcSteps = tpList.stream().anyMatch(t ->
                        isActiveOcStatus(StrUtil.nullToEmpty(t.getStepVerify()))
                        || isActiveOcStatus(StrUtil.nullToEmpty(t.getStepData()))
                        || isActiveOcStatus(StrUtil.nullToEmpty(t.getStepTaxNational()))
                        || isActiveOcStatus(StrUtil.nullToEmpty(t.getStepPayment()))
                        || isActiveOcStatus(StrUtil.nullToEmpty(t.getStepTaxAnnual())));
            }
        }

        // 2. 查找 queued 任务
        List<OcTask> queued = ocTaskRepository.findByStatusOrderByCreatedAtAsc("queued");

        Map<String, TaskPeriod> tpCache = new HashMap<>();
        boolean hasBlocked = false;

        for (OcTask oc : queued) {
            // 只看该账套的任务
            if (!accountSetId.equals(oc.getAccountSetId())) continue;
            // 按实例过滤
            if (!ocInstanceIds.contains(oc.getOcInstanceId())) continue;
            // 跳过本地任务（parse_bank/copy_salary/bookkeep_*/init_import），OC 不处理
            if (isLocalTask(oc.getTaskType())) continue;
            // 按任务类型过滤
            if (iitOnly && !isTaxIitTask(oc.getTaskType())) continue;
            if (nationalOnly && !isNationalTask(oc.getTaskType())) continue;

            // 检查前置依赖
            String tpKey = oc.getAccountSetId() + "_" + oc.getPeriod();
            TaskPeriod cachedTp = tpCache.computeIfAbsent(tpKey,
                    k -> taskPeriodRepository.findByAccountSetIdAndPeriod(
                            oc.getAccountSetId(), oc.getPeriod()).orElse(null));

            String prereq = checkOcTaskPrerequisite(oc.getTaskType(), cachedTp, oc.getAccountSetId(), oc.getPeriod());

            if ("failed".equals(prereq)) {
                oc.setStatus("failed");
                oc.setFinishedAt(LocalDateTime.now());
                ocTaskRepository.save(oc);
                if (cachedTp != null) {
                    // data 类任务：同步把 task_data_sub.status 也标 error，避免 UI 误导
                    if (isDataTask(oc.getTaskType())) {
                        cascadeFailDataSub(cachedTp.getId(), oc.getTaskType(),
                                "前置任务失败，本任务级联中止");
                    }
                    setCascadeFailStep(cachedTp, oc.getTaskType());
                    taskPeriodRepository.save(cachedTp);
                    tpCache.put(tpKey, cachedTp);
                }
                log.info("级联失败: ocTaskId={}, taskType={}, accountSetId={}, period={}",
                        oc.getId(), oc.getTaskType(), oc.getAccountSetId(), oc.getPeriod());
                continue;
            }

            if ("blocked".equals(prereq)) {
                hasBlocked = true;
                continue;
            }

            // 找到可执行任务
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "ok");
            result.put("task", buildOcTaskBrief(oc));
            return result;
        }

        // 3. 级联失败：如果 blocked 的步骤其前置已 error/failed，则级联标记为 error
        //    防止 OC 在前置失败后永远等待 blocked 步骤
        if (hasUnfinishedOcSteps) {
            boolean cascadeUpdated = false;
            for (TaskPeriod tpCheck : tpList) {
                boolean changed = false;
                // data 失败 → bookkeep blocked 级联失败（bookkeep 不在 OC 等待列表中，但其后置步骤会受影响）
                if ("error".equals(tpCheck.getStepData()) && "blocked".equals(tpCheck.getStepBookkeep())) {
                    tpCheck.setStepBookkeep("error");
                    changed = true;
                }
                // bookkeep 失败 → vat blocked 级联失败（iit 不依赖 bookkeep）
                // 例外：余额异常（Redis key 存在）属于可修复错误，不级联
                if ("error".equals(tpCheck.getStepBookkeep())) {
                    String balanceKey = "BOOKKEEP_BLOCK:" + accountSetId + ":" + tpCheck.getPeriod();
                    boolean isBalanceError = StrUtil.isNotBlank(redisUtil.get(balanceKey));
                    if (!isBalanceError) {
                        if ("blocked".equals(tpCheck.getStepTaxNational())) { tpCheck.setStepTaxNational("error"); changed = true; }
                    }
                }
                // vat 失败 → payment blocked 级联失败
                String vatSt = StrUtil.nullToEmpty(tpCheck.getStepTaxNational());
                if (("error".equals(vatSt) || "failed".equals(vatSt)) && "blocked".equals(tpCheck.getStepPayment())) {
                    tpCheck.setStepPayment("error");
                    changed = true;
                }
                if (changed) {
                    taskPeriodRepository.save(tpCheck);
                    cascadeUpdated = true;
                    // 同步级联失败对应的 blocked oc_task
                    if (tpCheck.getId() != null) {
                        List<OcTask> blockedOcTasks = ocTaskRepository.findByTaskPeriodIdAndStatus(tpCheck.getId(), "blocked");
                        for (OcTask blockedOc : blockedOcTasks) {
                            String prereq = checkOcTaskPrerequisite(blockedOc.getTaskType(), tpCheck, accountSetId, blockedOc.getPeriod());
                            if ("failed".equals(prereq)) {
                                blockedOc.setStatus("failed");
                                blockedOc.setFinishedAt(LocalDateTime.now());
                                ocTaskRepository.save(blockedOc);
                                if (isDataTask(blockedOc.getTaskType())) {
                                    cascadeFailDataSub(tpCheck.getId(), blockedOc.getTaskType(),
                                            "前置任务失败，本任务级联中止");
                                }
                                log.info("级联失败(blocked->failed): ocTaskId={}, taskType={}, accountSetId={}",
                                        blockedOc.getId(), blockedOc.getTaskType(), accountSetId);
                            }
                        }
                    }
                }
            }
            if (cascadeUpdated) {
                // 重新判断是否还有未完成的 OC 步骤
                if (iitOnly) {
                    hasUnfinishedOcSteps = tpList.stream().anyMatch(t ->
                            isActiveOcStatus(StrUtil.nullToEmpty(t.getStepTaxIit())));
                } else {
                    hasUnfinishedOcSteps = tpList.stream().anyMatch(t ->
                            isActiveOcStatus(StrUtil.nullToEmpty(t.getStepVerify()))
                            || isActiveOcStatus(StrUtil.nullToEmpty(t.getStepData()))
                            || isActiveOcStatus(StrUtil.nullToEmpty(t.getStepTaxNational()))
                            || isActiveOcStatus(StrUtil.nullToEmpty(t.getStepPayment())));
                }
            }
        }

        // 4. 无可执行的 queued 任务，综合判断是需要等待还是真正结束
        //    - oc_task 有 blocked：前置步骤正在处理中
        //    - task_period 有 OC 步骤未完成：本地任务正在执行或后续步骤即将被自动创建
        if (hasBlocked || hasUnfinishedOcSteps) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "waiting");
            result.put("task", null);
            result.put("retry_after", 10);
            result.put("msg", "当前企业任务处理中，请等待后重试");
            return result;
        }

        // 5. 做账余额异常等待检查：stepBookkeep=error 且 Redis 倒计时未过期 → OC 等待
        //    Redis 过期（600秒后）→ 返回 empty 让 OC 跳过
        for (TaskPeriod tpCheck : tpList) {
            if ("error".equals(tpCheck.getStepBookkeep())) {
                String redisKey = "BOOKKEEP_BLOCK:" + accountSetId + ":" + tpCheck.getPeriod();
                if (StrUtil.isNotBlank(redisUtil.get(redisKey))) {
                    // Redis key 存在（600 秒内），OC 继续等待
                    log.info("做账余额异常，OC 等待人工处理: accountSetId={}, period={}", accountSetId, tpCheck.getPeriod());
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("status", "waiting");
                    result.put("task", null);
                    result.put("retry_after", 10);
                    result.put("msg", "做账资产负债表不平衡，等待人工处理");
                    return result;
                }
                // Redis key 已过期 → 跳过（继续到下面返回 empty）
                log.info("做账异常超时（600秒），OC 跳过该企业: accountSetId={}, period={}", accountSetId, tpCheck.getPeriod());
            }
        }

        // 该企业所有任务已处理完毕，OC 可以切换下一家
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "empty");
        result.put("task", null);
        return result;
    }

    /**
     * 检查 OcTask 的前置步骤是否满足。
     * <p>所有前置条件已移至下发时（dispatch）检查，此处统一放行。
     * 仅保留级联失败检测：若前置步骤 error，则级联标记当前任务 failed。
     *
     * @return "ok"      = 可执行
     *         "failed"  = 前置失败（error），级联失败
     */
    private String checkOcTaskPrerequisite(String taskType, TaskPeriod tp, Long accountSetId, String period) {
        if (tp == null) return "ok";

        // verify / init / annual_report：无前置
        if (isVerifyOrInitTask(taskType)) return "ok";

        // 做账前申报（vat_declare_pre）：一般纳税人必须等 invoice_check 完成（done/skipped/warn 均放行；
        // pending/running 则阻塞；error 则级联失败）。小规模无 invoice_check，直接放行。
        if ("vat_declare_pre".equals(taskType)) {
            AccountSets as = accountSetId != null
                    ? accountSetsRepository.findById(accountSetId).orElse(null) : null;
            if (as != null && as.getVatType() == VatType.COMMONLY) {
                var checkSub = taskDataSubRepository.findByTaskPeriodIdAndSubType(tp.getId(), "invoice_check");
                if (checkSub.isEmpty()) return "blocked"; // 尚未下发 invoice_check
                String s = checkSub.get().getStatus();
                if ("pending".equals(s) || "running".equals(s)) return "blocked";
                if ("error".equals(s)) return "failed"; // 勾选失败 → vat_declare_pre 级联失败
            }
            return "ok";
        }

        // data 任务
        if (isDataTask(taskType)) {
            // check_invoice：等 invoice_in / invoice_out 子任务完成
            if ("check_invoice".equals(taskType)) {
                for (String waitFor : List.of("invoice_in", "invoice_out")) {
                    var preSub = taskDataSubRepository.findByTaskPeriodIdAndSubType(tp.getId(), waitFor);
                    if (preSub.isPresent()) {
                        String preStatus = preSub.get().getStatus();
                        if ("pending".equals(preStatus) || "running".equals(preStatus)) {
                            return "blocked";
                        }
                    }
                }
                return "ok";
            }
            // download_invoice_checked / download_invoice_unchecked：
            // 等 invoice_in / invoice_out 完成，且若 check_invoice oc_task 实际存在则等其完成
            if ("download_invoice_checked".equals(taskType) || "download_invoice_unchecked".equals(taskType)) {
                for (String waitFor : List.of("invoice_in", "invoice_out")) {
                    var preSub = taskDataSubRepository.findByTaskPeriodIdAndSubType(tp.getId(), waitFor);
                    if (preSub.isPresent()) {
                        String preStatus = preSub.get().getStatus();
                        if ("pending".equals(preStatus) || "running".equals(preStatus)) {
                            return "blocked";
                        }
                    }
                }
                // invoice_check：error 级联失败；pending/running 时只有 check_invoice oc_task 存在才阻塞
                // 无 oc_task 说明勾选已由会计手动完成或任务被清除，直接放行
                var checkSub = taskDataSubRepository.findByTaskPeriodIdAndSubType(tp.getId(), "invoice_check");
                if (checkSub.isPresent()) {
                    String checkStatus = checkSub.get().getStatus();
                    if ("error".equals(checkStatus)) return "failed";
                    if ("pending".equals(checkStatus) || "running".equals(checkStatus)) {
                        boolean hasOcTask = !ocTaskRepository.findByAccountSetIdAndPeriodAndTaskTypeIn(
                                accountSetId, period, List.of("check_invoice")).isEmpty();
                        if (hasOcTask) return "blocked";
                    }
                }
            }
            return "ok";
        }

        // bookkeep 任务：data 失败则级联失败；data 待确认时 OcTask 不应存在（dispatch 时已拦截）
        if (isBookkeepTask(taskType)) {
            if ("error".equals(tp.getStepData())) return "failed";
            return "ok";
        }

        // payment 任务前置检查须在 isTaxNationalTask 之前，因 query_payment_national 在 isTaxNationalTask 内
        // payment_national：tax_national 失败则级联失败
        if ("query_payment_national".equals(taskType)) {
            if ("error".equals(tp.getStepTaxNational())) return "failed";
            return "ok";
        }

        // payment_iit：tax_iit 失败则级联失败
        if ("query_payment_iit".equals(taskType)) {
            if ("error".equals(tp.getStepTaxIit())) return "failed";
            return "ok";
        }

        // query_payment（旧）：任一失败级联失败
        if ("query_payment".equals(taskType)) {
            String taxNat = tp.getStepTaxNational();
            String taxIit = tp.getStepTaxIit();
            if ("error".equals(taxNat) || "error".equals(taxIit)) return "failed";
            return "ok";
        }

        // tax_national 任务：bookkeep 失败则级联失败
        if (isTaxNationalTask(taskType)) {
            if ("error".equals(tp.getStepBookkeep())) return "failed";
            return "ok";
        }

        // tax_iit 任务：无额外级联条件
        if (isTaxIitTask(taskType)) {
            return "ok";
        }

        return "ok"; // 未知类型不拦截
    }

    /** 根据任务类型设置级联失败对应的 task_period 步骤字段。
     *  只改非终态（pending/running/blocked/waiting），已完成的步骤不覆盖。
     *  注意：verify_tax 和 payment 类型必须在 isTaxNationalTask 之前判断，
     *  因为 isTaxNationalTask 内部也包含这些类型，会导致步骤字段错位。 */
    private void setCascadeFailStep(TaskPeriod tp, String taskType) {
        if ("verify_tax".equals(taskType)) {
            if (isNonTerminal(tp.getStepVerify())) tp.setStepVerify("error");
        } else if ("annual_business_report".equals(taskType)) {
            if (isNonTerminal(tp.getStepAnnualReport())) tp.setStepAnnualReport("error");
        } else if ("query_payment_national".equals(taskType)
                || "query_payment_iit".equals(taskType)
                || "query_payment".equals(taskType)) {
            if (isNonTerminal(tp.getStepPayment())) tp.setStepPayment("error");
        } else if (isDataTask(taskType)) {
            if (isNonTerminal(tp.getStepData())) tp.setStepData("error");
        } else if (isBookkeepTask(taskType)) {
            if (isNonTerminal(tp.getStepBookkeep())) tp.setStepBookkeep("error");
        } else if (isTaxNationalTask(taskType)) {
            if (isNonTerminal(tp.getStepTaxNational())) tp.setStepTaxNational("error");
        } else if (isTaxIitTask(taskType)) {
            if (isNonTerminal(tp.getStepTaxIit())) tp.setStepTaxIit("error");
        } else if (isTaxAnnualTask(taskType)) {
            if (isNonTerminal(tp.getStepTaxAnnual())) tp.setStepTaxAnnual("error");
        }
    }

    /** 判断步骤是否为非终态（可被覆盖为 error） */
    private boolean isNonTerminal(String status) {
        return status == null
                || "pending".equals(status)
                || "running".equals(status)
                || "blocked".equals(status)
                || "waiting".equals(status);
    }

    private boolean isVerifyOrInitTask(String t) {
        if (t == null) return false;
        return "verify_tax".equals(t) || "init_import".equals(t) || "annual_business_report".equals(t)
                || isTaxAnnualTask(t);
    }

    private boolean isTaxAnnualTask(String t) {
        return t != null && "tax_annual".equals(t);
    }

    private boolean isDataTask(String t) {
        if (t == null) return false;
        return t.startsWith("download_invoice")
                || "check_invoice".equals(t)
                || "vat_declare_pre".equals(t)
                || "parse_bank".equals(t)
                || "copy_salary".equals(t);
    }

    private boolean isBookkeepTask(String t) {
        return t != null && t.startsWith("bookkeep_");
    }

    /** 判断任务类型是否为本地执行的内部任务（不需要 OC 处理，OC 应跳过） */
    private boolean isLocalTask(String taskType) {
        if (taskType == null) return false;
        return "parse_bank".equals(taskType)
                || "copy_salary".equals(taskType)
                || "init_import".equals(taskType)
                || taskType.startsWith("bookkeep_");
    }

    /** 判断是否为国税 OC 处理的任务（税务核验、发票下载、国税申报、缴费） */
    private boolean isTaxNationalTask(String t) {
        if (t == null) return false;
        return "verify_tax".equals(t)
                || t.startsWith("download_invoice")
                || "check_invoice".equals(t)
                || "vat_declare_pre".equals(t)
                || "vat_declare_post".equals(t)
                || "fetch_tax_items".equals(t)
                || "declare_tax_item".equals(t)
                || "query_payment_national".equals(t);
    }

    /**
     * 判断步骤状态是否为 OC 应等待的活跃状态（非终态）。
     * running/waiting 表示 OC 有任务正在执行或即将下发；
     * blocked 表示前置步骤正在处理中，完成后会自动解锁，OC 应等待；
     * done/error/warn/failed/skipped/na/pending/空 均视为 OC 不需继续等待。
     */
    private boolean isActiveOcStatus(String status) {
        return "running".equals(status) || "waiting".equals(status)
                || "blocked".equals(status);
    }

    private boolean isTaxIitTask(String t) {
        if (t == null) return false;
        return "iit_declare".equals(t) || t.startsWith("iit_declare_");
    }

    private boolean isNationalTask(String t) {
        if (t == null) return false;
        return "verify_tax".equals(t)
                || t.startsWith("download_invoice")
                || "vat_declare_pre".equals(t)
                || "vat_declare_post".equals(t)
                || "query_payment_national".equals(t)
                || "check_invoice".equals(t)
                || "tax_annual".equals(t);
    }

    /**
     * 发票子任务执行顺序：in → out → checked
     * 返回当前任务需要等待完成的前置子任务 subType，null 表示无需等待。
     */

    /** verify=done/warn 视为通过 */
    private boolean isVerifyPassed(String s) {
        return "done".equals(s) || "warn".equals(s);
    }

    /** init=done/skipped/na 视为通过 */
    private boolean isInitPassed(String s) {
        return "done".equals(s) || "skipped".equals(s) || "na".equals(s);
    }

    /** tax_national=done/warn 视为通过 */
    private boolean isTaxNationalPassed(String s) {
        return "done".equals(s) || "warn".equals(s);
    }

    /** tax_iit=done/warn/skipped/na 视为通过 */
    private boolean isTaxIitPassed(String s) {
        return "done".equals(s) || "warn".equals(s) || "skipped".equals(s) || "na".equals(s);
    }

    /** 检查指定账套对应月份的工资是否已复核 */
    private boolean isSalaryReviewed(Long accountSetId, String period) {
        if (accountSetId == null || StrUtil.isBlank(period)) return false;
        YearMonth ym = YearMonth.parse(period, PERIOD_FMT);
        List<Salary> salaries = salaryRepository.findByAccountSetsIdAndYearAndMonth(
                accountSetId, ym.getYear(), ym.getMonthValue());
        if (salaries.isEmpty()) return false;
        return salaries.stream().allMatch(s -> Boolean.TRUE.equals(s.getState()));
    }

    /**
     * OpenClaw 选定任务后，原子性地将其标记为 running。
     *
     * @param taskIds      要启动的任务 ID 列表
     * @param ocInstanceId 调用方 OC 实例编号
     * @return 实际更新的条数
     */
    @Transactional
    public int startOcTasks(List<Long> taskIds, String ocInstanceId) {
        String instanceId = StrUtil.isBlank(ocInstanceId) ? "unspecified" : ocInstanceId;
        // 记录真正进入 running 的时刻，作为 running 超时回收的计时基准（不含排队等待时间）
        int updated = ocTaskRepository.markAsRunning(taskIds, instanceId, LocalDateTime.now());
        log.info("OC[{}] 启动任务: 请求={}, 实际更新={}", instanceId, taskIds.size(), updated);
        return updated;
    }

    // ─────────────────────────────────────────────────────────────────────
    // 登录失败 — 批量取消该企业剩余 queued 任务
    // ─────────────────────────────────────────────────────────────────────

    /**
     * OC 登录失败或切换身份失败时调用，将该企业当期所有 queued 任务标记为 failed。
     *
     * @param accountSetId 账套ID
     * @param period       账期（可选，为空则取当前账期）
     * @param errorMsg     失败原因
     * @return 被取消的任务数量
     */
    @Transactional
    public Map<String, Object> failQueuedTasksOnLoginFailure(Long accountSetId, String period, String errorMsg) {
        String msg = StrUtil.isBlank(errorMsg) ? "税务局登录失败，任务自动取消" : errorMsg;

        // period 为空时取消该账套下所有账期的 queued 任务
        List<OcTask> allTasks = StrUtil.isBlank(period)
                ? ocTaskRepository.findByAccountSetId(accountSetId)
                : ocTaskRepository.findByAccountSetIdAndPeriod(accountSetId, period);
        List<OcTask> cancelled = new ArrayList<>();
        for (OcTask oc : allTasks) {
            // queued / running / blocked 均取消
            // 本地任务（bookkeep_* 等）不依赖税务局登录，不受影响；个税任务同理跳过
            if (!"queued".equals(oc.getStatus()) && !"running".equals(oc.getStatus()) && !"blocked".equals(oc.getStatus())) continue;
            if (isTaxIitTask(oc.getTaskType())) continue;
            if (isLocalTask(oc.getTaskType())) continue;

            oc.setStatus("failed");
            oc.setFinishedAt(LocalDateTime.now());
            oc.setResultJson(JSON.toJSONString(Map.of("error", msg)));
            ocTaskRepository.save(oc);
            cancelled.add(oc);
        }

        // 回写各步骤状态 — 按账期分组处理
        if (!cancelled.isEmpty()) {
            Map<String, List<OcTask>> byPeriod = cancelled.stream()
                    .collect(Collectors.groupingBy(OcTask::getPeriod));
            for (Map.Entry<String, List<OcTask>> entry : byPeriod.entrySet()) {
                TaskPeriod tp = taskPeriodRepository.findByAccountSetIdAndPeriod(accountSetId, entry.getKey()).orElse(null);
                if (tp != null) {
                    for (OcTask oc : entry.getValue()) {
                        if (isDataTask(oc.getTaskType())) {
                            String subType = dataOcTaskTypeToSubType(oc.getTaskType());
                            if (subType != null) {
                                updateSubTaskStatusWithResult(tp.getId(), subType, "error", msg);
                            }
                        }
                        setCascadeFailStep(tp, oc.getTaskType());
                    }
                    tp.setErrorMsg(msg);
                    // recalcStepData 根据子任务聚合，在 updateSubTaskStatusWithResult 之后调用确保子任务已更新
                    recalcStepData(tp);
                    taskPeriodRepository.save(tp);
                }
            }
        }

        log.info("登录/切换身份失败批量取消任务: accountSetId={}, period={}, cancelled={}", accountSetId, StrUtil.isBlank(period) ? "ALL" : period, cancelled.size());
        return Map.of("success", true, "cancelled", cancelled.size(), "msg",
                "已取消 " + cancelled.size() + " 个排队任务");
    }

    /**
     * data 类 OcTask 级联失败时，同步把 task_data_sub.status 标 error 并写入失败原因，
     * 防止 OcTask=failed 但 task_data_sub 仍卡在 running 的 UI 误导。
     */
    private void cascadeFailDataSub(Long taskPeriodId, String taskType, String errMsg) {
        if (taskPeriodId == null) return;
        String subType = ocTaskTypeToDataSubType(taskType);
        if (subType == null) return;
        taskDataSubRepository.findByTaskPeriodIdAndSubType(taskPeriodId, subType).ifPresent(sub -> {
            String s = sub.getStatus();
            if ("done".equals(s) || "skipped".equals(s) || "warn".equals(s)) return; // 不覆盖终态
            sub.setStatus("error");
            sub.setErrorMsg(errMsg);
            sub.setExecutedAt(LocalDateTime.now());
            taskDataSubRepository.save(sub);
        });
    }

    /** OcTask taskType → TaskDataSub subType 全量反向映射（含 vat_declare_pre / parse_bank / copy_salary） */
    private String ocTaskTypeToDataSubType(String taskType) {
        if (taskType == null) return null;
        return switch (taskType) {
            case "download_invoice_in"        -> "invoice_in";
            case "download_invoice_out"       -> "invoice_out";
            case "check_invoice"              -> "invoice_check";
            case "download_invoice_checked"   -> "invoice_checked";
            case "download_invoice_unchecked" -> "invoice_unchecked";
            case "vat_declare_pre"            -> "vat_declare_pre";
            case "parse_bank"                 -> "bank";
            case "copy_salary"                -> "salary";
            default -> null;
        };
    }

    /** OcTask taskType → TaskDataSub subType 反向映射（仅含发票相关，用于回调流程） */
    private String dataOcTaskTypeToSubType(String taskType) {
        return switch (taskType) {
            case "download_invoice_in"        -> "invoice_in";
            case "download_invoice_out"       -> "invoice_out";
            case "check_invoice"              -> "invoice_check";
            case "download_invoice_checked"   -> "invoice_checked";
            case "download_invoice_unchecked" -> "invoice_unchecked";
            default -> null;
        };
    }

    // ─────────────────────────────────────────────────────────────────────
    // OpenClaw 回调处理
    // ─────────────────────────────────────────────────────────────────────

    @Transactional
    public void handleVerifyTaxCallback(Map<String, Object> body) {
        Long taskId = toLong(body.get("task_id"));

        // 通过 oc_task 反查 task_period
        OcTask ocTask = ocTaskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("OcTask 不存在：" + taskId));

        // 复用共用解析（与 /openapi/v1/tax-verify/result 同口径，避免两路分叉）
        ParsedVerifyResult parsed = parseVerifyResult(body);

        // 解析完成后保存 oc_task
        ocTask.setStatus(parsed.success ? "done" : "failed");
        ocTask.setResultJson(JSON.toJSONString(body));
        ocTask.setFinishedAt(LocalDateTime.now());
        ocTaskRepository.save(ocTask);

        // 更新 task_period.step_verify
        TaskPeriod tp = taskPeriodRepository.findById(ocTask.getTaskPeriodId())
                .orElseThrow(() -> new IllegalArgumentException("TaskPeriod 不存在：" + ocTask.getTaskPeriodId()));
        applyVerifyStepStatus(tp, parsed);
        taskPeriodRepository.save(tp);

        // 写入核验结果明细（共用）
        saveVerifyResult(tp, taskId, body, parsed);
    }

    /**
     * FN-TAX-VERIFY-PROFILE-WIRE 改造 A —— 接收 BT 直推的核验结果（不走 OcTask 队列）。
     * <p>{@code POST /openapi/v1/tax-verify/result} 调用入口。复用 {@link #parseVerifyResult}/
     * {@link #saveVerifyResult} 与 {@code /oc/callback} 同口径写 {@code task_verify_result}，
     * 然后并入账套画像（{@code tax_verify} section，改造 B）并做核验资格 vs vatType 比对告警（改造 C，不自动改）。
     *
     * @return 响应体（含 taskPeriodId、派生字段、{@code vatTypeMismatch} 及文案）。
     */
    @Transactional
    public Map<String, Object> ingestTaxVerifyResult(Long accountSetsId, String period, Map<String, Object> body) {
        AccountSets as = accountSetsRepository.findById(accountSetsId)
                .orElseThrow(() -> new IllegalArgumentException("账套不存在：" + accountSetsId));

        // 复用共用解析
        ParsedVerifyResult parsed = parseVerifyResult(body);

        // FN-VERIFY-READ-MONTHLY（jianye 定调：核验改按月）：写侧按<b>原始月</b>存，不再季末归一，与读侧按月一致。
        //   ⚠ 仅"核验"改按月；增值税小规模申报/报税仍法定季报，declare 季度口径不在此改、别误伤。
        String effPeriod = period;   // 原始月（不归一）

        // 取/建账期 + 同口径更新 step_verify + 写 task_verify_result（ocTaskId=null，BT 不走 OC 队列）
        TaskPeriod tp = getOrCreateTaskPeriod(accountSetsId, effPeriod, as);
        applyVerifyStepStatus(tp, parsed);
        taskPeriodRepository.save(tp);
        saveVerifyResult(tp, null, body, parsed);

        // 改造 C：核验资格 vs AccountSets.vatType 比对（只告警，绝不自动改 vatType）
        Boolean verifiedGeneral = deriveVerifiedGeneral(parsed);
        String vatTypeMismatchMsg = vatTypeMismatchMsg(verifiedGeneral, as.getVatType());
        boolean vatTypeMismatch = vatTypeMismatchMsg != null;

        // 改造 B：并入账套画像（tax_verify section，source=system_suggested，upsert 覆盖）
        writeTaxVerifyProfile(accountSetsId, parsed, vatTypeMismatch, vatTypeMismatchMsg);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("accountSetsId", accountSetsId);
        resp.put("period", effPeriod); // 实际落库账期=原始月（按月，与 getTaxVerifyResultDto 读侧一致）
        resp.put("taskPeriodId", tp.getId());
        resp.put("status", parsed.status);
        resp.put("qualificationStatus", parsed.qualificationStatus);
        resp.put("taxpayerStatus", parsed.taxpayerStatus);
        resp.put("hasArrears", parsed.hasArrears);
        resp.put("hasShouldDeclare", parsed.hasShouldDeclare);
        resp.put("hasUnreported", parsed.hasUnreported);
        resp.put("vatTypeMismatch", vatTypeMismatch);
        if (vatTypeMismatch) {
            resp.put("vatTypeMismatchMsg", vatTypeMismatchMsg);
        }
        resp.put("profileWritten", true);
        return resp;
    }

    /**
     * 把核验资格映射成"一般/小规模"信号：数电「一般纳税人资格查询」
     * <b>有某行"资格类型"含"一般纳税人"→一般</b>；有 query_qualification 但无此类行（空/占位行）→小规模；
     * 未返回/未成功→null（判不出，不告警）。
     *
     * <p>FN-VERIFY-VATTYPE-CONTENT-JUDGE：原实现用 {@code !isEmpty()} 只数行数、非空即判一般，
     * 与"纳税人资格"弹窗(取 tax_items[0].资格类型 内容)同源不一致——非空但无资格类型的占位行会被判一般、
     * 而弹窗显示"—"，自相矛盾。改为<b>按内容判定</b>，与弹窗展示同源（label 空必判非一般）。
     */
    static Boolean deriveVerifiedGeneral(ParsedVerifyResult p) {
        if (!p.success) return null;
        Object qi = p.verifyItems.get("query_qualification");
        if (qi instanceof List) {
            // 有 query_qualification：按内容判——有行"资格类型"含"一般纳税人"才判一般，否则判小规模（不再 !isEmpty()）
            return rowsIndicateGeneral((List<?>) qi);
        }
        // 兜底：仅 qualificationStatus 含"一般纳税人"才判一般（与"纳税人资格"展示同源）
        if (StrUtil.isNotBlank(p.qualificationStatus)) {
            return p.qualificationStatus.contains("一般纳税人");
        }
        return null;
    }

    /**
     * 核验资格行内容判定：query_qualification 的 tax_items 里有某行"资格类型"含"一般纳税人"→true（一般纳税人）；
     * 否则 false（空/占位行/仅小规模→小规模）。与 parseVerifyResult 取 tax_items[0].资格类型 同源。
     */
    static boolean rowsIndicateGeneral(List<?> rows) {
        if (rows == null) return false;
        for (Object row : rows) {
            if (row instanceof Map) {
                Object qt = ((Map<?, ?>) row).get("资格类型");
                if (qt != null && qt.toString().contains("一般纳税人")) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 改造 C —— 核验资格信号 vs 账套 vatType 比对，返回告警文案；一致 / 判不出 → null（不告警）。
     * 只针对"真冲突维度"——一般纳税人 ↔ 小规模。<b>绝不据此自动改 vatType</b>（由会计把关）。
     */
    static String vatTypeMismatchMsg(Boolean verifiedGeneral, VatType accountVat) {
        if (verifiedGeneral == null || accountVat == null) return null;
        if (verifiedGeneral && accountVat == VatType.SMALL_SCALE) {
            return "税局资格=一般纳税人，账套配=小规模，请会计核对后在账套档案修改";
        }
        if (!verifiedGeneral && accountVat == VatType.COMMONLY) {
            return "税局资格=小规模，账套配=一般纳税人，请会计核对后在账套档案修改";
        }
        return null;
    }

    /**
     * 改造 B —— 把核验客观信息写进 {@code AccountSetProfile} 的 {@code tax_verify} section（已落库，复用 upsert 语义）。
     * 每次核验按 (accountSetsId + tax_verify + default) upsert 覆盖。
     */
    private void writeTaxVerifyProfile(Long accountSetsId, ParsedVerifyResult p,
                                       boolean vatTypeMismatch, String vatTypeMismatchMsg) {
        final String sectionType = "tax_verify";
        final String sectionKey = "default";

        Map<String, Object> rules = new LinkedHashMap<>();
        rules.put("qualificationType", p.qualificationStatus);
        rules.put("taxpayerStatus", p.taxpayerStatus);
        rules.put("hasArrears", p.hasArrears);
        rules.put("arrearsDetail", p.verifyItems.get("query_arrears"));
        rules.put("hasShouldDeclare", p.hasShouldDeclare);
        rules.put("shouldDeclareTaxTypes", p.verifyItems.get("query_should_declare"));
        rules.put("hasUnreported", p.hasUnreported);
        rules.put("unreportedDetail", p.verifyItems.get("query_current_unreported"));
        rules.put("vatTypeMismatch", vatTypeMismatch);
        if (vatTypeMismatch) {
            rules.put("vatTypeMismatchMsg", vatTypeMismatchMsg);
        }
        rules.put("verifiedAt", LocalDateTime.now().toString());

        // prompt：给 BT 画像页/会计的人类可读摘要
        StringBuilder prompt = new StringBuilder();
        if (StrUtil.isNotBlank(p.qualificationStatus)) prompt.append("资格：").append(p.qualificationStatus).append("；");
        if (StrUtil.isNotBlank(p.taxpayerStatus)) {
            String tsCn = switch (p.taxpayerStatus) {
                case "normal" -> "正常";
                case "cancelled" -> "注销";
                case "abnormal" -> "非正常";
                default -> p.taxpayerStatus;
            };
            prompt.append("纳税人状态：").append(tsCn).append("；");
        }
        if (Boolean.TRUE.equals(p.hasArrears)) prompt.append("存在欠税；");
        if (Boolean.TRUE.equals(p.hasUnreported)) prompt.append("存在未申报税种；");
        if (vatTypeMismatch) prompt.append("⚠ ").append(vatTypeMismatchMsg).append("；");

        LocalDateTime now = LocalDateTime.now();
        cn.gson.financial.kernel.model.entity.AccountSetProfile entity = accountSetProfileRepository
                .findByAccountSetsIdAndSectionTypeAndSectionKey(accountSetsId, sectionType, sectionKey)
                .orElseGet(() -> {
                    cn.gson.financial.kernel.model.entity.AccountSetProfile e =
                            new cn.gson.financial.kernel.model.entity.AccountSetProfile();
                    e.setAccountSetsId(accountSetsId);
                    e.setSectionType(sectionType);
                    e.setSectionKey(sectionKey);
                    e.setCreatedAt(now);
                    return e;
                });
        entity.setTitle("税务核验");
        entity.setRules(JSON.toJSONString(rules));
        entity.setPrompt(prompt.length() == 0 ? null : prompt.toString());
        if (entity.getPriority() == null) entity.setPriority(0);
        entity.setEnabled(Boolean.TRUE);
        entity.setSource("system_suggested");
        entity.setUpdatedAt(now);
        accountSetProfileRepository.save(entity);
    }

    /**
     * 核验结果解析产物（供 {@code /oc/callback} 与 {@code /openapi/v1/tax-verify/result} 共用）。
     */
    public static class ParsedVerifyResult {
        public boolean success;
        public String status;
        public String errorMsg;
        public Boolean hasArrears;
        public Boolean hasShouldDeclare;
        public Boolean hasUnreported;
        public String qualificationStatus;
        public String taxpayerStatus;
        public final Map<String, Object> verifyItems = new LinkedHashMap<>();
        public final Map<String, Object> screenshots = new LinkedHashMap<>();
        public final Map<String, Object> rawData     = new LinkedHashMap<>();
    }

    /**
     * 解析核验回调 body（{@code {status, error_msg, result:{query_*}}}），派生
     * qualificationStatus/taxpayerStatus/hasArrears/hasShouldDeclare/hasUnreported 及
     * verifyItems/screenshots/rawData。供 {@code /oc/callback} 与新 openapi 端点共用，避免两路分叉。
     * 解析失败（缺 result）抛出，由外层标记失败。
     */
    @SuppressWarnings("unchecked")
    public ParsedVerifyResult parseVerifyResult(Map<String, Object> body) {
        ParsedVerifyResult p = new ParsedVerifyResult();
        p.status = (String) body.get("status");
        p.errorMsg = (String) body.get("error_msg");
        p.success = "done".equals(p.status) || "success".equals(p.status);

        String[] queryKeys = {
            "query_qualification", "query_status", "query_should_declare",
            "query_current_unreported", "query_arrears"
        };

        if (p.success) {
            // query_* 字段在 body["result"] 里
            if (!(body.get("result") instanceof Map)) {
                throw new IllegalArgumentException("回调数据缺少 result 字段");
            }
            Map<String, Object> resultMap = (Map<String, Object>) body.get("result");

            for (String key : queryKeys) {
                Object subRaw = resultMap.get(key);
                if (!(subRaw instanceof Map)) continue;
                Map<String, Object> sub = (Map<String, Object>) subRaw;

                Object items = sub.get("tax_items");
                if (items != null) p.verifyItems.put(key, items);

                Object shots = sub.get("screenshots");
                if (shots != null) p.screenshots.put(key, shots);

                Object html = sub.get("table_html");
                if (html != null) p.rawData.put(key, html);
            }

            // qualification_status：取 query_qualification.tax_items[0].资格类型
            Object qualItems = p.verifyItems.get("query_qualification");
            if (qualItems instanceof List && !((List<?>) qualItems).isEmpty()) {
                Object first = ((List<?>) qualItems).get(0);
                if (first instanceof Map) {
                    Object qt = ((Map<?, ?>) first).get("资格类型");
                    if (qt instanceof String && StrUtil.isNotBlank((String) qt)) {
                        p.qualificationStatus = (String) qt;
                    }
                }
            }

            // taxpayer_status：取 query_status.tax_items[0].纳税人状态，转换为前端枚举值
            Object statusItems = p.verifyItems.get("query_status");
            if (statusItems instanceof List && !((List<?>) statusItems).isEmpty()) {
                Object first = ((List<?>) statusItems).get(0);
                if (first instanceof Map) {
                    Object ts = ((Map<?, ?>) first).get("纳税人状态");
                    if (ts instanceof String && StrUtil.isNotBlank((String) ts)) {
                        String tsStr = (String) ts;
                        p.taxpayerStatus = switch (tsStr) {
                            case "正常" -> "normal";
                            case "注销" -> "cancelled";
                            case "非正常" -> "abnormal";
                            default -> tsStr;
                        };
                    }
                }
            }

            // has_should_declare：query_should_declare.total_count > 0
            Object sdRaw = resultMap.get("query_should_declare");
            if (sdRaw instanceof Map) {
                Integer cnt = toInteger(((Map<?, ?>) sdRaw).get("total_count"));
                p.hasShouldDeclare = cnt != null && cnt > 0;
            }

            // has_unreported：tax_items 中存在标签状态不是"已申报"的条目
            Object urRaw = resultMap.get("query_current_unreported");
            if (urRaw instanceof Map) {
                Object urItems = ((Map<?, ?>) urRaw).get("tax_items");
                if (urItems instanceof List) {
                    p.hasUnreported = ((List<?>) urItems).stream()
                            .filter(it -> it instanceof Map)
                            .anyMatch(it -> {
                                Object labelStatus = ((Map<?, ?>) it).get("标签状态");
                                return labelStatus instanceof String && !"已申报".equals(labelStatus);
                            });
                } else {
                    p.hasUnreported = false;
                }
            }

            // has_arrears：tax_items 中存在"税（费）种"非空的条目
            Object arItems = p.verifyItems.get("query_arrears");
            if (arItems instanceof List) {
                p.hasArrears = ((List<?>) arItems).stream()
                        .filter(it -> it instanceof Map)
                        .anyMatch(it -> {
                            Object taxType = ((Map<?, ?>) it).get("税（费）种");
                            return taxType instanceof String && StrUtil.isNotBlank((String) taxType);
                        });
            } else {
                p.hasArrears = false;
            }
        }
        return p;
    }

    /**
     * 依据解析结果更新 {@code task_period.step_verify}（error/warn/done/waiting）。共用。
     */
    private void applyVerifyStepStatus(TaskPeriod tp, ParsedVerifyResult p) {
        if (!p.success) {
            tp.setStepVerify("error");
            tp.setErrorStep("step_verify");
            tp.setErrorMsg(StrUtil.isBlank(p.errorMsg) ? "核验税务失败" : p.errorMsg);
        } else if (Boolean.TRUE.equals(p.hasArrears) || Boolean.TRUE.equals(p.hasUnreported)) {
            tp.setStepVerify("warn");
            tp.setErrorStep("step_verify");
            StringBuilder warn = new StringBuilder();
            if (Boolean.TRUE.equals(p.hasArrears)) warn.append("存在欠税；");
            if (Boolean.TRUE.equals(p.hasUnreported)) warn.append("存在未申报税种；");
            tp.setErrorMsg(warn.toString());
        } else if (p.hasArrears == null || p.hasUnreported == null) {
            tp.setStepVerify("warn");
            tp.setErrorStep("step_verify");
            tp.setErrorMsg("核验结果数据不完整，请人工复核");
        } else {
            tp.setStepVerify("done");
            tp.setErrorStep(null);
            tp.setErrorMsg(null);
        }
        // 人工确认模式：执行完毕后改为 waiting，由人工确认后才继续
        if ("done".equals(tp.getStepVerify()) && isManualStep(tp.getAccountSetId(), "verify")) {
            tp.setStepVerify("waiting");
        }
    }

    /**
     * 写入/更新 {@code task_verify_result}（1:1 task_period）。{@code ocTaskId} 可为 null（BT 直推不走 OC 队列）。
     * 供 {@code /oc/callback} 与 {@code /openapi/v1/tax-verify/result} 共用。
     */
    public void saveVerifyResult(TaskPeriod tp, Long ocTaskId, Map<String, Object> body, ParsedVerifyResult p) {
        TaskVerifyResult vr = taskVerifyResultRepository.findByTaskPeriodId(tp.getId())
                .orElseGet(() -> {
                    TaskVerifyResult nvr = new TaskVerifyResult();
                    nvr.setTaskPeriodId(tp.getId());
                    return nvr;
                });
        vr.setOcTaskId(ocTaskId);
        vr.setStatus(p.status);
        vr.setErrorMsg(p.errorMsg);
        vr.setQualificationStatus(p.qualificationStatus);
        vr.setTaxpayerStatus(p.taxpayerStatus);
        vr.setHasArrears(p.hasArrears);
        vr.setHasShouldDeclare(p.hasShouldDeclare);
        vr.setHasUnreported(p.hasUnreported);
        if (!p.verifyItems.isEmpty()) {
            vr.setVerifyItems(JSON.toJSONString(p.verifyItems));
        }
        if (!p.screenshots.isEmpty()) {
            vr.setScreenshots(JSON.toJSONString(p.screenshots));
        }
        if (!p.rawData.isEmpty()) {
            vr.setRawData(JSON.toJSONString(p.rawData));
        }
        // verify_time 取 OC 的 execute_time
        String executeTimeStr = (String) body.get("execute_time");
        if (StrUtil.isNotBlank(executeTimeStr)) {
            try {
                vr.setVerifyTime(LocalDateTime.parse(executeTimeStr,
                        java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME));
            } catch (Exception e) {
                log.warn("execute_time 解析失败: {}", executeTimeStr);
                vr.setVerifyTime(LocalDateTime.now());
            }
        } else {
            vr.setVerifyTime(LocalDateTime.now());
        }
        vr.setExecutedAt(LocalDateTime.now());
        taskVerifyResultRepository.save(vr);
    }

    private Integer toInteger(Object val) {
        if (val == null) return null;
        try {
            return Integer.valueOf(val.toString());
        } catch (Exception e) {
            return null;
        }
    }

    @Transactional
    public void handleDownloadInvoiceCallback(Map<String, Object> body) {
        Long taskId = toLong(body.get("task_id"));
        Long accountSetId = toLong(body.get("account_set_id"));
        String period = (String) body.get("period");
        String status = (String) body.get("status");
        // 根据 task_type 确定对应的 task_data_sub.sub_type
        String taskType = (String) body.get("task_type");
        String subType = switch (StrUtil.nullToDefault(taskType, "download_invoice")) {
            case "download_invoice_in"        -> "invoice_in";
            case "download_invoice_out"       -> "invoice_out";
            case "download_invoice_checked"   -> "invoice_checked";
            case "download_invoice_unchecked" -> "invoice_unchecked";
            default                           -> "invoice"; // 兼容旧版
        };

        // 更新 oc_task 状态为 done/failed
        updateOcTask(taskId, status, body);

        OcTask ocTask = ocTaskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("OcTask 不存在：" + taskId));
        TaskPeriod tp = taskPeriodRepository.findById(ocTask.getTaskPeriodId())
                .orElseThrow(() -> new IllegalArgumentException("TaskPeriod 不存在：" + ocTask.getTaskPeriodId()));
        String subStatus = "done".equals(status) ? "done" : "error";

        // 提取 result 子对象中的统计数据
        @SuppressWarnings("unchecked")
        Map<String, Object> result = body.get("result") instanceof Map
                ? (Map<String, Object>) body.get("result") : Map.of();

        TaskDataSub sub = taskDataSubRepository
                .findByTaskPeriodIdAndSubType(tp.getId(), subType)
                .orElseGet(() -> {
                    TaskDataSub s = new TaskDataSub();
                    s.setTaskPeriodId(tp.getId());
                    s.setSubType(subType);
                    return s;
                });
        sub.setStatus(subStatus);
        sub.setExecutedAt(LocalDateTime.now());

        if ("done".equals(subStatus)) {
            // 只存有意义的统计摘要，不存本机路径（download_file）
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("invoice_count",  result.get("invoice_count"));
            summary.put("total_amount",   result.get("total_amount"));
            summary.put("total_tax",      result.get("total_tax"));
            summary.put("date_start",     result.get("date_start"));
            summary.put("date_end",       result.get("date_end"));
            summary.put("invoice_type",   result.get("invoice_type"));
            sub.setResultJson(JSON.toJSONString(summary));
            sub.setErrorMsg(null);
        } else {
            String errMsg = (String) body.get("error_msg");
            if (StrUtil.isBlank(errMsg)) errMsg = (String) result.get("error");
            if (StrUtil.isBlank(errMsg)) errMsg = "下载发票失败";
            sub.setErrorMsg(errMsg);
            sub.setResultJson(JSON.toJSONString(Map.of("error", errMsg)));
        }

        // 截图：优先取顶层 screenshot_urls，其次取 result.screenshots
        Object screenshotUrls = body.get("screenshot_urls");
        if (!(screenshotUrls instanceof List) || ((List<?>) screenshotUrls).isEmpty()) {
            screenshotUrls = result.get("screenshots");
        }
        if (screenshotUrls instanceof List) {
            sub.setScreenshotUrls(JSON.toJSONString(screenshotUrls));
        } else if (body.get("screenshot_url") != null) {
            sub.setScreenshotUrls(JSON.toJSONString(List.of(body.get("screenshot_url"))));
        }
        // 人工确认模式：执行完毕后改为 waiting
        if ("done".equals(sub.getStatus()) && isManualStep(tp.getAccountSetId(), "data." + subType)) {
            sub.setStatus("waiting");
        }
        taskDataSubRepository.save(sub);

        // 重新聚合 step_data
        recalcStepData(tp);
        taskPeriodRepository.save(tp);

        // 无论 OC 子任务成功还是失败，都尝试触发本地任务（bank/salary）。
        // autoExecutePendingLocalDataSubs 内部会判断所有 OC 子任务是否均已终态，再决定是否真正执行。
        // 这样即使某个 OC 子任务（如 vat_declare_pre / invoice_unchecked）失败，
        // bank/salary 依然可以独立执行，不会因为 stepData==error 而被永久阻断。
        if (isStepPassed(tp.getStepData())) {
            publishStepCompleted(tp.getAccountSetId(), tp.getPeriod(), "data");
        } else {
            publishStepCompleted(tp.getAccountSetId(), tp.getPeriod(), "data_local");
        }
    }

    /**
     * 做账前国税预申报回调（vat_declare_pre）。
     * 属于获取数据阶段子任务，解析各税种代缴税费存入 task_vat_pre 表，做账时读取生成凭证。
     */
    @Transactional
    @SuppressWarnings("unchecked")
    public void handleVatDeclarePreCallback(Map<String, Object> body) {
        Long taskId = toLong(body.get("task_id"));
        Long accountSetId = toLong(body.get("account_set_id"));
        String period = (String) body.get("period");
        String status = (String) body.get("status");

        updateOcTask(taskId, "done".equals(status) ? "done" : "failed", body);

        OcTask ocTask = ocTaskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("OcTask 不存在：" + taskId));
        TaskPeriod tp = taskPeriodRepository.findById(ocTask.getTaskPeriodId())
                .orElseThrow(() -> new IllegalArgumentException("TaskPeriod 不存在：" + ocTask.getTaskPeriodId()));

        String subStatus = "done".equals(status) ? "done" : "error";

        Map<String, Object> resultData = body.get("result") instanceof Map
                ? (Map<String, Object>) body.get("result") : Map.of();

        if ("done".equals(subStatus)) {
            // 1. 保存 results → task_tax_item（申报详情 + 截图，与 vat_declare_post 共用展示逻辑）
            try {
                self.saveTaxItemsFromCallback(tp.getId(), taskId, resultData);
            } catch (Exception e) {
                log.warn("写入 task_tax_item 失败（不影响主流程）: taskPeriodId={}", tp.getId(), e);
            }

            // 2. 保存 tax_items → task_vat_pre（代缴税费，做账时生成凭证）
            Object taxItemsObj = resultData.get("tax_items");
            if (taxItemsObj == null) {
                log.warn("做账前申报回调未包含 tax_items 字段，本期无税款数据写入: accountSetId={}, period={}", accountSetId, period);
            } else if (taxItemsObj instanceof List) {
                List<Map<String, Object>> taxItems = (List<Map<String, Object>>) taxItemsObj;
                if (taxItems.isEmpty()) {
                    log.info("做账前申报 tax_items 为空列表，本期无应缴税款: accountSetId={}, period={}", accountSetId, period);
                }
                int savedCount = 0;
                for (Map<String, Object> item : taxItems) {
                    String seqNo = item.get("序号") != null ? item.get("序号").toString() : null;
                    String taxName = (String) item.get("税（费）种");
                    if (StrUtil.isBlank(seqNo) || StrUtil.isBlank(taxName)) continue;

                    cn.gson.financial.kernel.model.entity.TaskVatPre vatPre = taskVatPreRepository
                            .findByTaskPeriodIdAndSeqNo(tp.getId(), seqNo)
                            .orElseGet(() -> {
                                cn.gson.financial.kernel.model.entity.TaskVatPre v = new cn.gson.financial.kernel.model.entity.TaskVatPre();
                                v.setTaskPeriodId(tp.getId());
                                v.setSeqNo(seqNo);
                                return v;
                            });

                    vatPre.setTaxName(taxName);
                    vatPre.setTaxItem((String) item.get("税（品）目"));
                    vatPre.setTaxPeriodStart(parseLocalDate(item.get("税款所属期起")));
                    vatPre.setTaxPeriodEnd(parseLocalDate(item.get("税款所属期止")));
                    vatPre.setDeclareDate(parseLocalDate(item.get("申报日期")));
                    vatPre.setPaymentDeadline(parseLocalDate(item.get("缴款期限")));
                    vatPre.setTaxPayable(parseBigDecimal(item.get("应缴税额")));
                    vatPre.setTaxPaid(parseBigDecimal(item.get("实缴税额")));
                    vatPre.setSurcharge(parseBigDecimal(item.get("滞纳金、利息、违约金、加罚款")));
                    vatPre.setOcTaskId(taskId);
                    vatPre.setStatus("done");
                    vatPre.setResultJson(JSON.toJSONString(item));
                    vatPre.setExecutedAt(LocalDateTime.now());
                    taskVatPreRepository.save(vatPre);
                    savedCount++;
                }
                if (savedCount == 0 && !taxItems.isEmpty()) {
                    log.info("做账前申报 tax_items 均为空字段占位行，本期无应缴税款: accountSetId={}, period={}", accountSetId, period);
                } else {
                    log.info("做账前申报税费已保存: accountSetId={}, period={}, 保存{}条（原始{}条）",
                            accountSetId, period, savedCount, taxItems.size());
                }
            }
        }

        // 更新 task_data_sub 状态
        TaskDataSub sub = taskDataSubRepository
                .findByTaskPeriodIdAndSubType(tp.getId(), "vat_declare_pre")
                .orElseGet(() -> {
                    TaskDataSub s = new TaskDataSub();
                    s.setTaskPeriodId(tp.getId());
                    s.setSubType("vat_declare_pre");
                    return s;
                });
        sub.setStatus(subStatus);
        sub.setExecutedAt(LocalDateTime.now());
        sub.setOcTaskId(taskId);
        if ("error".equals(subStatus)) {
            sub.setErrorMsg(StrUtil.nullToDefault((String) body.get("error_msg"), "做账前申报失败"));
        } else {
            sub.setErrorMsg(null);
        }
        // 人工确认模式
        if ("done".equals(sub.getStatus()) && isManualStep(tp.getAccountSetId(), "data.vat_declare_pre")) {
            sub.setStatus("waiting");
        }
        taskDataSubRepository.save(sub);

        // 重新聚合 step_data
        recalcStepData(tp);
        taskPeriodRepository.save(tp);

        // 无论 OC 子任务成功还是失败，都尝试触发本地任务（bank/salary）
        if (isStepPassed(tp.getStepData())) {
            publishStepCompleted(tp.getAccountSetId(), tp.getPeriod(), "data");
        } else {
            publishStepCompleted(tp.getAccountSetId(), tp.getPeriod(), "data_local");
        }
    }

    /**
     * 将税局"税款所属期"格式转为系统标准账期格式。
     * 示例："2026年02月" → "2026-02"
     */
    private String parseTaxPeriod(String taxPeriod) {
        if (StrUtil.isBlank(taxPeriod)) return null;
        return taxPeriod.replace("年", "-").replace("月", "").trim();
    }

    @Transactional
    @SuppressWarnings("unchecked")
    public void handleVatDeclareCallback(Map<String, Object> body) {
        Long taskId = toLong(body.get("task_id"));
        String status = (String) body.get("status");

        // OC 回调将实际数据包在 "result" 子对象中，兼容直接在顶层的旧格式
        Map<String, Object> resultData = (body.get("result") instanceof Map)
                ? (Map<String, Object>) body.get("result") : body;
        // status=done → 全部成功；status=failed → error（无论部分还是全部失败）
        Integer successCount = toInteger(resultData.get("success_count"));
        boolean totalFailed  = "failed".equals(status) && (successCount == null || successCount == 0);

        updateOcTask(taskId, totalFailed ? "failed" : "done", body);

        OcTask ocTask = ocTaskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("OcTask 不存在：" + taskId));
        TaskPeriod tp = taskPeriodRepository.findById(ocTask.getTaskPeriodId())
                .orElseThrow(() -> new IllegalArgumentException("TaskPeriod 不存在：" + ocTask.getTaskPeriodId()));

        // ── 解析各税种申报结果，写入 task_tax_item ──
        try {
            self.saveTaxItemsFromCallback(tp.getId(), taskId, resultData);
        } catch (Exception e) {
            log.warn("写入 task_tax_item 失败（不影响主流程）: taskPeriodId={}", tp.getId(), e);
        }

        if (totalFailed) {
            tp.setStepTaxNational("error");
            tp.setErrorStep("step_tax_national");
            tp.setErrorMsg(StrUtil.nullToDefault((String) body.get("error_msg"), "国税申报失败"));
            taskPeriodRepository.save(tp);
            return;
        }

        // status=done → 全部成功；status=failed → error（含部分失败）
        if ("failed".equals(status)) {
            tp.setStepTaxNational("error");
            tp.setErrorStep("step_tax_national");
            tp.setErrorMsg("部分税种申报失败，请查看详情");
        } else {
            tp.setStepTaxNational("done");
            tp.setErrorStep(null);
            tp.setErrorMsg(null);
        }

        // 人工确认模式：done 或 warn 均需人工确认后再推进
        boolean isManual = isManualStep(tp.getAccountSetId(), "vat");
        log.info("vat回调处理: accountSetId={}, stepTaxNational={}, isManualStep={}", tp.getAccountSetId(), tp.getStepTaxNational(), isManual);
        if (isStepPassed(tp.getStepTaxNational()) && isManual) {
            tp.setStepTaxNational("waiting");
        }
        taskPeriodRepository.save(tp);
        // vat 处理完成后解锁 blocked 的 payment（不管成功还是失败，缴费都需要查询）
        String vatStatus = tp.getStepTaxNational();
        log.info("vat回调最终状态: accountSetId={}, vatStatus={}, isStepPassed={}", tp.getAccountSetId(), vatStatus, isStepPassed(vatStatus));
        if (isStepPassed(vatStatus) || "error".equals(vatStatus) || "failed".equals(vatStatus)) {
            log.info("vat回调: 发布 publishStepCompleted('vat')");
            publishStepCompleted(tp.getAccountSetId(), tp.getPeriod(), "vat");
        } else {
            log.warn("vat回调: 未触发 publishStepCompleted, vatStatus={}", vatStatus);
        }
    }

    /**
     * 从 vat_declare 回调数据中解析各税种申报结果，写入 task_tax_item 表。
     * 兼容两种 OC 回调格式：
     * - 新格式：result.results = { "增值税...": { status:"done", screenshots:[...] }, ... }
     * - 旧格式：result.results = { "增值税...": { success:true, img_urls:[...] }, ... }
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveTaxItemsFromCallback(Long taskPeriodId, Long ocTaskId, Map<String, Object> resultData) {
            Object resultsObj = resultData.get("results");
            if (!(resultsObj instanceof Map)) return;
            @SuppressWarnings("unchecked")
            Map<String, Object> resultsMap = (Map<String, Object>) resultsObj;
            String taxPeriod = (String) resultData.get("tax_period");

            int sortOrder = 0;
            for (Map.Entry<String, Object> entry : resultsMap.entrySet()) {
                if (!(entry.getValue() instanceof Map)) continue;
                @SuppressWarnings("unchecked")
                Map<String, Object> item = (Map<String, Object>) entry.getValue();
                String taxName = entry.getKey();

                // upsert：按 taskPeriodId + taxName 查找
                TaskTaxItem taxItem = taskTaxItemRepository
                        .findByTaskPeriodIdAndTaxName(taskPeriodId, taxName)
                        .orElseGet(() -> {
                            TaskTaxItem ti = new TaskTaxItem();
                            ti.setTaskPeriodId(taskPeriodId);
                            ti.setTaxName(taxName);
                            return ti;
                        });

                // 兼容 status:"done" (新) 和 success:true (旧)
                Object statusVal = item.get("status");
                boolean ok = "done".equals(statusVal) || Boolean.TRUE.equals(item.get("success"));
                taxItem.setDeclareStatus(ok ? "done" : "failed");
                taxItem.setDeclarePeriod(taxPeriod);
                taxItem.setOcTaskId(ocTaskId);
                taxItem.setExecutedAt(LocalDateTime.now());
                taxItem.setSortOrder(sortOrder++);
                taxItem.setDeclareResult(JSON.toJSONString(item));

                if (!ok) {
                    Object errMsg = item.get("error") != null ? item.get("error") : item.get("failure_reason");
                    taxItem.setErrorMsg(errMsg != null ? errMsg.toString() : "申报失败");
                } else {
                    taxItem.setErrorMsg(null);
                }

                // 截图：screenshots（新）或 img_urls（旧）
                Object screenshotsVal = item.get("screenshots") instanceof List ? item.get("screenshots") : item.get("img_urls");
                if (screenshotsVal instanceof List && !((List<?>) screenshotsVal).isEmpty()) {
                    List<String> urls = ((List<?>) screenshotsVal).stream()
                            .map(Object::toString)
                            .filter(s -> !s.isBlank())
                            .toList();
                    if (!urls.isEmpty()) taxItem.setScreenshotUrls(JSON.toJSONString(urls));
                }

                taskTaxItemRepository.save(taxItem);
            }
    }

    @Transactional
    public void handleIitDeclareCallback(Map<String, Object> body) {
        Long taskId = toLong(body.get("task_id"));
        Long accountSetId = toLong(body.get("account_set_id"));
        String period = (String) body.get("period");
        String status = (String) body.get("status");
        // iit_type: salary（工资薪金）/ business（经营所得），默认 salary 向下兼容
        String iitType = body.get("iit_type") != null ? (String) body.get("iit_type") : "salary";

        updateOcTask(taskId, status, body);

        OcTask ocTask = ocTaskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("OcTask 不存在：" + taskId));
        TaskPeriod tp = taskPeriodRepository.findById(ocTask.getTaskPeriodId())
                .orElseThrow(() -> new IllegalArgumentException("TaskPeriod 不存在：" + ocTask.getTaskPeriodId()));

        // 写入 task_iit_item 明细
        try {
            TaskIitItem iitItem = taskIitItemRepository
                    .findByTaskPeriodIdAndIitType(tp.getId(), iitType)
                    .orElseGet(() -> {
                        TaskIitItem ni = new TaskIitItem();
                        ni.setTaskPeriodId(tp.getId());
                        ni.setIitType(iitType);
                        return ni;
                    });
            iitItem.setOcTaskId(taskId);
            String itemStatus = "done".equals(status) ? "done"
                    : "partial_failed".equals(status) ? "done"
                    : "error";
            iitItem.setDeclareStatus(itemStatus);
            iitItem.setDeclareResult(JSON.toJSONString(body));
            iitItem.setErrorMsg((String) body.get("error_msg"));
            iitItem.setExecutedAt(LocalDateTime.now());
            // 截图：支持数组和单URL
            Object urls = body.get("screenshot_urls");
            if (urls instanceof List) {
                iitItem.setScreenshotUrls(JSON.toJSONString(urls));
            } else if (body.get("screenshot_url") != null) {
                iitItem.setScreenshotUrls(JSON.toJSONString(List.of(body.get("screenshot_url"))));
            }
            self.saveIitItemBestEffort(iitItem);
        } catch (Exception e) {
            log.warn("保存个税申报明细失败（不影响主流程）: accountSetId={}, iitType={}", accountSetId, iitType, e);
        }

        // 聚合 step_tax_iit：取所有 IIT 项中的最差状态
        String aggregated = recalcStepIit(tp.getId());
        if (aggregated != null) {
            tp.setStepTaxIit(aggregated);
            if ("error".equals(aggregated)) {
                tp.setErrorStep("step_tax_iit");
                tp.setErrorMsg((String) body.getOrDefault("error_msg", "个税申报失败"));
            }
        } else {
            // 无 IIT 记录时，直接用回调状态
            if ("done".equals(status)) {
                tp.setStepTaxIit("done");
            } else if ("partial_failed".equals(status)) {
                tp.setStepTaxIit("warn");
            } else {
                tp.setStepTaxIit("error");
                tp.setErrorStep("step_tax_iit");
                tp.setErrorMsg((String) body.getOrDefault("error_msg", "个税申报失败"));
            }
        }
        // 人工确认模式：执行完毕后改为 waiting
        if ("done".equals(tp.getStepTaxIit()) && isManualStep(tp.getAccountSetId(), "iit")) {
            tp.setStepTaxIit("waiting");
        }
        taskPeriodRepository.save(tp);
        // iit 真正完成后自动解锁 blocked 的 payment（事务提交后异步触发）
        if (isStepPassed(tp.getStepTaxIit())) {
            publishStepCompleted(tp.getAccountSetId(), tp.getPeriod(), "iit");
        }
    }

    /**
     * 按所有 IIT 子项聚合 step_tax_iit 状态。
     * 优先级：error > running > pending > warn > done > skipped。
     * 全部完成才返回 done；存在 error 则 error；存在 running/pending 则对应。
     */
    private String recalcStepIit(Long taskPeriodId) {
        List<TaskIitItem> items = taskIitItemRepository.findByTaskPeriodId(taskPeriodId);
        if (CollUtil.isEmpty(items)) return null;
        List<String> priority = List.of("error", "running", "pending", "warn", "done", "skipped");
        return items.stream()
                .map(TaskIitItem::getDeclareStatus)
                .min(Comparator.comparingInt(s -> {
                    int idx = priority.indexOf(s);
                    return idx < 0 ? priority.size() : idx;
                }))
                .orElse(null);
    }

    /**
     * payment 步骤：仅下发国税缴费查询（query_payment_national）。
     */
    private Map<String, Object> dispatchPaymentStep(TaskPeriod tp, AccountSets as, String period, String resolvedOcId) {
        Long accountSetId = tp.getAccountSetId();

        // 下发前清除旧的缴费明细，避免重新下发后弹窗显示历史数据
        taskPaymentItemRepository.deleteByTaskPeriodId(tp.getId());

        // 复用已有的 blocked oc_task，或创建新的
        OcTask nationalTask = unlockBlockedOcTask(tp.getId(), "query_payment_national");
        if (nationalTask != null) {
            Map<String, Object> nationalPayload = buildPayloadMap(as, period, resolvedOcId, "query_payment_national");
            nationalTask.setPayloadJson(JSON.toJSONString(nationalPayload));
            nationalTask.setOcInstanceId(resolvedOcId);
            ocTaskRepository.save(nationalTask);
        } else {
            nationalTask = new OcTask();
            nationalTask.setAccountSetId(accountSetId);
            nationalTask.setPeriod(period);
            nationalTask.setTaskType("query_payment_national");
            nationalTask.setStatus("queued");
            nationalTask.setTaskPeriodId(tp.getId());
            nationalTask.setOcInstanceId(resolvedOcId);
            Map<String, Object> nationalPayload = buildPayloadMap(as, period, resolvedOcId, "query_payment_national");
            nationalTask.setPayloadJson(JSON.toJSONString(nationalPayload));
            ocTaskRepository.save(nationalTask);
        }

        tp.setStepPayment("running");
        taskPeriodRepository.save(tp);
        log.info("下发缴费查询任务: accountSetId={}, period={}", accountSetId, period);
        return Map.of("success", true, "msg", "国税缴费查询任务已下发");
    }

    /**
     * 国税缴费查询回调（query_payment_national）。
     * 回写 task_payment_item，前缀 taxCode=NATIONAL_xxx 区分来源。
     * 两条均完成后聚合 stepPayment。
     */
    @Transactional
    @SuppressWarnings("unchecked")
    public void handleQueryPaymentNationalCallback(Map<String, Object> body) {
        Long taskId = toLong(body.get("task_id"));
        Long accountSetId = toLong(body.get("account_set_id"));
        String period = (String) body.get("period");
        String status = (String) body.get("status");

        updateOcTask(taskId, status, body);
        OcTask ocTask = ocTaskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("OcTask 不存在：" + taskId));
        TaskPeriod tp = taskPeriodRepository.findById(ocTask.getTaskPeriodId())
                .orElseThrow(() -> new IllegalArgumentException("TaskPeriod 不存在：" + ocTask.getTaskPeriodId()));

        // OC 回调数据可能包裹在 result 子对象中，需解包
        Map<String, Object> resultData = (body.get("result") instanceof Map)
                ? (Map<String, Object>) body.get("result") : body;
        savePaymentItems(tp.getId(), taskId, resultData, "NATIONAL_");

        recalcAndSavePaymentStep(tp);
        taskPeriodRepository.save(tp);
    }

    /**
     * 个税缴费查询回调（query_payment_iit）。
     * 回写 task_payment_item，前缀 taxCode=IIT_xxx 区分来源。
     * 两条均完成后聚合 stepPayment。
     */
    @Transactional
    @SuppressWarnings("unchecked")
    public void handleQueryPaymentIitCallback(Map<String, Object> body) {
        Long taskId = toLong(body.get("task_id"));
        Long accountSetId = toLong(body.get("account_set_id"));
        String period = (String) body.get("period");
        String status = (String) body.get("status");

        updateOcTask(taskId, status, body);
        OcTask ocTask = ocTaskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("OcTask 不存在：" + taskId));
        TaskPeriod tp = taskPeriodRepository.findById(ocTask.getTaskPeriodId())
                .orElseThrow(() -> new IllegalArgumentException("TaskPeriod 不存在：" + ocTask.getTaskPeriodId()));

        Map<String, Object> resultData = (body.get("result") instanceof Map)
                ? (Map<String, Object>) body.get("result") : body;
        savePaymentItems(tp.getId(), taskId, resultData, "IIT_");

        recalcAndSavePaymentStep(tp);
        taskPeriodRepository.save(tp);
    }

    /**
     * 旧版 query_payment 回调（向后兼容，单任务覆盖整体结果）。
     */
    @Transactional
    @SuppressWarnings("unchecked")
    public void handleQueryPaymentCallback(Map<String, Object> body) {
        Long taskId = toLong(body.get("task_id"));
        Long accountSetId = toLong(body.get("account_set_id"));
        String period = (String) body.get("period");
        String status = (String) body.get("status");

        updateOcTask(taskId, status, body);
        OcTask ocTask = ocTaskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("OcTask 不存在：" + taskId));
        TaskPeriod tp = taskPeriodRepository.findById(ocTask.getTaskPeriodId())
                .orElseThrow(() -> new IllegalArgumentException("TaskPeriod 不存在：" + ocTask.getTaskPeriodId()));

        Map<String, Object> resultData = (body.get("result") instanceof Map)
                ? (Map<String, Object>) body.get("result") : body;
        savePaymentItems(tp.getId(), taskId, resultData, "");

        tp.setStepPayment("done".equals(status) ? "done" : "error");
        if ("error".equals(tp.getStepPayment())) {
            tp.setErrorStep("step_payment");
            tp.setErrorMsg((String) body.getOrDefault("error_msg", "缴费查询失败"));
        }
        // 人工确认模式：执行完毕后改为 waiting
        if ("done".equals(tp.getStepPayment()) && isManualStep(tp.getAccountSetId(), "payment")) {
            tp.setStepPayment("waiting");
        }
        taskPeriodRepository.save(tp);
    }

    /**
     * 保存缴费查询明细到 task_payment_item，taxCode 加指定前缀以区分国税/个税来源。
     * 优先解析新格式 tax_items（中文 key），向后兼容旧格式 payment_items（英文 key）。
     */
    private void savePaymentItems(Long taskPeriodId, Long ocTaskId, Map<String, Object> data, String taxCodePrefix) {
        // 重新执行时先删除同前缀的旧数据，避免重复累积
        if (StrUtil.isNotBlank(taxCodePrefix)) {
            taskPaymentItemRepository.deleteByTaskPeriodIdAndTaxCodeStartingWith(taskPeriodId, taxCodePrefix);
        } else {
            taskPaymentItemRepository.deleteByTaskPeriodId(taskPeriodId);
        }
        // 优先新格式 tax_items，其次旧格式 payment_items
        Object itemsObj = data.get("tax_items");
        boolean isNewFormat = itemsObj instanceof List;
        if (!isNewFormat) {
            itemsObj = data.get("payment_items");
        }
        if (itemsObj instanceof List) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) itemsObj;
            int idx = 0;
            for (Map<String, Object> item : items) {
                idx++;
                try {
                    TaskPaymentItem pi = new TaskPaymentItem();
                    pi.setTaskPeriodId(taskPeriodId);
                    pi.setOcTaskId(ocTaskId);
                    if (isNewFormat) {
                        // 新格式：中文 key
                        String taxName = (String) item.get("税（费）种");
                        if (StrUtil.isBlank(taxName)) taxName = "缴费查询结果";
                        String taxCode = "ITEM_" + idx;
                        pi.setTaxCode(taxCodePrefix + taxCode);
                        pi.setTaxName(taxName);
                        pi.setTaxAmount(parseAmountStr(item.get("应缴税额")));
                        pi.setPaidAmount(parseAmountStr(item.get("实缴税额")));
                        // 计算未缴金额
                        if (pi.getTaxAmount() != null && pi.getPaidAmount() != null) {
                            pi.setUnpaidAmount(pi.getTaxAmount().subtract(pi.getPaidAmount()));
                        }
                        // 根据金额推导缴款状态
                        if (pi.getTaxAmount() != null && pi.getPaidAmount() != null) {
                            int cmp = pi.getPaidAmount().compareTo(pi.getTaxAmount());
                            pi.setPayStatus(cmp >= 0 ? "paid" : pi.getPaidAmount().signum() == 0 ? "unpaid" : "partial");
                        }
                        if (item.get("缴款期限") != null) {
                            try {
                                pi.setPayDeadline(java.time.LocalDate.parse(item.get("缴款期限").toString()));
                            } catch (Exception ignored) {}
                        }
                    } else {
                        // 旧格式：英文 key
                        String taxCode = (String) item.get("tax_code");
                        if (StrUtil.isBlank(taxCode)) taxCode = "ITEM_" + idx;
                        String taxName = (String) item.get("tax_name");
                        if (StrUtil.isBlank(taxName)) taxName = "缴费查询结果";
                        pi.setTaxCode(taxCodePrefix + taxCode);
                        pi.setTaxName(taxName);
                        if (item.get("tax_amount") != null) {
                            pi.setTaxAmount(new java.math.BigDecimal(item.get("tax_amount").toString()));
                        }
                        if (item.get("paid_amount") != null) {
                            pi.setPaidAmount(new java.math.BigDecimal(item.get("paid_amount").toString()));
                        }
                        if (item.get("unpaid_amount") != null) {
                            pi.setUnpaidAmount(new java.math.BigDecimal(item.get("unpaid_amount").toString()));
                        }
                        pi.setPayStatus((String) item.get("pay_status"));
                        if (item.get("pay_deadline") != null) {
                            try {
                                pi.setPayDeadline(java.time.LocalDate.parse(item.get("pay_deadline").toString()));
                            } catch (Exception ignored) {}
                        }
                        Object urls = item.get("screenshot_urls");
                        if (urls instanceof List) {
                            pi.setScreenshotUrls(JSON.toJSONString(urls));
                        } else if (item.get("screenshot_url") != null) {
                            pi.setScreenshotUrls(JSON.toJSONString(List.of(item.get("screenshot_url"))));
                        }
                    }
                    self.savePaymentItemBestEffort(pi);
                } catch (Exception e) {
                    log.warn("保存缴费查询明细失败: idx={}", idx, e);
                }
            }
        } else {
            // tax_items / payment_items 都不存在时，用整体截图兜底
            Object urls = data.get("screenshots");
            if (!(urls instanceof List)) urls = data.get("screenshot_urls");
            if (urls instanceof List && !((List<?>) urls).isEmpty()) {
                try {
                    TaskPaymentItem pi = new TaskPaymentItem();
                    pi.setTaskPeriodId(taskPeriodId);
                    pi.setOcTaskId(ocTaskId);
                    pi.setTaxCode(taxCodePrefix + "ALL");
                    pi.setTaxName("缴费查询结果");
                    pi.setPayStatus("paid");
                    pi.setScreenshotUrls(JSON.toJSONString(urls));
                    self.savePaymentItemBestEffort(pi);
                } catch (Exception e) {
                    log.warn("保存缴费查询截图失败", e);
                }
            }
        }
    }

    /** 解析带逗号的金额字符串，如 "3,000.00" → BigDecimal(3000.00) */
    private java.math.BigDecimal parseAmountStr(Object val) {
        if (val == null) return null;
        String s = val.toString().replace(",", "").trim();
        if (s.isEmpty()) return null;
        try {
            return new java.math.BigDecimal(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 查询当前账期 query_payment_national / query_payment_iit 的 oc_task 状态，
     * 聚合后更新 stepPayment：全部 done → done；任一 error → error；其余 running。
     */
    private void recalcAndSavePaymentStep(TaskPeriod tp) {
        // 仅查国税缴费查询 oc_task
        List<OcTask> paymentTasks = ocTaskRepository.findByAccountSetIdAndPeriodAndTaskTypeIn(
                tp.getAccountSetId(), tp.getPeriod(),
                List.of("query_payment_national"));
        if (CollUtil.isEmpty(paymentTasks)) {
            tp.setStepPayment("error");
            tp.setErrorStep("step_payment");
            tp.setErrorMsg("缴费查询任务不存在");
            return;
        }
        boolean anyError = paymentTasks.stream().anyMatch(t -> "failed".equals(t.getStatus()) || "error".equals(t.getStatus()));
        boolean allDone = paymentTasks.stream().allMatch(t -> "done".equals(t.getStatus()));
        if (anyError) {
            tp.setStepPayment("error");
            tp.setErrorStep("step_payment");
            tp.setErrorMsg("缴费查询失败");
        } else if (allDone) {
            tp.setStepPayment("done");
            // 人工确认模式：执行完毕后改为 waiting
            if (isManualStep(tp.getAccountSetId(), "payment")) {
                tp.setStepPayment("waiting");
            }
        }
        // else keep running
    }

    // ─────────────────────────────────────────────────────────────────────
    // 内部工具方法
    // ─────────────────────────────────────────────────────────────────────

    private String resolvePeriod(String period) {
        if (StrUtil.isBlank(period)) {
            return YearMonth.now().format(PERIOD_FMT);
        }
        return period;
    }

    private TaskPeriod getOrCreateTaskPeriod(Long accountSetId, String period, AccountSets as) {
        TaskPeriod tp = taskPeriodRepository.findByAccountSetIdAndPeriod(accountSetId, period)
                .orElseGet(() -> {
                    TaskPeriod newTp = new TaskPeriod();
                    newTp.setAccountSetId(accountSetId);
                    newTp.setPeriod(period);
                    // 判断是否需要执行期初：账期早于建账期则 skipped，否则 pending（含补录场景）
                    if (as != null && as.getEnableDate() != null) {
                        YearMonth enableYm = YearMonth.from(as.getEnableDate());
                        YearMonth periodYm = YearMonth.parse(period, PERIOD_FMT);
                        if (periodYm.isBefore(enableYm)) {
                            newTp.setStepInit("skipped");
                        } else {
                            newTp.setStepInit("pending");
                        }
                    }
                    // 新建账期时从账套继承 OC 实例绑定
                    if (as != null && StrUtil.isNotBlank(as.getOcInstanceId())) {
                        newTp.setOcInstanceId(as.getOcInstanceId());
                    }
                    return taskPeriodRepository.save(newTp);
                });
        // 若该账期已结账，自动将做账前步骤标记为跳过、做账标记为完成
        applyCheckoutedStatusIfNeeded(tp, accountSetId, period);
        return tp;
    }

    /**
     * 判断账期是否已结账（checkout.status = 2）。
     * 抽取为独立方法，供自动跳过、重新下发时复核结账状态等多处复用。
     */
    private boolean isPeriodCheckedOut(Long accountSetId, String period) {
        try {
            YearMonth ym = YearMonth.parse(period, PERIOD_FMT);
            QCheckout qCheckout = QCheckout.checkout;
            return bqf.selectFrom(qCheckout)
                    .where(qCheckout.accountSetsId.eq(accountSetId)
                            .and(qCheckout.checkYear.eq(ym.getYear()))
                            .and(qCheckout.checkMonth.eq(ym.getMonthValue()))
                            .and(qCheckout.status.eq(2)))
                    .fetchFirst() != null;
        } catch (Exception e) {
            log.warn("结账状态检查失败（按未结账处理）: accountSetId={}, period={}", accountSetId, period, e);
            return false;
        }
    }

    /**
     * 检查账期是否已结账（checkout.status = 2），若是则：
     * - 做账前的本地数据准备步骤（init/data）标记为 skipped（仅当状态为 pending 时）
     * - 做账（bookkeep）标记为 done（仅当状态为 pending/blocked 时）
     * 这样后续下发国税申报、个税、缴费等任务时不会被做账前置锁住。
     * 注意：核验税务（verify）查的是税务局实时数据（欠税/未申报/纳税人状态），
     * 与本地是否结账无关，不在自动跳过之列。
     */
    private void applyCheckoutedStatusIfNeeded(TaskPeriod tp, Long accountSetId, String period) {
        try {
            if (!isPeriodCheckedOut(accountSetId, period)) return;

            boolean changed = false;
            // 做账前的本地数据准备步骤：pending → skipped
            if ("pending".equals(tp.getStepInit()))   { tp.setStepInit("skipped");   changed = true; }
            if ("pending".equals(tp.getStepData()))   { tp.setStepData("skipped");   changed = true; }
            // 做账：pending / blocked → done
            if ("pending".equals(tp.getStepBookkeep()) || "blocked".equals(tp.getStepBookkeep())) {
                tp.setStepBookkeep("done"); changed = true;
            }
            if (changed) {
                taskPeriodRepository.save(tp);
                log.info("账期已结账，自动跳过前置步骤并标记做账完成: accountSetId={}, period={}", accountSetId, period);
            }
        } catch (Exception e) {
            log.warn("结账状态检查失败（不影响主流程）: accountSetId={}, period={}", accountSetId, period, e);
        }
    }

    /**
     * 保存账套级 OC 实例绑定
     * 同时更新 AccountSets 和当期 TaskPeriod，后续新账期自动继承
     */
    @Transactional
    public Map<String, Object> saveOcBinding(Long accountSetId, String period, String ocInstanceId) {
        AccountSets as = accountSetsRepository.findById(accountSetId)
                .orElseThrow(() -> new IllegalArgumentException("账套不存在：" + accountSetId));
        // 1. 永久写入账套
        as.setOcInstanceId(ocInstanceId);
        accountSetsRepository.save(as);
        // 2. 同步当期 TaskPeriod（若存在）
        String p = resolvePeriod(period);
        taskPeriodRepository.findByAccountSetIdAndPeriod(accountSetId, p).ifPresent(tp -> {
            tp.setOcInstanceId(ocInstanceId);
            taskPeriodRepository.save(tp);
        });
        return Map.of("success", true, "msg", StrUtil.isBlank(ocInstanceId) ? "已设为自动分配" : "已绑定 " + ocInstanceId);
    }

    /**
     * 保存账套级登录渠道
     */
    @Transactional
    public Map<String, Object> saveLoginType(Long accountSetId, String loginType) {
        AccountSets as = accountSetsRepository.findById(accountSetId)
                .orElseThrow(() -> new IllegalArgumentException("账套不存在：" + accountSetId));
        String lt = StrUtil.isBlank(loginType) ? "officer" : loginType;
        as.setLoginType(lt);
        accountSetsRepository.save(as);
        return Map.of("success", true, "loginType", lt, "loginTypeDisplay", resolveLoginTypeDisplay(lt));
    }

    public Map<String, Object> updateStarRating(Long accountSetId, Integer rating) {
        AccountSets as = accountSetsRepository.findById(accountSetId)
                .orElseThrow(() -> new IllegalArgumentException("账套不存在：" + accountSetId));
        as.setStarRating(rating);
        accountSetsRepository.save(as);
        return Map.of("success", true, "starRating", rating);
    }

    @Transactional
    public void saveAiProfile(Long accountSetId, String aiProfile) {
        AccountSets as = accountSetsRepository.findById(accountSetId)
                .orElseThrow(() -> new IllegalArgumentException("账套不存在：" + accountSetId));
        as.setAiProfile(aiProfile);
        accountSetsRepository.save(as);
    }

    /**
     * 切换本征期申报完成标记
     */
    /**
     * 检查账套上一年度是否全部结账（12个月均 status=2）。
     * 用于税务年报下发前判断是否具备条件。
     *
     * @param accountSetId 账套ID
     * @param period       当前账期（YYYY-MM），自动推算税年 = 年份 - 1
     * @return "ok" 全年已结账；"no" 尚未全部结账
     */
    @Transactional(readOnly = true)
    public String checkAnnualCheckoutStatus(Long accountSetId, String period) {
        String p = resolvePeriod(period);
        int taxYear = Integer.parseInt(p.substring(0, 4)) - 1;
        boolean checked = checkoutRepository.existsByAccountSetsIdAndCheckYearAndCheckMonthAndStatus(accountSetId, taxYear, 12, 2);
        return checked ? "ok" : "no";
    }

    @Transactional
    public Map<String, Object> toggleDeclareCompleted(Long accountSetId, String period, boolean completed) {
        String p = resolvePeriod(period);
        TaskPeriod tp = taskPeriodRepository.findByAccountSetIdAndPeriod(accountSetId, p)
                .orElse(null);
        if (tp == null) {
            // 尚无 TaskPeriod 记录时自动创建
            AccountSets as = accountSetsRepository.findById(accountSetId)
                    .orElseThrow(() -> new IllegalArgumentException("账套不存在：" + accountSetId));
            tp = getOrCreateTaskPeriod(accountSetId, p, as);
        }
        tp.setDeclareCompleted(completed);
        taskPeriodRepository.save(tp);
        return Map.of("success", true, "declareCompleted", completed);
    }

    private Map<String, Object> buildRow(AccountSets as, TaskPeriod tp, String period, Set<Long> everDoneInitIds) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("accountSetId", as.getId());
        row.put("companyName", as.getCompanyName());
        row.put("creditCode", as.getCreditCode());
        row.put("starRating", as.getStarRating() != null ? as.getStarRating() : 0);
        row.put("vatType", as.getVatType() != null ? as.getVatType().name() : null);
        row.put("vatTypeDisplay", as.getVatType() != null ? as.getVatType().display : null);
        row.put("province", as.getProvince());
        row.put("period", period);
        // 账套级 OC 绑定（跨账期持久有效）
        row.put("ocInstanceId", as.getOcInstanceId());
        row.put("aiProfile", as.getAiProfile());
        row.put("stepConfig", JSON.toJSONString(resolveStepConfig(as)));
        // 账套级登录渠道
        String lt = StrUtil.isBlank(as.getLoginType()) ? "officer" : as.getLoginType();
        row.put("loginType", lt);
        row.put("loginTypeDisplay", resolveLoginTypeDisplay(lt));
        row.put("currentAccountDate", as.getCurrentAccountDate() != null ? as.getCurrentAccountDate().toString() : null);

        if (tp != null) {
            row.put("taskPeriodId", tp.getId());
            row.put("stepVerify", tp.getStepVerify());
            // stepInit 动态根据当前启用日期计算（非终态时），不依赖数据库旧值
            row.put("stepInit", resolveStepInit(as, tp.getStepInit(), period, everDoneInitIds));
            row.put("stepData", tp.getStepData());
            row.put("stepBookkeep", tp.getStepBookkeep());
            row.put("stepTaxNational", tp.getStepTaxNational());
            row.put("stepTaxIit", tp.getStepTaxIit());
            // 个税申报结果详情
            row.put("iitResultDetail", null); // 由 getTasks 的批量预加载覆盖；getDetail 走单独路径
            row.put("stepPayment", tp.getStepPayment());
            // stepTaxAnnual 跨月共享，不从 tp 读取（可能过时），统一由批量预加载覆盖
            row.put("stepTaxAnnual", "pending");
            row.put("declareCompleted", Boolean.TRUE.equals(tp.getDeclareCompleted()));
            row.put("stepVerifyChecked",     Boolean.TRUE.equals(tp.getStepVerifyChecked()));
            row.put("stepInitChecked",       Boolean.TRUE.equals(tp.getStepInitChecked()));
            row.put("stepDataChecked",       Boolean.TRUE.equals(tp.getStepDataChecked()));
            row.put("stepBookkeepChecked",   Boolean.TRUE.equals(tp.getStepBookkeepChecked()));
            row.put("stepTaxNationalChecked",Boolean.TRUE.equals(tp.getStepTaxNationalChecked()));
            row.put("stepTaxIitChecked",     Boolean.TRUE.equals(tp.getStepTaxIitChecked()));
            row.put("stepPaymentChecked",    Boolean.TRUE.equals(tp.getStepPaymentChecked()));
            row.put("stepTaxAnnualChecked",  Boolean.TRUE.equals(tp.getStepTaxAnnualChecked()));
            row.put("errorStep", tp.getErrorStep());
            row.put("errorMsg", tp.getErrorMsg());
            row.put("updatedAt", tp.getUpdatedAt());
        } else {
            row.put("taskPeriodId", null);
            row.put("stepVerify", "pending");
            // 无 TaskPeriod 记录时同样动态计算
            row.put("stepInit", resolveStepInit(as, null, period, everDoneInitIds));
            row.put("stepData", "pending");
            row.put("stepBookkeep", "pending");
            row.put("stepTaxNational", "pending");
            row.put("stepTaxIit", "pending");
            row.put("stepPayment", "pending");
            row.put("stepTaxAnnual", "pending");
            row.put("declareCompleted", false);
            row.put("stepVerifyChecked", false);
            row.put("stepInitChecked", false);
            row.put("stepDataChecked", false);
            row.put("stepBookkeepChecked", false);
            row.put("stepTaxNationalChecked", false);
            row.put("stepTaxIitChecked", false);
            row.put("stepPaymentChecked", false);
            row.put("stepTaxAnnualChecked", false);
            row.put("errorStep", null);
            row.put("errorMsg", null);
            row.put("updatedAt", null);
        }

        // 行级整体状态（取最差值）
        row.put("overallStatus", calcOverallStatus(row));
        return row;
    }

    /**
     * 构建个税申报结果详情（结构化）。
     * 返回列表，每项包含 iitType / taxRefund / declarationResult / errorMsg / status。
     * 前端用于分层展示：应补/退税额突出显示，错误信息红色，申报结果灰色。
     */
    private List<Map<String, String>> buildIitResultDetail(Long taskPeriodId) {
        if (taskPeriodId == null) return null;
        return buildIitResultDetailFromList(taskIitItemRepository.findByTaskPeriodId(taskPeriodId));
    }

    /** 从已预加载的 IIT 明细列表构建前端 DTO，供批量场景使用（避免 N+1 查询）。 */
    private List<Map<String, String>> buildIitResultDetailFromList(List<TaskIitItem> items) {
        if (CollUtil.isEmpty(items)) return null;

        List<Map<String, String>> result = new ArrayList<>();
        for (TaskIitItem item : items) {
            Map<String, String> detail = new LinkedHashMap<>();
            detail.put("iitType", item.getIitType());
            detail.put("status", item.getDeclareStatus());

            // 从 declareResult JSON 中解析 declaration_result 和 error_msg
            String declarationResult = null;
            String errorMsg = null;
            String taxRefund = null;

            if (StrUtil.isNotBlank(item.getDeclareResult())) {
                try {
                    Map<String, Object> json = JSON.parseObject(item.getDeclareResult());
                    if (json != null) {
                        if (json.get("declaration_result") != null) {
                            declarationResult = json.get("declaration_result").toString().trim()
                                    .replaceAll("\\r\\n|\\r|\\n", " ").replaceAll("\\s+", " ");
                        }
                        if (json.get("error_msg") != null) {
                            errorMsg = json.get("error_msg").toString().trim();
                        }
                    }
                } catch (Exception ignored) {}
            }

            // 兜底：如果 JSON 中没解析到 errorMsg，用 item 本身的
            if (StrUtil.isBlank(errorMsg) && StrUtil.isNotBlank(item.getErrorMsg())) {
                errorMsg = item.getErrorMsg().trim();
            }

            // 从 declarationResult 中提取应补/退税额
            if (StrUtil.isNotBlank(declarationResult)) {
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("应补[/／]退税额[=：:：]\\s*-?[\\d,]+\\.?\\d*")
                        .matcher(declarationResult);
                if (m.find()) {
                    taxRefund = m.group();
                }
            }

            detail.put("taxRefund", taxRefund);
            detail.put("declarationResult", declarationResult);
            detail.put("errorMsg", errorMsg);
            result.add(detail);
        }
        return result.isEmpty() ? null : result;
    }

    /**
     * 构建缴费税额汇总（用于列表行内展示）。
     * 返回 Map 包含 totalAmount（应缴合计）、unpaidAmount（待缴合计）、itemCount（税种数）。
     */
    private Map<String, Object> buildPaymentSummary(List<TaskPaymentItem> items) {
        if (CollUtil.isEmpty(items)) return null;
        BigDecimal totalAmount = BigDecimal.ZERO;
        BigDecimal unpaidAmount = BigDecimal.ZERO;
        for (TaskPaymentItem item : items) {
            if (item.getTaxAmount() != null) totalAmount = totalAmount.add(item.getTaxAmount());
            if (item.getUnpaidAmount() != null) unpaidAmount = unpaidAmount.add(item.getUnpaidAmount());
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalAmount", totalAmount);
        summary.put("unpaidAmount", unpaidAmount);
        summary.put("itemCount", items.size());
        return summary;
    }

    /**
     * 构建税务年报详情（用于列表行和详情抽屉）。
     * 返回列表，每项包含 reportType / reportTypeName / status / taxYear / errorMsg / screenshotUrls。
     */
    private Map<String, String> buildTaxAnnualResultDetail(List<TaskTaxAnnualItem> items) {
        if (CollUtil.isEmpty(items)) return null;
        TaskTaxAnnualItem item = items.get(0);
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("status", item.getDeclareStatus());
        detail.put("taxYear", item.getTaxYear());
        detail.put("errorMsg", item.getErrorMsg());
        detail.put("screenshotUrls", item.getScreenshotUrls());
        detail.put("taxItems", item.getTaxItems());
        detail.put("executedAt", item.getExecutedAt() != null ? item.getExecutedAt().toString() : null);
        return detail;
    }

    /**
     * 动态计算 stepInit 的显示值。
     * <ul>
     *   <li>已终态（done / error）→ 直接返回存储值</li>
     *   <li>账套当前记账日期 == 启用月 → pending（建账第一个月，需要导期初）</li>
     *   <li>其余情况（老账套已过启用月 / 尚未到启用月）→ skipped</li>
     * </ul>
     */
    private String resolveStepInit(AccountSets as, String stored, String period, Set<Long> everDoneInitIds) {
        if ("done".equals(stored) || "error".equals(stored)) return stored;
        if (as == null || as.getEnableDate() == null || as.getCurrentAccountDate() == null) return "skipped";
        try {
            YearMonth enableYm = YearMonth.from(as.getEnableDate());
            YearMonth currentYm = YearMonth.from(as.getCurrentAccountDate());
            // 只有账套当前记账日期正好等于启用月，才需要导期初
            if (currentYm.equals(enableYm)) return "pending";
            return "skipped";
        } catch (Exception e) {
            return "skipped";
        }
    }

    private Map<String, Object> buildSubRow(TaskDataSub sub) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", sub.getId());
        m.put("subType", sub.getSubType());
        m.put("status", sub.getStatus());
        m.put("errorMsg", sub.getErrorMsg());
        m.put("executedAt", sub.getExecutedAt());
        if (StrUtil.isNotBlank(sub.getResultJson())) {
            try {
                m.put("result", JSON.parseObject(sub.getResultJson()));
            } catch (Exception e) {
                m.put("result", null);
            }
        }
        if (StrUtil.isNotBlank(sub.getScreenshotUrls())) {
            m.put("screenshotUrls", sub.getScreenshotUrls());
        }
        return m;
    }

    /** 精简版任务信息，仅供 next-task 接口使用，不含登录凭证 */
    private Map<String, Object> buildOcTaskBrief(OcTask oc) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", oc.getId());
        m.put("taskType", oc.getTaskType());
        m.put("accountSetId", oc.getAccountSetId());
        m.put("period", oc.getPeriod());
        accountSetsRepository.findById(oc.getAccountSetId()).ifPresent(as -> {
            m.put("companyName", as.getCompanyName());
            m.put("creditCode", as.getCreditCode());
        });
        // 返回 payload 供 OC 获取任务参数（如小规模季度下载范围等）
        if (StrUtil.isNotBlank(oc.getPayloadJson())) {
            m.put("payload", JSON.parseObject(oc.getPayloadJson()));
        }
        return m;
    }

    /**
     * 将 TaskVerifyResult 实体转换为前端期望的 DTO 格式。
     * <p>
     * 前端需要的字段（与 Entity 字段名不完全一致）：
     * <ul>
     *   <li>taxpayerStatus   — 直接使用存储值（OC 约定传 normal/cancelled/abnormal）</li>
     *   <li>companyTypeLabel — 取自 qualificationStatus（原始资格类型描述）</li>
     *   <li>hasArrears       — 直接使用</li>
     *   <li>arrearsDetail    — JSON 字符串，提取自 verifyItems.query_arrears，格式 [{tax_name, amount}]</li>
     *   <li>taxTypes         — JSON 字符串，提取自 verifyItems.query_should_declare，格式 [{tax_name, declare_cycle, start_date, end_date}]</li>
     *   <li>screenshotUrls   — JSON 字符串，将 screenshots 各 key 下的 URL 合并为平铺数组</li>
     *   <li>executedAt       — 直接使用</li>
     * </ul>
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> buildVerifyResultDto(TaskVerifyResult v) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("taxpayerStatus",  v.getTaxpayerStatus());
        dto.put("companyTypeLabel", v.getQualificationStatus());
        dto.put("hasArrears",       v.getHasArrears());
        dto.put("hasShouldDeclare", v.getHasShouldDeclare());
        dto.put("hasUnreported",    v.getHasUnreported());
        dto.put("executedAt",       v.getExecutedAt());
        dto.put("verifyTime",       v.getVerifyTime());
        dto.put("errorMsg",         v.getErrorMsg());

        // verifyItems：解析后提取 arrearsDetail 和 taxTypes
        if (StrUtil.isNotBlank(v.getVerifyItems())) {
            try {
                Map<String, Object> items = JSON.parseObject(v.getVerifyItems(),
                        new com.alibaba.fastjson2.TypeReference<Map<String, Object>>() {});

                // 欠税明细：query_arrears.tax_items → [{tax_name, amount}]
                Object arrearsRaw = items.get("query_arrears");
                if (arrearsRaw instanceof List) {
                    List<Map<String, Object>> arrearsDetail = new ArrayList<>();
                    for (Object item : (List<?>) arrearsRaw) {
                        if (!(item instanceof Map)) continue;
                        Map<?, ?> m = (Map<?, ?>) item;
                        Map<String, Object> row = new LinkedHashMap<>();
                        // 兼容 tax_name 和 税（费）种 两种 key
                        Object name = m.get("tax_name");
                        if (name == null) name = m.get("税（费）种");
                        row.put("tax_name", name);
                        Object amount = m.get("amount");
                        if (amount == null) amount = m.get("欠税金额");
                        row.put("amount", amount);
                        if (name != null) arrearsDetail.add(row);
                    }
                    dto.put("arrearsDetail", JSON.toJSONString(arrearsDetail));
                }

                // 税种列表：query_should_declare.tax_items → [{tax_name, declare_cycle, start_date, end_date}]
                Object taxTypesRaw = items.get("query_should_declare");
                if (taxTypesRaw instanceof List) {
                    List<Map<String, Object>> taxTypes = new ArrayList<>();
                    for (Object item : (List<?>) taxTypesRaw) {
                        if (!(item instanceof Map)) continue;
                        Map<?, ?> m = (Map<?, ?>) item;
                        Map<String, Object> row = new LinkedHashMap<>();
                        Object name = m.get("tax_name"); if (name == null) name = m.get("税（费）种");
                        Object cycle = m.get("declare_cycle"); if (cycle == null) cycle = m.get("纳税（费）期限");
                        Object start = m.get("start_date"); if (start == null) start = m.get("认定有效期起");
                        Object end = m.get("end_date"); if (end == null) end = m.get("认定有效期止");
                        row.put("tax_name",      name);
                        row.put("declare_cycle", cycle);
                        row.put("start_date",    start);
                        row.put("end_date",      end);
                        taxTypes.add(row);
                    }
                    if (!taxTypes.isEmpty()) dto.put("taxTypes", JSON.toJSONString(taxTypes));
                }

                // 当期未申报：query_current_unreported.tax_items → [{事项名称, 办理期限, 标签状态}]
                Object unreportedRaw = items.get("query_current_unreported");
                if (unreportedRaw instanceof List) {
                    dto.put("currentUnreported", JSON.toJSONString(unreportedRaw));
                }
            } catch (Exception e) {
                log.warn("解析 verifyItems 失败: taskPeriodId={}", v.getTaskPeriodId(), e);
            }
        }

        // screenshots：将各 query key 下的 URL 合并为平铺数组
        if (StrUtil.isNotBlank(v.getScreenshots())) {
            try {
                Map<String, Object> screenshotMap = JSON.parseObject(v.getScreenshots(),
                        new com.alibaba.fastjson2.TypeReference<Map<String, Object>>() {});
                List<String> allUrls = new ArrayList<>();
                for (Object val : screenshotMap.values()) {
                    if (val instanceof List) {
                        for (Object url : (List<?>) val) {
                            if (url instanceof String) allUrls.add((String) url);
                        }
                    } else if (val instanceof String) {
                        allUrls.add((String) val);
                    }
                }
                if (!allUrls.isEmpty()) dto.put("screenshotUrls", JSON.toJSONString(allUrls));
            } catch (Exception e) {
                log.warn("解析 screenshots 失败: taskPeriodId={}", v.getTaskPeriodId(), e);
            }
        }

        return dto;
    }

    // ─────────────────────────────────────────────────────────────────────
    // FN-SHOW-TAX-VERIFY-RESULT：openapi 读核验结果（复用 buildVerifyResultDto，纯读）
    // ─────────────────────────────────────────────────────────────────────

    /**
     * 供 {@code GET /openapi/v1/tax-verify/result} 用：按 accountSetsId + period 取核验结果 DTO。
     * <ul>
     *   <li><b>复用</b> {@link #buildVerifyResultDto}（不另写口径）；无 TaskPeriod / 无核验记录 → 返 {@code null}（不报错）。</li>
     *   <li>FN-VERIFY-READ-MONTHLY：核验<b>按原始月</b>读（jianye 定调），写读都按月、不季末归一。</li>
     *   <li>补 {@code vatTypeMismatch} + {@code vatTypeMismatchMsg}：同 {@code ingestTaxVerifyResult} 口径<b>现算</b>
     *       （核验资格 vs {@code AccountSets.vatType}），兼容 /oc/callback 与 BT 直推两条写路径，避免读写两套结论。</li>
     *   <li>{@code screenshotUrls} <b>代理化</b>为 {@code /openapi/v1/tax-verify/screenshot?name=}（@SaIgnore 可达），
     *       因原始 {@code /oc-screenshots/**} 受 Sa-Token 登录拦截、BT openapi 不可直达（见 §5.1）。</li>
     * </ul>
     */
    public Map<String, Object> getTaxVerifyResultDto(Long accountSetsId, String period) {
        AccountSets as = accountSetsRepository.findById(accountSetsId).orElse(null);
        if (as == null) return null;

        // FN-VERIFY-READ-MONTHLY（jianye 定调：核验改按月）：按<b>原始月</b>读核验，不再季末归一，与写侧一致。
        //   查 2026-05 直接命中 05 的 task_period + task_verify_result（数据本就在原始月）。
        //   ⚠ 仅核验读改按月；declare 法定季报口径不动。
        String effPeriod = period;   // 原始月（不归一）

        TaskPeriod tp = taskPeriodRepository.findByAccountSetIdAndPeriod(accountSetsId, effPeriod).orElse(null);
        if (tp == null) return null;
        TaskVerifyResult v = taskVerifyResultRepository.findByTaskPeriodId(tp.getId()).orElse(null);
        if (v == null) return null;

        Map<String, Object> dto = buildVerifyResultDto(v);
        dto.put("period", effPeriod);

        // vatTypeMismatch 现算（同 ingestTaxVerifyResult 口径）
        Boolean verifiedGeneral = deriveVerifiedGeneralFromResult(v);
        String mismatchMsg = vatTypeMismatchMsg(verifiedGeneral, as.getVatType());
        dto.put("vatTypeMismatch", mismatchMsg != null);
        if (mismatchMsg != null) dto.put("vatTypeMismatchMsg", mismatchMsg);

        // 截图代理化（BT 可直达）
        rewriteScreenshotUrlsForOpenApi(dto);
        return dto;
    }

    /**
     * 从已落库的 {@link TaskVerifyResult} 还原"核验资格=一般/小规模"信号，口径同
     * {@link #deriveVerifiedGeneral}（query_qualification 有行"资格类型"含"一般纳税人"→一般 / 否则小规模 / 无→判不出）。
     *
     * <p>FN-VERIFY-VATTYPE-CONTENT-JUDGE：读路径同写路径，改为按内容判定，不再 {@code !isEmpty()}。
     */
    static Boolean deriveVerifiedGeneralFromResult(TaskVerifyResult v) {
        if (StrUtil.isNotBlank(v.getVerifyItems())) {
            try {
                Map<String, Object> items = JSON.parseObject(v.getVerifyItems(),
                        new com.alibaba.fastjson2.TypeReference<Map<String, Object>>() {});
                Object q = items.get("query_qualification");
                // 有 query_qualification：按内容判——有行"资格类型"含"一般纳税人"才判一般，否则判小规模
                if (q instanceof List) return rowsIndicateGeneral((List<?>) q);
            } catch (Exception ignore) { /* fall through */ }
        }
        // 兜底：仅 qualificationStatus 含"一般纳税人"才判一般（与"纳税人资格"展示同源）
        if (StrUtil.isNotBlank(v.getQualificationStatus())) return v.getQualificationStatus().contains("一般纳税人");
        return null;
    }

    /** 把 DTO 里 screenshotUrls（JSON 字符串数组）逐条改写成 openapi 截图代理 URL。 */
    private void rewriteScreenshotUrlsForOpenApi(Map<String, Object> dto) {
        Object raw = dto.get("screenshotUrls");
        if (!(raw instanceof String) || StrUtil.isBlank((String) raw)) return;
        try {
            List<String> urls = JSON.parseObject((String) raw,
                    new com.alibaba.fastjson2.TypeReference<List<String>>() {});
            List<String> out = new ArrayList<>();
            for (String u : urls) out.add(toOpenApiScreenshotUrl(u));
            dto.put("screenshotUrls", JSON.toJSONString(out));
        } catch (Exception e) {
            log.warn("改写 screenshotUrls 失败: {}", raw, e);
        }
    }

    /** 原始截图 URL → openapi 代理 URL（提取文件名走 /openapi/v1/tax-verify/screenshot?name=；外部 URL 原样）。 */
    static String toOpenApiScreenshotUrl(String u) {
        if (StrUtil.isBlank(u)) return u;
        final String marker = "/oc-screenshots/";
        int idx = u.indexOf(marker);
        String name;
        if (idx >= 0) {
            name = u.substring(idx + marker.length());
        } else if (u.startsWith("http://") || u.startsWith("https://")) {
            return u;   // 外部 URL（非本服务截图），原样返回
        } else {
            name = u;   // 裸文件名
        }
        return "/openapi/v1/tax-verify/screenshot?name="
                + java.net.URLEncoder.encode(name, java.nio.charset.StandardCharsets.UTF_8);
    }

    // ─────────────────────────────────────────────────────────────────────
    // 期初文件自动获取
    // ─────────────────────────────────────────────────────────────────────

    private static final String COMMON_TASK_FILE_API = "https://www.qingyicloud.com/finmaster-api/commonTask/getCommonTaskFileList";

    /**
     * 自动处理银行流水文件：查询当期已上传但未处理的文件，逐个触发识别导入。
     */
    private void autoProcessBankFiles(Long accountSetId, String period) {
        LocalDate periodDate = LocalDate.parse(period + "-01");
        // 查询当期银行流水文件（page=1, pageSize=999 取全部）
        cn.gson.financial.kernel.controller.Page page = new cn.gson.financial.kernel.controller.Page();
        page.setPage(1);
        page.setPageSize(999);
        var fileList = fileInfoService.list(accountSetId, page, "bankStatement", periodDate);
        if (fileList == null || fileList.getResults() == null || fileList.getResults().isEmpty()) {
            log.info("银行流水无已上传文件: accountSetId={}, period={}", accountSetId, period);
            return;
        }
        AccountSets as = accountSetsRepository.findById(accountSetId).orElse(null);
        if (as == null) return;
        int processed = 0;
        for (var dto : fileList.getResults()) {
            // 跳过已处理的文件
            if (dto.getIsDeal() != null && dto.getIsDeal() == 1) continue;
            try {
                boolean ok = fileInfoService.dealFile(dto.getId(), as);
                if (ok) processed++;
                log.info("自动处理银行流水文件: id={}, fileName={}, result={}", dto.getId(), dto.getFileName(), ok);
            } catch (Exception e) {
                log.warn("自动处理银行流水文件失败: id={}, error={}", dto.getId(), e.getMessage());
            }
        }
        log.info("银行流水自动处理完成: accountSetId={}, period={}, 处理文件数={}", accountSetId, period, processed);
    }

    /**
     * 调用外部系统查询是否存在期初数据文件，返回文件下载 URL；无文件则返回 null。
     */
    @SuppressWarnings("unchecked")
    private String fetchInitFileUrl(String creditCode) {
        String resp = cn.hutool.http.HttpUtil.post(COMMON_TASK_FILE_API,
                JSON.toJSONString(Map.of("taxNumber", creditCode, "uploadType", "oldAccount")));
        Map<String, Object> result = JSON.parseObject(resp, new com.alibaba.fastjson2.TypeReference<Map<String, Object>>() {});
        if (!Integer.valueOf(200).equals(result.get("code"))) {
            log.warn("查询期初文件失败: creditCode={}, resp={}", creditCode, resp);
            return null;
        }
        List<Map<String, Object>> dataList = (List<Map<String, Object>>) result.get("data");
        if (dataList == null || dataList.isEmpty()) {
            log.info("外部系统无期初文件: creditCode={}", creditCode);
            return null;
        }
        // 取最新一条的下载地址
        return (String) dataList.get(0).get("filePath");
    }

    /**
     * 将 byte[] 包装为 MultipartFile，供 importInit 使用。
     */
    private static MultipartFile wrapBytesAsMultipartFile(String filename, byte[] bytes) {
        return new MultipartFile() {
            @Override public String getName() { return "file"; }
            @Override public String getOriginalFilename() { return filename; }
            @Override public String getContentType() { return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"; }
            @Override public boolean isEmpty() { return bytes == null || bytes.length == 0; }
            @Override public long getSize() { return bytes.length; }
            @Override public byte[] getBytes() { return bytes; }
            @Override public java.io.InputStream getInputStream() { return new java.io.ByteArrayInputStream(bytes); }
            @Override public void transferTo(java.io.File dest) throws java.io.IOException {
                java.nio.file.Files.write(dest.toPath(), bytes);
            }
        };
    }

    private Map<String, Object> buildOcTaskRow(OcTask oc) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", oc.getId());
        m.put("taskType", oc.getTaskType());
        m.put("accountSetId", oc.getAccountSetId());
        m.put("period", oc.getPeriod());
        m.put("status", oc.getStatus());
        m.put("screenshotUrl", oc.getScreenshotUrl());
        m.put("createdAt", oc.getCreatedAt());
        m.put("finishedAt", oc.getFinishedAt());
        // 关联账套信息：公司名称、税号、个税密码
        accountSetsRepository.findById(oc.getAccountSetId()).ifPresent(as -> {
            m.put("companyName", as.getCompanyName());
            m.put("creditCode", as.getCreditCode());
            m.put("individualTaxCode", as.getIndividualTaxCode());
        });
        if (StrUtil.isNotBlank(oc.getPayloadJson())) {
            try { m.put("payload", JSON.parseObject(oc.getPayloadJson())); } catch (Exception ignored) {}
        }
        if (StrUtil.isNotBlank(oc.getResultJson())) {
            try { m.put("result", JSON.parseObject(oc.getResultJson())); } catch (Exception ignored) {}
        }
        return m;
    }

    /**
     * 检测并修复孤立的 running 步骤：
     * 若某账套某步骤为 running，但该账期内无任何处于 queued/running 状态的 oc_task，则将该步骤重置为 error。
     * 产生原因：下发时 oc_task 创建失败但异常被捕获吞没，步骤状态仍保留为 running。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void healOrphanedRunningSteps(List<Map<String, Object>> rows, String period) {
        // 找出所有存在 running 步骤的 accountSetId
        List<String> stepFields = List.of("stepVerify", "stepInit", "stepData",
                "stepBookkeep", "stepTaxNational", "stepTaxIit", "stepPayment", "stepTaxAnnual");
        List<Long> runningIds = rows.stream()
                .filter(r -> stepFields.stream().anyMatch(f -> "running".equals(r.get(f))))
                .map(r -> (Long) r.get("accountSetId"))
                .distinct().collect(Collectors.toList());
        if (runningIds.isEmpty()) return;

        // 批量查出这些账套在该账期有活跃 oc_task 的记录
        List<OcTask> activeOcTasks = ocTaskRepository
                .findByPeriodAndAccountSetIdInAndStatusIn(period, runningIds, List.of("queued", "running"));

        // running 超时回收：OC 领走后超过阈值仍未回调视为卡死（崩溃/掉线/中断），自动标记 failed，
        // 随后下面的孤立步骤逻辑会把对应步骤重置为 error——会计可见并重新下发，而非永远停在"执行中"。
        // 计时基准用 started_at（真正进入 running 的时刻），历史行为空时回退 created_at。
        // 排队中（queued）的任务 started_at 为空且状态非 running，天然不在回收范围，杜绝误杀。
        LocalDateTime now = LocalDateTime.now();
        for (OcTask oc : activeOcTasks) {
            if (!"running".equals(oc.getStatus())) continue;
            int limit = reclaimTimeoutMinutes(oc.getTaskType());
            if (limit <= 0) continue;   // 该类型不做超时回收
            LocalDateTime since = oc.getStartedAt() != null ? oc.getStartedAt() : oc.getCreatedAt();
            if (since == null || !since.isBefore(now.minusMinutes(limit))) continue;   // 未超时
            // 防误杀：该任务近期有验证码/二维码交互 ⇒ OC 还活着、正等人工填码，本轮跳过
            if (oc.getId() != null
                    && ocCaptchaRequestRepository.existsByTaskIdAndCreatedAtAfter(oc.getId(), now.minusMinutes(captchaAliveMin))) {
                log.info("running 超时但近期有验证码交互，跳过回收: ocTaskId={}, taskType={}", oc.getId(), oc.getTaskType());
                continue;
            }
            oc.setStatus("failed");
            oc.setFinishedAt(now);
            oc.setResultJson("{\"error\":\"任务执行超时（超过" + limit + "分钟未回调），已自动标记失败，请重新下发\"}");
            ocTaskRepository.save(oc);
            log.warn("running 任务超时自动标记失败: ocTaskId={}, taskType={}, accountSetId={}, since={}",
                    oc.getId(), oc.getTaskType(), oc.getAccountSetId(), since);
        }

        // 重新计算活跃 oc_task 集合（排除已被标记失败的超时任务）
        Set<Long> hasActiveOcTask = activeOcTasks.stream()
                .filter(oc -> "queued".equals(oc.getStatus()) || "running".equals(oc.getStatus()))
                .map(OcTask::getAccountSetId).collect(Collectors.toSet());

        // 找出真正需要修复的 accountSetIds（无活跃 oc_task）
        List<Long> orphanIds = runningIds.stream()
                .filter(asId -> !hasActiveOcTask.contains(asId))
                .collect(Collectors.toList());
        if (orphanIds.isEmpty()) return;

        // 批量查 TaskPeriod，避免 N+1
        Map<Long, TaskPeriod> tpByAsId = taskPeriodRepository
                .findByPeriodAndAccountSetIdIn(period, orphanIds).stream()
                .collect(Collectors.toMap(TaskPeriod::getAccountSetId, t -> t, (a, b) -> a));

        List<TaskPeriod> toSave = new ArrayList<>();
        for (Long asId : orphanIds) {
            TaskPeriod tp = tpByAsId.get(asId);
            if (tp == null) continue;

            boolean changed = false;
            for (String sf : stepFields) {
                if ("running".equals(getStepStatusByField(tp, sf))) {
                    setStepStatusByField(tp, sf, "error");
                    if (tp.getErrorStep() == null) {
                        tp.setErrorStep(sf.replaceAll("([A-Z])", "_$1").toLowerCase()
                                .replaceFirst("^step_", "step_"));
                        tp.setErrorMsg("任务状态异常：下发后未找到执行记录，请重新下发");
                    }
                    changed = true;
                    log.warn("修复孤立 running 步骤: accountSetId={}, step={}", asId, sf);
                }
            }
            if (changed) {
                toSave.add(tp);
                final Long fAsId = asId;
                rows.stream().filter(r -> fAsId.equals(r.get("accountSetId"))).findFirst()
                        .ifPresent(row -> {
                            for (String sf : stepFields) {
                                if ("running".equals(row.get(sf))) row.put(sf, "error");
                            }
                            row.put("overallStatus", calcOverallStatus(row));
                        });
            }
        }
        if (!toSave.isEmpty()) {
            taskPeriodRepository.saveAll(toSave);
        }
    }

    /**
     * running 超时回收的阈值（分钟），按任务类型区分；返回 ≤0 表示该类型不做超时回收。
     * 做账 30 / 个税 60 / 国税侧 60 / 数据类 60；其它类型（含本地任务）暂不回收。
     */
    private int reclaimTimeoutMinutes(String taskType) {
        if (taskType == null) return 0;
        if (taskType.startsWith("bookkeep_")) return bookkeepTimeoutMin;
        if (isTaxIitTask(taskType)) return iitTimeoutMin;
        if (isTaxNationalTask(taskType)) return nationalTimeoutMin;
        if (isDataTask(taskType)) return dataTimeoutMin;
        return 0;
    }

    /** 按字段名读取步骤状态 */
    private String getStepStatusByField(TaskPeriod tp, String field) {
        return switch (field) {
            case "stepVerify"      -> tp.getStepVerify();
            case "stepInit"        -> tp.getStepInit();
            case "stepData"        -> tp.getStepData();
            case "stepBookkeep"    -> tp.getStepBookkeep();
            case "stepCarry"       -> tp.getStepCarry();              // STAGE-STATUS-PERSIST
            case "stepTaxNational" -> tp.getStepTaxNational();
            case "stepTaxIit"      -> tp.getStepTaxIit();
            case "stepPayment"      -> tp.getStepPayment();
            case "stepTaxAnnual"    -> tp.getStepTaxAnnual();
            case "stepAnnualReport" -> tp.getStepAnnualReport();
            case "stepArchive"      -> tp.getStepArchive();            // STAGE-STATUS-PERSIST
            default -> null;
        };
    }

    /**
     * 按字段名写入步骤状态。FN-TASK-PERIOD-SCHEMA-HARDEN：① 状态值统一过 {@link #assertValidStatus}；
     * ② 未知 field 抛 {@link IllegalArgumentException}（不再静默 no-op，杜绝字段名拼错=回写悄悄丢）。
     * 为所有低层写入（writeStepStatusTx/Direct/Checked/backfillStepColumns/孤立修复）的单一校验收口点。
     */
    static void setStepStatusByField(TaskPeriod tp, String field, String status) {
        assertValidStatus(status);
        switch (field) {
            case "stepVerify"       -> tp.setStepVerify(status);
            case "stepInit"         -> tp.setStepInit(status);
            case "stepData"         -> tp.setStepData(status);
            case "stepBookkeep"     -> tp.setStepBookkeep(status);
            case "stepCarry"        -> tp.setStepCarry(status);        // STAGE-STATUS-PERSIST
            case "stepTaxNational"  -> tp.setStepTaxNational(status);
            case "stepTaxIit"       -> tp.setStepTaxIit(status);
            case "stepPayment"      -> tp.setStepPayment(status);
            case "stepTaxAnnual"    -> tp.setStepTaxAnnual(status);
            case "stepAnnualReport" -> tp.setStepAnnualReport(status); // 工商年报（按年；通用口径不含，补全 switch 杜绝静默丢）
            case "stepArchive"      -> tp.setStepArchive(status);      // STAGE-STATUS-PERSIST
            default -> throw new IllegalArgumentException("未知 step field: " + field);
        }
    }

    private Map<String, Object> calcStats(List<Map<String, Object>> rows) {
        // 整体统计：error/warn/running 与 matchStatus 保持同一口径（anyMatch + 归一化），
        // 确保 tab 徽章数 ≥ 各步骤下拉命中数，tabs 之间可重叠（一个账套可同时出现在 error 和 running）
        long error   = rows.stream().filter(r -> ALL_STEP_FIELDS.stream().anyMatch(f -> "error".equals(r.get(f)))).count();
        long warn    = rows.stream().filter(r -> ALL_STEP_FIELDS.stream().anyMatch(f -> "warn".equals(r.get(f)))).count();
        long running = rows.stream().filter(r -> ALL_STEP_FIELDS.stream().anyMatch(f -> {
            Object v = r.get(f);
            return "running".equals(v) || "waiting".equals(v) || "blocked".equals(v);
        })).count();
        long done    = rows.stream().filter(r -> "done".equals(r.get("overallStatus"))).count();
        long skipped = rows.stream().filter(r -> "skipped".equals(r.get("overallStatus"))).count();
        long pending = rows.stream().filter(r -> "pending".equals(r.get("overallStatus"))).count();

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("total", rows.size());
        stats.put("done", done);
        stats.put("skipped", skipped);
        stats.put("running", running);
        stats.put("error", error);
        stats.put("warn", warn);
        stats.put("pending", pending);

        // 每个步骤的分状态统计（互斥，加起来 = total）
        List<String> stepFields = List.of("stepVerify", "stepInit", "stepData",
                "stepBookkeep", "stepTaxNational", "stepTaxIit", "stepPayment", "stepTaxAnnual");
        Map<String, Object> stepStats = new LinkedHashMap<>();
        for (String sf : stepFields) {
            Map<String, Long> m = calcStepStatusCounts(rows, sf);
            stepStats.put(sf, m);
        }
        stats.put("steps", stepStats);

        // 各步骤（含子步骤字段）按归一化状态的账套数，供 tab 下拉菜单显示各步骤命中数
        // 每个步骤的各状态加起来 = total
        List<String> allDropdownFields = List.of(
                "stepVerify", "stepInit", "stepData",
                "sub_invoice", "sub_invoice_check", "sub_bank", "sub_salary", "sub_vat_declare_pre",
                "stepBookkeep", "stepTaxNational", "stepTaxIit", "stepPayment", "stepTaxAnnual");
        Map<String, Object> stepAllCounts = new LinkedHashMap<>();
        for (String field : allDropdownFields) {
            stepAllCounts.put(field, calcStepStatusCounts(rows, field));
        }
        // __total__：整体互斥计数
        Map<String, Long> totalCounts = new LinkedHashMap<>();
        totalCounts.put("error",   error);
        totalCounts.put("warn",    warn);
        totalCounts.put("running", running);
        totalCounts.put("done",    done);
        totalCounts.put("skipped", skipped);
        totalCounts.put("pending", pending);
        stepAllCounts.put("__total__", totalCounts);
        stats.put("stepAllCounts", stepAllCounts);

        return stats;
    }

    /**
     * 统计单个步骤字段在所有行中的状态分布（互斥，加起来 = rows.size()）。
     * 将原始状态归一化为6种：error / warn / running / done / skipped / pending
     *   error   ← error
     *   warn    ← warn
     *   running ← running, waiting, blocked
     *   done    ← done
     *   skipped ← skipped, na
     *   pending ← pending, null, 其他
     */
    private Map<String, Long> calcStepStatusCounts(List<Map<String, Object>> rows, String field) {
        long cError = 0, cWarn = 0, cRunning = 0, cDone = 0, cSkipped = 0, cPending = 0;
        for (Map<String, Object> r : rows) {
            String s = r.get(field) != null ? r.get(field).toString() : "pending";
            switch (s) {
                case "error" -> cError++;
                case "warn" -> cWarn++;
                case "running", "waiting", "blocked" -> cRunning++;
                case "done" -> cDone++;
                case "skipped", "na" -> cSkipped++;
                default -> cPending++;
            }
        }
        Map<String, Long> m = new LinkedHashMap<>();
        m.put("error", cError);
        m.put("warn", cWarn);
        m.put("running", cRunning);
        m.put("done", cDone);
        m.put("skipped", cSkipped);
        m.put("pending", cPending);
        return m;
    }

    /** 取所有步骤中最差状态作为整体状态 */
    private String calcOverallStatus(Map<String, Object> row) {
        List<String> steps = List.of("stepVerify", "stepInit", "stepData",
                "stepBookkeep", "stepTaxNational", "stepTaxIit", "stepPayment", "stepTaxAnnual");
        // 统计各状态是否存在
        boolean hasError = false, hasRunning = false, hasWarn = false, hasPending = false;
        boolean allDoneOrSkipped = true;
        for (String step : steps) {
            String s = (String) row.get(step);
            if (s == null) s = "pending";
            switch (s) {
                case "error" -> hasError = true;
                case "running", "waiting", "blocked" -> hasRunning = true;
                case "warn" -> hasWarn = true;
                case "pending" -> hasPending = true;
            }
            if (!"done".equals(s) && !"skipped".equals(s) && !"na".equals(s)) {
                allDoneOrSkipped = false;
            }
        }
        // 互斥优先级：error > running > warn > done > skipped > pending
        if (hasError) return "error";
        if (hasRunning) return "running";
        if (hasWarn) return "warn";
        if (allDoneOrSkipped) {
            // 全部为 skipped/na（无任何步骤真正完成）→ skipped；否则 → done
            boolean anyDone = steps.stream().anyMatch(step -> "done".equals(row.get(step)));
            return anyDone ? "done" : "skipped";
        }
        return "pending";
    }

    /**
     * 聚合「下载发票」状态（不含勾选发票动作，invoice_check 是独立子任务）。
     * <p>小规模：[invoice_in, invoice_out]
     * <p>一般纳税人：[invoice_in, invoice_out, invoice_checked, invoice_unchecked]
     * 优先级：error > running > pending > done
     */
    private String aggregateInvoiceStatus(List<TaskDataSub> subs, AccountSets as) {
        boolean isSmall = as != null && as.getVatType() == VatType.SMALL_SCALE;
        List<String> invoiceTypes = isSmall
                ? List.of("invoice_in", "invoice_out")
                : List.of("invoice_in", "invoice_out", "invoice_checked", "invoice_unchecked");
        List<String> statuses = subs.stream()
                .filter(s -> invoiceTypes.contains(s.getSubType()))
                .map(TaskDataSub::getStatus)
                .collect(Collectors.toList());
        if (statuses.isEmpty()) return "pending";
        if (statuses.contains("error")) return "error";
        if (statuses.contains("running")) return "running";
        if (statuses.stream().allMatch("done"::equals)) return "done";
        return "pending";
    }

    private boolean matchStatus(Map<String, Object> r, String status) {
        return matchStatus(r, status, null);
    }

    /** 所有步骤 + 子步骤字段（tab 标签计数 / 过滤 / 下拉全部步骤 共用） */
    private static final List<String> ALL_STEP_FIELDS = List.of(
            "stepVerify", "stepInit", "stepData",
            "sub_invoice", "sub_invoice_check", "sub_bank", "sub_salary", "sub_vat_declare_pre",
            "stepBookkeep", "stepTaxNational", "stepTaxIit", "stepPayment", "stepTaxAnnual");

    private boolean matchStatus(Map<String, Object> r, String status, String stepField) {
        if (StrUtil.isBlank(status) || "all".equals(status)) return true;
        if (StrUtil.isBlank(stepField)) {
            // 未指定步骤字段：error/warn/running 只要有任意主步骤命中即匹配；done/pending 按整体状态
            if ("error".equals(status) || "warn".equals(status)) {
                return ALL_STEP_FIELDS.stream().anyMatch(f -> status.equals(r.get(f)));
            }
            if ("running".equals(status) || "blocked".equals(status)) {
                // 归一化：running/waiting/blocked 都算"执行中"，与 calcStepStatusCounts 保持一致
                return ALL_STEP_FIELDS.stream().anyMatch(f -> {
                    Object v = r.get(f);
                    return "running".equals(v) || "waiting".equals(v) || "blocked".equals(v);
                });
            }
            return status.equals(r.get("overallStatus"));
        }
        // 指定了步骤字段：按归一化状态匹配（与 calcStepStatusCounts 保持一致）
        String stepStatus = (String) r.get(stepField);
        if (stepStatus == null) {
            return "pending".equals(status);
        }
        // 归一化：skipped/na → skipped；running/waiting/blocked → running
        String normalized = switch (stepStatus) {
            case "skipped", "na" -> "skipped";
            case "running", "waiting", "blocked" -> "running";
            default -> stepStatus;
        };
        return status.equals(normalized);
    }

    private boolean matchVat(Map<String, Object> r, String vat) {
        if (StrUtil.isBlank(vat)) return true;
        if ("small".equals(vat)) return VatType.SMALL_SCALE.name().equals(r.get("vatType"));
        if ("general".equals(vat)) return VatType.COMMONLY.name().equals(r.get("vatType"));
        return true;
    }

    private boolean matchProvince(Map<String, Object> r, String province) {
        if (StrUtil.isBlank(province)) return true;
        return province.equals(r.get("province"));
    }

    private boolean matchAccountPeriod(Map<String, Object> r, String accountPeriod) {
        if (StrUtil.isBlank(accountPeriod)) return true;
        return accountPeriod.equals(r.get("currentAccountDate"));
    }

    private boolean matchDeclareFilter(Map<String, Object> r, String declareFilter) {
        if (StrUtil.isBlank(declareFilter)) return true;
        boolean completed = Boolean.TRUE.equals(r.get("declareCompleted"));
        return "completed".equals(declareFilter) ? completed : !completed;
    }

    private boolean matchIitFilter(Map<String, Object> r, String iitFilter) {
        if (StrUtil.isBlank(iitFilter)) return true;
        boolean hasSalary = Boolean.TRUE.equals(r.get("hasSalary"));
        return "has_salary".equals(iitFilter) ? hasSalary : !hasSalary;
    }

    /**
     * 手动完成步骤后，将该步骤对应的 queued/blocked oc_task 取消，
     * 防止 OC 实例仍捡起这些任务重复执行。
     */
    private void cancelPendingOcTasksForStep(TaskPeriod tp, String step) {
        List<String> taskTypes = ocTaskTypesForStep(step);
        if (taskTypes.isEmpty()) return;
        List<OcTask> pending = new ArrayList<>();
        pending.addAll(ocTaskRepository.findByTaskPeriodIdAndStatus(tp.getId(), "queued"));
        pending.addAll(ocTaskRepository.findByTaskPeriodIdAndStatus(tp.getId(), "blocked"));
        for (OcTask oc : pending) {
            if (taskTypes.contains(oc.getTaskType())) {
                oc.setStatus("done");
                oc.setFinishedAt(LocalDateTime.now());
                ocTaskRepository.save(oc);
            }
        }
    }

    /** step（含子步骤如 data.invoice）→ 对应的 oc_task task_type 列表 */
    private List<String> ocTaskTypesForStep(String step) {
        if (StrUtil.isBlank(step)) return Collections.emptyList();
        if (step.contains(".")) {
            String[] parts = step.split("\\.", 2);
            String parent = parts[0], sub = parts[1];
            if ("bookkeep".equals(parent)) return List.of("bookkeep_" + sub);
            if ("data".equals(parent)) {
                return switch (sub) {
                    case "invoice"           -> List.of("download_invoice_in", "download_invoice_out",
                                                        "check_invoice",
                                                        "download_invoice_checked", "download_invoice_unchecked");
                    case "invoice_in"        -> List.of("download_invoice_in");
                    case "invoice_out"       -> List.of("download_invoice_out");
                    case "invoice_check"     -> List.of("check_invoice");
                    case "invoice_checked"   -> List.of("download_invoice_checked");
                    case "invoice_unchecked" -> List.of("download_invoice_unchecked");
                    case "vat_declare_pre"   -> List.of("vat_declare_pre");
                    default                  -> Collections.emptyList(); // bank/salary 为内部任务
                };
            }
            return Collections.emptyList();
        }
        return switch (step) {
            case "verify"   -> List.of("verify_tax");
            case "init"     -> List.of("init_import");
            case "data"     -> List.of("download_invoice_in", "download_invoice_out",
                                       "check_invoice",
                                       "download_invoice_checked", "download_invoice_unchecked",
                                       "vat_declare_pre");
            case "bookkeep" -> List.of("bookkeep_asset", "bookkeep_invoice", "bookkeep_salary",
                                       "bookkeep_bank", "bookkeep_tax", "bookkeep_carry_forward");
            case "vat"      -> List.of("vat_declare_post", "fetch_tax_items", "vat_declare_pre");
            case "iit"      -> List.of("iit_declare");
            case "payment"  -> List.of("query_payment_national", "query_payment_iit");
            case "tax_annual" -> List.of("tax_annual");
            default         -> Collections.emptyList();
        };
    }

    private boolean matchKeyword(Map<String, Object> r, String keyword) {
        if (StrUtil.isBlank(keyword)) return true;
        String kw = keyword.toLowerCase();
        String name = StrUtil.nullToEmpty((String) r.get("companyName")).toLowerCase();
        String code = StrUtil.nullToEmpty((String) r.get("creditCode")).toLowerCase();
        return name.contains(kw) || code.contains(kw);
    }

    private String getStepStatus(TaskPeriod tp, String step) {
        return switch (step) {
            case "verify" -> tp.getStepVerify();
            case "init" -> tp.getStepInit();
            case "data", "invoice", "bank", "salary", "tax_items",
                    "invoice_in", "invoice_out", "invoice_check",
                    "invoice_checked", "invoice_unchecked" -> tp.getStepData();
            case "bookkeep" -> tp.getStepBookkeep();
            case "vat" -> tp.getStepTaxNational();
            case "iit" -> tp.getStepTaxIit();
            case "payment" -> tp.getStepPayment();
            case "tax_annual" -> tp.getStepTaxAnnual();
            default -> "pending";
        };
    }

    private void setStepStatus(TaskPeriod tp, String step, String status) {
        switch (step) {
            case "verify" -> tp.setStepVerify(status);
            case "init" -> tp.setStepInit(status);
            case "data" -> tp.setStepData(status);
            case "invoice", "bank", "salary", "tax_items",
                    "invoice_in", "invoice_out", "invoice_check",
                    "invoice_checked", "invoice_unchecked" -> recalcStepData(tp);
            case "bookkeep" -> tp.setStepBookkeep(status);
            case "vat" -> tp.setStepTaxNational(status);
            case "iit" -> tp.setStepTaxIit(status);
            case "payment" -> tp.setStepPayment(status);
            case "tax_annual" -> tp.setStepTaxAnnual(status);
        }
    }

    /**
     * 将 TaskBookkeepService 返回的 detail 列表持久化到 task_bookkeep_sub，并回写 oc_task_id。
     * detail 中的 type 字段映射：asset_depreciation→asset，其余同名。
     * carry_forward 类型不对应 task_bookkeep_sub 行，跳过。
     */
    private void saveBookkeepSubResults(Long taskPeriodId, List<Map<String, Object>> detail,
                                        Map<String, OcTask> ocTaskMap) {
        if (taskPeriodId == null || CollUtil.isEmpty(detail)) return;
        for (Map<String, Object> item : detail) {
            String rawType = (String) item.get("type");
            String subType = switch (rawType != null ? rawType : "") {
                case "asset_depreciation" -> "asset";
                case "invoice" -> "invoice";
                case "salary" -> "salary";
                case "bank" -> "bank";
                case "tax" -> "tax";
                case "carry_forward" -> "carry_forward";
                default -> null;
            };
            if (subType == null) continue;

            TaskBookkeepSub sub = taskBookkeepSubRepository
                    .findByTaskPeriodIdAndSubType(taskPeriodId, subType)
                    .orElseGet(() -> {
                        TaskBookkeepSub s = new TaskBookkeepSub();
                        s.setTaskPeriodId(taskPeriodId);
                        s.setSubType(subType);
                        return s;
                    });

            String status = (String) item.getOrDefault("status", "done");
            String msg = (String) item.get("msg");
            sub.setStatus(status);
            sub.setMsg(msg);
            sub.setErrorMsg("error".equals(status) ? msg : null);
            if (item.get("generated") instanceof Integer generated) {
                sub.setVoucherCount(generated);
            }
            sub.setExecutedAt(LocalDateTime.now());

            // 回写 oc_task_id，同步更新 oc_task 状态
            OcTask ocTask = ocTaskMap != null ? ocTaskMap.get(subType) : null;
            if (ocTask != null) {
                sub.setOcTaskId(ocTask.getId());
                ocTask.setStatus("error".equals(status) ? "failed" : "done");
                ocTask.setFinishedAt(LocalDateTime.now());
                ocTask.setResultJson(JSON.toJSONString(item));
                ocTaskRepository.save(ocTask);
            }

            taskBookkeepSubRepository.save(sub);
        }
    }

    /** step_data 聚合规则：error > running > pending > waiting > warn > done */
    private void recalcStepData(TaskPeriod tp) {
        if (tp.getId() == null) return;
        List<TaskDataSub> subs = taskDataSubRepository.findByTaskPeriodId(tp.getId()).stream()
                .filter(s -> !"tax_items".equals(s.getSubType())) // tax_items 已独立，不参与 stepData 聚合
                .collect(Collectors.toList());
        // 当存在 invoice_in/invoice_out 明细记录时，忽略旧版遗留的笼统 "invoice" 记录，避免重复计算
        boolean hasDetailedInvoice = subs.stream()
                .anyMatch(s -> "invoice_in".equals(s.getSubType()) || "invoice_out".equals(s.getSubType()));
        if (hasDetailedInvoice) {
            subs = subs.stream().filter(s -> !"invoice".equals(s.getSubType())).collect(Collectors.toList());
        }
        // 小规模纳税人不应参与 invoice_check / invoice_checked / invoice_unchecked 聚合
        AccountSets as = tp.getAccountSetId() != null
                ? accountSetsRepository.findById(tp.getAccountSetId()).orElse(null) : null;
        if (as != null && as.getVatType() == VatType.SMALL_SCALE) {
            subs = subs.stream()
                    .filter(s -> !"invoice_check".equals(s.getSubType())
                            && !"invoice_checked".equals(s.getSubType())
                            && !"invoice_unchecked".equals(s.getSubType()))
                    .collect(Collectors.toList());
        }
        if (CollUtil.isEmpty(subs)) {
            tp.setStepData("pending");
            return;
        }
        List<String> priority = List.of("error", "running", "pending", "waiting", "warn", "done");
        for (String worst : priority) {
            if (subs.stream().anyMatch(s -> worst.equals(s.getStatus()))) {
                tp.setStepData(worst);
                return;
            }
        }
        tp.setStepData("pending");
    }

    /** 判断步骤是否"通过"（done 或 warn），可以解锁后置步骤 */
    private boolean isStepPassed(String status) {
        return "done".equals(status) || "warn".equals(status);
    }

    /** 根据做账子任务重新聚合 stepBookkeep 状态，写入 tp（不保存） */
    private void recalcStepBookkeep(TaskPeriod tp) {
        if (tp.getId() == null) return;
        List<TaskBookkeepSub> subs = taskBookkeepSubRepository.findByTaskPeriodId(tp.getId()).stream()
                .filter(s -> !"skipped".equals(s.getStatus()))
                .collect(Collectors.toList());
        if (CollUtil.isEmpty(subs)) return;
        List<String> priority = List.of("error", "running", "pending", "warn", "done");
        for (String worst : priority) {
            for (TaskBookkeepSub sub : subs) {
                if (worst.equals(sub.getStatus())) {
                    tp.setStepBookkeep(worst);
                    return;
                }
            }
        }
        tp.setStepBookkeep("done");
    }

    /**
     * OC 发票等远程子任务全部完成后，自动执行 pending 的本地数据子任务（bank / salary）。
     * <p>
     * 触发条件：所有非 bank/salary 的子任务已处于终态（done/warn/error/skipped），
     * 而 bank 或 salary 仍为 pending。
     * <p>
     * 执行后重新聚合 stepData，若 data 步骤整体完成则发布事件以解锁后置步骤（bookkeep 等）。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void autoExecutePendingLocalDataSubs(Long accountSetId, String period) {
        log.info("autoExecutePendingLocalDataSubs 开始: accountSetId={}, period={}", accountSetId, period);
        TaskPeriod tp = taskPeriodRepository.findByAccountSetIdAndPeriod(accountSetId, period).orElse(null);
        if (tp == null) { log.warn("autoExecutePendingLocalDataSubs: TaskPeriod 不存在，退出"); return; }

        List<TaskDataSub> subs = taskDataSubRepository.findByTaskPeriodId(tp.getId());
        if (CollUtil.isEmpty(subs)) { log.warn("autoExecutePendingLocalDataSubs: 无子任务记录，退出"); return; }

        // 与 recalcStepData 保持一致的过滤逻辑
        // 1. 有明细发票记录时，忽略旧版笼统 "invoice" 记录
        boolean hasDetailedInvoice = subs.stream()
                .anyMatch(s -> "invoice_in".equals(s.getSubType()) || "invoice_out".equals(s.getSubType()));
        if (hasDetailedInvoice) {
            subs = subs.stream().filter(s -> !"invoice".equals(s.getSubType())).collect(Collectors.toList());
        }
        // 2. 小规模纳税人不需要 invoice_check / invoice_checked / invoice_unchecked
        AccountSets as = accountSetsRepository.findById(accountSetId).orElse(null);
        if (as != null && as.getVatType() == VatType.SMALL_SCALE) {
            subs = subs.stream()
                    .filter(s -> !"invoice_check".equals(s.getSubType())
                            && !"invoice_checked".equals(s.getSubType())
                            && !"invoice_unchecked".equals(s.getSubType()))
                    .collect(Collectors.toList());
        }

        // 判断所有 OC 子任务（非 bank/salary）是否已完成
        Set<String> localTypes = Set.of("bank", "salary");
        Set<String> terminalStatuses = Set.of("done", "warn", "error", "skipped");

        List<TaskDataSub> ocSubs = subs.stream()
                .filter(s -> !localTypes.contains(s.getSubType()) && !"tax_items".equals(s.getSubType()))
                .collect(Collectors.toList());
        ocSubs.forEach(s -> log.info("  OC sub: type={}, status={}", s.getSubType(), s.getStatus()));

        boolean allOcSubsDone = ocSubs.stream()
                .allMatch(s -> terminalStatuses.contains(s.getStatus()));
        if (!allOcSubsDone) { log.info("autoExecutePendingLocalDataSubs: OC子任务未全部完成，退出"); return; }
        log.info("autoExecutePendingLocalDataSubs: OC子任务全部完成，开始执行本地子任务");

        // 执行 pending 的 bank 子任务
        subs.stream().filter(s -> "bank".equals(s.getSubType()) && "pending".equals(s.getStatus())).findFirst()
                .ifPresent(bankSub -> {
                    OcTask ocTask = bankSub.getOcTaskId() != null
                            ? ocTaskRepository.findById(bankSub.getOcTaskId()).orElse(null) : null;
                    if (ocTask != null) {
                        ocTask.setStatus("running");
                        ocTaskRepository.save(ocTask);
                    }
                    try {
                        autoProcessBankFiles(accountSetId, period);
                    } catch (Exception e) {
                        log.warn("自动处理银行流水文件失败: accountSetId={}, error={}", accountSetId, e.getMessage());
                    }
                    try {
                        Map<String, Object> bankResult = taskBankParseService.execute(accountSetId);
                        String bankStatus = (String) bankResult.get("status");
                        if (ocTask != null) {
                            ocTask.setStatus("done".equals(bankStatus) || "warn".equals(bankStatus) ? "done" : "failed");
                            ocTask.setFinishedAt(LocalDateTime.now());
                            ocTask.setResultJson(JSON.toJSONString(bankResult));
                            ocTaskRepository.save(ocTask);
                        }
                        // 人工确认模式：执行完毕后改为 waiting
                        if ("done".equals(bankStatus) && isManualStep(accountSetId, "data.bank")) {
                            bankStatus = "waiting";
                        }
                        updateSubTaskStatusWithOcTask(tp.getId(), "bank", bankStatus,
                                (String) bankResult.get("msg"), ocTask != null ? ocTask.getId() : null);
                        log.info("银行流水自动执行完成: accountSetId={}, status={}", accountSetId, bankStatus);
                    } catch (Exception e) {
                        log.error("银行流水自动执行异常: accountSetId={}", accountSetId, e);
                        if (ocTask != null) {
                            ocTask.setStatus("failed");
                            ocTask.setFinishedAt(LocalDateTime.now());
                            ocTaskRepository.save(ocTask);
                        }
                        updateSubTaskStatusWithOcTask(tp.getId(), "bank", "error",
                                "执行异常：" + e.getMessage(), ocTask != null ? ocTask.getId() : null);
                    }
                });

        // 执行 pending 的 salary 子任务
        subs.stream().filter(s -> "salary".equals(s.getSubType()) && "pending".equals(s.getStatus())).findFirst()
                .ifPresent(salarySub -> {
                    OcTask ocTask = salarySub.getOcTaskId() != null
                            ? ocTaskRepository.findById(salarySub.getOcTaskId()).orElse(null) : null;
                    if (ocTask != null) {
                        ocTask.setStatus("running");
                        ocTaskRepository.save(ocTask);
                    }
                    try {
                        Map<String, Object> salaryResult = taskSalaryService.execute(accountSetId, period);
                        String salaryStatus = (String) salaryResult.get("status");
                        if (ocTask != null) {
                            ocTask.setStatus("done".equals(salaryStatus) || "warn".equals(salaryStatus) ? "done" : "failed");
                            ocTask.setFinishedAt(LocalDateTime.now());
                            ocTask.setResultJson(JSON.toJSONString(salaryResult));
                            ocTaskRepository.save(ocTask);
                        }
                        // 人工确认模式：执行完毕后改为 waiting
                        if ("done".equals(salaryStatus) && isManualStep(accountSetId, "data.salary")) {
                            salaryStatus = "waiting";
                        }
                        updateSubTaskStatusWithOcTask(tp.getId(), "salary", salaryStatus,
                                (String) salaryResult.get("msg"), ocTask != null ? ocTask.getId() : null);
                        log.info("工资自动执行完成: accountSetId={}, status={}", accountSetId, salaryStatus);
                    } catch (Exception e) {
                        log.error("工资自动执行异常: accountSetId={}", accountSetId, e);
                        if (ocTask != null) {
                            ocTask.setStatus("failed");
                            ocTask.setFinishedAt(LocalDateTime.now());
                            ocTaskRepository.save(ocTask);
                        }
                        updateSubTaskStatusWithOcTask(tp.getId(), "salary", "error",
                                "执行异常：" + e.getMessage(), ocTask != null ? ocTask.getId() : null);
                    }
                });

        // 重新聚合 stepData 并检查是否可以解锁后置步骤
        recalcStepData(tp);
        taskPeriodRepository.save(tp);
        if (isStepPassed(tp.getStepData())) {
            publishStepCompleted(accountSetId, period, "data");
        }
    }

    /**
     * 单账套全量下发（类似批量下发，但只针对一个账套）。
     * <p>
     * 依次下发所有步骤：verify、data（全量子任务）、bookkeep、vat、iit、payment。
     * 有前置约束的步骤会自动变为 blocked，待前置完成后由 autoDispatchBlockedDependents 链式解锁。
     * 需要登录凭证但未配置默认办税员时，对应步骤跳过并记录到 errors。
     *
     * @return { success, dispatched, skipped, errors }
     */
    public Map<String, Object> dispatchAll(Long accountSetId, String period) {
        String p = resolvePeriod(period);
        AccountSets as = accountSetsRepository.findById(accountSetId)
                .orElseThrow(() -> new IllegalArgumentException("账套不存在：" + accountSetId));

        boolean hasOfficer = taxOfficerRepository.existsByAccountSetId(accountSetId);
        String loginType = StrUtil.isBlank(as.getLoginType()) ? "officer" : as.getLoginType();
        boolean hasCredential = hasOfficer || "ca".equals(loginType);

        List<String> dispatched = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        // 检查账期是否已结账：已结账则做账前步骤标记跳过、做账标记完成，后置任务直接下发（无需等待做账）
        TaskPeriod tpCheck = getOrCreateTaskPeriod(accountSetId, p, as);
        boolean alreadyCheckedOut = "done".equals(tpCheck.getStepBookkeep())
                && "skipped".equals(tpCheck.getStepData());

        if (alreadyCheckedOut) {
            // 前置步骤已由 applyCheckoutedStatusIfNeeded 处理，直接跳过，不再重复下发
            skipped.add("verify（账期已结账）");
            skipped.add("init（账期已结账）");
            skipped.add("data（账期已结账）");
            skipped.add("bookkeep（账期已结账，已标记完成）");
            log.info("全量下发：账期已结账，跳过做账前步骤: accountSetId={}, period={}", accountSetId, p);
        } else {
            // 每个步骤通过 self 调用，各自独立事务，一个失败不影响其他
            // 1. 核验税务
            if (hasCredential) {
                try {
                    self.dispatch(accountSetId, p, "verify", false);
                    dispatched.add("verify");
                } catch (Exception e) {
                    errors.add("verify: " + e.getMessage());
                }
            } else {
                skipped.add("verify（无登录凭证）");
            }

            // 2. 获取数据
            try {
                self.dispatch(accountSetId, p, "data", false);
                dispatched.add("data");
            } catch (Exception e) {
                errors.add("data: " + e.getMessage());
            }

            // 3. 做账（前置 data 未完成 → blocked，已满足则事务外执行做账）
            try {
                Map<String, Object> bkResult = self.dispatch(accountSetId, p, "bookkeep", false);
                executeBookkeepAfterDispatch(bkResult);
                dispatched.add("bookkeep");
            } catch (Exception e) {
                errors.add("bookkeep: " + e.getMessage());
            }
        }

        // 4. 国税申报（已结账时 bookkeep=done，不会被锁；未结账时前置 bookkeep 未完成 → blocked）
        if (hasCredential) {
            try {
                self.dispatch(accountSetId, p, "vat", false);
                dispatched.add("vat");
            } catch (Exception e) {
                errors.add("vat: " + e.getMessage());
            }
        } else {
            skipped.add("vat（无登录凭证）");
        }

        // 5. 个税申报
        if (hasCredential) {
            try {
                self.dispatch(accountSetId, p, "iit", false);
                dispatched.add("iit");
            } catch (Exception e) {
                errors.add("iit: " + e.getMessage());
            }
        } else {
            skipped.add("iit（无登录凭证）");
        }

        // 6. 缴费（前置 vat 未完成 → blocked）
        if (hasCredential) {
            try {
                self.dispatch(accountSetId, p, "payment", false);
                dispatched.add("payment");
            } catch (Exception e) {
                errors.add("payment: " + e.getMessage());
            }
        } else {
            skipped.add("payment（无登录凭证）");
        }

        log.info("全量下发完成: accountSetId={}, period={}, dispatched={}, skipped={}", accountSetId, p, dispatched, skipped);
        return Map.of("success", true, "dispatched", dispatched, "skipped", skipped, "errors", errors);
    }

    /**
     * 前置步骤完成后，将已手动下发的 blocked 后置步骤解锁。
     * <p>
     * 核心原则：不创建新的 oc_task，只解锁下发阶段已创建的 blocked 任务。
     * 如果某步骤未被手动下发（无 blocked 标记），则不执行。
     * <p>
     * OC 任务（vat/payment）：blocked → queued，等待 OC 轮询领取。
     * 本地任务（bookkeep）：blocked → running，直接执行做账逻辑。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void autoDispatchBlockedDependents(Long accountSetId, String period, String completedStep) {
        TaskPeriod tp = taskPeriodRepository.findByAccountSetIdAndPeriod(accountSetId, period).orElse(null);
        if (tp == null) return;

        switch (completedStep) {
            case "data" -> {
                // bookkeep 是本地任务，解锁后直接执行
                if ("blocked".equals(tp.getStepBookkeep())) {
                    log.info("前置 data 已完成，自动执行做账: accountSetId={}", accountSetId);
                    try {
                        Map<String, Object> bkResult = self.dispatchInNewTx(accountSetId, period, "bookkeep", false);
                        executeBookkeepAfterDispatch(bkResult);
                    } catch (Exception e) {
                        log.error("自动执行做账失败: accountSetId={}", accountSetId, e);
                        tp = taskPeriodRepository.findByAccountSetIdAndPeriod(accountSetId, period).orElse(tp);
                        tp.setStepBookkeep("error");
                        tp.setErrorStep("step_bookkeep");
                        tp.setErrorMsg("自动执行失败：" + e.getMessage());
                        taskPeriodRepository.save(tp);
                    }
                }
            }
            case "bookkeep" -> {
                // vat 是 OC 任务：只解锁已有的 blocked oc_task → queued，不创建新任务
                tp = taskPeriodRepository.findByAccountSetIdAndPeriod(accountSetId, period).orElse(tp);
                if ("blocked".equals(tp.getStepTaxNational())) {
                    OcTask unlockedVat = unlockBlockedOcTask(tp.getId(), "vat_declare_post");
                    if (unlockedVat != null) {
                        tp.setStepTaxNational("running");
                        taskPeriodRepository.save(tp);
                        log.info("前置 bookkeep 已完成，解锁国税申报任务: accountSetId={}, ocTaskId={}", accountSetId, unlockedVat.getId());
                    } else {
                        log.warn("国税申报步骤为 blocked 但无对应 oc_task，跳过: accountSetId={}", accountSetId);
                    }
                }
                // iit 不依赖 bookkeep，无需在此解锁
            }
            case "vat" -> {
                // payment 是 OC 任务：只解锁已有的 blocked oc_task → queued
                tp = taskPeriodRepository.findByAccountSetIdAndPeriod(accountSetId, period).orElse(tp);
                String vatSt = tp.getStepTaxNational();
                boolean vatFinished = isTaxNationalPassed(vatSt) || "error".equals(vatSt) || "failed".equals(vatSt);
                if ("blocked".equals(tp.getStepPayment()) && vatFinished) {
                    OcTask unlockedPayment = unlockBlockedOcTask(tp.getId(), "query_payment_national");
                    if (unlockedPayment != null) {
                        tp.setStepPayment("running");
                        taskPeriodRepository.save(tp);
                        log.info("前置国税申报已完成，解锁缴费任务: accountSetId={}, ocTaskId={}", accountSetId, unlockedPayment.getId());
                    } else {
                        log.warn("缴费步骤为 blocked 但无对应 oc_task，跳过: accountSetId={}", accountSetId);
                    }
                }
            }
        }
    }

    /**
     * 判断指定步骤是否配置为人工确认模式。
     */
    /**
     * 创建 blocked 状态的 oc_task（前置任务未完成时的占位记录）。
     * 如果已存在同类型的 blocked 记录则跳过，避免重复创建。
     */
    /** 在独立事务中执行 dispatch，失败不影响调用方事务 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Map<String, Object> dispatchInNewTx(Long accountSetId, String period, String step, boolean force) {
        return dispatch(accountSetId, period, step, force);
    }

    /**
     * dispatch 返回后，如果是做账步骤，在事务外执行实际的做账逻辑。
     * dispatch 事务已提交（oc_task 已创建、step=running），此方法不在事务内，
     * 各子步骤（资产/发票/工资/银行/结转）各自独立事务。
     */
    public Map<String, Object> executeBookkeepAfterDispatch(Map<String, Object> dispatchResult) {
        if (!Boolean.TRUE.equals(dispatchResult.get("executeBookkeep"))) {
            return dispatchResult;
        }
        Long accountSetId = toLong(dispatchResult.get("accountSetId"));
        String period = (String) dispatchResult.get("period");
        String subType = (String) dispatchResult.get("subType");
        boolean force = Boolean.TRUE.equals(dispatchResult.get("force"));
        @SuppressWarnings("unchecked")
        Map<String, Long> ocTaskIds = (Map<String, Long>) dispatchResult.getOrDefault("ocTaskIds", Map.of());

        Map<String, Object> bookkeepResult;
        try {
            bookkeepResult = taskBookkeepService.execute(accountSetId, period, subType, force);
        } catch (Exception e) {
            log.error("做账执行异常: accountSetId={}, period={}", accountSetId, period, e);
            bookkeepResult = Map.of("status", "error", "msg", "做账执行异常：" + e.getMessage(), "detail", List.of());
        }
        // 无论成功还是失败，都更新 step 和 oc_task 状态（兜底，防止永远卡在 running）
        try {
            self.updateBookkeepResult(accountSetId, period, bookkeepResult, ocTaskIds);
        } catch (Exception e) {
            log.error("更新做账结果失败: accountSetId={}, period={}", accountSetId, period, e);
        }

        return Map.of("success", true,
                "status", bookkeepResult.getOrDefault("status", "error"),
                "msg", bookkeepResult.getOrDefault("msg", ""),
                "detail", bookkeepResult.getOrDefault("detail", List.of()));
    }

    /** 做账执行完后，短事务更新 step 状态和 oc_task 结果 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @SuppressWarnings("unchecked")
    public void updateBookkeepResult(Long accountSetId, String period,
                                     Map<String, Object> bookkeepResult, Map<String, Long> ocTaskIds) {
        TaskPeriod tp = taskPeriodRepository.findByAccountSetIdAndPeriod(accountSetId, period).orElse(null);
        if (tp == null) return;

        List<Map<String, Object>> detail = (List<Map<String, Object>>) bookkeepResult.getOrDefault("detail", List.of());
        // 按 ocTaskIds 精确更新对应的 oc_task（避免误更新历史记录）
        for (Map<String, Object> d : detail) {
            String rawType = (String) d.get("type");
            String status = (String) d.get("status");
            if (rawType == null) continue;
            // result detail 的 type（如 asset_depreciation）映射到 ocTaskIds 的 key（如 asset）
            String mappedType = switch (rawType) {
                case "asset_depreciation" -> "asset";
                default -> rawType; // invoice/salary/bank/carry_forward 无需映射
            };
            // 优先按 dispatch 返回的 ID 精确查找
            Long ocTaskId = ocTaskIds.get(mappedType);
            Optional<OcTask> opt = ocTaskId != null
                    ? ocTaskRepository.findById(ocTaskId)
                    : ocTaskRepository.findTopByAccountSetIdAndTaskTypeAndPeriodOrderByCreatedAtDesc(
                            accountSetId, "bookkeep_" + mappedType, period);
            opt.ifPresent(oc -> {
                oc.setStatus("done".equals(status) || "warn".equals(status) ? "done" : "failed");
                oc.setFinishedAt(LocalDateTime.now());
                oc.setResultJson(JSON.toJSONString(d));
                ocTaskRepository.save(oc);
            });
        }
        // 如果 detail 为空（异常情况），将所有 running 的 bookkeep oc_task 标记为 failed
        if (detail.isEmpty()) {
            for (Long ocId : ocTaskIds.values()) {
                ocTaskRepository.findById(ocId).ifPresent(oc -> {
                    if ("running".equals(oc.getStatus())) {
                        oc.setStatus("failed");
                        oc.setFinishedAt(LocalDateTime.now());
                        oc.setResultJson(JSON.toJSONString(bookkeepResult));
                        ocTaskRepository.save(oc);
                    }
                });
            }
        }

        // 更新 task_bookkeep_sub 记录（前端读取的是这个表）
        Map<String, OcTask> bookkeepOcTasks = new LinkedHashMap<>();
        for (Map<String, Object> d : detail) {
            String rt = (String) d.get("type");
            if (rt == null) continue;
            String mt = "asset_depreciation".equals(rt) ? "asset" : rt;
            Long oid = ocTaskIds.get(mt);
            if (oid != null) ocTaskRepository.findById(oid).ifPresent(oc -> bookkeepOcTasks.put(mt, oc));
        }
        saveBookkeepSubResults(tp.getId(), detail, bookkeepOcTasks);

        recalcStepBookkeep(tp);
        if (tp.getStepBookkeep() == null || "running".equals(tp.getStepBookkeep())) {
            tp.setStepBookkeep((String) bookkeepResult.get("status"));
        }
        if ("error".equals(tp.getStepBookkeep())) {
            tp.setErrorStep("step_bookkeep");
            tp.setErrorMsg((String) bookkeepResult.get("msg"));
        }
        // ── 做账完成后检查资产负债表是否平衡（优先于人工确认，不平衡直接标记异常）──
        if (isStepPassed(tp.getStepBookkeep())) {
            try {
                YearMonth ym = YearMonth.parse(period, PERIOD_FMT);
                LocalDate checkDate = ym.atDay(1);
                var trialBalance = balanceService.trialBalance(accountSetId, checkDate);
                if (!trialBalance.isFlat()) {
                    String balanceDetail = String.format("期末借方合计: %s，期末贷方合计: %s，差额: %s",
                            trialBalance.getDebitTotal(), trialBalance.getCreditTotal(), trialBalance.getDiffTotal());
                    log.warn("资产负债表不平衡: accountSetId={}, period={}, {}", accountSetId, period, balanceDetail);
                    tp.setStepBookkeep("error");
                    tp.setErrorStep("step_bookkeep");
                    tp.setErrorMsg("资产负债表不平衡（" + balanceDetail + "），请人工处理后点击异常解除");
                    String redisKey = "BOOKKEEP_BLOCK:" + accountSetId + ":" + period;
                    redisUtil.set(redisKey, "unbalanced", 600);
                }
            } catch (Exception e) {
                log.error("资产负债表检查失败（不阻塞流程）: accountSetId={}", accountSetId, e);
            }
        }
        // ── 做账完成后检查是否存在未生成凭证的发票（优先于人工确认，存在则直接标记异常）──
        if (isStepPassed(tp.getStepBookkeep())) {
            try {
                YearMonth ym = YearMonth.parse(period, PERIOD_FMT);
                LocalDate periodStart = ym.atDay(1);
                LocalDate periodEnd = ym.atEndOfMonth();
                QInvoice qInv = QInvoice.invoice;
                long unmatchedCount = bqf.selectFrom(qInv)
                        .where(qInv.accountSetsId.eq(accountSetId)
                                .and(qInv.assignmentDate.between(periodStart, periodEnd)
                                        .or(qInv.assignmentDate.isNull()
                                                .and(qInv.tradeDate.between(periodStart, periodEnd))))
                                .and(qInv.voucherId.isNull())
                                .and(qInv.state.ne(InvoiceState.作废)))
                        .fetchCount();
                if (unmatchedCount > 0) {
                    log.warn("存在未生成凭证的发票: accountSetId={}, period={}, count={}", accountSetId, period, unmatchedCount);
                    tp.setStepBookkeep("error");
                    tp.setErrorStep("step_bookkeep");
                    tp.setErrorMsg(unmatchedCount + " 张发票未生成凭证，请处理后点击异常解除");
                }
            } catch (Exception e) {
                log.error("发票凭证检查失败（不阻塞流程）: accountSetId={}", accountSetId, e);
            }
        }

        // 人工确认模式（仅平衡通过后才进入，不平衡已标记 error 不会进入此分支）
        if (isStepPassed(tp.getStepBookkeep()) && isManualStep(accountSetId, "bookkeep")) {
            tp.setStepBookkeep("waiting");
        }

        taskPeriodRepository.save(tp);

        if (isStepPassed(tp.getStepBookkeep())) {
            publishStepCompleted(accountSetId, period, "bookkeep");
        }
        log.info("做账结果已更新: accountSetId={}, stepBookkeep={}", accountSetId, tp.getStepBookkeep());
    }

    /**
     * 异常解除：用户手动修复资产负债表后，重新检查平衡并解锁后置任务。
     */
    @Transactional
    public Map<String, Object> resolveBookkeepError(Long accountSetId, String period) {
        String p = resolvePeriod(period);
        TaskPeriod tp = taskPeriodRepository.findByAccountSetIdAndPeriod(accountSetId, p)
                .orElseThrow(() -> new IllegalArgumentException("任务周期不存在"));

        if (!"error".equals(tp.getStepBookkeep())) {
            return Map.of("success", false, "msg", "当前做账步骤状态非异常，无需解除");
        }

        // 重新检查资产负债表
        YearMonth ym = YearMonth.parse(p, PERIOD_FMT);
        LocalDate checkDate = ym.atDay(1);
        var trialBalance = balanceService.trialBalance(accountSetId, checkDate);

        if (!trialBalance.isFlat()) {
            // 仍不平衡，刷新 Redis TTL
            String redisKey = "BOOKKEEP_BLOCK:" + accountSetId + ":" + p;
            redisUtil.set(redisKey, "unbalanced", 600);
            log.info("异常解除失败，资产负债表仍不平衡: accountSetId={}, period={}", accountSetId, p);
            return Map.of("success", false, "msg", "资产负债表仍不平衡，请继续处理");
        }

        // 资产负债表平衡后，继续检查是否仍有未生成凭证的发票
        LocalDate periodStart = ym.atDay(1);
        LocalDate periodEnd = ym.atEndOfMonth();
        QInvoice qInv = QInvoice.invoice;
        long unmatchedCount = bqf.selectFrom(qInv)
                .where(qInv.accountSetsId.eq(accountSetId)
                        .and(qInv.assignmentDate.between(periodStart, periodEnd)
                                .or(qInv.assignmentDate.isNull()
                                        .and(qInv.tradeDate.between(periodStart, periodEnd))))
                        .and(qInv.voucherId.isNull())
                        .and(qInv.state.ne(InvoiceState.作废)))
                .fetchCount();
        if (unmatchedCount > 0) {
            log.info("异常解除失败，仍有 {} 张发票未生成凭证: accountSetId={}, period={}", unmatchedCount, accountSetId, p);
            return Map.of("success", false, "msg", unmatchedCount + " 张发票未生成凭证，请处理后再试");
        }

        // 平衡 → 解除异常
        tp.setStepBookkeep("done");
        tp.setErrorStep(null);
        tp.setErrorMsg(null);

        // 恢复被级联失败的后置步骤和 oc_task（error → blocked，failed → blocked）
        if ("error".equals(tp.getStepTaxNational())) {
            // 检查是否有对应的 oc_task（说明用户下发过该步骤）
            List<OcTask> failedVatTasks = ocTaskRepository.findByAccountSetIdAndPeriodAndTaskTypeIn(
                    accountSetId, p, List.of("vat_declare_post"));
            boolean wasDispatched = failedVatTasks.stream().anyMatch(t -> "failed".equals(t.getStatus()));
            if (wasDispatched) {
                tp.setStepTaxNational("blocked");
                failedVatTasks.stream().filter(t -> "failed".equals(t.getStatus())).forEach(t -> {
                    t.setStatus("blocked");
                    t.setFinishedAt(null);
                    ocTaskRepository.save(t);
                });
                log.info("恢复国税申报任务: accountSetId={}, blocked", accountSetId);
            }
        }
        if ("error".equals(tp.getStepPayment())) {
            List<OcTask> failedPaymentTasks = ocTaskRepository.findByAccountSetIdAndPeriodAndTaskTypeIn(
                    accountSetId, p, List.of("query_payment_national", "query_payment_iit"));
            boolean wasDispatched = failedPaymentTasks.stream().anyMatch(t -> "failed".equals(t.getStatus()));
            if (wasDispatched) {
                tp.setStepPayment("blocked");
                failedPaymentTasks.stream().filter(t -> "failed".equals(t.getStatus())).forEach(t -> {
                    t.setStatus("blocked");
                    t.setFinishedAt(null);
                    ocTaskRepository.save(t);
                });
                log.info("恢复缴费任务: accountSetId={}, blocked", accountSetId);
            }
        }

        taskPeriodRepository.save(tp);

        // 删除 Redis key
        String redisKey = "BOOKKEEP_BLOCK:" + accountSetId + ":" + p;
        redisUtil.del(redisKey);

        // 解锁后置步骤
        publishStepCompleted(accountSetId, p, "bookkeep");

        log.info("异常解除成功，资产负债表已平衡: accountSetId={}, period={}", accountSetId, p);
        return Map.of("success", true, "msg", "资产负债表已平衡，后置任务已解锁");
    }

    private void createBlockedOcTask(TaskPeriod tp, Long accountSetId, String period,
                                     String taskType, String ocInstanceId, String payloadJson) {
        if (ocTaskRepository.findFirstByTaskPeriodIdAndTaskTypeAndStatus(tp.getId(), taskType, "blocked").isPresent()) {
            return; // 已存在 blocked 记录，不重复创建
        }
        OcTask ocTask = new OcTask();
        ocTask.setAccountSetId(accountSetId);
        ocTask.setPeriod(period);
        ocTask.setTaskType(taskType);
        ocTask.setStatus("blocked");
        ocTask.setTaskPeriodId(tp.getId());
        ocTask.setOcInstanceId(ocInstanceId);
        if (payloadJson != null) ocTask.setPayloadJson(payloadJson);
        ocTaskRepository.save(ocTask);
    }

    /**
     * 解锁指定 taskPeriod 下指定类型的 blocked oc_task → queued。
     * 返回解锁的 oc_task（如果存在），否则返回 null。
     */
    private OcTask unlockBlockedOcTask(Long taskPeriodId, String taskType) {
        return ocTaskRepository.findFirstByTaskPeriodIdAndTaskTypeAndStatus(taskPeriodId, taskType, "blocked")
                .map(t -> { t.setStatus("queued"); return ocTaskRepository.save(t); })
                .orElse(null);
    }

    private boolean isManualStep(Long accountSetId, String configKey) {
        return accountSetsRepository.findById(accountSetId)
                .map(as -> "manual".equals(resolveStepConfig(as).get(configKey)))
                .orElse(false);
    }

    private void updateSubTaskStatus(Long taskPeriodId, String subType, String status) {
        TaskDataSub sub = taskDataSubRepository
                .findByTaskPeriodIdAndSubType(taskPeriodId, subType)
                .orElseGet(() -> {
                    TaskDataSub s = new TaskDataSub();
                    s.setTaskPeriodId(taskPeriodId);
                    s.setSubType(subType);
                    return s;
                });
        sub.setStatus(status);
        taskDataSubRepository.save(sub);
    }

    private void updateSubTaskStatusWithResult(Long taskPeriodId, String subType, String status, String msg) {
        TaskDataSub sub = taskDataSubRepository
                .findByTaskPeriodIdAndSubType(taskPeriodId, subType)
                .orElseGet(() -> {
                    TaskDataSub s = new TaskDataSub();
                    s.setTaskPeriodId(taskPeriodId);
                    s.setSubType(subType);
                    return s;
                });
        sub.setStatus(status);
        sub.setExecutedAt(LocalDateTime.now());
        if ("error".equals(status) && StrUtil.isNotBlank(msg)) {
            sub.setErrorMsg(msg);
        } else {
            sub.setErrorMsg(null);
            sub.setResultJson(JSON.toJSONString(Map.of("msg", StrUtil.nullToEmpty(msg))));
        }
        taskDataSubRepository.save(sub);
    }

    /** 更新 task_data_sub 状态并写入 oc_task_id（内部任务用） */
    private void updateSubTaskStatusWithOcTask(Long taskPeriodId, String subType, String status, String msg, Long ocTaskId) {
        TaskDataSub sub = taskDataSubRepository
                .findByTaskPeriodIdAndSubType(taskPeriodId, subType)
                .orElseGet(() -> {
                    TaskDataSub s = new TaskDataSub();
                    s.setTaskPeriodId(taskPeriodId);
                    s.setSubType(subType);
                    return s;
                });
        sub.setStatus(status);
        sub.setOcTaskId(ocTaskId);
        sub.setExecutedAt(LocalDateTime.now());
        if ("error".equals(status) && StrUtil.isNotBlank(msg)) {
            sub.setErrorMsg(msg);
        } else {
            sub.setErrorMsg(null);
            sub.setResultJson(JSON.toJSONString(Map.of("msg", StrUtil.nullToEmpty(msg))));
        }
        taskDataSubRepository.save(sub);
    }

    private String stepToOcTaskType(String step) {
        return switch (step) {
            // verify 由 dispatchVerifyStep 处理（4个并行子任务）；
            // iit 由 dispatchIitStep 处理（按 tax_declare 税种拆分）；
            // payment 由 dispatchPaymentStep 处理（national + iit 两条）；
            // data 子任务由 dataSubTypeToOcTaskType 处理；
            // vat 由 dispatchVatStep 直接创建 vat_declare；
            // bank/salary/bookkeep/init 为内部任务
            default -> null;
        };
    }

    /**
     * 构建 OcTask 的 payload_json。
     * <p>
     * 登录渠道优先使用账套级 {@code loginType}（在"任务工作台"中配置），
     * 凭证（手机号 / 密码 / 身份证）仍从该账套的默认办税员记录读取。
     * 若无默认办税员，回退使用账套旧字段（officer 方式）。
     */
    /**
     * 同 buildPayload，但返回 Map 以便调用方追加额外字段后再序列化。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> buildPayloadMap(AccountSets as, String period, String ocInstanceId, String taskType) {
        return JSON.parseObject(buildPayload(as, period, ocInstanceId, taskType), java.util.LinkedHashMap.class);
    }

    private String buildPayload(AccountSets as, String period, String ocInstanceId, String taskType) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tax_no", as.getCreditCode());
        payload.put("province", as.getProvince());
        payload.put("period", period);
        payload.put("vat_type", as.getVatType() != null ? as.getVatType().display : null);

        // 凭证从默认办税员读取，不再回退账套旧字段
        String accountLoginType = StrUtil.isBlank(as.getLoginType()) ? "officer" : as.getLoginType();

        TaxOfficer defaultOfficer = taxOfficerRepository.findByAccountSetIdAndDefaultOfficerTrue(as.getId())
                .orElse(null);
        if (defaultOfficer != null) {
            applyLoginCredentials(payload, defaultOfficer, accountLoginType);
        }

        // 个税类任务附带个税密码
        if ("iit_declare".equals(taskType)) {
            payload.put("iit_password", as.getIndividualTaxCode());
        }

        if (StrUtil.isNotBlank(ocInstanceId)) {
            payload.put("oc_instance_id", ocInstanceId);
        }
        return JSON.toJSONString(payload);
    }

    /**
     * 将登录凭证写入 payload。
     * <p>
     * 登录渠道（loginType）由账套级配置决定，凭证字段从办税员记录读取。
     *
     * @param accountLoginType 账套级登录渠道（officer/proxy/legal_person/ca）
     */
    private void applyLoginCredentials(Map<String, Object> payload, TaxOfficer officer, String accountLoginType) {
        String loginType = StrUtil.isBlank(accountLoginType) ? "officer" : accountLoginType;
        // 归一化旧值
        if ("password".equals(loginType)) loginType = "officer";

        payload.put("login_type", loginType);
        payload.put("officer_name", officer.getName());

        switch (loginType) {
            case "officer" -> {
                // 办税员账号+密码登录
                payload.put("phone", officer.getPhone());
                payload.put("password", officer.getPassword());
            }
            case "proxy" -> {
                // 代理通道：phone/password 存代理平台账号密码
                payload.put("proxy_account", officer.getPhone());
                payload.put("proxy_password", officer.getPassword());
                payload.put("proxy_tax_no", officer.getProxyTaxNo());
            }
            case "legal_person" -> {
                // 法人信息登录：姓名 + 身份证 + 密码
                payload.put("legal_name", officer.getName());
                payload.put("id_card", officer.getIdCard());
                payload.put("password", officer.getPassword());
            }
            case "ca" -> {
                // CA 证书：凭证由 OpenClaw 本地加载，仅传类型
            }
            default -> {
                // 未知类型，兜底传 phone/password
                payload.put("phone", officer.getPhone());
                payload.put("password", officer.getPassword());
            }
        }
    }

    /**
     * 解析电税登录凭证为 snake_case payload（供 OpenApiEtaxLoginController 单一真源使用）。
     * <p>与核验/报税 RPA 同一套：账套级 {@code loginType}（空→officer）决定渠道，凭证字段由默认办税员
     * 经 {@link #applyLoginCredentials} 写入，保证与 buildPayload 逐字同源。返回 map 含
     * {@code login_type/officer_name} + 随渠道的凭证字段（phone、password、proxy_account、proxy_password、legal_name、id_card 等）。
     */
    public Map<String, Object> resolveEtaxLoginCredentials(AccountSets as, TaxOfficer officer) {
        Map<String, Object> payload = new LinkedHashMap<>();
        String accountLoginType = StrUtil.isBlank(as.getLoginType()) ? "officer" : as.getLoginType();
        applyLoginCredentials(payload, officer, accountLoginType);
        return payload;
    }

    /**
     * 从任务 payload_json 中提取登录凭证值，用于操作日志次数判断。
     * 根据 login_type 对应不同字段：officer/enterprise→phone，proxy→proxy_account，legal_person→id_card
     */
    private String extractLoginCredential(String payloadJson) {
        return extractLoginCredentialByType(payloadJson, null);
    }

    /**
     * 从 payload_json 提取登录凭证值。
     * <p>
     * loginTypeHint 为调用方指定的凭证类型（officer / enterprise / proxy / legal_person）；
     * 为 null 时自动从 payload.login_type 推断。
     * officer 和 enterprise 均视为同一类型（都使用 phone 字段）。
     * <ul>
     *   <li>officer / enterprise → phone</li>
     *   <li>proxy              → proxy_account</li>
     *   <li>legal_person       → id_card</li>
     * </ul>
     */
    private String extractLoginCredentialByType(String payloadJson, String loginTypeHint) {
        if (StrUtil.isBlank(payloadJson)) return null;
        try {
            Map<String, Object> payload = JSON.parseObject(payloadJson,
                    new com.alibaba.fastjson2.TypeReference<Map<String, Object>>() {});
            // 优先使用调用方提供的类型；未提供时从 payload 自动推断
            String lt = StrUtil.isNotBlank(loginTypeHint) ? loginTypeHint : (String) payload.get("login_type");
            String field = switch (StrUtil.nullToEmpty(lt)) {
                case "officer", "enterprise" -> "phone";
                case "proxy"                 -> "proxy_account";
                case "legal_person"          -> "id_card";
                default                      -> null;
            };
            return field != null ? (String) payload.get(field) : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 从 payload_json 提取省份，与 oc_operation_log.province 格式一致（英文） */
    private String extractPayloadProvince(String payloadJson) {
        if (StrUtil.isBlank(payloadJson)) return null;
        try {
            Map<String, Object> payload = JSON.parseObject(payloadJson,
                    new com.alibaba.fastjson2.TypeReference<Map<String, Object>>() {});
            return (String) payload.get("province");
        } catch (Exception e) {
            return null;
        }
    }

    /** payload_json 为空或解析失败时，从默认办税员读取登录凭证 */
    private void applyLoginFallback(Map<String, Object> info, AccountSets as) {
        String accountLoginType = StrUtil.isBlank(as.getLoginType()) ? "officer" : as.getLoginType();
        taxOfficerRepository.findByAccountSetIdAndDefaultOfficerTrue(as.getId())
                .ifPresent(officer -> applyLoginCredentials(info, officer, accountLoginType));
    }

    private void updateOcTask(Long taskId, String status, Map<String, Object> body) {
        if (taskId == null) return;
        ocTaskRepository.findById(taskId).ifPresent(oc -> {
            // partial_failed 表示任务已执行完毕（部分税种失败），oc_task 本身视为 done
            oc.setStatus("done".equals(status) || "partial_failed".equals(status) ? "done" : "failed");
            oc.setResultJson(JSON.toJSONString(body));
            oc.setFinishedAt(LocalDateTime.now());
            if (body.get("screenshot_url") != null) {
                oc.setScreenshotUrl((String) body.get("screenshot_url"));
            }
            ocTaskRepository.save(oc);
        });
    }

    /**
     * 手动上传发票 Excel，解析入库并将 invoice 子任务标记为完成。
     * <p>
     * 支持税务局导出格式（含"发票基础信息"Sheet）。
     * 用于 OC 无法自动下载时的人工补录，或 OC 回调前的提前录入。
     *
     * @param file         Excel 文件（.xlsx / .xls）
     * @param accountSetId 账套 ID
     * @param period       账期 YYYY-MM
     * @param invoiceType  发票类型标记，"开具发票" 时强制归为销售收入；空则智能匹配
     * @param importMode   导入模式：{@code "month"}（按月，默认）/ {@code "season"}（按季度）
     */
    @Transactional
    public Map<String, Object> uploadInvoice(MultipartFile file, Long accountSetId, String period,
                                             String invoiceType, String importMode) {
        String p = resolvePeriod(period);
        AccountSets as = accountSetsRepository.findById(accountSetId)
                .orElseThrow(() -> new IllegalArgumentException("账套不存在：" + accountSetId));
        String creditCode = as.getCreditCode();
        if (StrUtil.isBlank(creditCode)) {
            return Map.of("success", false, "msg", "账套未设置统一社会信用代码，无法判断发票进销项");
        }

        Map<String, String> invoiceTypeMap = new HashMap<>();
        if (StrUtil.isNotBlank(invoiceType)) {
            invoiceTypeMap.put("invoiceType", invoiceType);
        }

        List<InvoiceDto> imported;
        try {
            if ("season".equalsIgnoreCase(importMode)) {
                imported = invoiceImportService.saveImportInvoiceSeason(
                        creditCode, file.getInputStream(), accountSetId, invoiceTypeMap);
            } else {
                imported = invoiceImportService.saveImportInvoice(
                        creditCode, file.getInputStream(), accountSetId, invoiceTypeMap);
            }
        } catch (Exception e) {
            log.error("发票导入失败: accountSetId={}", accountSetId, e);
            return Map.of("success", false, "msg", "发票导入失败：" + e.getMessage());
        }

        int total = imported.size();
        long inputCount = imported.stream()
                .filter(d -> d.getType() != null && "IN".equals(d.getType().name())).count();
        long outputCount = total - inputCount;

        // 更新 task_data_sub invoice 状态为 done
        TaskPeriod tp = getOrCreateTaskPeriod(accountSetId, p, as);
        String subStatus = total > 0 ? "done" : "warn";
        String msg = total > 0
                ? String.format("已导入 %d 张（进项 %d / 销项 %d）", total, inputCount, outputCount)
                : "未导入任何发票，请检查文件格式或发票内容";

        TaskDataSub sub = taskDataSubRepository
                .findByTaskPeriodIdAndSubType(tp.getId(), "invoice")
                .orElseGet(() -> {
                    TaskDataSub s = new TaskDataSub();
                    s.setTaskPeriodId(tp.getId());
                    s.setSubType("invoice");
                    return s;
                });
        sub.setStatus(subStatus);
        sub.setExecutedAt(LocalDateTime.now());
        sub.setErrorMsg(null);
        sub.setResultJson(JSON.toJSONString(Map.of(
                "total", total,
                "input_count", inputCount,
                "output_count", outputCount,
                "msg", msg
        )));
        taskDataSubRepository.save(sub);

        // 重新聚合 step_data
        recalcStepData(tp);
        taskPeriodRepository.save(tp);
        // 无论 OC 子任务成功还是失败，都尝试触发本地任务（bank/salary）
        if (isStepPassed(tp.getStepData())) {
            publishStepCompleted(accountSetId, p, "data");
        } else {
            publishStepCompleted(tp.getAccountSetId(), tp.getPeriod(), "data_local");
        }

        log.info("发票上传完成: accountSetId={}, period={}, total={}", accountSetId, p, total);
        return Map.of(
                "success", true,
                "total", total,
                "inputCount", inputCount,
                "outputCount", outputCount,
                "msg", msg
        );
    }

    // ─────────────────────────────────────────────────────────────────────
    // OpenClaw 文件上传处理（RPA 推文件）
    // ─────────────────────────────────────────────────────────────────────

    /**
     * 保存未勾选发票。
     * 1. 先删除该账套该账期旧记录
     * 2. 解析 Excel，将每行存入 task_unchecked_invoice 表（不含明细）
     * 3. 同步到发票表：将匹配发票的 showStatus 更新为 invoiceType（通常为"未勾选"）
     *
     * Excel 列顺序（从 0 起）：
     *   0-序号  1-是否勾选  2-数电发票号码  3-发票代码  4-发票号码
     *   5-开票日期  6-金额  7-票面税额  8-有效抵扣税额  9-购买方识别号
     *   10-销售方名称  11-销售方识别号  12-发票来源  13-票种  14-发票状态
     */
    @Transactional
    public void saveUnCheckInvoice(java.io.InputStream fileStream, Long accountSetsId, String invoiceType, Long ocTaskId) {
        // 1. 清除该账套所有旧数据（重新下载时完整覆盖）
        taskUncheckedInvoiceRepository.deleteByAccountSetsId(accountSetsId);

        try {
            Workbook workbook = new XSSFWorkbook(fileStream);
            Sheet sheet = workbook.getSheetAt(0);
            DataFormatter fmt = new DataFormatter();
            List<TaskUncheckedInvoice> toSave = new ArrayList<>();

            for (Row row : sheet) {
                if (row.getRowNum() < 1) continue;

                String netInvoiceNumber = fmt.formatCellValue(row.getCell(2)).trim();
                String invoiceCode      = fmt.formatCellValue(row.getCell(3)).trim();
                String invoiceNumber    = fmt.formatCellValue(row.getCell(4)).trim();

                // 跳过两个号码都为空的行
                if (netInvoiceNumber.isEmpty() && invoiceNumber.isEmpty()) continue;

                // 2. 存入专用表
                TaskUncheckedInvoice entity = new TaskUncheckedInvoice();
                entity.setAccountSetsId(accountSetsId);
                entity.setOcTaskId(ocTaskId);
                entity.setNetInvoiceNumber(netInvoiceNumber.isEmpty() ? null : netInvoiceNumber);
                entity.setInvoiceCode(invoiceCode.isEmpty() ? null : invoiceCode);
                entity.setInvoiceNumber(invoiceNumber.isEmpty() ? null : invoiceNumber);
                entity.setBuyerTaxNo(fmt.formatCellValue(row.getCell(9)).trim());
                entity.setSellerName(fmt.formatCellValue(row.getCell(10)).trim());
                entity.setSellerTaxNo(fmt.formatCellValue(row.getCell(11)).trim());
                entity.setInvoiceSource(fmt.formatCellValue(row.getCell(12)).trim());
                entity.setInvoiceType(fmt.formatCellValue(row.getCell(13)).trim());
                entity.setInvoiceState(fmt.formatCellValue(row.getCell(14)).trim());
                entity.setRedLockFlag(fmt.formatCellValue(row.getCell(15)).trim());
                entity.setDomesticSalesCertNo(fmt.formatCellValue(row.getCell(16)).trim());
                entity.setBusinessType(fmt.formatCellValue(row.getCell(17)).trim());
                entity.setCheckTime(fmt.formatCellValue(row.getCell(18)).trim());
                entity.setRiskLevel(fmt.formatCellValue(row.getCell(19)).trim());
                entity.setRiskStatus(fmt.formatCellValue(row.getCell(20)).trim());
                entity.setDifferenceDeductionFlag(fmt.formatCellValue(row.getCell(21)).trim());
                entity.setShowStatus("unchecked");

                String tradeDateStr = fmt.formatCellValue(row.getCell(5)).trim();
                if (!tradeDateStr.isEmpty()) {
                    try { entity.setTradeDate(LocalDate.parse(tradeDateStr.substring(0, 10))); } catch (Exception ignore) {}
                }
                String amountStr      = fmt.formatCellValue(row.getCell(6)).trim();
                String taxStr         = fmt.formatCellValue(row.getCell(7)).trim();
                String deductibleStr  = fmt.formatCellValue(row.getCell(8)).trim();
                try { entity.setAmount(amountStr.isEmpty() ? BigDecimal.ZERO : new BigDecimal(amountStr)); } catch (Exception ignore) { entity.setAmount(BigDecimal.ZERO); }
                try { entity.setTaxAmount(taxStr.isEmpty() ? BigDecimal.ZERO : new BigDecimal(taxStr)); } catch (Exception ignore) { entity.setTaxAmount(BigDecimal.ZERO); }
                try { entity.setDeductibleTaxAmount(deductibleStr.isEmpty() ? BigDecimal.ZERO : new BigDecimal(deductibleStr)); } catch (Exception ignore) { entity.setDeductibleTaxAmount(BigDecimal.ZERO); }

                toSave.add(entity);

                // 3. 同步到发票表：用数电号码或发票号码匹配
                Invoice inv = new Invoice();
                inv.setAccountSetsId(accountSetsId);
                inv.setShowStatus(invoiceType);
                inv.setInvoiceNumber(netInvoiceNumber.isEmpty() ? invoiceNumber : netInvoiceNumber);
                inv.setInvoiceNumber1(invoiceNumber);
                checkInvoiceService.updateByInvoiceNumber(inv);
            }

            taskUncheckedInvoiceRepository.saveAll(toSave);
            log.info("saveUnCheckInvoice 完成: accountSetsId={}, ocTaskId={}, count={}", accountSetsId, ocTaskId, toSave.size());
        } catch (Exception e) {
            log.error("saveUnCheckInvoice 解析失败: accountSetsId={}, ocTaskId={}", accountSetsId, ocTaskId, e);
            throw new RuntimeException("未勾选发票保存失败：" + e.getMessage(), e);
        }
    }

    /**
     * OpenClaw 上传发票 Excel 文件（RPA 下载后直接推送）。
     * 解析文件 → 入库 → 更新 OcTask 为 done → 更新 task_data_sub invoice 状态。
     */
    @Transactional
    public Map<String, Object> ocUploadInvoice(MultipartFile file, Long taskId, Long accountSetId,
                                               String period, String importMode,
                                               Integer inputCount, Integer outputCount,
                                               String checkStatus, String errorMsg) {
        AccountSets as = accountSetsRepository.findById(accountSetId)
                .orElseThrow(() -> new IllegalArgumentException("账套不存在：" + accountSetId));
        String creditCode = as.getCreditCode();
        if (StrUtil.isBlank(creditCode)) {
            self.markOcTaskFailed(taskId,"账套未设置统一社会信用代码");
            return Map.of("success", false, "msg", "账套未设置统一社会信用代码");
        }

        List<InvoiceDto> imported;
        try {
            if ("season".equalsIgnoreCase(importMode)) {
                imported = invoiceImportService.saveImportInvoiceSeason(
                        creditCode, file.getInputStream(), accountSetId, Map.of());
            } else {
                imported = invoiceImportService.saveImportInvoice(
                        creditCode, file.getInputStream(), accountSetId, Map.of());
            }
        } catch (Exception e) {
            log.error("OC发票解析入库失败: taskId={}, accountSetId={}", taskId, accountSetId, e);
            self.markOcTaskFailed(taskId,"发票解析失败：" + e.getMessage());
            return Map.of("success", false, "msg", "发票解析失败：" + e.getMessage());
        }

        int total = imported.size();
        long actualInput = imported.stream()
                .filter(d -> d.getType() != null && "IN".equals(d.getType().name())).count();
        long actualOutput = total - actualInput;

        // 构建 resultJson
        Map<String, Object> resultData = new LinkedHashMap<>();
        resultData.put("total", total);
        resultData.put("input_count", inputCount != null ? inputCount : actualInput);
        resultData.put("output_count", outputCount != null ? outputCount : actualOutput);
        resultData.put("actual_imported", total);
        if (StrUtil.isNotBlank(checkStatus)) resultData.put("check_status", checkStatus);
        if (StrUtil.isNotBlank(errorMsg)) resultData.put("error_msg", errorMsg);
        resultData.put("status", "done");

        // 检查 OC 任务类型，确定对应的 task_data_sub.sub_type
        String[] ocTaskTypeHolder = { "invoice" }; // default
        ocTaskRepository.findById(taskId).ifPresent(oc -> {
            oc.setStatus("done");
            oc.setResultJson(JSON.toJSONString(resultData));
            oc.setFinishedAt(LocalDateTime.now());
            ocTaskRepository.save(oc);
            ocTaskTypeHolder[0] = switch (StrUtil.nullToDefault(oc.getTaskType(), "")) {
                case "download_invoice_in"        -> "invoice_in";
                case "download_invoice_out"       -> "invoice_out";
                case "download_invoice_checked"   -> "invoice_checked";
                case "download_invoice_unchecked" -> "invoice_unchecked";
                default                           -> "invoice";
            };
        });
        String dataSubType = ocTaskTypeHolder[0];

        // 若为未勾选发票任务，标记导入的发票 showStatus = 'unchecked'
        if ("invoice_unchecked".equals(dataSubType) && !imported.isEmpty()) {
            markInvoicesAsUnchecked(accountSetId, imported);
        }

        // 更新 task_data_sub
        TaskPeriod tp = getOrCreateTaskPeriod(accountSetId, period, as);
        String subStatus = total > 0 ? "done" : "warn";
        String msg = total > 0
                ? String.format("OC下载发票完成：共导入 %d 张（进项 %d / 销项 %d）", total, actualInput, actualOutput)
                : "OC下载完成但未解析到有效发票";

        TaskDataSub sub = taskDataSubRepository
                .findByTaskPeriodIdAndSubType(tp.getId(), dataSubType)
                .orElseGet(() -> {
                    TaskDataSub s = new TaskDataSub();
                    s.setTaskPeriodId(tp.getId());
                    s.setSubType(dataSubType);
                    return s;
                });
        sub.setStatus(subStatus);
        sub.setExecutedAt(LocalDateTime.now());
        sub.setErrorMsg(null);
        sub.setResultJson(JSON.toJSONString(resultData));
        taskDataSubRepository.save(sub);

        recalcStepData(tp);
        taskPeriodRepository.save(tp);
        // 无论 OC 子任务成功还是失败，都尝试触发本地任务（bank/salary）
        if (isStepPassed(tp.getStepData())) {
            publishStepCompleted(accountSetId, period, "data");
        } else {
            publishStepCompleted(tp.getAccountSetId(), tp.getPeriod(), "data_local");
        }
        log.info("OC发票上传完成: taskId={}, accountSetId={}, dataSubType={}, total={}", taskId, accountSetId, dataSubType, total);
        return Map.of("success", true, "total", total, "inputCount", actualInput,
                "outputCount", actualOutput, "msg", msg);
    }

    /**
     * OpenClaw 上传税务核验截图（仅更新任务状态和截图，无完整核验数据）。
     */
    @Transactional
    public Map<String, Object> ocUploadVerifyScreenshot(MultipartFile screenshot, Long taskId,
                                                        Long accountSetId, String period,
                                                        String status, boolean loginOk,
                                                        String taxStatus, String errorMsg) {
        List<String> refs = convertScreenshotsToBase64(List.of(screenshot));
        String screenshotRef = refs.isEmpty() ? null : refs.get(0);

        // 更新 OcTask 状态
        OcTask ocTask = ocTaskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("OcTask 不存在：" + taskId));
        ocTask.setStatus("done".equals(status) ? "done" : "failed");
        ocTask.setFinishedAt(LocalDateTime.now());
        if (screenshotRef != null) ocTask.setScreenshotUrl(screenshotRef);
        ocTaskRepository.save(ocTask);

        // 更新 task_period.step_verify
        TaskPeriod tp = taskPeriodRepository.findById(ocTask.getTaskPeriodId())
                .orElseThrow(() -> new IllegalArgumentException("TaskPeriod 不存在：" + ocTask.getTaskPeriodId()));
        if (!"done".equals(status) || !loginOk) {
            tp.setStepVerify("error");
            tp.setErrorStep("step_verify");
            tp.setErrorMsg(StrUtil.isBlank(errorMsg) ? "核验税务失败" : errorMsg);
        } else {
            // 截图上传接口无完整核验数据，标为 warn 待人工复核
            tp.setStepVerify("warn");
            tp.setErrorStep("step_verify");
            tp.setErrorMsg("核验截图已上传，请人工确认核验结果");
        }
        taskPeriodRepository.save(tp);

        log.info("OC核验截图处理完成: taskId={}, status={}, loginOk={}", taskId, status, loginOk);
        return Map.of("success", true, "msg", "核验截图已接收");
    }

    /** 单个截图文件大小上限：10MB */
    private static final long MAX_SCREENSHOT_SIZE = 10 * 1024 * 1024;

    /**
     * 将多个截图文件转为 Base64 data URI 列表。
     * 单个文件超过 10MB 则跳过并打印警告。
     * 供通用回调和独立上传接口复用。
     */
    public List<String> convertScreenshotsToBase64(List<MultipartFile> screenshots) {
        List<String> refs = new ArrayList<>();
        if (screenshots == null) return refs;
        for (MultipartFile file : screenshots) {
            if (file == null || file.isEmpty()) continue;
            try {
                byte[] bytes = file.getBytes();
                if (bytes.length > MAX_SCREENSHOT_SIZE) {
                    log.warn("截图文件过大({}MB)，超过10MB限制，跳过: fileName={}",
                            bytes.length / 1024 / 1024, file.getOriginalFilename());
                    continue;
                }
                refs.add("data:" + file.getContentType() + ";base64,"
                        + java.util.Base64.getEncoder().encodeToString(bytes));
            } catch (Exception e) {
                log.warn("截图文件读取失败，跳过: fileName={}", file.getOriginalFilename(), e);
            }
        }
        return refs;
    }

    /**
     * OpenClaw 个税申报截图上传 + 状态回写。
     * 支持多文件上传，单个文件不超过 10MB。
     * 接收截图文件后保存为 Base64，再复用 handleIitDeclareCallback 更新任务状态和申报明细。
     */
    @Transactional
    public Map<String, Object> ocUploadIitScreenshots(List<MultipartFile> screenshots, Long taskId,
                                                      Long accountSetId, String period,
                                                      String status, String iitType, String errorMsg) {
        List<String> screenshotRefs = convertScreenshotsToBase64(screenshots);

        // 组装回调 body，复用现有 handleIitDeclareCallback
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("task_id", taskId);
        body.put("account_set_id", accountSetId);
        body.put("period", period);
        body.put("status", status);
        body.put("iit_type", StrUtil.isBlank(iitType) ? "salary" : iitType);
        if (StrUtil.isNotBlank(errorMsg)) body.put("error_msg", errorMsg);
        if (!screenshotRefs.isEmpty()) body.put("screenshot_urls", screenshotRefs);

        handleIitDeclareCallback(body);
        log.info("OC个税截图处理完成: taskId={}, iitType={}, status={}, 截图数={}",
                taskId, iitType, status, screenshotRefs.size());
        return Map.of("success", true, "msg", "个税申报结果已接收", "screenshotCount", screenshotRefs.size());
    }

    /**
     * OpenClaw 以 JSON 格式上报发票数据（无需 Excel 文件）。
     * <p>
     * body 结构见 {@code OcCallbackController#ocImportInvoiceJson} 的 Javadoc。
     * 委托给 {@link InvoiceImportService#saveImportInvoiceJson} 完成解析入库，
     * 然后更新 OcTask & task_data_sub 状态。
     */
    @Transactional
    @SuppressWarnings("unchecked")
    public Map<String, Object> ocImportInvoiceJson(Long taskId, Long accountSetId,
                                                   String period, Map<String, Object> body) {
        String p = resolvePeriod(period);
        AccountSets as = accountSetsRepository.findById(accountSetId)
                .orElseThrow(() -> new IllegalArgumentException("账套不存在：" + accountSetId));
        String creditCode = as.getCreditCode();
        if (StrUtil.isBlank(creditCode)) {
            self.markOcTaskFailed(taskId,"账套未设置统一社会信用代码");
            return Map.of("success", false, "msg", "账套未设置统一社会信用代码");
        }

        String checkStatus = (String) body.get("check_status");
        String errorMsg    = (String) body.get("error_msg");
        List<Map<String, Object>> rawList = (List<Map<String, Object>>) body.getOrDefault("invoices", List.of());

        List<InvoiceDto> invoiceDtos;
        try {
            invoiceDtos = invoiceImportService.saveImportInvoiceJson(creditCode, accountSetId, rawList);
        } catch (Exception e) {
            log.error("OC发票JSON入库失败: taskId={}, accountSetId={}", taskId, accountSetId, e);
            self.markOcTaskFailed(taskId,"发票入库失败：" + e.getMessage());
            return Map.of("success", false, "msg", "发票入库失败：" + e.getMessage());
        }

        int total = invoiceDtos.size();
        long actualInput = invoiceDtos.stream()
                .filter(d -> d.getType() != null && "IN".equals(d.getType().name())).count();
        long actualOutput = total - actualInput;

        // 构建 resultJson
        Map<String, Object> resultData = new LinkedHashMap<>();
        resultData.put("total", total);
        resultData.put("input_count", actualInput);
        resultData.put("output_count", actualOutput);
        if (StrUtil.isNotBlank(checkStatus)) resultData.put("check_status", checkStatus);
        if (StrUtil.isNotBlank(errorMsg))    resultData.put("error_msg", errorMsg);
        resultData.put("status", "done");

        // 检查 OC 任务类型，确定对应的 task_data_sub.sub_type
        String dataSubType = "invoice"; // default
        if (taskId != null) {
            Optional<OcTask> ocTaskOpt = ocTaskRepository.findById(taskId);
            if (ocTaskOpt.isPresent()) {
                OcTask ot = ocTaskOpt.get();
                ot.setStatus("done");
                ot.setResultJson(JSON.toJSONString(resultData));
                ot.setFinishedAt(LocalDateTime.now());
                ocTaskRepository.save(ot);
                dataSubType = switch (StrUtil.nullToDefault(ot.getTaskType(), "")) {
                    case "download_invoice_in"        -> "invoice_in";
                    case "download_invoice_out"       -> "invoice_out";
                    case "download_invoice_checked"   -> "invoice_checked";
                    case "download_invoice_unchecked" -> "invoice_unchecked";
                    default                           -> "invoice";
                };
            }
        }

        // 若为未勾选发票任务，标记导入的发票 showStatus = 'unchecked'
        if ("invoice_unchecked".equals(dataSubType) && !invoiceDtos.isEmpty()) {
            markInvoicesAsUnchecked(accountSetId, invoiceDtos);
        }

        // 更新 task_data_sub
        TaskPeriod tp = getOrCreateTaskPeriod(accountSetId, p, as);
        String subStatus = total > 0 ? "done" : "warn";
        String msg = total > 0
                ? String.format("OC下载发票完成：共导入 %d 张（进项 %d / 销项 %d）", total, actualInput, actualOutput)
                : "OC上报完成但未解析到属于本账套的发票";

        final String finalDataSubType = dataSubType;
        TaskDataSub sub = taskDataSubRepository
                .findByTaskPeriodIdAndSubType(tp.getId(), finalDataSubType)
                .orElseGet(() -> {
                    TaskDataSub s = new TaskDataSub();
                    s.setTaskPeriodId(tp.getId());
                    s.setSubType(finalDataSubType);
                    return s;
                });
        sub.setStatus(subStatus);
        sub.setExecutedAt(LocalDateTime.now());
        sub.setErrorMsg(null);
        sub.setResultJson(JSON.toJSONString(resultData));
        taskDataSubRepository.save(sub);

        recalcStepData(tp);
        taskPeriodRepository.save(tp);
        // 无论 OC 子任务成功还是失败，都尝试触发本地任务（bank/salary）
        if (isStepPassed(tp.getStepData())) {
            publishStepCompleted(tp.getAccountSetId(), tp.getPeriod(), "data");
        } else {
            publishStepCompleted(tp.getAccountSetId(), tp.getPeriod(), "data_local");
        }
        log.info("OC发票JSON导入完成: taskId={}, accountSetId={}, dataSubType={}, total={}", taskId, accountSetId, finalDataSubType, total);
        return Map.of("success", true, "total", total,
                "inputCount", actualInput, "outputCount", actualOutput, "msg", msg);
    }

    /** 将 OcTask 标记为失败，使用独立事务确保外层回滚后仍能写入 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markOcTaskFailed(Long taskId, String reason) {
        if (taskId == null) return;
        ocTaskRepository.findById(taskId).ifPresent(oc -> {
            oc.setStatus("failed");
            oc.setResultJson(JSON.toJSONString(Map.of("error_msg", reason)));
            oc.setFinishedAt(LocalDateTime.now());
            ocTaskRepository.save(oc);

            // 同步将 task_period 对应步骤标记为 error，避免步骤卡在 running
            if (oc.getTaskPeriodId() != null) {
                taskPeriodRepository.findById(oc.getTaskPeriodId()).ifPresent(tp -> {
                    if (isDataTask(oc.getTaskType())) {
                        // 数据采集子任务：先更新 task_data_sub 为 error，再用 recalcStepData 聚合，
                        // 保证子任务状态与父步骤状态一致，避免 task_data_sub 卡在 running 导致 bank/salary 永远无法触发
                        String subType = dataOcTaskTypeToSubType(oc.getTaskType());
                        if (subType == null && "vat_declare_pre".equals(oc.getTaskType())) subType = "vat_declare_pre";
                        if (subType != null) {
                            updateSubTaskStatusWithResult(tp.getId(), subType, "error", reason);
                        }
                        recalcStepData(tp);
                    } else {
                        // 其他步骤（bookkeep/vat/iit/payment）：直接设置对应步骤为 error
                        setCascadeFailStep(tp, oc.getTaskType());
                    }
                    if (StrUtil.isBlank(tp.getErrorMsg())) {
                        tp.setErrorMsg(StrUtil.isBlank(reason) ? "任务执行失败" : reason);
                    }
                    taskPeriodRepository.save(tp);
                });
            }
        });
    }

    /** 保存个税申报明细（独立事务，失败不影响外层主事务） */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveIitItemBestEffort(TaskIitItem item) {
        taskIitItemRepository.save(item);
    }

    /** 保存缴费查询明细（独立事务，失败不影响外层主事务） */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void savePaymentItemBestEffort(TaskPaymentItem item) {
        taskPaymentItemRepository.save(item);
    }

    /**
     * 标记期初导入步骤为完成（前端完成实际导入后调用）。
     * 同步更新对应 oc_task(init_import) 为 done。
     */
    @Transactional
    public Map<String, Object> completeInit(Long accountSetId, String period) {
        String p = resolvePeriod(period);
        AccountSets as = accountSetsRepository.findById(accountSetId).orElse(null);
        TaskPeriod tp = getOrCreateTaskPeriod(accountSetId, p, as);
        tp.setStepInit("done");
        taskPeriodRepository.save(tp);

        // 更新对应 oc_task 为 done
        ocTaskRepository.findTopByAccountSetIdAndTaskTypeAndPeriodOrderByCreatedAtDesc(
                accountSetId, "init_import", p)
                .ifPresent(oc -> {
                    if (!"done".equals(oc.getStatus())) {
                        oc.setStatus("done");
                        oc.setFinishedAt(LocalDateTime.now());
                        ocTaskRepository.save(oc);
                    }
                });

        log.info("期初导入完成: accountSetId={}, period={}", accountSetId, p);
        return Map.of("success", true, "msg", "期初导入已标记为完成");
    }

    /**
     * 标记期初导入步骤为失败。
     * 同步更新对应 oc_task(init_import) 为 failed。
     */
    @Transactional
    public Map<String, Object> failInit(Long accountSetId, String period, String errorMsg) {
        String p = resolvePeriod(period);
        AccountSets as = accountSetsRepository.findById(accountSetId).orElse(null);
        TaskPeriod tp = getOrCreateTaskPeriod(accountSetId, p, as);
        tp.setStepInit("error");
        tp.setErrorStep("step_init");
        tp.setErrorMsg(StrUtil.isBlank(errorMsg) ? "期初导入失败" : errorMsg);
        taskPeriodRepository.save(tp);

        ocTaskRepository.findTopByAccountSetIdAndTaskTypeAndPeriodOrderByCreatedAtDesc(
                accountSetId, "init_import", p)
                .ifPresent(oc -> {
                    if (!"done".equals(oc.getStatus())) {
                        oc.setStatus("failed");
                        oc.setFinishedAt(LocalDateTime.now());
                        ocTaskRepository.save(oc);
                    }
                });

        log.info("期初导入失败: accountSetId={}, period={}, errorMsg={}", accountSetId, p, errorMsg);
        return Map.of("success", false, "msg", StrUtil.isBlank(errorMsg) ? "期初导入失败" : errorMsg);
    }

    /**
     * 执行结账操作并将做账步骤标记为完成。
     * <p>
     * 调用前提：当期所有凭证已确认。
     * 执行 {@link CheckoutService#invoicing} 完成结账，同步更新 step_bookkeep = "done"。
     */
    @Transactional
    public Map<String, Object> completeBookkeep(Long accountSetId, String period) {
        String p = resolvePeriod(period);
        AccountSets as = accountSetsRepository.findById(accountSetId)
                .orElseThrow(() -> new IllegalArgumentException("账套不存在：" + accountSetId));

        // 解析账期为 year/month
        String[] parts = p.split("-");
        int year = Integer.parseInt(parts[0]);
        int month = Integer.parseInt(parts[1]);

        try {
            checkoutService.invoicing(as, year, month);
        } catch (Exception e) {
            log.error("结账失败: accountSetId={}, period={}", accountSetId, p, e);
            // 结账异常时将 step_bookkeep 置为 error
            TaskPeriod tp = getOrCreateTaskPeriod(accountSetId, p, as);
            tp.setStepBookkeep("error");
            tp.setErrorStep("step_bookkeep");
            tp.setErrorMsg("结账失败：" + e.getMessage());
            taskPeriodRepository.save(tp);
            return Map.of("success", false, "msg", "结账失败：" + e.getMessage());
        }

        TaskPeriod tp = getOrCreateTaskPeriod(accountSetId, p, as);
        tp.setStepBookkeep("done");
        tp.setErrorStep(null);
        tp.setErrorMsg(null);
        taskPeriodRepository.save(tp);
        log.info("做账结账完成: accountSetId={}, period={}", accountSetId, p);
        return Map.of("success", true, "msg", "结账完成，做账步骤已标记为完成");
    }

    private Map<String, Object> buildEmptyResult(String period, int page, int pageSize) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("stats", Map.of("total", 0, "done", 0, "running", 0, "error", 0, "warn", 0, "pending", 0));
        result.put("tasks", Collections.emptyList());
        result.put("total", 0);
        result.put("page", page);
        result.put("pageSize", pageSize);
        result.put("period", period);
        return result;
    }

    // ─────────────────────────────────────────────────────────────────────
    // 办税员 CRUD
    // ─────────────────────────────────────────────────────────────────────

    /** 获取账套下所有办税员 */
    public List<Map<String, Object>> getTaxOfficers(Long accountSetId) {
        List<TaxOfficer> list = taxOfficerRepository
                .findByAccountSetIdOrderByDefaultOfficerDescCreatedAtAsc(accountSetId);
        return list.stream().map(this::buildOfficerRow).collect(Collectors.toList());
    }

    /** 新增或更新办税员 */
    @Transactional
    public Map<String, Object> saveTaxOfficer(Long accountSetId, Map<String, Object> body) {
        Long id = toLong(body.get("id"));
        String phone = (String) body.get("phone");
        TaxOfficer officer;
        if (id != null) {
            officer = taxOfficerRepository.findById(id).orElseGet(TaxOfficer::new);
        } else if (StrUtil.isNotBlank(phone)) {
            // 同账套同手机号已存在则更新，不重复新增
            officer = taxOfficerRepository.findByAccountSetIdAndPhone(accountSetId, phone)
                    .orElseGet(TaxOfficer::new);
        } else {
            officer = new TaxOfficer();
        }

        officer.setAccountSetId(accountSetId);
        officer.setName((String) body.getOrDefault("name", ""));
        officer.setPhone((String) body.get("phone"));
        officer.setPassword((String) body.get("password"));
        String loginType = (String) body.get("login_type");
        officer.setLoginType(StrUtil.isBlank(loginType) ? "officer" : loginType);
        // FN-TAX-LOGIN-CREDENTIALS-WRITE-API：登录方式 auto/manual，缺省 auto（与现状一致）
        String loginMode = (String) body.get("login_mode");
        officer.setLoginMode(StrUtil.isBlank(loginMode) ? "auto" : loginMode);
        // FN-TAX-LOGIN-MANUAL-METHOD-FIELD：手动子方式 qrcode/sms（仅 manual 有意义，auto 由写口置 null）
        officer.setManualMethod((String) body.get("manual_method"));
        officer.setIdCard((String) body.get("id_card")); // 法人身份证（legal_person 类型时使用）
        officer.setProxyTaxNo((String) body.get("proxy_tax_no")); // 代理机构税号（proxy 类型时使用）
        officer.setRemark((String) body.get("remark"));
        // FN-OFFICER-DEDUP ①：启用状态 ACTIVE/INACTIVE。仅当传入时更新（不传则新建走实体默认 ACTIVE、更新保留原值）
        String officerStatus = (String) body.get("status");
        if (StrUtil.isNotBlank(officerStatus)) {
            officer.setStatus(officerStatus.trim().toUpperCase());
        }

        boolean setDefault = Boolean.TRUE.equals(body.get("is_default"));
        if (setDefault) {
            taxOfficerRepository.clearDefaultByAccountSetId(accountSetId);
            officer.setDefaultOfficer(true);
        } else if (officer.getId() == null) {
            // 第一个办税员自动设为默认
            boolean hasAny = taxOfficerRepository.existsByAccountSetId(accountSetId);
            officer.setDefaultOfficer(!hasAny);
        }

        taxOfficerRepository.save(officer);
        return Map.of("success", true, "officer", buildOfficerRow(officer));
    }

    /** 删除办税员 */
    @Transactional
    public Map<String, Object> deleteTaxOfficer(Long officerId, Long accountSetId) {
        TaxOfficer officer = taxOfficerRepository.findById(officerId)
                .orElseThrow(() -> new IllegalArgumentException("办税员不存在"));
        if (!accountSetId.equals(officer.getAccountSetId())) {
            throw new IllegalArgumentException("无权删除该办税员");
        }
        boolean wasDefault = Boolean.TRUE.equals(officer.getDefaultOfficer());
        taxOfficerRepository.delete(officer);
        // 若删除的是默认，自动将最早创建的设为新默认
        if (wasDefault) {
            taxOfficerRepository.findByAccountSetIdOrderByDefaultOfficerDescCreatedAtAsc(accountSetId)
                    .stream().findFirst().ifPresent(first -> {
                        first.setDefaultOfficer(true);
                        taxOfficerRepository.save(first);
                    });
        }
        return Map.of("success", true);
    }

    /** 设置默认办税员 */
    @Transactional
    public Map<String, Object> setDefaultOfficer(Long officerId, Long accountSetId) {
        TaxOfficer officer = taxOfficerRepository.findById(officerId)
                .orElseThrow(() -> new IllegalArgumentException("办税员不存在"));
        if (!accountSetId.equals(officer.getAccountSetId())) {
            throw new IllegalArgumentException("无权操作该办税员");
        }
        taxOfficerRepository.clearDefaultByAccountSetId(accountSetId);
        officer.setDefaultOfficer(true);
        taxOfficerRepository.save(officer);
        return Map.of("success", true);
    }

    /** 系统默认办税员手机号集合，用于判断数据库记录是否源自系统默认 */

    private Map<String, Object> buildOfficerRow(TaxOfficer o) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", o.getId());
        m.put("accountSetId", o.getAccountSetId());
        m.put("name", o.getName());
        m.put("phone", o.getPhone());
        m.put("loginType", o.getLoginType());
        m.put("loginTypeDisplay", resolveLoginTypeDisplay(o.getLoginType()));
        m.put("loginMode", o.getLoginMode());       // FN-TAX-LOGIN-CREDENTIALS-WRITE-API：auto/manual
        m.put("manualMethod", o.getManualMethod()); // FN-TAX-LOGIN-MANUAL-METHOD-FIELD：qrcode/sms（manual 时）
        m.put("password", o.getPassword());
        m.put("idCard", o.getIdCard());
        m.put("proxyTaxNo", o.getProxyTaxNo());
        m.put("isDefault", o.getDefaultOfficer());
        m.put("status", o.getStatus());   // FN-OFFICER-DEDUP ①：ACTIVE/INACTIVE（停用办税员语义）
        m.put("remark", o.getRemark());
        m.put("createdAt", o.getCreatedAt());
        m.put("system", Boolean.TRUE.equals(o.getSystemOfficer()));
        return m;
    }

    private String resolveLoginTypeDisplay(String loginType) {
        if (loginType == null) return "办税员登录";
        return switch (loginType) {
            case "officer", "password" -> "办税员登录";
            case "proxy"               -> "代理通道";
            case "legal_person"        -> "法人信息";
            case "ca"                  -> "CA证书";
            default                    -> loginType;
        };
    }

    // ─────────────────────────────────────────────────────────────────────
    // 勾选票（仅一般纳税人）
    // ─────────────────────────────────────────────────────────────────────

    /**
     * 将一批导入的发票标记为 showStatus='unchecked'（待勾选）。
     * 仅对 showStatus 为空的发票写入，不覆盖已有状态。
     */
    private void markInvoicesAsUnchecked(Long accountSetId, List<InvoiceDto> dtos) {
        List<Long> ids = dtos.stream()
                .map(InvoiceDto::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        if (ids.isEmpty()) return;
        QInvoice qInv = QInvoice.invoice;
        List<Invoice> invoices = bqf.selectFrom(qInv)
                .where(qInv.accountSetsId.eq(accountSetId).and(qInv.id.in(ids)))
                .fetch();
        invoices.forEach(inv -> {
            if (inv.getShowStatus() == null) {
                inv.setShowStatus("unchecked");
            }
        });
        invoiceRepository.saveAll(invoices);
        log.info("标记未勾选发票: accountSetId={}, count={}", accountSetId, invoices.size());
    }

    /**
     * OpenClaw 完成"勾选发票"任务后的回调处理（subType=invoice_check）。
     * <p>
     * 全自动流程：OC 自决勾选哪些票、提交、确认。
     * 回调 result 字段：
     * <ul>
     *   <li>tax_no / province / target_period — 基本标识</li>
     *   <li>issued_tax / retained_tax / target_tax — 税额对比（销项/留抵/目标）</li>
     *   <li>selected_invoice_count / selected_tax_total — 实际勾选张数和税额</li>
     *   <li>skipped_reason — 跳过的原因（场景 2/3/4），仍记 done</li>
     *   <li>screenshots — 已被回调控制器替换为 HTTP URL</li>
     *   <li>error — 失败时</li>
     * </ul>
     * 状态映射：status=done + result.success=true → invoice_check 子任务 done（即使 skipped_reason 不空）；
     *          status=failed 或 result.success=false → invoice_check 子任务 error。
     */
    @Transactional
    @SuppressWarnings("unchecked")
    public void handleCheckInvoiceCallback(Map<String, Object> body) {
        Long taskId = toLong(body.get("task_id"));
        String status = (String) body.get("status");

        updateOcTask(taskId, status, body);

        OcTask ocTask = taskId != null ? ocTaskRepository.findById(taskId).orElse(null) : null;
        if (ocTask == null) {
            log.warn("勾选发票回调：未找到 ocTask, taskId={}", taskId);
            return;
        }
        TaskPeriod tp = ocTask.getTaskPeriodId() != null
                ? taskPeriodRepository.findById(ocTask.getTaskPeriodId()).orElse(null) : null;
        if (tp == null) {
            log.warn("勾选发票回调：未找到 TaskPeriod, taskPeriodId={}", ocTask.getTaskPeriodId());
            return;
        }

        Map<String, Object> result = body.get("result") instanceof Map
                ? (Map<String, Object>) body.get("result") : Collections.emptyMap();
        boolean success = Boolean.TRUE.equals(result.get("success"));
        boolean isDone = "done".equals(status) && success;

        // 业务结果摘要（供前端展示税额对比、勾选数量、跳过原因等）
        Map<String, Object> resultJson = new LinkedHashMap<>();
        for (String k : List.of("tax_no", "province", "target_period",
                "issued_tax", "retained_tax", "target_tax",
                "selected_invoice_count", "selected_tax_total", "skipped_reason")) {
            if (result.containsKey(k)) resultJson.put(k, result.get(k));
        }

        // 截图 URL（已是 HTTP URL）
        String screenshotUrlsJson = null;
        Object shots = result.get("screenshots");
        if (shots instanceof List<?> shotList && !shotList.isEmpty()) {
            screenshotUrlsJson = JSON.toJSONString(shotList);
        } else if (body.get("screenshot_urls") instanceof List<?> flatShots && !flatShots.isEmpty()) {
            screenshotUrlsJson = JSON.toJSONString(flatShots);
        }

        // 错误信息
        String errorMsg = null;
        if (!isDone) {
            Object err = result.get("error");
            if (err == null) err = body.get("error_msg");
            if (err != null) errorMsg = err.toString();
        }

        // 写入 invoice_check 子任务
        TaskDataSub sub = taskDataSubRepository
                .findByTaskPeriodIdAndSubType(tp.getId(), "invoice_check")
                .orElseGet(() -> {
                    TaskDataSub s = new TaskDataSub();
                    s.setTaskPeriodId(tp.getId());
                    s.setSubType("invoice_check");
                    return s;
                });
        sub.setStatus(isDone ? "done" : "error");
        sub.setOcTaskId(taskId);
        if (!resultJson.isEmpty()) sub.setResultJson(JSON.toJSONString(resultJson));
        if (screenshotUrlsJson != null) sub.setScreenshotUrls(screenshotUrlsJson);
        sub.setErrorMsg(errorMsg);
        sub.setExecutedAt(LocalDateTime.now());
        taskDataSubRepository.save(sub);

        // 重新聚合 stepData，并尝试触发后续子任务（invoice_checked / invoice_unchecked）
        recalcStepData(tp);
        taskPeriodRepository.save(tp);
        if (isDone && ocTask.getAccountSetId() != null) {
            publishStepCompleted(ocTask.getAccountSetId(), ocTask.getPeriod(), "data_local");
        }

        log.info("勾选发票回调处理完成: taskId={}, status={}, isDone={}, errorMsg={}",
                taskId, status, isDone, errorMsg);
    }

    /**
     * 保存账套级步骤执行模式配置（auto / manual）。
     * config 示例：{"verify":"auto","init":"manual","data":"auto","bookkeep":"manual","vat":"auto","iit":"auto","payment":"auto"}
     */
    @Transactional
    public void saveStepConfig(Long accountSetId, Map<String, String> config) {
        AccountSets as = accountSetsRepository.findById(accountSetId)
                .orElseThrow(() -> new IllegalArgumentException("账套不存在：" + accountSetId));
        // 仅保留 DEFAULT_STEP_CONFIG 中的合法 key，防止注入
        Map<String, String> safe = new LinkedHashMap<>();
        for (String key : DEFAULT_STEP_CONFIG.keySet()) {
            String val = config.getOrDefault(key, "auto");
            safe.put(key, "manual".equals(val) ? "manual" : "auto");
        }
        as.setStepConfig(JSON.toJSONString(safe));
        accountSetsRepository.save(as);
    }

    /**
     * 批量保存步骤执行模式配置。
     * <p>
     * 两种模式：
     * <ul>
     *   <li><b>指定账套</b>：传入非空 accountSetIds，仅更新这批账套</li>
     *   <li><b>全量筛选</b>：accountSetIds 为 null/空 且 applyAll=true，
     *       按 status/vat/province/keyword 筛选全部符合条件的账套并更新</li>
     * </ul>
     */
    @Transactional
    public int batchSaveStepConfig(List<Long> accountSetIds, Map<String, String> config,
                                    boolean applyAll, String period,
                                    String status, String vat, String province, String keyword) {
        Map<String, String> safe = new LinkedHashMap<>();
        for (String key : DEFAULT_STEP_CONFIG.keySet()) {
            String val = config.getOrDefault(key, "auto");
            safe.put(key, "manual".equals(val) ? "manual" : "auto");
        }
        String configJson = JSON.toJSONString(safe);

        List<AccountSets> targets;
        if (!applyAll && !CollUtil.isEmpty(accountSetIds)) {
            // 模式一：指定 ID 列表
            targets = accountSetsRepository.findAllById(accountSetIds);
        } else {
            // 模式二：按筛选条件找出全部符合账套（复用 getTasks 的过滤逻辑）
            String p = resolvePeriod(period);
            List<AccountSets> allSets = accountSetsRepository.findAll().stream()
                    .filter(a -> !Boolean.TRUE.equals(a.getDeactivated()))
                    .collect(Collectors.toList());
            List<Long> allIds = allSets.stream().map(AccountSets::getId).collect(Collectors.toList());
            Map<Long, TaskPeriod> periodMap = taskPeriodRepository
                    .findByPeriodAndAccountSetIdIn(p, allIds)
                    .stream().collect(Collectors.toMap(TaskPeriod::getAccountSetId, tp -> tp));
            Set<Long> everDoneInitIds = new HashSet<>(
                    taskPeriodRepository.findAccountSetIdsByStepInitAndAccountSetIdIn("done", allIds));
            targets = allSets.stream().filter(as -> {
                TaskPeriod tp = periodMap.get(as.getId());
                Map<String, Object> row = buildRow(as, tp, p, everDoneInitIds);
                return matchStatus(row, status) && matchVat(row, vat)
                        && matchProvince(row, province) && matchKeyword(row, keyword);
            }).collect(Collectors.toList());
        }

        targets.forEach(as -> as.setStepConfig(configJson));
        accountSetsRepository.saveAll(targets);
        log.info("批量保存执行配置: count={}, applyAll={}", targets.size(), applyAll);
        return targets.size();
    }

    /**
     * 手动强制完成：无论当前状态如何，直接将步骤/子任务标记为 done，解锁后续任务。
     * step 可以是主步骤（verify/init/data/bookkeep/vat/iit/payment）
     * 也可以是子任务 key（data.invoice / data.bank / data.salary / bookkeep.invoice 等）
     */
    @Transactional
    public Map<String, Object> markStepDone(Long accountSetId, String period, String step) {
        String p = resolvePeriod(period);
        TaskPeriod tp = taskPeriodRepository.findByAccountSetIdAndPeriod(accountSetId, p)
                .orElseGet(() -> {
                    TaskPeriod n = new TaskPeriod();
                    n.setAccountSetId(accountSetId);
                    n.setPeriod(p);
                    return taskPeriodRepository.save(n);
                });

        // 子任务（如 data.invoice / bookkeep.bank）
        if (step.contains(".")) {
            String[] parts = step.split("\\.", 2);
            String parentStep = parts[0];
            String subType = parts[1];

            if ("data".equals(parentStep)) {
                // "invoice" 是旧版笼统类型，需要展开为新版明细子类型一起标记完成
                List<String> subTypesToMark;
                if ("invoice".equals(subType)) {
                    AccountSets as = accountSetsRepository.findById(accountSetId).orElse(null);
                    boolean isSmallScale = as != null && as.getVatType() == VatType.SMALL_SCALE;
                    subTypesToMark = isSmallScale
                            ? List.of("invoice", "invoice_in", "invoice_out")
                            : List.of("invoice", "invoice_in", "invoice_out",
                                    "invoice_check", "invoice_checked", "invoice_unchecked");
                } else {
                    subTypesToMark = List.of(subType);
                }
                for (String st : subTypesToMark) {
                    TaskDataSub sub = taskDataSubRepository
                            .findByTaskPeriodIdAndSubType(tp.getId(), st)
                            .orElseGet(() -> {
                                TaskDataSub s = new TaskDataSub();
                                s.setTaskPeriodId(tp.getId());
                                s.setSubType(st);
                                return s;
                            });
                    if (!"done".equals(sub.getStatus()) && !"skipped".equals(sub.getStatus())) {
                        sub.setStatus("done");
                        taskDataSubRepository.save(sub);
                    }
                }
                recalcStepData(tp);
                if ("error".equals(tp.getStepData())) { tp.setErrorStep(null); tp.setErrorMsg(null); }
                taskPeriodRepository.save(tp);
                if (isStepPassed(tp.getStepData())) publishStepCompleted(accountSetId, p, "data");
            } else if ("bookkeep".equals(parentStep)) {
                TaskBookkeepSub sub = taskBookkeepSubRepository
                        .findByTaskPeriodIdAndSubType(tp.getId(), subType)
                        .orElseGet(() -> {
                            TaskBookkeepSub s = new TaskBookkeepSub();
                            s.setTaskPeriodId(tp.getId());
                            s.setSubType(subType);
                            return s;
                        });
                sub.setStatus("done");
                taskBookkeepSubRepository.save(sub);
                recalcStepBookkeep(tp);
                if ("error".equals(tp.getStepBookkeep())) { tp.setErrorStep(null); tp.setErrorMsg(null); }
                taskPeriodRepository.save(tp);
                if (isStepPassed(tp.getStepBookkeep())) publishStepCompleted(accountSetId, p, "bookkeep");
            }
            cancelPendingOcTasksForStep(tp, step);
            log.info("手动完成子任务: accountSetId={}, step={}", accountSetId, step);
            return Map.of("success", true, "msg", "子任务已标记为完成");
        }

        // 主步骤：全量 upsert 子任务为 done（保留 skipped），确保抽屉中所有子任务行都显示"完成"
        if ("data".equals(step)) {
            AccountSets as = accountSetsRepository.findById(accountSetId).orElse(null);
            boolean isSmallScale = as != null && as.getVatType() == VatType.SMALL_SCALE;
            // invoice_check / invoice_checked / invoice_unchecked 仅一般纳税人才有，小规模跳过
            List<String> dataSubTypes = isSmallScale
                    ? List.of("invoice_in", "invoice_out", "bank", "salary")
                    : List.of("invoice_in", "invoice_out", "invoice_check",
                            "invoice_checked", "invoice_unchecked", "bank", "salary");
            Map<String, TaskDataSub> existing = taskDataSubRepository.findByTaskPeriodId(tp.getId())
                    .stream().collect(Collectors.toMap(TaskDataSub::getSubType, s -> s));
            for (String subType : dataSubTypes) {
                TaskDataSub sub = existing.get(subType);
                if (sub == null) {
                    sub = new TaskDataSub();
                    sub.setTaskPeriodId(tp.getId());
                    sub.setSubType(subType);
                    sub.setStatus("done");
                    taskDataSubRepository.save(sub);
                } else if (!"skipped".equals(sub.getStatus()) && !"done".equals(sub.getStatus())) {
                    sub.setStatus("done");
                    taskDataSubRepository.save(sub);
                }
            }
        } else if ("bookkeep".equals(step)) {
            List<String> bookkeepSubTypes = List.of("invoice", "bank", "salary", "asset", "carry_forward");
            Map<String, TaskBookkeepSub> existing = taskBookkeepSubRepository.findByTaskPeriodId(tp.getId())
                    .stream().collect(Collectors.toMap(TaskBookkeepSub::getSubType, s -> s));
            for (String subType : bookkeepSubTypes) {
                TaskBookkeepSub sub = existing.get(subType);
                if (sub == null) {
                    sub = new TaskBookkeepSub();
                    sub.setTaskPeriodId(tp.getId());
                    sub.setSubType(subType);
                    sub.setStatus("done");
                    taskBookkeepSubRepository.save(sub);
                } else if (!"skipped".equals(sub.getStatus()) && !"done".equals(sub.getStatus())) {
                    sub.setStatus("done");
                    taskBookkeepSubRepository.save(sub);
                }
            }
        }
        setStepStatus(tp, step, "done");
        tp.setErrorStep(null);
        tp.setErrorMsg(null);
        taskPeriodRepository.save(tp);
        cancelPendingOcTasksForStep(tp, step);
        log.info("手动完成主步骤: accountSetId={}, step={}", accountSetId, step);
        publishStepCompleted(accountSetId, p, step);
        return Map.of("success", true, "msg", "步骤已手动标记为完成，后续任务可继续执行");
    }

    /**
     * 批量手动完成指定步骤（可多选）。
     * steps 格式与单账套 markStepDone 的 step 参数一致，支持主步骤和子步骤混合传入。
     */
    public Map<String, Object> batchMarkDoneSteps(List<Long> accountSetIds, String period, List<String> steps) {
        int successCount = 0;
        int skippedCount = 0;
        List<String> errors = new ArrayList<>();

        for (Long id : accountSetIds) {
            boolean anyDone = false;
            for (String step : steps) {
                try {
                    // 通过 self 调用确保 @Transactional 生效，事务提交后 StepCompletedEvent 才能触发
                    Map<String, Object> r = self.markStepDone(id, period, step);
                    if (Boolean.TRUE.equals(r.get("success"))) anyDone = true;
                } catch (Exception e) {
                    errors.add("账套" + id + "[" + step + "]: " + e.getMessage());
                }
            }
            if (anyDone) successCount++;
            else skippedCount++;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("successCount", successCount);
        result.put("skippedCount", skippedCount);
        result.put("failCount", errors.size());
        result.put("errors", errors);
        return result;
    }

    /**
     * 人工确认：将已执行完毕处于 waiting 状态的步骤/子任务标记为 done，解除对后续任务的阻断。
     * step 可以是主步骤（verify/init/data/bookkeep/vat/iit/payment）
     * 也可以是子任务 key（data.invoice / data.bank / data.salary）
     */
    @Transactional
    public Map<String, Object> confirmManualStep(Long accountSetId, String period, String step, String ocInstanceId) {
        String p = resolvePeriod(period);
        TaskPeriod tp = taskPeriodRepository.findByAccountSetIdAndPeriod(accountSetId, p).orElse(null);
        if (tp == null) {
            return Map.of("success", false, "msg", "未找到任务记录");
        }

        // 子任务 key（如 "data.invoice"）
        if (step.contains(".")) {
            String[] parts = step.split("\\.", 2);
            String subType = parts[1];
            TaskDataSub sub = taskDataSubRepository
                    .findByTaskPeriodIdAndSubType(tp.getId(), subType).orElse(null);
            if (sub == null || (!"waiting".equals(sub.getStatus()) && !"warn".equals(sub.getStatus()))) {
                return Map.of("success", false, "msg", "子任务【" + subType + "】当前不在可确认状态");
            }
            sub.setStatus("done");
            taskDataSubRepository.save(sub);
            recalcStepData(tp);
            taskPeriodRepository.save(tp);
            log.info("人工确认子任务: accountSetId={}, subType={}", accountSetId, subType);
            if (isStepPassed(tp.getStepData())) {
                publishStepCompleted(accountSetId, p, "data");
            }
            return Map.of("success", true, "msg", "已确认，子任务【" + subType + "】标记为完成");
        }

        // 主步骤
        String currentStatus = getStepStatus(tp, step);
        if (!"waiting".equals(currentStatus) && !"warn".equals(currentStatus)) {
            return Map.of("success", false, "msg", "该步骤当前不在可确认状态（当前：" + currentStatus + "）");
        }
        setStepStatus(tp, step, "done");
        taskPeriodRepository.save(tp);
        log.info("人工确认步骤: accountSetId={}, step={}", accountSetId, step);
        // 确认完成后自动解锁后置 blocked 步骤
        String autoTriggerKey = switch (step) {
            case "data"     -> "data";
            case "bookkeep" -> "bookkeep";
            case "vat"      -> "vat";
            case "iit"      -> "iit";
            default         -> null;
        };
        if (autoTriggerKey != null) {
            publishStepCompleted(accountSetId, p, autoTriggerKey);
        }
        return Map.of("success", true, "msg", "已确认，步骤【" + step + "】标记为完成");
    }

    /**
     * 切换"已核查"标记。
     * step 仅支持主步骤：verify / init / data / bookkeep / vat / iit / payment / tax_annual。
     * 与执行状态完全独立，任意 status 下都可切换；reset 会清空。
     */
    @Transactional
    public Map<String, Object> markStepChecked(Long accountSetId, String period, String step, boolean checked) {
        String p = resolvePeriod(period);
        TaskPeriod tp = taskPeriodRepository.findByAccountSetIdAndPeriod(accountSetId, p).orElse(null);
        if (tp == null) {
            AccountSets as = accountSetsRepository.findById(accountSetId)
                    .orElseThrow(() -> new IllegalArgumentException("账套不存在：" + accountSetId));
            tp = getOrCreateTaskPeriod(accountSetId, p, as);
        }
        if (!setStepChecked(tp, step, checked)) {
            return Map.of("success", false, "msg", "未知步骤：" + step);
        }
        taskPeriodRepository.save(tp);
        return Map.of("success", true, "step", step, "checked", checked);
    }

    /**
     * 批量切换"已核查"标记。
     * steps 仅支持主步骤名。
     */
    @Transactional
    public Map<String, Object> batchMarkStepChecked(List<Long> accountSetIds, String period, List<String> steps, boolean checked) {
        int successCount = 0;
        List<String> errors = new ArrayList<>();
        for (Long id : accountSetIds) {
            boolean anyOk = false;
            for (String step : steps) {
                try {
                    Map<String, Object> r = markStepChecked(id, period, step, checked);
                    if (Boolean.TRUE.equals(r.get("success"))) anyOk = true;
                } catch (Exception e) {
                    errors.add("账套" + id + "[" + step + "]: " + e.getMessage());
                }
            }
            if (anyOk) successCount++;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("successCount", successCount);
        result.put("failCount", errors.size());
        result.put("errors", errors);
        return result;
    }

    /** 设置某主步骤的"已核查"标记，返回是否识别到该步骤。 */
    private boolean setStepChecked(TaskPeriod tp, String step, boolean checked) {
        switch (step) {
            case "verify":       tp.setStepVerifyChecked(checked);      return true;
            case "init":         tp.setStepInitChecked(checked);        return true;
            case "data":         tp.setStepDataChecked(checked);        return true;
            case "bookkeep":     tp.setStepBookkeepChecked(checked);    return true;
            case "vat":          tp.setStepTaxNationalChecked(checked); return true;
            case "iit":          tp.setStepTaxIitChecked(checked);      return true;
            case "payment":      tp.setStepPaymentChecked(checked);     return true;
            case "tax_annual":   tp.setStepTaxAnnualChecked(checked);   return true;
            default:             return false;
        }
    }

    /**
     * 解析账套的步骤执行模式配置。
     * 以 DEFAULT_STEP_CONFIG 为基础，数据库中有值的 key 覆盖默认值，
     * 数据库中没有的 key 保留默认值，保证始终返回完整的 7 个 key。
     */
    @SuppressWarnings("unchecked")
    private Map<String, String> resolveStepConfig(AccountSets as) {
        Map<String, String> result = new LinkedHashMap<>(DEFAULT_STEP_CONFIG);
        if (StrUtil.isNotBlank(as.getStepConfig())) {
            try {
                Map<String, Object> db = JSON.parseObject(as.getStepConfig(), LinkedHashMap.class);
                db.forEach((k, v) -> {
                    if (result.containsKey(k) && v != null) {
                        result.put(k, "manual".equals(v.toString()) ? "manual" : "auto");
                    }
                });
            } catch (Exception e) {
                log.warn("解析 stepConfig 失败，使用默认配置: accountSetId={}", as.getId(), e);
            }
        }
        return result;
    }

    private Long toLong(Object val) {
        if (val == null) return null;
        if (val instanceof Number) return ((Number) val).longValue();
        try { return Long.parseLong(val.toString()); } catch (Exception e) { return null; }
    }

    private java.math.BigDecimal parseBigDecimal(Object val) {
        if (val == null) return null;
        try { return new java.math.BigDecimal(val.toString().trim()); } catch (Exception e) { return null; }
    }

    private java.time.LocalDate parseLocalDate(Object val) {
        if (val == null) return null;
        try { return java.time.LocalDate.parse(val.toString().trim()); } catch (Exception e) { return null; }
    }
}
