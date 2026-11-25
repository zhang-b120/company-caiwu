package cn.gson.financial.kernel.service;

import cn.gson.financial.kernel.config.AccountSetsProcessor;
import cn.gson.financial.kernel.config.AppConfig;
import cn.gson.financial.kernel.config.ArchiveProcessor;
import cn.gson.financial.kernel.config.BackupProcessor;
import cn.gson.financial.kernel.exception.ServiceException;
import cn.gson.financial.kernel.model.dto.*;
import cn.gson.financial.kernel.model.entity.*;
import cn.gson.financial.kernel.model.enums.FormulaSource;
import cn.gson.financial.kernel.model.enums.ReportColDateLine;
import cn.gson.financial.kernel.model.enums.ReportItemType;
import cn.gson.financial.kernel.model.enums.SubjectCategory;
import cn.gson.financial.kernel.model.hibernate.PrimaryKey;
import cn.gson.financial.kernel.model.mapper.ReportItemsMapper;
import cn.gson.financial.kernel.model.repo.ReportItemsRepository;
import cn.gson.financial.kernel.model.repo.ReportRepository;
import cn.gson.financial.kernel.model.vo.DownLoadReportVo;
import cn.gson.financial.kernel.model.vo.FeeDetailQuery;
import cn.gson.financial.kernel.model.vo.UserVo;
import cn.gson.financial.kernel.tool.JxlsUtils;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.date.DateTime;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.lang.Dict;
import cn.hutool.core.lang.tree.Tree;
import cn.hutool.core.lang.tree.TreeUtil;
import cn.hutool.core.map.MapUtil;
import cn.hutool.core.util.NumberUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson.JSON;
import com.googlecode.aviator.AviatorEvaluator;
import com.googlecode.aviator.AviatorEvaluatorInstance;
import com.googlecode.aviator.EvalMode;
import com.googlecode.aviator.Expression;
import com.querydsl.core.Tuple;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.core.types.dsl.NumberExpression;
import com.querydsl.core.types.dsl.NumberPath;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static cn.gson.financial.kernel.model.enums.AccessRules.发生额;
import static cn.gson.financial.kernel.service.BalanceCalcService.FXY_FINANCIAL_BALANCE;
import static cn.gson.financial.kernel.service.BalanceCalcService.FXY_FINANCIAL_BALANCE_CASH_FLOW;
import static cn.hutool.core.util.RandomUtil.BASE_CHAR;

/**
 *
 * <p><b>Copyright © 2010-2022开发公司All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.kernel.service</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2022年11月17日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 *
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReportService extends AbsService implements AccountSetsProcessor, BackupProcessor, ArchiveProcessor {
    private final AviatorEvaluatorInstance evaluator = AviatorEvaluator.newInstance(EvalMode.INTERPRETER);

    private final ReportItemsMapper reportItemsMapper;

    private final AppConfig appConfig;

    private final QReport qReport = QReport.report;

    private final QVoucherDetails qVoucherDetails = QVoucherDetails.voucherDetails;

    private final QSubject qSubject = QSubject.subject;

    private final QReportItems qReportItems = QReportItems.reportItems;

    private final ReportRepository reportRepository;

    private final ReportItemsRepository reportItemsRepository;

    private final QBalance qBalance = QBalance.balance;
    private  final QAccountSets qAccountSets = QAccountSets.accountSets;
    private final SubjectService subjectService;

    private final QBalanceInitial qBalanceInitial = QBalanceInitial.balanceInitial;

    private final QBalanceCashFlow qBalanceCashFlow = QBalanceCashFlow.balanceCashFlow;

    private final QReportCashFlowDraft qReportCashFlowDraft = QReportCashFlowDraft.reportCashFlowDraft;
    private final CurrencyService currencyService;
    private final QCheckout qCheckout = QCheckout.checkout;

    @Value("${customize-config.base-path}")
    private String basePath;

    public List<Report> list(Long accountSetsId) {
        return bqf.selectFrom(qReport)
                .where(qReport.accountSetsId.eq(accountSetsId))
                .orderBy(qReport.pos.asc())
                .fetch();
    }

    public List<Report> list(long accountSetsId) {
        return bqf.selectFrom(qReport)
                .where(qReport.accountSetsId.eq(accountSetsId))
                .orderBy(qReport.pos.asc())
                .fetch();
    }
    public Boolean getResetStatus (Long accountSetsId) {
        return bqf.selectFrom(qAccountSets).select(qAccountSets.resetStatus).where(qAccountSets.id.eq(accountSetsId)).fetchFirst();
    }
    @Transactional
    public void setResetStatus(Long accountSetsId,boolean reset) {
        jqf.update(qAccountSets).set(qAccountSets.resetStatus, reset).where(qAccountSets.id.eq(accountSetsId)).execute();
    }
    public Report getOne(Long id, long accountSetsId) {
        return bqf.selectFrom(qReport)
                .where(qReport.accountSetsId.eq(accountSetsId).and(qReport.id.eq(id)))
                .fetchFirst();
    }

    public Report load(String reportKey, Long accountSetsId) {
        Report report = bqf.selectFrom(qReport)
                .where(qReport.templateKey.eq(reportKey).and(qReport.accountSetsId.eq(accountSetsId)))
                .fetchFirst();
        Assert.notNull(report, "报表加载失败~");

        List<ReportItems> templateItems = bqf.selectFrom(qReportItems)
                .where(qReportItems.templateId.eq(report.getId()).and(qReportItems.accountSetsId.eq(accountSetsId)))
                .orderBy(qReportItems.pos.asc(), qReportItems.id.desc())
                .fetch();

        report.setItems(templateItems);
        return report;
    }

    @Override
    @Transactional
    public void init(AccountSets accountSets, AccountSetsTemplate accountSetsTemplate) {
        reportRepository.saveAll(bqf.selectFrom(qReport)
                .where(qReport.accountSetsId.eq(accountSetsTemplate.getId()))
                .fetch().stream().map(s -> {
                    bqf.detach(s);
                    Report bean = BeanUtil.toBean(s, Report.class);
                    bean.setAccountSetsId(accountSets.getId());
                    return bean;
                }).collect(Collectors.toList()));

        reportItemsRepository.saveAll(bqf.selectFrom(qReportItems)
                .where(qReportItems.accountSetsId.eq(accountSetsTemplate.getId()))
                .fetch().stream().map(s -> {
                    bqf.detach(s);
                    ReportItems bean = reportItemsMapper.toEntity(reportItemsMapper.toDto(s));
                    bean.setAccountSetsId(accountSets.getId());
                    return bean;
                }).collect(Collectors.toList()));
    }

    @Override
    @Transactional
    public void clear(Long accountSetsId) {
        jqf.delete(qReport).where(qReport.accountSetsId.eq(accountSetsId)).execute();
        jqf.delete(qReportItems).where(qReportItems.accountSetsId.eq(accountSetsId)).execute();
    }

    @Override
    public String backup(Long accountSetsId) {
        List<Report> fetch = bqf.selectFrom(qReport)
                .where(qReport.accountSetsId.eq(accountSetsId))
                .fetch();
        for (Report report : fetch) {
            List<ReportItems> reportItems = bqf.selectFrom(qReportItems)
                    .where(qReportItems.accountSetsId.eq(accountSetsId)
                            .and(qReportItems.templateId.eq(report.getId())))
                    .fetch();
            if (CollUtil.isNotEmpty(reportItems)) {
                report.setItems(reportItems);
            }
        }
        return JSON.toJSONString(fetch);
    }

    @Override
    public Class<?> backupType() {
        return Report.class;
    }

    @Override
    public void recover(Long accountSetsId, String json) {
        List<Report> reports = JSON.parseArray(json, Report.class);
        for (Report report : reports) {
            report.setAccountSetsId(accountSetsId);
            reportRepository.save(report);
            if (CollUtil.isNotEmpty(report.getItems())) {
                for (ReportItems reportItem : report.getItems()) {
                    reportItem.setAccountSetsId(accountSetsId);
                    reportItemsRepository.save(reportItem);
                }
            }
        }
    }

    @Transactional
    public void delete(PrimaryKey primaryKey) {
        Report report = reportRepository.getReferenceById(primaryKey);
        Assert.isFalse(report.getIsDefault(), "默认报表不允许删除~");
        reportRepository.delete(report);
        jqf.delete(qReportItems)
                .where(qReportItems.accountSetsId.eq(primaryKey.getAccountSetsId())
                        .and(qReportItems.templateId.eq(primaryKey.getId())))
                .execute();
    }

    @Transactional
    public void save(Report report) {
        reportRepository.save(report);
    }

    public List<Report> listByKey(String templateKey, Long templateId) {
        return bqf.selectFrom(qReport)
                .where(qReport.accountSetsId.eq(0L)
                        .and(qReport.templateKey.eq(templateKey))
                        .and(qReport.id.ne(templateId))
                )
                .orderBy(qReport.pos.asc())
                .fetch();
    }

    /**
     * 报表项
     *
     * @param accountSetsId
     * @return
     */
    public List<ReportItemsDto> items(String templateKey, long accountSetsId, Boolean reset) {
        //报表信息
        Report report = bqf.selectFrom(qReport)
                .where(qReport.templateKey.eq(templateKey).and(qReport.accountSetsId.eq(accountSetsId)))
                .fetchFirst();
        //报表数据项列表
        return getReportItemsDtos(accountSetsId, reset, report);
    }

    /**
     * 报表项
     *
     * @param accountSetsId
     * @return
     */
    public List<ReportItemsDto> items(Long templateId, long accountSetsId, Boolean reset) {
        //报表信息
        Report report = bqf.selectFrom(qReport)
                .where(qReport.id.eq(templateId).and(qReport.accountSetsId.eq(accountSetsId)))
                .fetchFirst();
        return getReportItemsDtos(accountSetsId, reset, report);
    }

    @NotNull
    private List<ReportItemsDto> getReportItemsDtos(long accountSetsId, Boolean reset, Report report) {
        //报表数据项列表
        List<ReportItemsDto> reportItems = reportItemsRepository.list(accountSetsId, report.getId())
                .stream().map(reportItemsMapper::toDto).collect(Collectors.toList());
        if (reset != null) {
            if (reset) {
                reportItems = reportItems.stream().filter(it -> it.getItemType().contains(ReportItemType.重分类)).toList();
            } else {
                reportItems = reportItems.stream().filter(it -> it.getItemType().contains(ReportItemType.常规)).toList();
            }
        }
        return reportItems;
    }

    /**
     * 保存报表配置项
     *
     * @param reportItems
     * @return
     */
    @Transactional
    public ReportItems saveItems(ReportItems reportItems) {
        if (reportItems.getPrimaryKey() != null) {
            ReportItems reference = reportItemsRepository.getReferenceById(reportItems.getPrimaryKey());
            BeanUtil.copyProperties(reportItems, reference, CopyOptions.create().ignoreNullValue());
            return reportItemsRepository.save(reference);
        } else {
            return reportItemsRepository.save(reportItems);
        }
    }

    /**
     * 保存报表配置项
     *
     * @param reportItems
     * @return
     */
    public ReportItems loadByName(ReportItems reportItems) {
        return bqf.selectFrom(qReportItems)
                .where(qReportItems.templateId.eq(reportItems.getTemplateId())
                        .and(qReportItems.title.eq(reportItems.getTitle())))
                .fetchFirst();
    }

    /**
     * 保存报表配置项
     *
     * @param reportItems
     */
    @Transactional
    public void saveItemsFormula(ReportItems reportItems) {
        ReportItems reference = reportItemsRepository.getReferenceById(reportItems.getPrimaryKey());
        reference.setFormulas(reportItems.getFormulas());
        reportItemsRepository.save(reference);
    }

    @Transactional
    public void deleteItems(PrimaryKey primaryKey) {
        reportItemsRepository.deleteById(primaryKey);
    }

    public List<Report> selectList(Long accountSetsId) {
        return bqf.selectFrom(qReport)
                .select(qReport.name, qReport.dimensions, qReport.templateKey, qReport.id)
                .where(qReport.accountSetsId.eq(accountSetsId))
                .orderBy(qReport.pos.asc())
                .fetch().stream().map(tuple -> {
                    Report report = new Report();
                    report.setName(tuple.get(qReport.name));
                    report.setDimensions(tuple.get(qReport.dimensions));
                    report.setTemplateKey(tuple.get(qReport.templateKey));
                    report.setId(tuple.get(qReport.id));
                    return report;
                }).collect(Collectors.toList());
    }

    /**
     * 报表数据
     *
     * @param reportItems
     * @param accountSets
     * @param templateKey
     * @param localCurrencyId
     * @param voucherDate
     * @param dimension
     */
    public List<ReportItemsDto> reportData(List<ReportItemsDto> reportItems, AccountSets accountSets, String templateKey, Long localCurrencyId, LocalDate voucherDate, String dimension) {
        LocalDate startDate = voucherDate.withDayOfMonth(1);
        LocalDate endDate = voucherDate;

        //不能超过账套启用日期查询
        if (accountSets.getEnableDate().isAfter(startDate)) {
            startDate = accountSets.getEnableDate();
        }

        if ("QUARTER".equals(dimension)) {
            endDate = DateUtil.endOfQuarter(DateTime.from(startDate.atStartOfDay().toInstant(ZoneOffset.ofHours(8))))
                    .toLocalDateTime().toLocalDate().withDayOfMonth(1);
        }

        //报表信息
        Report report = bqf.selectFrom(qReport)
                .where(qReport.templateKey.eq(templateKey).and(qReport.accountSetsId.eq(accountSets.getId())))
                .fetchFirst();

        //数据项中涉及到的所有科目ID
        Set<Long> subjectIdSet = reportItems.stream()
                .filter(it -> CollUtil.isNotEmpty(it.getFormulas()))
                .flatMap(item -> item.getFormulas().stream())
                .filter(f -> f.getSource() != null && f.getSource().equals(FormulaSource.SUBJECTS))
                .map(f -> Long.parseLong(f.getFromTag())).collect(Collectors.toSet());
        //科目信息
        Map<Long, Subject> subjectMap = bqf.selectFrom(qSubject)
                .where(qSubject.accountSetsId.eq(accountSets.getId()).and(qSubject.id.in(subjectIdSet)))
                .fetch().stream().collect(Collectors.toMap(Subject::getId, s -> s));

        // 查询出所有科目的余额信息
        Map<Long, BalanceDto> balanceMap = getBalanceMap(accountSets.getId(), localCurrencyId, dimension, startDate, endDate, templateKey);
        // 发生额计算
        Map<String, BalanceDto> occurrenceBalanceMap = getOccurrenceBalanceMap(accountSets.getId(), accountSets.getEnableDate(), localCurrencyId, startDate, endDate, templateKey);

        //已计算的项
        Map<Long, ReportItemsDto> calculated = new HashMap<>();
        int i = 0;
        do {
            for (ReportItemsDto item : reportItems) {
                //已计算的不再处理
                if (!calculated.containsKey(item.getId())) {
                    if (CollUtil.isNotEmpty(item.getFormulas())) {
                        //循环报表列表
                        for (Report.ReportCol col : report.getReportCols()) {
                            if (item.getValues().containsKey(col.getTitle())) {
                                //列已经计算了就不重复计算
                                continue;
                            }
                            //循环数据项公式
                            List<String> express = new ArrayList<>();
                            //变量
                            Map<String, Object> vars = new HashMap<>();
                            List<List<Object>> varValue = new ArrayList<>();
                            for (ReportItemsFormula formula : item.getFormulas()) {
                                if (formula.getSource() == null) {
                                    continue;
                                }

                                express.add(formula.getCalculation());
                                String varName = "$" + RandomUtil.randomString(BASE_CHAR, 6);
                                express.add(varName);
                                switch (formula.getSource()) {
                                    case SUBJECTS -> {
                                        BigDecimal val = BigDecimal.ZERO;
                                        //科目对应余额数据
                                        BalanceDto balance = balanceMap.get(Long.parseLong(formula.getFromTag()));
                                        Subject subject1 = subjectMap.get(Long.parseLong(formula.getFromTag()));
                                        if (balance != null || 发生额.equals(formula.getAccessRules())) {
                                            switch (col.getDateLine()) {
                                                case 本期 -> {
                                                    switch (formula.getAccessRules()) {
                                                        case 发生额 -> {
                                                            BalanceDto currentBalance = new BalanceDto();
                                                            MapUtil.filter(occurrenceBalanceMap, entry -> entry.getKey().startsWith(subject1.getCode())).values().forEach(currentBalance::summarySubAccount);
                                                            if ("借".equals(subject1.getBalanceDirection())) {
                                                                val = NumberUtil.sub(currentBalance.getDebit(), currentBalance.getCredit());
                                                            } else {
                                                                val = NumberUtil.sub(currentBalance.getCredit(), currentBalance.getDebit());
                                                            }
                                                            // 取val的绝对值
                                                        }
                                                        case 借方发生额 -> val = balance.getDebit();
                                                        case 贷方发生额 -> val = balance.getCredit();
                                                        case 余额 -> val = balance.getEndBalance();
                                                        case 期初余额 -> val = balance.getBeginBalance();
                                                        case 借方余额 -> val = balance.getEndDebitBalance();
                                                        case 贷方余额 -> val = balance.getEndCreditBalance();
                                                        case 末级借方余额 -> val = getEndBalance(false, accountSets.getId(), subject1, balance, startDate, endDate, qBalance.endDebitBalance);
                                                        case 末级贷方余额 -> val = getEndBalance(false, accountSets.getId(), subject1, balance, startDate, endDate, qBalance.endCreditBalance);
                                                        case 辅助核算借方余额 -> val = getEndBalance(CollUtil.isNotEmpty(subject1.getAuxiliaryAccounting()), accountSets.getId(), subject1, balance, startDate, endDate, qBalance.endDebitBalance);
                                                        case 辅助核算贷方余额 -> val = getEndBalance(CollUtil.isNotEmpty(subject1.getAuxiliaryAccounting()), accountSets.getId(), subject1, balance, startDate, endDate, qBalance.endCreditBalance);
                                                    }
                                                }
                                                case 本年 -> {
                                                    switch (formula.getAccessRules()) {
                                                        case 发生额 -> {
                                                            BalanceDto currentBalance = new BalanceDto();
                                                            MapUtil.filter(occurrenceBalanceMap, entry -> entry.getKey().startsWith(subject1.getCode())).values().forEach(currentBalance::summarySubAccount);
                                                            if ("借".equals(subject1.getBalanceDirection())) {
                                                                val = NumberUtil.sub(currentBalance.getYearTotalDebit(), currentBalance.getYearTotalCredit());
                                                            } else {
                                                                val = NumberUtil.sub(currentBalance.getYearTotalCredit(), currentBalance.getYearTotalDebit());
                                                            }
                                                            // 取val的绝对值
                                                        }
                                                        case 借方发生额 -> val = balance.getYearTotalDebit();
                                                        case 贷方发生额 -> val = balance.getYearTotalCredit();
                                                        case 借方余额 -> val = NumberUtil.sub(balance.getYearTotalDebit(), balance.getYearTotalCredit());
                                                        case 贷方余额 -> val = NumberUtil.sub(balance.getYearTotalCredit(), balance.getYearTotalDebit());
                                                        case 余额 -> val = balance.getEndBalance();
                                                        case 期初余额 -> val = balance.getYearTotal();
                                                    }
                                                }
                                                case 年初 -> val = switch (formula.getAccessRules()) {
                                                    case 发生额, 损益发生额 -> BigDecimal.ZERO;
                                                    case 借方发生额 -> balance.getYearTotalDebit();
                                                    case 贷方发生额 -> balance.getYearTotalCredit();
                                                    case 借方余额 -> balance.getYearTotalBeginDebit();
                                                    case 贷方余额 -> balance.getYearTotalBeginCredit();
                                                    case 余额, 期初余额 -> balance.getYearTotal();
                                                    case 末级借方余额 -> getLastYearTotalBegin(false, accountSets.getId(), subject1, balance, endDate, qBalance.yearTotalBeginDebit);
                                                    case 末级贷方余额 -> getLastYearTotalBegin(false, accountSets.getId(), subject1, balance, endDate, qBalance.yearTotalBeginCredit);
                                                    case 辅助核算借方余额 -> getLastYearTotalBegin(CollUtil.isNotEmpty(subject1.getAuxiliaryAccounting()), accountSets.getId(), subject1, balance, endDate, qBalance.yearTotalBeginDebit);
                                                    case 辅助核算贷方余额 -> getLastYearTotalBegin(CollUtil.isNotEmpty(subject1.getAuxiliaryAccounting()), accountSets.getId(), subject1, balance, endDate, qBalance.yearTotalBeginCredit);
                                                };
                                            }
                                        }
                                        vars.put(varName, NumberUtil.nullToZero(val));
                                        Subject subject = subjectMap.get(Long.valueOf(formula.getFromTag()));
                                        Assert.notNull(subject, "数据行：" + item.getTitle() + accountSets.getCompanyName()+"，公式科目不存在,请检查配置～");
                                        Assert.notNull(formula.getAccessRules(), "数据行：" + item.getTitle() + ",公式数据项【" + subject.getName() + "】未配置'取数规则'，请检查~");
                                        varValue.add(List.of(formula.getCalculation() + " " + subject.getFullName() + "(" + formula.getAccessRules().name() + ")", NumberUtil.decimalFormatMoney(NumberUtil.nullToZero(val).doubleValue())));
                                    }
                                    case DATA_ITEMS -> {
                                        //数据项取数
                                        ReportItemsDto calculatedItem = calculated.get(Long.parseLong(formula.getFromTag()));
                                        //存在于已计算中，且这一列已经计算
                                        if (calculatedItem != null && calculatedItem.getValues().containsKey(col.getTitle())) {
                                            vars.put(varName, calculatedItem.getValues().get(col.getTitle()));
                                            varValue.add(List.of(formula.getCalculation() + " " + calculatedItem.getTitle(), NumberUtil.decimalFormatMoney(NumberUtil.nullToZero(calculatedItem.getValues().get(col.getTitle())).doubleValue())));
                                        }
                                    }
                                }
                            }
                            if (CollUtil.isNotEmpty(express) && item.getFormulas().size() == vars.size()) {
                                String expression = CollUtil.join(express, "");
                                try {
                                    Expression exp = evaluator.compile("0" + expression);
                                    Object result = exp.execute(vars);
                                    if (result instanceof Number) {
                                        item.getValues().put(col.getTitle(), NumberUtil.round(NumberUtil.toBigDecimal(result.toString()), 2));
                                    } else {
                                        log.error("公式：{}，计算结果：{}，结果类型错误~", expression, result);
                                    }
                                } catch (Exception e) {
                                    log.error("公式：{}计算错误~", expression, e);
                                }
                            }
                            item.getFormulaValue().put(col.getTitle(), varValue);
                        }
                        if (item.getValues().size() == report.getReportCols().size()) {
                            //全部列计算完成
                            calculated.put(item.getId(), item);
                        }
                    } else {
                        //没有配置公式的项，直接存储到已计算列表中
                        for (Report.ReportCol col : report.getReportCols()) {
                            item.getValues().put(col.getTitle(), BigDecimal.ZERO);
                        }
                        calculated.put(item.getId(), item);
                    }
                    // 叠加现金流期初本年累计金额
                    if ("xjll".equalsIgnoreCase(templateKey) && item.getColInitAmount() != null) {
                        if (CollUtil.isNotEmpty(item.getFormulas())
                            && item.getFormulas().stream().anyMatch(it -> it.getSource().equals(FormulaSource.SUBJECTS))) {
                            for (String col : item.getColInitAmount().keySet()) {
                                BigDecimal bigDecimal = item.getValues().get(col);
                                if (bigDecimal != null && !item.getTitle().contains("期初现金余额")) {
                                    item.getValues().put(col, NumberUtil.add(item.getColInitAmount().get(col), bigDecimal));
                                } else {
                                    if (item.getTitle().contains("期初现金余额") && accountSets.getEnableDate().getYear() != startDate.getYear()) {
                                        item.getValues().put(col, bigDecimal);
                                    } else {
                                        item.getValues().put(col, item.getColInitAmount().get(col));
                                    }
                                }
                            }
                        }

                        // 现金的本期起始期间特殊处理，取期初的本年为当期期初
                        if (item.getTitle().contains("期初现金余额") && accountSets.getEnableDate().getMonth().equals(startDate.getMonth())) {
                            reportItems.stream().filter(it -> it.getTitle().contains("期末现金余额")).findFirst().ifPresent(r -> {
                                report.getReportCols().stream().filter(f -> ReportColDateLine.本年.equals(f.getDateLine())).findFirst().ifPresent(d -> {
                                    BigDecimal bigDecimal = r.getColInitAmount().get(d.getTitle());
                                    report.getReportCols().stream().filter(f -> ReportColDateLine.本期.equals(f.getDateLine())).findFirst().ifPresent(dr -> {
                                        item.getValues().put(dr.getTitle(), bigDecimal);
                                    });
                                });
                            });
                        }
                    }
                }
            }
            i++;
            if (i > 100) {
                List<String> errRows = new ArrayList<>();
                reportItems.forEach(it -> {
                    if (!calculated.containsKey(it.getId())) {
                        errRows.add(it.getTitle());
                        log.info("\n未计算：\n{}", JSON.toJSONString(it));
                    }
                });
                throw new ServiceException("报表行：" + CollUtil.join(errRows, ",") + " 计算失败,请检查配置~");
            }
        } while (calculated.size() != reportItems.size());
        return reportItems;
    }

    /**
     * 获取报表中涉及到的科目期间余额数据
     *
     * @param accountSetsId   账套ID
     * @param localCurrencyId 本币ID
     * @param dimension       数据维度
     * @param startDate       期间开始
     * @param endDate         期间结束
     * @param templateKey
     * @return
     */
    private Map<Long, BalanceDto> getBalanceMap(Long accountSetsId, Long localCurrencyId, String dimension, LocalDate startDate, LocalDate endDate, String templateKey) {
        Map<Long, BalanceDto> balanceMap = new HashMap<>();

        if ("QUARTER".equals(dimension)) {
            Dict params = Dict.create().set("accountSetsId", accountSetsId)
                    .set("balanceTableName", "xjll".equals(templateKey) ? FXY_FINANCIAL_BALANCE_CASH_FLOW : FXY_FINANCIAL_BALANCE)
                    .set("currencyId", localCurrencyId)
                    .set("startDate", startDate)
                    .set("endDate", endDate);
            List<Balance> beginBalance = lazyDao.findBySql("quarterBeginBalance", params, Balance.class);
            List<Balance> currentBalance = lazyDao.findBySql("quarterCurrentBalance", params, Balance.class);
            List<Balance> endBalance = lazyDao.findBySql("quarterEndBalance", params, Balance.class);
            int max = NumberUtil.max(beginBalance.size(), currentBalance.size(), endBalance.size());
            ArrayList<List<Balance>> list = CollUtil.newArrayList(beginBalance, currentBalance, endBalance);
            for (int i = 0; i < max; i++) {
                for (List<Balance> balanceList : list) {
                    if (CollUtil.isEmpty(balanceList) || balanceList.size() <= i) {
                        continue;
                    }
                    Balance balance = balanceList.get(i);
                    BalanceDto bMap = balanceMap.get(balance.getSubjectId());
                    if (bMap != null) {
                        BeanUtil.copyProperties(balance, bMap, CopyOptions.create().ignoreNullValue());
                    } else {
                        balanceMap.put(balance.getSubjectId(), BeanUtil.toBean(balance, BalanceDto.class));
                    }
                }
            }
        } else if ("xjll".equals(templateKey)) {
            balanceMap = bqf.selectFrom(qBalanceCashFlow)
                    .where(qBalanceCashFlow.accountSetsId.eq(accountSetsId)
                            .and(qBalanceCashFlow.currencyId.eq(localCurrencyId))
                            .and(qBalanceCashFlow.auxiliaryDetailId.eq(0L))
                            .and(qBalanceCashFlow.year.eq(startDate.getYear()))
                            .and(qBalanceCashFlow.period.eq(startDate.getMonthValue())))
                    .fetch().stream().collect(Collectors.toMap(BalanceCashFlow::getSubjectId, b -> BeanUtil.toBean(b, BalanceDto.class)));
        } else {
            balanceMap = bqf.selectFrom(qBalance)
                    .where(qBalance.accountSetsId.eq(accountSetsId)
                            .and(qBalance.currencyId.eq(localCurrencyId))
                            .and(qBalance.auxiliaryDetailId.eq(0L))
                            .and(qBalance.year.eq(startDate.getYear()))
                            .and(qBalance.period.eq(startDate.getMonthValue())))
                    .fetch().stream().collect(Collectors.toMap(Balance::getSubjectId, b -> BeanUtil.toBean(b, BalanceDto.class)));
        }
        return balanceMap;
    }

    /**
     * 发生额度汇总
     *
     * @param accountSetsId
     * @param enableDate
     * @param localCurrencyId
     * @param startDate
     * @param endDate
     * @param templateKey
     * @return
     */
    private Map<String, BalanceDto> getOccurrenceBalanceMap(Long accountSetsId, LocalDate enableDate, Long localCurrencyId, LocalDate startDate, LocalDate endDate, String templateKey) {
        Map<String, BalanceDto> balanceMap = new HashMap<>();
        List<BalanceDto> balanceDtoList = new ArrayList<>();
        // 年度汇总子查询条件
        BooleanExpression subExp = qVoucherDetails.accountSetsId.eq(accountSetsId)
                .and(qVoucherDetails.year.eq(startDate.getYear()))
                .and(qVoucherDetails.carryForward.isFalse())
                .and(qVoucherDetails.summary.notLike("%损益%")).and(qVoucherDetails.summary.notLike("%期末结转%"))
                .and(qVoucherDetails.currencyId.eq(localCurrencyId));

        List<ReportCashFlowDraft> cashFlowDrafts = new ArrayList<>();
        if ("xjll".equals(templateKey)) {
            subExp = subExp.and(qVoucherDetails.cashFlow.isTrue());
            cashFlowDrafts = bqf.selectFrom(qReportCashFlowDraft)
                    .where(qReportCashFlowDraft.accountSetsId.eq(accountSetsId)
                            .and(qReportCashFlowDraft.period.between(startDate.withMonth(1).withDayOfMonth(1), endDate.withDayOfMonth(1))))
                    .fetch();
        }

        // 借贷金额汇总
        NumberExpression<BigDecimal> debitYearAmount = qVoucherDetails.debitAmount.sum();
        NumberExpression<BigDecimal> debitForYearAmount = qVoucherDetails.debitAmountFor.sum();
        NumberExpression<BigDecimal> creditYearAmount = qVoucherDetails.creditAmount.sum();
        NumberExpression<BigDecimal> creditForYearAmount = qVoucherDetails.creditAmountFor.sum();

        // 年度按月汇总
        for (Tuple tuple1 : bqf.selectFrom(qVoucherDetails)
                .select(qSubject.id, qSubject.code, qSubject.balanceDirection, qVoucherDetails.year, qVoucherDetails.month, debitYearAmount, debitForYearAmount, creditYearAmount, creditForYearAmount)
                .join(qSubject).on(qSubject.id.eq(qVoucherDetails.subjectId).and(qVoucherDetails.accountSetsId.eq(qSubject.accountSetsId)))
                .where(subExp.and(qVoucherDetails.month.loe(endDate.getMonthValue())))
                .groupBy(qSubject.id, qSubject.code, qVoucherDetails.year, qVoucherDetails.month)
                .fetch()) {
            BalanceDto dto = new BalanceDto();
            dto.setSubjectId(tuple1.get(qSubject.id));
            dto.setSubjectCode(tuple1.get(qSubject.code));
            dto.setBalanceDirection(tuple1.get(qSubject.balanceDirection));
            dto.setYear(tuple1.get(qVoucherDetails.year));
            dto.setPeriod(tuple1.get(qVoucherDetails.month));
            dto.setYearTotalCredit(NumberUtil.add(tuple1.get(creditYearAmount), dto.getYearTotalCredit()));
            dto.setYearTotalCreditFor(NumberUtil.add(tuple1.get(creditForYearAmount), dto.getYearTotalCreditFor()));
            dto.setYearTotalDebit(NumberUtil.add(tuple1.get(debitYearAmount), dto.getYearTotalDebit()));
            dto.setYearTotalDebitFor(NumberUtil.add(tuple1.get(debitForYearAmount), dto.getYearTotalDebitFor()));
            balanceDtoList.add(dto);
        }

        if (CollUtil.isNotEmpty(cashFlowDrafts)) {
            for (ReportCashFlowDraft draft : cashFlowDrafts) {
                Optional<BalanceDto> optional = balanceDtoList.stream()
                        .filter(d -> d.getSubjectId().equals(Long.parseLong(draft.getFromTag()))
                                     && draft.getPeriod().equals(LocalDate.of(d.getYear(), d.getPeriod(), 1)))
                        .findFirst();
                optional.ifPresentOrElse(a -> {
                    if ("借".equals(a.getBalanceDirection())) {
                        a.setYearTotalDebit(NumberUtil.add(draft.getAmount(), a.getYearTotalDebit()));
                        a.setDebit(draft.getAmount());
                    } else {
                        a.setYearTotalCredit(NumberUtil.add(draft.getAmount(), a.getYearTotalCredit()));
                        a.setCredit(draft.getAmount());
                    }
                }, () -> {
                    Subject subject = subjectService.getById(new PrimaryKey(Long.parseLong(draft.getFromTag()), accountSetsId));
                    BalanceDto b = new BalanceDto();
                    b.setSubjectCode(subject.getCode());
                    b.setSubjectId(subject.getId());
                    b.setBalanceDirection(subject.getBalanceDirection());
                    b.setPeriod(draft.getPeriod().getMonthValue());
                    b.setYear(draft.getPeriod().getYear());
                    if ("借".equals(subject.getBalanceDirection())) {
                        b.setYearTotalDebit(draft.getAmount());
                        b.setDebit(draft.getAmount());
                    } else {
                        b.setYearTotalCredit(draft.getAmount());
                        b.setCredit(draft.getAmount());
                    }
                    balanceDtoList.add(b);
                });
            }
        }

        // 年度合计
        for (BalanceDto balanceDto : balanceDtoList) {
            String subjectCode = balanceDto.getSubjectCode();
            BalanceDto dto = balanceMap.getOrDefault(subjectCode, new BalanceDto());
            dto.setSubjectCode(subjectCode);
            dto.setYearTotalCredit(NumberUtil.add(balanceDto.getYearTotalCredit(), dto.getYearTotalCredit()));
            dto.setYearTotalCreditFor(NumberUtil.add(balanceDto.getYearTotalCreditFor(), dto.getYearTotalCreditFor()));
            dto.setYearTotalDebit(NumberUtil.add(balanceDto.getYearTotalDebit(), dto.getYearTotalDebit()));
            dto.setYearTotalDebitFor(NumberUtil.add(balanceDto.getYearTotalDebitFor(), dto.getYearTotalDebitFor()));
            if (balanceDto.getPeriod() >= startDate.getMonthValue()) {
                dto.setCredit(NumberUtil.add(balanceDto.getCredit(), dto.getCredit()));
                dto.setDebit(NumberUtil.add(balanceDto.getDebit(), dto.getDebit()));
            }
            balanceMap.put(subjectCode, dto);
        }


        // 启用日期同年的要叠加期初
        if (startDate.getYear() == enableDate.getYear()) {
            for (Tuple tuple : bqf.selectFrom(qBalanceInitial)
                    .select(qSubject.code, qSubject.balanceDirection, qBalanceInitial.yearTotalDebit, qBalanceInitial.yearTotalCredit, qBalanceInitial.yearTotalCreditFor, qBalanceInitial.yearTotalDebitFor)
                    .join(qSubject).on(qSubject.id.eq(qBalanceInitial.subjectId).and(qBalanceInitial.accountSetsId.eq(qSubject.accountSetsId)))
                    .where(qBalanceInitial.accountSetsId.eq(accountSetsId)
                            .and(qSubject.leaf.isTrue())
                            .and(qBalanceInitial.currencyId.eq(localCurrencyId))
                            .and(qBalanceInitial.auxiliaryDetailId.eq(0L)))
                    .fetch()) {
                BalanceDto dto = balanceMap.get(tuple.get(qSubject.code));
                if (dto == null) {
                    dto = new BalanceDto();
                    dto.setSubjectCode(tuple.get(qSubject.code));
                }
                switch (tuple.get(qSubject.balanceDirection)) {
                    case "贷":
                        dto.setYearTotalCredit(NumberUtil.add(dto.getYearTotalCredit(), tuple.get(qBalanceInitial.yearTotalCredit)));
                        dto.setYearTotalCreditFor(NumberUtil.add(dto.getYearTotalCreditFor(), tuple.get(qBalanceInitial.yearTotalCreditFor)));
                        break;
                    case "借":
                        dto.setYearTotalDebit(NumberUtil.add(dto.getYearTotalDebit(), tuple.get(qBalanceInitial.yearTotalDebit)));
                        dto.setYearTotalDebitFor(NumberUtil.add(dto.getYearTotalDebitFor(), tuple.get(qBalanceInitial.yearTotalDebitFor)));
                        break;
                }
                balanceMap.put(tuple.get(qSubject.code), dto);
            }
        }

        // 当前汇总
        bqf.selectFrom(qVoucherDetails)
                .select(qSubject.code,
                        qVoucherDetails.debitAmount.sum(),
                        qVoucherDetails.creditAmount.sum(),
                        qVoucherDetails.creditAmountFor.sum(),
                        qVoucherDetails.debitAmountFor.sum())
                .join(qSubject).on(qSubject.id.eq(qVoucherDetails.subjectId).and(qVoucherDetails.accountSetsId.eq(qSubject.accountSetsId)))
                .where(subExp.and(qVoucherDetails.month.between(startDate.getMonthValue(), endDate.getMonthValue())))
                .groupBy(qSubject.code)
                .fetch().forEach(tuple -> {
                    BalanceDto dto = balanceMap.getOrDefault(tuple.get(qSubject.code), new BalanceDto());
                    dto.setSubjectCode(tuple.get(qSubject.code));
                    // 不等于0时，说明现金流底稿已经赋值
                    if (NumberUtil.equals(dto.getDebit(), BigDecimal.ZERO)) {
                        dto.setDebit(tuple.get(qVoucherDetails.debitAmount.sum()));
                        dto.setDebitFor(tuple.get(qVoucherDetails.debitAmountFor.sum()));
                    }
                    if (NumberUtil.equals(dto.getCredit(), BigDecimal.ZERO)) {
                        dto.setCredit(tuple.get(qVoucherDetails.creditAmount.sum()));
                        dto.setCreditFor(tuple.get(qVoucherDetails.creditAmountFor.sum()));
                    }
                    balanceMap.put(tuple.get(qSubject.code), dto);
                });
        return balanceMap;
    }

    private BigDecimal getEndBalance(boolean isAuxiliary, Long accountSetsId, Subject subject1, BalanceDto balance, LocalDate startDate, LocalDate endDate, NumberPath<BigDecimal> qField) {
        BooleanExpression expression = qBalance.accountSetsId.eq(accountSetsId)
                .and(subject1.getLeaf() ? qBalance.subjectId.eq(balance.getSubjectId()) : qSubject.code.like(subject1.getCode() + "%").and(qBalance.subjectId.ne(balance.getSubjectId())))
                .and(qSubject.leaf.isTrue())
                .and(qField.gt(BigDecimal.ZERO))
                .and(qBalance.date.between(startDate, endDate));
        if (isAuxiliary) {
            expression = expression.and(qBalance.auxiliaryDetailId.ne(0L));
        } else {
            expression = expression.and(qBalance.auxiliaryDetailId.eq(0L));
        }

        return bqf.selectFrom(qBalance)
                .select(qField.sum().abs())
                .innerJoin(qSubject).on(qSubject.accountSetsId.eq(qBalance.accountSetsId).and(qBalance.subjectId.eq(qSubject.id)))
                .where(expression)
                .fetchFirst();
    }

    private BigDecimal getLastYearTotalBegin(boolean isAuxiliary, Long accountSetsId, Subject subject1, BalanceDto balance, LocalDate endDate, NumberPath<BigDecimal> qField) {
        BooleanExpression expression = qBalance.accountSetsId.eq(accountSetsId)
                .and(subject1.getLeaf() ? qBalance.subjectId.eq(balance.getSubjectId()) : qSubject.code.like(subject1.getCode() + "%").and(qBalance.subjectId.ne(balance.getSubjectId())))
                .and(qSubject.leaf.isTrue())
                .and(qField.gt(BigDecimal.ZERO))
                .and(qBalance.date.between(endDate.withDayOfMonth(1), endDate.withDayOfMonth(endDate.lengthOfMonth())));
        if (isAuxiliary) {
            expression = expression.and(qBalance.auxiliaryDetailId.ne(0L));
        } else {
            expression = expression.and(qBalance.auxiliaryDetailId.eq(0L));
        }
        return bqf.selectFrom(qBalance)
                .select(qField.sum().abs())
                .innerJoin(qSubject).on(qSubject.accountSetsId.eq(qBalance.accountSetsId).and(qBalance.subjectId.eq(qSubject.id)))
                .where(expression)
                .fetchFirst();
    }

    /**
     * 归档
     *
     * @param userVo 账套ID
     * @return
     */
    @Override
    public CompletableFuture<Boolean> archive(UserVo userVo) {
        AccountSets accountSets = userVo.getAccountSets();
        try {
            List<Checkout> checkoutList = userVo.getCheckoutList();

            Map<Integer, List<Checkout>> listMap = checkoutList.stream().collect(Collectors.groupingBy(Checkout::getCheckYear));

            //报表列表
            List<Report> reports = this.list(accountSets.getId());

            for (Map.Entry<Integer, List<Checkout>> entry : listMap.entrySet()) {
                List<String> sheetNames = new ArrayList<>();
                List<Dict> dataList = new ArrayList<>();
                List<Checkout> checkouts = entry.getValue();
                checkouts.sort(Comparator.comparing(Checkout::getCheckDate));
                for (Checkout checkout : checkouts) {
                    for (Report report : reports) {
                        List<ReportItemsDto> items = this.items(report.getTemplateKey(), accountSets.getId(), false);
                        if (report.getIsDefault() && CollUtil.isNotEmpty(items)) {
                            List<Dict> headers = getHeaders(report, items);

                            sheetNames.add(report.getName() + "(" + checkout.getCheckMonth() + "月)");
                            List<ReportItemsDto> zcfz = this.reportData(items, accountSets, report.getTemplateKey(), userVo.getLocalCurrencyId(), checkout.getCheckDate(), "");

                            List<Tree<Long>> treeList = TreeUtil.build(zcfz, null, (item, tree) -> {
                                tree.setId(item.getId());
                                tree.setParentId(item.getParentId());
                                tree.setName(item.getTitle());
                                tree.putExtra("item", item);
                            });
                            List<Dict> rows = new ArrayList<>();
                            items = items.stream().filter(s -> s.getLevel() == 1 && s.getIsClassified()).toList();
                            if (CollUtil.isNotEmpty(items)) {
                                for (Tree<Long> node : treeList) {
                                    buildRow(node.getChildren(), rows, node.getName(), 0, new AtomicInteger(0));
                                }
                            } else {
                                buildRow(treeList, rows, "项目", 0, new AtomicInteger(0));
                            }

                            dataList.add(Dict.create()
                                    .set("report", report)
                                    .set("headers", headers)
                                    .set("accountSets", accountSets)
                                    .set("checkDate", checkout.getCheckDate().format(DateTimeFormatter.ofPattern("yyyy年MM期")))
                                    .set("reportData", rows));
                        }
                    }
                }
                if (CollUtil.isNotEmpty(sheetNames)) {
                    Dict dict = Dict.create()
                            .set("sheetNames", sheetNames)
                            .set("dataList", dataList);
                    File archiveDir = appConfig.getUploadRoot("ast-" + userVo.getAccountSets().getId() + "/archive/" + entry.getKey() + "年");
                    FileUtil.mkdir(archiveDir);
                    String fileName = "05财务报表(" + entry.getKey() + "年)_" + userVo.getAccountSets().getCompanyName() + ".xlsx";
                    File docFile = new File(archiveDir, fileName);
                    OutputStream os = new FileOutputStream(docFile);
                    JxlsUtils.exportExcel("template/report.xlsx", os, dict);
                }
            }
        } catch (Exception e) {
            log.error("\n{}归档异常", accountSets.getId(), e);
            return CompletableFuture.completedFuture(false);
        }

        return CompletableFuture.completedFuture(true);
    }

    private void buildRow(List<Tree<Long>> children, List<Dict> rows, CharSequence colTitle, int lv, AtomicInteger startIndex) {
        if (children == null) {
            return;
        }
        for (Tree<Long> child : children) {
            if (rows.size() <= startIndex.get() || rows.get(startIndex.get()) == null) {
                rows.add(startIndex.get(), Dict.create());
            }
            Map<String, Object> row = rows.get(startIndex.get());
            ReportItemsDto item = (ReportItemsDto) child.get("item");
            row.put(colTitle.toString(), StrUtil.repeat("   ", lv) + child.getName());
            row.put(colTitle + "isBolder", item.getIsBolder());
            row.put(colTitle + "rowNum", item.getRowNum());
            row.put(colTitle + "lv", lv);
            row.put(colTitle + "editFormulas", item.getEditFormulas());
            row.put(colTitle + "id", item.getId());

            for (String key : item.getValues().keySet()) {
                row.put(colTitle + key, item.getValues().get(key) == null || NumberUtil.equals(item.getValues().get(key), BigDecimal.ZERO) ? "" : NumberUtil.round(item.getValues().get(key), 2));
            }

            startIndex.incrementAndGet();

            if (CollUtil.isNotEmpty(child.getChildren())) {
                buildRow(child.getChildren(), rows, colTitle, lv + 1, startIndex);
            }
        }
    }

    private List<Dict> getHeaders(Report report, List<ReportItemsDto> items) {
        List<Dict> headers = new ArrayList<>();
        items = items.stream().filter(s -> s.getLevel() == 1 && s.getIsClassified()).toList();
        if (CollUtil.isNotEmpty(items)) {
            for (ReportItemsDto item : items) {
                headers.add(Dict.create()
                        .set("title", item.getTitle())
                        .set("key", item.getTitle())
                        .set("isTitle", true));
                headers.add(Dict.create()
                        .set("title", "行次")
                        .set("key", item.getTitle() + "rowNum")
                        .set("isTitle", true)
                        .set("isRowNum", true));
                for (Report.ReportCol reportCol : report.getReportCols()) {
                    headers.add(Dict.create()
                            .set("title", reportCol.getTitle())
                            .set("number", true)
                            .set("key", item.getTitle() + reportCol.getTitle())
                            .set("isTitle", true));
                }
            }
        } else {
            headers.add(Dict.create()
                    .set("title", "项目")
                    .set("key", "项目")
                    .set("isTitle", true));
            headers.add(Dict.create()
                    .set("title", "行次")
                    .set("key", "项目rowNum")
                    .set("isTitle", true)
                    .set("isRowNum", true));
            for (Report.ReportCol reportCol : report.getReportCols()) {
                headers.add(Dict.create()
                        .set("title", reportCol.getTitle())
                        .set("number", true)
                        .set("key", "项目" + reportCol.getTitle()));
            }
        }
        return headers;
    }

    public List<FeeDetailDto> feeDetail(AccountSets accountSets, FeeDetailQuery query) {
        Long accountSetsId = accountSets.getId();
        // 科目查询条件
        BooleanExpression sExp = qSubject.accountSetsId.eq(accountSetsId).and(qSubject.type.eq(SubjectCategory.INCOME.display));
        if (CollUtil.isNotEmpty(query.getSubjectId())) {
            sExp = sExp.and(qSubject.id.in(query.getSubjectId()));
        } else {
            switch (accountSets.getAccountStandard()) {
                case BUSINESS, BUSINESS20191, BUSINESS20192 -> sExp = sExp.and(qSubject.code.startsWith("66"));
                default -> sExp = sExp.and(qSubject.code.startsWith("560"));
            }
        }
        // 所有损益科目
        List<Subject> subjects = bqf.selectFrom(qSubject)
                .where(sExp)
                .orderBy(qSubject.queryCode.asc(), qSubject.id.asc())
                .fetch();
        // 损益科目Id集合
        Set<Long> subjectIdList = subjects.stream().map(Subject::getId).collect(Collectors.toSet());
        // 根据余额方向取发生额
        NumberExpression<BigDecimal> amount = qSubject.balanceDirection.when("借").then(qBalance.debit).otherwise(qBalance.credit);
        // 损益科目按科目和年份分组后的发生金额数据
        Map<Long, Map<Integer, List<FeeDetailBalanceDto>>> amountList = bqf.selectFrom(qBalance)
                .select(qBalance.subjectId, qBalance.year,
                        qBalance.period, qBalance.date,
                        amount,
                        qBalance.debit, qBalance.credit)
                .join(qSubject).on(qSubject.id.eq(qBalance.subjectId).and(qSubject.accountSetsId.eq(qBalance.accountSetsId)))
                .where(qBalance.accountSetsId.eq(accountSetsId)
                        .and(qBalance.subjectId.in(subjectIdList))
                        .and(qBalance.date.between(query.getStart(), query.getEnd()))
                        .and(qBalance.auxiliaryDetailId.eq(0L)))
                .fetch().stream().map(tuple -> {
                    FeeDetailBalanceDto dto = new FeeDetailBalanceDto();
                    dto.setSubjectId(tuple.get(qBalance.subjectId));
                    dto.setYear(tuple.get(qBalance.year));
                    dto.setPeriod(tuple.get(qBalance.period));
                    dto.setAmount(tuple.get(amount));
                    return dto;
                }).collect(Collectors.groupingBy(FeeDetailBalanceDto::getSubjectId, Collectors.groupingBy(FeeDetailBalanceDto::getYear)));

        List<FeeDetailDto> feeDetails = new ArrayList<>();
        for (Subject subject : subjects) {
            FeeDetailDto fd = BeanUtil.toBean(subject, FeeDetailDto.class);
            //按年份的金额列表
            Map<Integer, List<FeeDetailBalanceDto>> yearAmountList = amountList.get(fd.getId());
            if (CollUtil.isNotEmpty(yearAmountList)) {
                yearAmountList.forEach((year, dataList) -> {
                    fd.getDetails().addAll(dataList);
                    //算年度合计
                    BigDecimal total = dataList.stream().map(FeeDetailBalanceDto::getAmount).reduce(BigDecimal::add).orElse(BigDecimal.ZERO);
                    FeeDetailBalanceDto yearTotal = BeanUtil.toBean(dataList.get(0), FeeDetailBalanceDto.class);
                    yearTotal.setYearTotal(true);
                    yearTotal.setAmount(total);
                    fd.getDetails().add(yearTotal);
                });
            }
            feeDetails.add(fd);
        }
        return feeDetails;
    }

    /**
     * 保存报表列期初金额，只有现金流期初使用
     *
     * @param accountSetsId
     * @param reportItems
     */
    @Transactional
    public void saveInitColData(Long accountSetsId, List<ReportItems> reportItems) {
        for (ReportItems reportItem : reportItems) {
            jqf.update(qReportItems)
                    .set(qReportItems.colInitAmount, reportItem.getColInitAmount())
                    .where(qReportItems.id.eq(reportItem.getId())
                            .and(qReportItems.accountSetsId.eq(accountSetsId)))
                    .execute();
        }
    }

    /**
     * 导出资产负债表Excel
     */
    public void exportToExcel(List<ReportItemsDto> reportItems, AccountSets accountSets,
                             String templateKey, LocalDate voucherDate, String dimension,
                             jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {

        // 获取报表信息
        Report report = bqf.selectFrom(qReport)
                .where(qReport.templateKey.eq(templateKey).and(qReport.accountSetsId.eq(accountSets.getId())))
                .fetchFirst();

        if (report == null) {
            throw new ServiceException("报表模板不存在");
        }

        // 直接使用平铺的数据结构，按 isClassified 分组
        List<ReportItemsDto> leftColumnItems = new ArrayList<>();
        List<ReportItemsDto> rightColumnItems = new ArrayList<>();

        // 先找到分类项
        List<ReportItemsDto> classifiedItems = reportItems.stream()
                .filter(item -> item.getIsClassified() != null && item.getIsClassified())
                .sorted(Comparator.comparing(ReportItemsDto::getPos))
                .collect(Collectors.toList());

        for (ReportItemsDto classifiedItem : classifiedItems) {
            String title = classifiedItem.getTitle();
            if (title.contains("资产") && !title.contains("负债")) {
                // 获取该分类下的所有子项目
                List<ReportItemsDto> children = getChildrenRecursive(reportItems, classifiedItem.getId());
                leftColumnItems.addAll(children);
            } else if (title.contains("负债") || title.contains("所有者权益") || title.contains("股东权益")) {
                // 获取该分类下的所有子项目
                List<ReportItemsDto> children = getChildrenRecursive(reportItems, classifiedItem.getId());
                rightColumnItems.addAll(children);
            }
        }

        // 根据报表类型选择导出方法
        if ("zcfz".equals(templateKey)) {
            // 资产负债表导出
            cn.gson.financial.kernel.common.ExcelUtils.exportBalanceSheet(
                response,
                report.getName(),
                accountSets,
                voucherDate,
                dimension,
                leftColumnItems,
                rightColumnItems
            );
        } else if ("lr".equals(templateKey)) {
            // 利润表导出
            cn.gson.financial.kernel.common.ExcelUtils.exportIncomeStatement(
                response,
                report.getName(),
                accountSets,
                voucherDate,
                dimension,
                reportItems
            );
        } else if ("xjll".equals(templateKey)) {
            // 现金流量表导出
            cn.gson.financial.kernel.common.ExcelUtils.exportCashFlowStatement(
                response,
                report.getName(),
                accountSets,
                voucherDate,
                dimension,
                reportItems
            );
        } else {
            throw new ServiceException("暂不支持该报表类型的导出");
        }
    }

    /**
     * 导出财务报表Excel为字节数组
     */
    public byte[] exportToExcelBytes(List<ReportItemsDto> reportItems, AccountSets accountSets,
                                    String templateKey, LocalDate voucherDate, String dimension) throws java.io.IOException {

        // 获取报表信息
        Report report = bqf.selectFrom(qReport)
                .where(qReport.templateKey.eq(templateKey).and(qReport.accountSetsId.eq(accountSets.getId())))
                .fetchFirst();

        if (report == null) {
            throw new ServiceException("报表模板不存在");
        }

        // 根据报表类型选择导出方法
        if ("zcfz".equals(templateKey)) {
            // 资产负债表导出
            // 直接使用平铺的数据结构，按 isClassified 分组
            List<ReportItemsDto> leftColumnItems = new ArrayList<>();
            List<ReportItemsDto> rightColumnItems = new ArrayList<>();

            // 先找到分类项
            List<ReportItemsDto> classifiedItems = reportItems.stream()
                    .filter(item -> item.getIsClassified() != null && item.getIsClassified())
                    .sorted(Comparator.comparing(ReportItemsDto::getPos))
                    .collect(Collectors.toList());

            for (ReportItemsDto classifiedItem : classifiedItems) {
                String title = classifiedItem.getTitle();
                if (title.contains("资产") && !title.contains("负债")) {
                    // 获取该分类下的所有子项目
                    List<ReportItemsDto> children = getChildrenRecursive(reportItems, classifiedItem.getId());
                    leftColumnItems.addAll(children);
                } else if (title.contains("负债") || title.contains("所有者权益") || title.contains("股东权益")) {
                    // 获取该分类下的所有子项目
                    List<ReportItemsDto> children = getChildrenRecursive(reportItems, classifiedItem.getId());
                    rightColumnItems.addAll(children);
                }
            }

            return cn.gson.financial.kernel.common.ExcelUtils.exportBalanceSheetBytes(
                report.getName(),
                accountSets,
                voucherDate,
                dimension,
                leftColumnItems,
                rightColumnItems
            );
        } else if ("lr".equals(templateKey)) {
            // 利润表导出
            return cn.gson.financial.kernel.common.ExcelUtils.exportIncomeStatementBytes(
                report.getName(),
                accountSets,
                voucherDate,
                dimension,
                reportItems
            );
        } else if ("xjll".equals(templateKey)) {
            // 现金流量表导出
            return cn.gson.financial.kernel.common.ExcelUtils.exportCashFlowStatementBytes(
                report.getName(),
                accountSets,
                voucherDate,
                dimension,
                reportItems
            );
        } else {
            throw new ServiceException("暂不支持该报表类型的导出");
        }
    }


    /**
     * 导出财务报表Excel到流 - 流式传输
     */
    public void exportToExcelStream(List<ReportItemsDto> reportItems, AccountSets accountSets,
                                    String templateKey, LocalDate voucherDate, String dimension,
                                    java.io.OutputStream outputStream) throws java.io.IOException {

        // 获取报表信息
        Report report = bqf.selectFrom(qReport)
                .where(qReport.templateKey.eq(templateKey).and(qReport.accountSetsId.eq(accountSets.getId())))
                .fetchFirst();

        if (report == null) {
            throw new ServiceException("报表模板不存在");
        }

        // 根据报表类型选择导出方法
        if ("zcfz".equals(templateKey)) {
            // 资产负债表导出
            // 直接使用平铺的数据结构，按 isClassified 分组
            List<ReportItemsDto> leftColumnItems = new ArrayList<>();
            List<ReportItemsDto> rightColumnItems = new ArrayList<>();

            // 先找到分类项
            List<ReportItemsDto> classifiedItems = reportItems.stream()
                    .filter(item -> item.getIsClassified() != null && item.getIsClassified())
                    .sorted(Comparator.comparing(ReportItemsDto::getPos))
                    .collect(Collectors.toList());

            for (ReportItemsDto classifiedItem : classifiedItems) {
                String title = classifiedItem.getTitle();
                if (title.contains("资产") && !title.contains("负债")) {
                    // 获取该分类下的所有子项目
                    List<ReportItemsDto> children = getChildrenRecursive(reportItems, classifiedItem.getId());
                    leftColumnItems.addAll(children);
                } else if (title.contains("负债") || title.contains("所有者权益") || title.contains("股东权益")) {
                    // 获取该分类下的所有子项目
                    List<ReportItemsDto> children = getChildrenRecursive(reportItems, classifiedItem.getId());
                    rightColumnItems.addAll(children);
                }
            }

            cn.gson.financial.kernel.common.ExcelUtils.exportBalanceSheetStream(
                    report.getName(),
                    accountSets,
                    voucherDate,
                    dimension,
                    leftColumnItems,
                    rightColumnItems,
                    outputStream
            );
        } else if ("lr".equals(templateKey)) {
            // 利润表导出
            cn.gson.financial.kernel.common.ExcelUtils.exportIncomeStatementStream(
                    report.getName(),
                    accountSets,
                    voucherDate,
                    dimension,
                    reportItems,
                    outputStream
            );
        } else if ("xjll".equals(templateKey)) {
            // 现金流量表导出
            cn.gson.financial.kernel.common.ExcelUtils.exportCashFlowStatementStream(
                    report.getName(),
                    accountSets,
                    voucherDate,
                    dimension,
                    reportItems,
                    outputStream
            );
        } else {
            throw new ServiceException("暂不支持该报表类型的导出");
        }
    }

    /**
     * 导出合并报表（年报.xls 或 月季报.xls），填充资产负债表(Sheet0) + 利润表(Sheet1)
     * Sheet2(现金流量表) 保持模板原样
     */
    public void exportCombinedReport(AccountSets accountSets, LocalDate voucherDate,
                                     String dimension, boolean isYear,
                                     java.io.OutputStream outputStream) throws java.io.IOException {
        boolean reset = Boolean.TRUE.equals(accountSets.getResetStatus());
        Long currencyId = getLocalCurrencyId(accountSets.getId());
        String templateName = isYear
                ? "财务报表报送与信息采集（小企业会计准则）年报.xls"
                : "财务报表报送与信息采集（小企业会计准则）月季报.xls";

        try (InputStream is = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream("template/" + templateName);
             Workbook wb = WorkbookFactory.create(is)) {

            // Sheet[0]：资产负债表，始终用TERM（时间点快照，不区分月/季）
            List<ReportItemsDto> zcfzRaw = items("zcfz", accountSets.getId(), reset);
            List<ReportItemsDto> zcfzData = reportData(zcfzRaw, accountSets, "zcfz", currencyId, voucherDate, "TERM");
            List<ReportItemsDto> left = new ArrayList<>(), right = new ArrayList<>();
            zcfzData.stream()
                    .filter(i -> Boolean.TRUE.equals(i.getIsClassified()))
                    .sorted(Comparator.comparing(ReportItemsDto::getPos))
                    .forEach(c -> {
                        List<ReportItemsDto> children = getChildrenRecursive(zcfzData, c.getId());
                        if (c.getTitle().contains("资产") && !c.getTitle().contains("负债")) left.addAll(children);
                        else if (c.getTitle().contains("负债") || c.getTitle().contains("所有者权益") || c.getTitle().contains("股东权益")) right.addAll(children);
                    });
            Sheet zcfzSheet = wb.getSheetAt(0);
            LocalDate zcfzStart = voucherDate.withDayOfMonth(1);
            fillCombinedReportHeader(zcfzSheet, accountSets, zcfzStart, voucherDate, true);
            fillBalanceSheetData(zcfzSheet, left, right);

            // Sheet[1]：利润表，月报/季报用实际dimension，年报用TERM取本年累计金额
            String lrDimension = isYear ? "TERM" : dimension;
            List<ReportItemsDto> lrRaw = items("lr", accountSets.getId(), reset);
            // lrStart 须先算出，用于 reportData（取数起始月）和表头填充
            LocalDate lrStart;
            if (isYear) {
                lrStart = LocalDate.of(voucherDate.getYear(), 1, 1);
            } else if ("QUARTER".equals(dimension)) {
                int startMonth = ((voucherDate.getMonthValue() - 1) / 3) * 3 + 1;
                lrStart = LocalDate.of(voucherDate.getYear(), startMonth, 1);
            } else {
                lrStart = voucherDate.withDayOfMonth(1);
            }
            List<ReportItemsDto> lrData = reportData(lrRaw, accountSets, "lr", currencyId, lrStart, lrDimension);
            Sheet lrSheet = wb.getSheetAt(1);
            fillCombinedReportHeader(lrSheet, accountSets, lrStart, voucherDate, false);
            fillIncomeStatementData(lrSheet, lrData, isYear);

            wb.write(outputStream);
        }
    }

    /**
     * 递归获取子项目
     */
    private List<ReportItemsDto> getChildrenRecursive(List<ReportItemsDto> allItems, Long parentId) {
        List<ReportItemsDto> children = new ArrayList<>();

        // 获取直接子项目
        List<ReportItemsDto> directChildren = allItems.stream()
                .filter(item -> parentId.equals(item.getParentId()))
                .sorted(Comparator.comparing(ReportItemsDto::getPos))
                .collect(Collectors.toList());

        for (ReportItemsDto child : directChildren) {
            children.add(child);
            // 递归获取孙项目
            children.addAll(getChildrenRecursive(allItems, child.getId()));
        }

        return children;
    }

    /**
     * 下载报表到本地
     *  资产负债表、利润表（月报或季报）
     * @param downLoadReportVo
     */
    public void downLoadReport(DownLoadReportVo downLoadReportVo) {
        List<AccountSets> accountSetsList = getCheckOutAccountSets(downLoadReportVo.getVoucherDate(), downLoadReportVo.getAccountSetsIdList());
        if (CollUtil.isEmpty(accountSetsList)) return;

        boolean isYear = Boolean.TRUE.equals(downLoadReportVo.getLrIsYear());
        boolean isQuarter = Boolean.TRUE.equals(downLoadReportVo.getLrIsQuarter());
        LocalDate voucherDate = downLoadReportVo.getVoucherDate();

        accountSetsList.forEach(accountSets -> {
            Long accountSetId = accountSets.getId();
            Long currencyId = getLocalCurrencyId(accountSetId);
            File dir = new File(basePath, accountSets.getProvince());
            dir.mkdirs();

            // 资产负债表
            List<ReportItemsDto> zcfzRaw = items("zcfz", accountSetId, accountSets.getResetStatus());
            List<ReportItemsDto> zcfzData = reportData(zcfzRaw, accountSets, "zcfz", currencyId, voucherDate, "TERM");
            List<ReportItemsDto> left = new ArrayList<>(), right = new ArrayList<>();
            zcfzData.stream()
                    .filter(i -> Boolean.TRUE.equals(i.getIsClassified()))
                    .sorted(Comparator.comparing(ReportItemsDto::getPos))
                    .forEach(c -> {
                        List<ReportItemsDto> children = getChildrenRecursive(zcfzData, c.getId());
                        if (c.getTitle().contains("资产") && !c.getTitle().contains("负债")) left.addAll(children);
                        else if (c.getTitle().contains("负债") || c.getTitle().contains("所有者权益") || c.getTitle().contains("股东权益")) right.addAll(children);
                    });
            saveReport(dir, accountSets.getCompanyName() + "资产负债表.xls",
                    "financial-statements-balance-sheet.xls", 0, left, right, null, accountSets.getCompanyName(), voucherDate, "zcfz", "TERM");

            // 利润表（年报不需要）
            if (!isYear) {
                String lrDimension = isQuarter ? "QUARTER" : "TERM";
                LocalDate lrDate = isQuarter ? cn.gson.financial.kernel.common.DateUtil.getFirstDayOfQuarter(voucherDate) : voucherDate;
                List<ReportItemsDto> lrRaw = items("lr", accountSetId, false);
                List<ReportItemsDto> lrData = reportData(lrRaw, accountSets, "lr", currencyId, lrDate, lrDimension);
                saveReport(dir, accountSets.getCompanyName() + "利润表.xls",
                        "financial-statements-income-statement.xls", 0, null, null, lrData, accountSets.getCompanyName(), lrDate, "lr", lrDimension);
            }
        });
    }

    private void saveReport(File dir, String fileName, String templateName, int sheetIndex,
                            List<ReportItemsDto> left, List<ReportItemsDto> right, List<ReportItemsDto> items,
                            String companyName, LocalDate voucherDate, String templateKey, String dimension) {
        File outFile = new File(dir, fileName);
        try (FileOutputStream fos = new FileOutputStream(outFile)) {
            fillAndWrite(templateName, sheetIndex, left, right, items, companyName, voucherDate, templateKey, dimension, fos);
            log.info("报表导出成功: {}", outFile.getAbsolutePath());
        } catch (Exception e) {
            log.error("{}导出失败", outFile.getName(), e);
        }
    }

    /**
     * 加载模板、填充数据、写出到流
     * 资产负债表传 left/right，利润表传 items
     */
    private void fillAndWrite(String templateName, int sheetIndex,
                              List<ReportItemsDto> left, List<ReportItemsDto> right, List<ReportItemsDto> items,
                              String companyName, LocalDate voucherDate, String templateKey, String dimension,
                              OutputStream outputStream) throws IOException {
        try (InputStream is = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream("template/" + templateName);
             Workbook wb = WorkbookFactory.create(is)) {
            Sheet sheet = wb.getSheetAt(sheetIndex);
            fillReportHeader(sheet, companyName, voucherDate, templateKey, dimension);
            if ("zcfz".equals(templateKey)) {
                fillBalanceSheetData(sheet, left, right);
            } else {
                fillIncomeStatementData(sheet, items);
            }
            wb.write(outputStream);
        }
    }

    /**
     * 填充合并模板（年报/月季报）的表头
     * zcfz(9列): R2 col3=纳税人识别号, col7=纳税人名称; R3 col3=所属期起, col7=所属期止
     * lr(6列):   R2 col3=纳税人识别号, col5=纳税人名称; R3 col3=所属期起, col5=所属期止
     */
    private void fillCombinedReportHeader(Sheet sheet, AccountSets accountSets,
                                          LocalDate periodStart, LocalDate periodEnd, boolean isZcfz) {
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy年MM月dd日");
        int nameCol = isZcfz ? 7 : 5;
        int endCol  = isZcfz ? 7 : 5;

        Row r2 = sheet.getRow(2);
        if (r2 != null) {
            setCellStr(r2, 3, accountSets.getCreditCode());
            setCellStr(r2, nameCol, accountSets.getCompanyName());
        }
        Row r3 = sheet.getRow(3);
        if (r3 != null) {
            setCellStr(r3, 3, periodStart.format(fmt));
            setCellStr(r3, endCol, periodEnd.format(fmt));
        }
    }

    private void setCellStr(Row row, int col, String value) {
        if (value == null) return;
        Cell cell = row.getCell(col);
        if (cell == null) cell = row.createCell(col);
        cell.setCellValue(value);
    }

    /** 填充表头：公司名、账期 */
    private void fillReportHeader(Sheet sheet, String companyName, LocalDate voucherDate,
                                  String templateKey, String dimension) {
        Row row = sheet.getRow(2);
        if (row == null) return;
        // 编制单位
        Cell companyCell = row.getCell(0);
        if (companyCell == null) companyCell = row.createCell(0);
        companyCell.setCellValue("编制单位：" + companyName);
        // 账期（资产负债表在第3列，利润表在第2列）
        int dateCol = "zcfz".equals(templateKey) ? 2 : 1;
        Cell dateCell = row.getCell(dateCol);
        if (dateCell == null) dateCell = row.createCell(dateCol);
        dateCell.setCellValue(formatVoucherDate(voucherDate, dimension));
    }

    /** 按顺序位置填充资产负债表左右两栏数值：数据侧跳过分类头，模板侧跳过无行次的行 */
    private void fillBalanceSheetData(Sheet sheet, List<ReportItemsDto> left, List<ReportItemsDto> right) {
        log.debug("fillBalanceSheetData left={} right={}", left.size(), right.size());
        Iterator<ReportItemsDto> leftIter = left.stream()
                .filter(i -> i.getRowNum() != null && !i.getRowNum().trim().isEmpty())
                .iterator();
        Iterator<ReportItemsDto> rightIter = right.stream()
                .filter(i -> i.getRowNum() != null && !i.getRowNum().trim().isEmpty())
                .iterator();
        for (int r = 4; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            // 左栏：检查行次列(col2)，有数字值才是数据行（排除"行次"文字表头和分类头行）
            if (isDataRowCell(row.getCell(2)) && leftIter.hasNext()) {
                Map<String, BigDecimal> vals = leftIter.next().getValues();
                if (vals != null) {
                    setNumCell(row, 3, vals.get("期末余额"));
                    setNumCell(row, 4, vals.get("年初余额"));
                }
            }
            // 右栏：检查行次列(col6)，有数字值才是数据行
            if (isDataRowCell(row.getCell(6)) && rightIter.hasNext()) {
                Map<String, BigDecimal> vals = rightIter.next().getValues();
                if (vals != null) {
                    setNumCell(row, 7, vals.get("期末余额"));
                    setNumCell(row, 8, vals.get("年初余额"));
                }
            }
        }
    }

    /** 按顺序位置填充利润表数值：数据侧跳过分类头，模板侧跳过无行次的行 */
    private void fillIncomeStatementData(Sheet sheet, List<ReportItemsDto> items) {
        fillIncomeStatementData(sheet, items, false);
    }

    private void fillIncomeStatementData(Sheet sheet, List<ReportItemsDto> items, boolean isYear) {
        Iterator<ReportItemsDto> iter = items.stream()
                .filter(i -> i.getRowNum() != null && !i.getRowNum().trim().isEmpty())
                .iterator();
        // 年报：col3=本年累计金额，col4=上年金额（数据中无此key，填0）
        // 月季报：col3=本期金额，col4=本年累计金额
        String col3Key = isYear ? "本年累计金额" : "本期金额";
        String col4Key = isYear ? "上年金额" : "本年累计金额";
        for (int r = 4; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            if (isDataRowCell(row.getCell(2)) && iter.hasNext()) {
                Map<String, BigDecimal> vals = iter.next().getValues();
                if (vals != null) {
                    setNumCell(row, 3, vals.get(col3Key));
                    setNumCell(row, 4, vals.get(col4Key));
                }
            }
        }
    }

    /** 判断是否为行次数据行：有值且不是表头文字 */
    private boolean isDataRowCell(Cell cell) {
        String v = getCellStr(cell);
        return v != null && !"行次".equals(v);
    }

    private String getCellStr(Cell cell) {
        if (cell == null) return null;
        if (cell.getCellType() == CellType.NUMERIC) return String.valueOf((int) cell.getNumericCellValue());
        if (cell.getCellType() == CellType.STRING) {
            String v = cell.getStringCellValue().trim();
            return v.isEmpty() ? null : v;
        }
        return null;
    }

    private void setNumCell(Row row, int col, BigDecimal value) {
        if (value == null) return;
        Cell cell = row.getCell(col);
        if (cell == null) cell = row.createCell(col);
        cell.setCellValue(value.doubleValue());
    }

    private String formatVoucherDate(LocalDate date, String dimension) {
        if ("QUARTER".equals(dimension)) {
            int quarter = (date.getMonthValue() - 1) / 3 + 1;
            return date.getYear() + "年第" + quarter + "季度";
        }
        return date.format(DateTimeFormatter.ofPattern("yyyy年MM月"));
    }

    /**
     * 获取账套的本位币ID
     */
    public Long getLocalCurrencyId(Long accountSetId) {
        try {
            // 通过CurrencyService获取本位币信息
            CurrencyDto localCurrency = currencyService.fetchLocal(accountSetId);
            return localCurrency.getId();
        } catch (Exception e) {
            log.error("获取账套本位币失败，ID: " + accountSetId, e);
            // 返回默认值，比如人民币的ID
            return 1L;
        }
    }
    /**
     * 获取默认报表名称
     */
    public String getReportName(String templateKey) {
        switch (templateKey) {
            case "zcfz":
                return "资产负债表";
            case "lr":
                return "利润表";
            case "xjll":
                return "现金流量表";
            default:
                return "财务报表";
        }
    }
    /**
     * 获取所选账期已结账的数据
     *  accountSetsIdList为空查询所有账套
     * @param checkOutDate
     * @param accountSetsIdList
     * @return
     */
    public List<AccountSets> getCheckOutAccountSets(LocalDate checkOutDate, List<Long> accountSetsIdList) {
        List<AccountSets> accountSetsList = bqf.selectFrom(qAccountSets)
                .select(qAccountSets)
                .leftJoin(qCheckout).on(qCheckout.accountSetsId.eq(qAccountSets.id))
                .where(qCheckout.checkDate.eq(checkOutDate)
                        .and(qCheckout.status.eq(2))
                        .and(CollUtil.isEmpty(accountSetsIdList) ? null : qAccountSets.id.in(accountSetsIdList))
                )
                .fetch();
        return accountSetsList;
    }
}
