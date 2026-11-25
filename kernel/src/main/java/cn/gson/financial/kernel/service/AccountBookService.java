package cn.gson.financial.kernel.service;

import cn.gson.financial.kernel.config.AppConfig;
import cn.gson.financial.kernel.config.ArchiveProcessor;
import cn.gson.financial.kernel.controller.PageResults;
import cn.gson.financial.kernel.model.dto.*;
import cn.gson.financial.kernel.model.entity.*;
import cn.gson.financial.kernel.model.enums.AccountingCategoryType;
import cn.gson.financial.kernel.model.hibernate.AbsEntity;
import cn.gson.financial.kernel.model.hibernate.PrimaryKey;
import cn.gson.financial.kernel.model.mapper.MultiColumnLedgerVoMapper;
import cn.gson.financial.kernel.model.vo.*;
import cn.gson.financial.kernel.tool.JxlsUtils;
import cn.gson.financial.kernel.tool.NumUtil;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.collection.ListUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.lang.Dict;
import cn.hutool.core.util.NumberUtil;
import cn.hutool.extra.spring.SpringUtil;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson2.JSON;
import com.blazebit.persistence.querydsl.BlazeJPAQuery;
import com.querydsl.core.Tuple;
import com.querydsl.core.types.dsl.BooleanExpression;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.sagacity.sqltoy.model.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.util.function.Tuple3;
import reactor.util.function.Tuples;

import java.io.File;
import java.io.FileOutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 *
 * <p><b>Copyright © 2010-2023 开发公司 All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : 总账</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2023年04月10日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 *
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AccountBookService extends AbsService implements ArchiveProcessor {
    private final MultiColumnLedgerVoMapper multiColumnLedgerVoMapper;

    private final QSubject qSubject = QSubject.subject;

    private final QSubject qParentSubject = new QSubject("parentSubject");

    private final QVoucherDetails qVoucherDetails = QVoucherDetails.voucherDetails;

    private final QVoucher qVoucher = QVoucher.voucher;

    private final QAuxiliaryDetail qAuxiliaryDetail = QAuxiliaryDetail.auxiliaryDetail;

    private final CurrencyService currencyService;

    private final QBalance qBalance = QBalance.balance;

    private final AppConfig appConfig;

    private final SubjectService subjectService;

    /**
     * 获取期间科目列表
     *
     * @param queryVO
     * @return
     */
    public List<Subject> subjectList(AccountBookQueryVO queryVO) {
        return lazyDao.findBySql("ab-subjectList", queryVO.toMap(), Subject.class);
    }

    /**
     * 获取期间辅助项列表
     *
     * @param queryVO
     * @return
     */
    public List<? extends AcDetails> auxiliaryList(AccountBookQueryVO queryVO) {
        if (queryVO.getCategoryType() == null) {
            return new ArrayList<>();
        }
        List<JSONObject> results = lazyDao.findBySql("ab-auxiliaryList", queryVO.toMap(), JSONObject.class);
        Set<Long> collect = results.stream().flatMap(it -> {
            List<AuxiliaryDetail.Detail> details = JSONArray.parseArray(it.getString("details"), AuxiliaryDetail.Detail.class);
            return details.stream()
                    .filter(d -> d.getCategoryType().equals(queryVO.getCategoryType()))
                    .map(AuxiliaryDetail.Detail::getValue);
        }).collect(Collectors.toSet());
        AuxiliaryDataService dataService = SpringUtil.getBean(queryVO.getCategoryType().dataService);
        return dataService.loadListByIdList(queryVO.getAccountSetsId(), collect);
    }

    /**
     * 明细账
     *
     * @param queryVO
     * @param enableDate
     * @return
     */
    public List<AbDetailDto> details(AccountBookQueryVO queryVO, LocalDate enableDate) {
        List<AbDetailDto> dataList = new ArrayList<>();
        Subject subject;
        if (queryVO.getSubjectId() != null) {
            subject = bqf.selectFrom(qSubject)
                    .where(qSubject.accountSetsId.eq(queryVO.getAccountSetsId())
                            .and(qSubject.id.eq(queryVO.getSubjectId())))
                    .fetchFirst();
        } else {
            subject = new Subject();
            subject.setCode("");
            subject.setName("");
            subject.setFullName("");
            subject.setBalanceDirection("借");
        }

        //明细
        List<AbDetailDto> detailsList = lazyDao.findBySql("ab-details", queryVO.toMap(), AbDetailDto.class);

        //期初
        AbBalanceDto beginBalance = Optional.ofNullable(lazyDao.loadBySql(enableDate.isEqual(queryVO.getStart()) ? "ab-details-begin-initial-balance" : "ab-details-begin-balance", Dict.create()
                .set("accountSetsId", queryVO.getAccountSetsId())
                .set("subjectId", queryVO.getSubjectId())
                .set("detailIds", CollUtil.isEmpty(queryVO.getDetailIds()) ? 0L : queryVO.getDetailIds())
                .set("currencyId", queryVO.getCurrencyId())
                .set("year", queryVO.getStart().plusMonths(-1).getYear())
                .set("month", queryVO.getStart().plusMonths(-1).getMonthValue()), AbBalanceDto.class)).orElse(new AbBalanceDto());

        AbDetailDto initBalance = AbDetailDto.builder()
                .voucherDate(queryVO.getStart())
                .subjectCode(subject.getCode())
                .subjectSortName(subject.getName())
                .subjectName(subject.getCode() + " " + subject.getFullName())
                .balanceDirection(BigDecimal.ZERO.compareTo(beginBalance.getEndBalance()) == 0 ? "平" : subject.getBalanceDirection())
                .summary("期初余额")
                .debitAmount(beginBalance.getDebit())
                .creditAmount(beginBalance.getCredit())
                .debitNum(beginBalance.getDebitNum())
                .creditNum(beginBalance.getCreditNum())
                .creditPrice(beginBalance.getCreditPrice())
                .debitPrice(beginBalance.getDebitPrice())
                .num(beginBalance.getNum())
                .price(beginBalance.getPrice())
                .balance(beginBalance.getEndBalance()).build();
        dataList.add(initBalance);

        //按年月分组
        Map<Integer, Map<Integer, List<AbDetailDto>>> yearMonthMap = detailsList.stream()
                .collect(Collectors.groupingBy(AbDetailDto::getYear, Collectors.groupingBy(AbDetailDto::getMonth)));

        //初始余额
        BigDecimal balance = Optional.ofNullable(beginBalance.getEndBalance()).orElse(BigDecimal.ZERO);
        BigDecimal num = Optional.ofNullable(beginBalance.getNum()).orElse(BigDecimal.ZERO);

        //组装数据
        for (Map.Entry<Integer, Map<Integer, List<AbDetailDto>>> yearEntry : yearMonthMap.entrySet()) {
            int year = yearEntry.getKey();
            Map<Integer, List<AbDetailDto>> monthMap = yearEntry.getValue();

            for (Map.Entry<Integer, List<AbDetailDto>> monthEntry : monthMap.entrySet()) {
                int month = monthEntry.getKey();
                //科目当期余额
                Balance monthSubjectBalance = Optional.ofNullable(bqf.selectFrom(qBalance)
                        .where(qBalance.accountSetsId.eq(queryVO.getAccountSetsId())
                                .and(qBalance.subjectId.eq(queryVO.getSubjectId()))
                                .and(qBalance.auxiliaryDetailId.eq(CollUtil.isEmpty(queryVO.getDetailIds()) ? 0L : queryVO.getDetailIds().get(0)))
                                .and(qBalance.year.eq(yearEntry.getKey()))
                                .and(qBalance.period.eq(month))
                                .and(qBalance.currencyId.eq(queryVO.getCurrencyId())))
                        .fetchFirst()).orElse(new Balance());

                LocalDate monthDate = LocalDate.of(year, month, 1);
                //月末
                monthDate = monthDate.withDayOfMonth(monthDate.lengthOfMonth());

                List<AbDetailDto> details = monthEntry.getValue();
                //按照voucherDate排序
                details.sort(Comparator.comparing(AbDetailDto::getVoucherDate).thenComparing(AbDetailDto::getCode));

                BigDecimal debitAmount = BigDecimal.ZERO;
                BigDecimal creditAmount = BigDecimal.ZERO;
                BigDecimal debitNum = BigDecimal.ZERO;
                BigDecimal creditNum = BigDecimal.ZERO;
                BigDecimal balanceNum = BigDecimal.ZERO;

                for (AbDetailDto d : details) {
                    d.setSubjectCode(d.getSubjectCode());
                    d.setSubjectName(d.getSubjectCode() + " " + d.getSubjectName());
                    switch (subject.getBalanceDirection()) {
                        case "借" -> {
                            balance = NumberUtil.add(balance, d.getDebitAmount(), NumberUtil.sub(BigDecimal.ZERO, d.getCreditAmount()));
                            balanceNum = NumberUtil.round(NumberUtil.add(balanceNum, d.getDebitNum(), NumberUtil.sub(BigDecimal.ZERO, d.getCreditNum())), 5);
                        }
                        case "贷" -> {
                            balance = NumberUtil.add(balance, d.getCreditAmount(), NumberUtil.sub(BigDecimal.ZERO, d.getDebitAmount()));
                            balanceNum = NumberUtil.round(NumberUtil.add(balanceNum, d.getCreditNum(), NumberUtil.sub(BigDecimal.ZERO, d.getDebitNum())), 5);
                        }
                    }
                    if (d.getCreditNum() != null && NumberUtil.isGreater(d.getCreditNum(), BigDecimal.ZERO)) {
                        d.setCreditPrice(d.getPrice());
                    } else {
                        d.setDebitPrice(d.getPrice());
                    }
                    //本期累计
                    debitAmount = NumberUtil.add(debitAmount, d.getDebitAmount());
                    creditAmount = NumberUtil.add(creditAmount, d.getCreditAmount());
                    debitNum = NumberUtil.round(NumberUtil.add(debitNum, d.getDebitNum()), 5);
                    creditNum = NumberUtil.round(NumberUtil.add(creditNum, d.getCreditNum()), 5);
                    d.setBalance(balance);
                    d.setNum(NumberUtil.add(num, balanceNum));
                    d.setPrice(NumUtil.div(balance, d.getNum()));
                    d.setBalanceDirection(BigDecimal.ZERO.compareTo(balance) == 0 ? "平" : subject.getBalanceDirection());
                    dataList.add(d);
                }

                AbDetailDto monthTotal = AbDetailDto.builder()
                        .voucherDate(monthDate)
                        .subjectCode(subject.getCode())
                        .subjectName(subject.getCode() + " " + subject.getFullName())
                        .balanceDirection(BigDecimal.ZERO.compareTo(balance) == 0 ? "平" : subject.getBalanceDirection())
                        .summary("本期合计")
                        .summaryRow(true)
                        .debitAmount(debitAmount)
                        .creditAmount(creditAmount)
                        .debitNum(debitNum)
                        .creditNum(creditNum)
                        .debitPrice(NumUtil.div(debitAmount, debitNum))
                        .creditPrice(NumUtil.div(creditAmount, creditNum))
                        .num(NumberUtil.add(num, balanceNum))
                        .price(NumUtil.div(balance, NumberUtil.add(num, balanceNum)))
                        .balance(balance).build();
                dataList.add(monthTotal);

                AbDetailDto yearTotal = AbDetailDto.builder()
                        .voucherDate(monthDate)
                        .subjectCode(subject.getCode())
                        .subjectSortName(subject.getName())
                        .summaryRow(true)
                        .subjectName(subject.getCode() + " " + subject.getFullName())
                        .balanceDirection(BigDecimal.ZERO.compareTo(balance) == 0 ? "平" : subject.getBalanceDirection())
                        .summary("本年累计")
                        .debitAmount(monthSubjectBalance.getYearTotalDebit())
                        .creditAmount(monthSubjectBalance.getYearTotalCredit())
                        .creditNum(monthSubjectBalance.getYearTotalCreditQuantity())
                        .debitNum(monthSubjectBalance.getYearTotalDebitQuantity())
                        .debitPrice(NumUtil.div(monthSubjectBalance.getYearTotalDebit(), monthSubjectBalance.getYearTotalDebitQuantity()))
                        .creditPrice(NumUtil.div(monthSubjectBalance.getYearTotalCredit(), monthSubjectBalance.getYearTotalCreditQuantity()))
                        .num(NumberUtil.add(num, balanceNum))
                        .price(NumUtil.div(balance, NumberUtil.add(num, balanceNum)))
                        .balance(balance).build();
                dataList.add(yearTotal);

                num = NumberUtil.add(num, balanceNum);
            }
        }


        return dataList;

    }




    public List<AbGeneralLedgerVo> generalLedgerNum(AccountBookQueryVO queryVO) {
        Set<Long> subjectIdSet = this.subjectList(queryVO).stream().map(Subject::getId).collect(Collectors.toSet());
        Map<String, Object> params = queryVO.toMap();
        params.put("subjectId", subjectIdSet);
        params.put("detailId", 0L);
        return lazyDao.findBySql("ab-generalLedger", params, AbGeneralLedgerVo.class);
    }

    /**
     * 总账
     *
     * @param queryVO
     * @return
     */
    public List<Map<String, Object>> generalLedger(AccountBookQueryVO queryVO) {
        Set<Long> subjectIdSet = this.subjectList(queryVO).stream().map(Subject::getId).collect(Collectors.toSet());
        Map<String, Object> params = queryVO.toMap();
        params.put("subjectId", subjectIdSet);
        params.put("detailId", 0L);
        List<GeneralLedgerDataDto> dataList = lazyDao.findBySql("ab-generalLedger", params, GeneralLedgerDataDto.class);
        List<Map<String, Object>> list = new ArrayList<>();
        for (GeneralLedgerDataDto row : dataList) {
            Map<String, Object> rowMap = new HashMap<>();
            rowMap.put("subjectName", row.getSubjectName());
            rowMap.put("unit", row.getUnit());
            rowMap.put("_expand", true);
            rowMap.put("subjectId", row.getSubjectId());
            List<Dict> rows = new ArrayList<>();
            Dict subBaseRow = Dict.create()
                    .set("code", row.getCode())
                    .set("subjectName", row.getSubjectName())
                    .set("subjectId", row.getSubjectId())
                    .set("date", row.getDate())
                    .set("balanceDirection", row.getBalanceDirection());
            rows.add(subBaseRow.clone()
                    .set("summary", "期初余额")
                    .set("balance", row.getBeginBalance())
                    .set("balanceFor", row.getBeginBalanceFor())
                    .set("debit", NumberUtil.sub(row.getYearTotalDebit(), row.getDebit()))
                    .set("credit", NumberUtil.sub(row.getYearTotalCredit(), row.getCredit()))
                    .set("debitFor", NumberUtil.sub(row.getYearTotalDebitFor(), row.getDebitFor()))
                    .set("creditFor", NumberUtil.sub(row.getYearTotalCreditFor(), row.getCreditFor()))
                    .set("balanceDirection", BigDecimal.ZERO.compareTo(row.getBeginBalance()) == 0 ? "平" : row.getBalanceDirection()));
            rows.add(subBaseRow.clone()
                    .set("summary", "本期合计")
                    .set("balance", row.getEndBalance())
                    .set("balanceFor", row.getEndBalanceFor())
                    .set("debit", row.getDebit())
                    .set("debitFor", row.getDebitFor())
                    .set("credit", row.getCredit())
                    .set("creditFor", row.getCreditFor())
                    .set("balanceDirection", BigDecimal.ZERO.compareTo(row.getEndBalance()) == 0 ? "平" : row.getBalanceDirection()));
            rows.add(subBaseRow.clone()
                    .set("summary", "本年累计")
                    .set("balance", row.getEndBalance())
                    .set("balanceFor", row.getEndBalanceFor())
                    .set("debit", row.getYearTotalDebit())
                    .set("credit", row.getYearTotalCredit())
                    .set("debitFor", row.getYearTotalDebitFor())
                    .set("creditFor", row.getYearTotalCreditFor())
                    .set("balanceDirection", BigDecimal.ZERO.compareTo(row.getEndBalance()) == 0 ? "平" : row.getBalanceDirection()));
            rowMap.put("dataRows", rows);
            list.add(rowMap);
        }
        return list;
    }

    /**
     * 科目余额
     *
     * @param queryVO
     * @return
     */
    public List<BalanceDto> subjectBalance(AccountBookQueryVO queryVO) {
        Set<Long> subjectIdSet = this.subjectList(queryVO).stream().map(Subject::getId).collect(Collectors.toSet());
        CurrencyDto currencyDto = currencyService.fetchLocal(queryVO.getAccountSetsId());

        List<BalanceDto> balanceDtoList;

        Map<String, Object> params = queryVO.toMap();
        params.put("subjectId", subjectIdSet);
        params.put("currencyId", currencyDto.getId());
        params.put("detailId", 0L);
        params.put("auxiliary", false);

        if (queryVO.getStart().isEqual(queryVO.getEnd().withDayOfMonth(1))) {
            params.put("year", queryVO.getStart().getYear());
            params.put("period", queryVO.getStart().getMonthValue());
            balanceDtoList = lazyDao.findBySql("ab-subject-balance-one", params, BalanceDto.class);
        } else {
            params.put("byear", queryVO.getStart().getYear());
            params.put("bperiod", queryVO.getStart().getMonthValue());
            params.put("eyear", queryVO.getEnd().getYear());
            params.put("eperiod", queryVO.getEnd().getMonthValue());
            List<BalanceDto> balanceDtos = lazyDao.findBySql("ab-subject-balance-block", params, BalanceDto.class);
            balanceDtoList = extracted(balanceDtos);
        }

        // 显示辅助项目
        if (queryVO.isAuxiliary()) {
            List<Long> subjectId = balanceDtoList.stream().filter(BalanceDto::getLeaf).map(BalanceDto::getSubjectId).toList();
            params.put("subjectId", subjectId);
            params.put("currencyId", currencyDto.getId());
            params.remove("detailId");
            params.put("auxiliary", true);
            List<BalanceDto> auxiliaryBalanceDtos = new ArrayList<>();
            if (queryVO.getStart().isEqual(queryVO.getEnd().withDayOfMonth(1))) {
                auxiliaryBalanceDtos = lazyDao.findBySql("ab-subject-balance-one", params, BalanceDto.class);
            } else {
                auxiliaryBalanceDtos = extracted(lazyDao.findBySql("ab-subject-balance-block", params, BalanceDto.class));
            }

            Set<Long> detailIds = auxiliaryBalanceDtos.stream().map(BalanceDto::getAuxiliaryDetailId).collect(Collectors.toSet());
            List<AuxiliaryDetail> details = bqf.selectFrom(qAuxiliaryDetail)
                    .where(qAuxiliaryDetail.id.in(detailIds))
                    .fetch();

            Map<Long, BalanceDto> aBalanceDto = new HashMap<>();
            for (AuxiliaryDetail auxiliaryDetail : details) {
                List<AuxiliaryDetail.Detail> allDetails = auxiliaryDetail.getDetails();
                BalanceDto abd = new BalanceDto();
                List<String> codes = new ArrayList<>();
                List<String> names = new ArrayList<>();
                for (AuxiliaryDetail.Detail detail : allDetails) {
                    AuxiliaryDataService dataService = SpringUtil.getBean(detail.getCategoryType().dataService);
                    Optional<AcDetails> acDetails = dataService.loadById(queryVO.getAccountSetsId(), detail.getCategoryId(), detail.getValue());
                    acDetails.ifPresentOrElse(d -> {
                        codes.add(d.getCode());
                        names.add(d.getName());
                    }, () -> log.error("\nSubjectBalance 错误的未关联 detail:\n{}", JSON.toJSONString(detail)));
                }
                abd.setSubjectCode(CollUtil.join(codes, "_"));
                abd.setSubjectName(CollUtil.join(names, "_"));
                aBalanceDto.put(auxiliaryDetail.getId(), abd);
            }

            for (BalanceDto dto : auxiliaryBalanceDtos) {
                BalanceDto dto1 = aBalanceDto.get(dto.getAuxiliaryDetailId());
                if (dto1 != null) {
                    dto.setAuxiliary(true);
                    dto.setParentTreeId(dto.getSubjectId());
                    dto.setTreeId(dto.getAuxiliaryDetailId());
                    dto.setSubjectName(dto1.getSubjectName());
                    dto.setSubjectCode(dto.getSubjectCode() + "_" + dto1.getSubjectCode());
                }
            }
            balanceDtoList.addAll(auxiliaryBalanceDtos);
        }

        return balanceDtoList.stream()
                .sorted(Comparator.comparing(BalanceDto::getQueryCode))
                .collect(Collectors.toList());
    }

    /**
     * 科目汇总
     *
     * @param queryVO
     * @return
     */
    public List<BalanceDto> subjectSummary(AccountBookQueryVO queryVO) {
        Set<Long> subjectIdSet = this.subjectList(queryVO).stream().map(Subject::getId).collect(Collectors.toSet());
        Map<String, Object> params = queryVO.toMap();
        params.put("subjectId", subjectIdSet);
        params.put("detailId", 0L);
        List<BalanceDto> balanceDtos = lazyDao.findBySql("ab-subject-summary", params, BalanceDto.class);
        if (CollUtil.isNotEmpty(balanceDtos)) {
            List<JSONObject> dictList = lazyDao.findBySql("ab-subject-voucher-count", params, JSONObject.class);

            // Calculate the sum of credits
            BigDecimal totalCredit = balanceDtos.stream()
                    .map(BalanceDto::getCredit)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            // Calculate the sum of debits
            BigDecimal totalDebit = balanceDtos.stream()
                    .map(BalanceDto::getDebit)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            // Calculate the sum of credits
            BigDecimal totalCreditQuantity = balanceDtos.stream()
                    .map(BalanceDto::getCreditQuantity)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            // Calculate the sum of debits
            BigDecimal totalDebitQuantity = balanceDtos.stream()
                    .map(BalanceDto::getDebitQuantity)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            BalanceDto total = new BalanceDto();
            total.setSubjectName("合计");
            total.setCredit(totalCredit);
            total.setDebit(totalDebit);
            total.setDebitQuantity(totalDebitQuantity);
            total.setCreditQuantity(totalCreditQuantity);

            if (CollUtil.isNotEmpty(dictList)) {
                int vcount = dictList.get(0).getIntValue("vcount");
                int rcount = dictList.get(0).getIntValue("rcount");
                total.setSubjectCode("凭证总张数：" + vcount + " 张，附件总张数：" + rcount + " 张");
            }

            balanceDtos.add(total);
        }
        return balanceDtos;
    }

    /**
     * 时序账
     *
     * @param page
     * @param queryVo
     * @return
     */
    public PageResults<AbTimeSeriesVo> timeSeries(cn.gson.financial.kernel.controller.Page page, TimeSeriesQueryVo queryVo) {
        Page<AbTimeSeriesVo> p = new Page<>(page.getSize(), page.getPage());
        Page<AbTimeSeriesVo> pageBySql = lazyDao.findPageBySql(p, "ab-timeSeries", BeanUtil.beanToMap(queryVo), AbTimeSeriesVo.class);
        return new PageResults<>(pageBySql.getRows(), page, pageBySql.getRecordCount());
    }

    /**
     * 辅助核算明细
     *
     * @param queryVO
     * @return
     */
    public List<AbDetailDto> auxiliaryDetails(AccountBookQueryVO queryVO, LocalDate enableDate) {
        List<BalanceDto> results = lazyDao.findBySql("ab-auxiliary-id-list", queryVO.toMap(), BalanceDto.class);
        List<Long> subjectIds = results.stream().map(BalanceDto::getSubjectId).distinct().toList();
        //按科目分组辅助项目ID
        Map<Long, Set<Long>> subjectIdMap = results.stream().collect(Collectors.groupingBy(BalanceDto::getSubjectId, Collectors.mapping(BalanceDto::getAuxiliaryDetailId, Collectors.toSet())));
        List<AbDetailDto> mergeDetails = new ArrayList<>();
        for (Long subjectId : subjectIds) {
            queryVO.setDetailIds(subjectIdMap.get(subjectId).stream().toList());
            queryVO.setSubjectId(subjectId);
            mergeDetails.addAll(this.details(queryVO, enableDate));
        }
        return mergeDetails;
    }

    public List<AbTimeSeriesVo> timeSeries(TimeSeriesQueryVo queryVo) {
        return lazyDao.findBySql("ab-timeSeries", BeanUtil.beanToMap(queryVo), AbTimeSeriesVo.class);
    }

    /**
     * 辅助核算余额
     *
     * @param queryVO
     * @return
     */
    public List<BalanceDto> auxiliaryBalance(AccountBookQueryVO queryVO, AccountSets accountSets) {
        queryVO.setAccountSetsId(accountSets.getId());
        List<JSONObject> results = lazyDao.findBySql("ab-auxiliaryList", queryVO.toMap(), JSONObject.class);
        if (CollUtil.isEmpty(results)) {
            return new ArrayList<>();
        }

        Set<Long> detailIds = results.stream().map(d -> d.getLong("auxiliaryDetailId")).collect(Collectors.toSet());
        CurrencyDto currencyDto = currencyService.fetchLocal(queryVO.getAccountSetsId());
        Map<Long, AcDetails> map = new HashMap<>();
        results.forEach(item -> {
            List<AuxiliaryDetail.Detail> allDetails = item.getJSONArray("details").toJavaList(AuxiliaryDetail.Detail.class);
            if (CollUtil.isNotEmpty(allDetails)) {
                for (AuxiliaryDetail.Detail detail : allDetails) {
                    if (detail.getCategoryId().equals(Long.parseLong(queryVO.getCategoryId()))) {
                        AuxiliaryDataService dataService = SpringUtil.getBean(detail.getCategoryType().dataService);
                        Optional<AcDetails> acDetails = dataService.loadById(accountSets.getId(), detail.getCategoryId(), detail.getValue());
                        acDetails.ifPresentOrElse(d -> {
                            map.put(item.getLong("auxiliaryDetailId"), d);
                        }, () -> log.error("辅助项目CategoryId:{},ID:{},加载失败～", detail.getCategoryId(), detail.getValue()));
                    }
                }
            } else {
                log.error("辅助项目加载失败：{}", item.toJSONString());
            }
        });

        Map<String, Object> params = queryVO.toMap();
        params.remove("detailId");
        params.put("currencyId", currencyDto.getId());
        params.put("detailIds", detailIds);
        List<BalanceDto> balanceDtos = new ArrayList<>();
        if (queryVO.getStart().isEqual(queryVO.getEnd().withDayOfMonth(1))) {
            params.put("year", queryVO.getStart().getYear());
            params.put("period", queryVO.getStart().getMonthValue());
            List<BalanceDto> balanceDtoList = lazyDao.findBySql("ab-subject-balance-one", params, BalanceDto.class);

            Map<Long, List<BalanceDto>> sMap = balanceDtoList.stream().collect(Collectors.groupingBy(BalanceDto::getSubjectId));
            balanceDtos = lazyDao.findBySql("ab-subject-balance-one", Dict.create()
                    .set("accountSetsId", accountSets.getId())
                    .set("subjectId", sMap.keySet())
                    .set(queryVO.getDetailId() != null ? "detailIds" : "detailId", queryVO.getDetailId() != null ? detailIds : 0)
                    .set("year", queryVO.getStart().getYear())
                    .set("period", queryVO.getStart().getMonthValue())
                    .set("currencyId", currencyDto.getId()), BalanceDto.class);
            List<BalanceDto> acSubBalanceDtos = new ArrayList<>();
            for (BalanceDto b : balanceDtos) {
                if (b.getLeaf()) {
                    List<BalanceDto> acBDtoList = sMap.get(b.getSubjectId());
                    acBDtoList = acBDtoList.stream().filter(bd -> map.get(bd.getAuxiliaryDetailId()) != null).toList();
                    for (BalanceDto dto : acBDtoList) {
                        AcDetails acDetails = map.get(dto.getAuxiliaryDetailId());
                        dto.setParentTreeId(b.getTreeId());
                        dto.setTreeId(acDetails.getId());
                        dto.setSubjectCode(b.getSubjectCode() + "_" + acDetails.getCode());
                        dto.setSubjectName(acDetails.getName());
                    }
                    acSubBalanceDtos.addAll(acBDtoList);
                }
            }
            balanceDtos.addAll(acSubBalanceDtos);
        } else {
            params.put("byear", queryVO.getStart().getYear());
            params.put("bperiod", queryVO.getStart().getMonthValue());
            params.put("eyear", queryVO.getEnd().getYear());
            params.put("eperiod", queryVO.getEnd().getMonthValue());

            List<BalanceDto> balanceDtoList = lazyDao.findBySql("ab-subject-balance-block", params, BalanceDto.class);
            balanceDtoList = extracted(balanceDtoList);
            Map<Long, List<BalanceDto>> sMap = balanceDtoList.stream().collect(Collectors.groupingBy(BalanceDto::getSubjectId));

            //有辅助的科目余额
            List<BalanceDto> balanceDtoList2 = lazyDao.findBySql("ab-subject-balance-block", Dict.create()
                    .set("accountSetsId", accountSets.getId())
                    .set("subjectId", sMap.keySet())
                    .set(queryVO.getDetailId() != null ? "detailIds" : "detailId", queryVO.getDetailId() != null ? detailIds : 0)
                    .set("start", queryVO.getStart())
                    .set("end", queryVO.getEnd())
                    .set("byear", queryVO.getStart().getYear())
                    .set("bperiod", queryVO.getStart().getMonthValue())
                    .set("eyear", queryVO.getEnd().getYear())
                    .set("eperiod", queryVO.getEnd().getMonthValue())
                    .set("currencyId", currencyDto.getId()), BalanceDto.class);

            balanceDtos = extracted(balanceDtoList2);

            List<BalanceDto> acSubBalanceDtos = new ArrayList<>();
            for (BalanceDto b : balanceDtos) {
                if (b.getLeaf()) {
                    List<BalanceDto> acBDtoList = sMap.get(b.getSubjectId());
                    for (BalanceDto dto : acBDtoList) {
                        AcDetails acDetails = map.get(dto.getAuxiliaryDetailId());
                        dto.setParentTreeId(b.getTreeId());
                        dto.setTreeId(acDetails.getId());
                        dto.setSubjectCode(b.getSubjectCode() + "_" + acDetails.getCode());
                        dto.setQueryCode(acDetails.getCode());
                        dto.setSubjectName(acDetails.getName());
                    }
                    acSubBalanceDtos.addAll(acBDtoList);
                }
            }
            balanceDtos.addAll(ListUtil.sort(acSubBalanceDtos, Comparator.comparing(d -> Long.parseLong(d.getQueryCode()))));
        }

        return balanceDtos;
    }

    private List<BalanceDto> extracted(List<BalanceDto> balanceDtoList2) {
        List<BalanceDto> balanceDtos = new ArrayList<>();
        Map<String, List<BalanceDto>> bDtomap = balanceDtoList2.stream().collect(Collectors.groupingBy(d -> String.valueOf(d.getSubjectId()) + d.getAuxiliaryDetailId() + d.getCurrencyId()));

        for (List<BalanceDto> rows : bDtomap.values()) {
            Optional<BalanceDto> begin = rows.stream().filter(d -> "begin".equals(d.getType())).findFirst();
            Optional<BalanceDto> current = rows.stream().filter(d -> "current".equals(d.getType())).findFirst();
            Optional<BalanceDto> end = rows.stream().filter(d -> "end".equals(d.getType())).findFirst();
            if (current.isPresent()) {
                BalanceDto dto = current.get();
                begin.ifPresent(b -> {
                    dto.setBeginBalance(b.getBeginBalance());
                    dto.setBeginBalanceFor(b.getBeginBalanceFor());
                    dto.setBeginQuantity(b.getBeginQuantity());
                });
                end.ifPresent(e -> {
                    dto.setEndBalance(e.getEndBalance());
                    dto.setEndBalanceFor(e.getEndBalanceFor());
                    dto.setEndQuantity(e.getEndQuantity());
                    dto.setYearTotalDebit(e.getYearTotalDebit());
                    dto.setYearTotalDebitFor(e.getYearTotalDebitFor());
                    dto.setYearTotalCredit(e.getYearTotalCredit());
                    dto.setYearTotalCreditFor(e.getYearTotalCreditFor());
                    dto.setYearTotalDebitQuantity(e.getYearTotalDebitQuantity());
                    dto.setYearTotalCreditQuantity(e.getYearTotalCreditQuantity());
                });
                balanceDtos.add(dto);
            }
        }
        return balanceDtos;
    }

    /**
     * 归档
     *
     * @param userVo 账套ID
     * @return
     */
    @Override
    public CompletableFuture<Boolean> archive(UserVo userVo) {
        try {
            List<Checkout> checkoutList = userVo.getCheckoutList();
            Map<Integer, List<Checkout>> listMap = checkoutList.stream().collect(Collectors.groupingBy(Checkout::getCheckYear));
            Dict dict = Dict.create();
            dict.set("accountSets", userVo.getAccountSets());
            for (Map.Entry<Integer, List<Checkout>> entry : listMap.entrySet()) {
                Integer year = entry.getKey();
                List<Checkout> checkouts = entry.getValue();
                AccountBookQueryVO queryVO = new AccountBookQueryVO();
                queryVO.setAccountSetsId(userVo.getAccountSets().getId());
                queryVO.setCurrencyId(userVo.getLocalCurrencyId());
                queryVO.setEnd(checkouts.get(0).getCheckDate());
                queryVO.setStart(checkouts.get(checkouts.size() - 1).getCheckDate());
                List<Map<String, Object>> generalLedger = this.generalLedger(queryVO);
                List<Object> dataRows = generalLedger.stream().map(s -> s.get("dataRows")).toList();
                List<Dict> rows = new ArrayList<>();
                for (Object dataRow : dataRows) {
                    rows.addAll((Collection<? extends Dict>) dataRow);
                }

                List<BalanceDto> subjectSummary = this.subjectSummary(queryVO);

                dict.set("dataList", rows);
                dict.set("subjectSummary", subjectSummary);
                dict.set("startDate", queryVO.getStart());
                dict.set("endDate", queryVO.getEnd());

                File archiveDir = appConfig.getUploadRoot("ast-" + queryVO.getAccountSetsId() + "/archive/" + year + "年");
                FileUtil.mkdir(archiveDir);

                String fileName = "01总账(" + year + "年)_" + userVo.getAccountSets().getCompanyName() + ".xlsx";
                File docFile = new File(archiveDir, fileName);
                FileOutputStream os = new FileOutputStream(docFile);
                JxlsUtils.exportExcel("template/general-ledger.xlsx", os, dict);

                // 明细账
                List<Subject> subjects = this.subjectList(queryVO);
                List<Dict> dataList = new ArrayList<>();
                for (Subject subject : subjects) {
                    if (subject.getLeaf()) {
                        queryVO.setSubjectId(subject.getId());
                        List<AbDetailDto> details = this.details(queryVO, userVo.getAccountSets().getEnableDate());
                        dataList.add(Dict.create().set("subject", subject)
                                .set("dataList", details));
                    }
                }

                dict = Dict.create();
                dict.set("accountSets", userVo.getAccountSets());
                dict.set("startDate", queryVO.getStart());
                dict.set("endDate", queryVO.getEnd());
                dict.set("dataList", dataList);
                fileName = "02明细账(" + year + "年)_" + userVo.getAccountSets().getCompanyName() + ".xlsx";
                docFile = new File(archiveDir, fileName);
                os = new FileOutputStream(docFile);
                JxlsUtils.exportExcel("template/detailed-accounts.xlsx", os, dict);

                List<BalanceDto> datas = new ArrayList<>();
                for (Checkout checkout : CollUtil.reverse(checkouts)) {
                    queryVO = new AccountBookQueryVO();
                    queryVO.setAccountSetsId(userVo.getAccountSets().getId());
                    queryVO.setCurrencyId(userVo.getLocalCurrencyId());
                    queryVO.setEnd(checkout.getCheckDate());
                    queryVO.setStart(checkout.getCheckDate());
                    queryVO.setAuxiliary(true);
                    List<BalanceDto> balanceDtos = this.subjectBalance(queryVO);
                    for (BalanceDto balanceDto : balanceDtos) {
                        balanceDto.setDate(checkout.getCheckDate());
                    }
                    datas.addAll(balanceDtos);
                }
                dict.set("dataList", datas);
                fileName = "03科目余额表(" + year + "年)_" + userVo.getAccountSets().getCompanyName() + ".xlsx";
                docFile = new File(archiveDir, fileName);
                os = new FileOutputStream(docFile);
                JxlsUtils.exportExcel("template/subject-balance.xlsx", os, dict);
            }
            return CompletableFuture.completedFuture(true);
        } catch (Exception e) {
            log.info("\n{}归档异常", userVo.getAccountSets().getId(), e);
            return CompletableFuture.completedFuture(false);
        }
    }

    /**
     * 多栏账
     *
     * @return
     */
    public Tuple3<List<MultiColumnLedgerVo>, Subject, List<Subject>> multiColumnLedger(MultiColumnLedgerQueryVo queryVo) {
        Subject subject = bqf.selectFrom(qSubject)
                .where(qSubject.accountSetsId.eq(queryVo.getAccountSetsId())
                        .and(qSubject.id.eq(queryVo.getSubjectId())))
                .fetchFirst();
        List<Subject> subjects;
        if (queryVo.getSubjectShow() == 1) {
            // 只显示明细科目
            subjects = bqf.selectFrom(qSubject)
                    .where(qSubject.accountSetsId.eq(queryVo.getAccountSetsId())
                            .and(qSubject.code.startsWith(subject.getCode()))
                            .and(qSubject.leaf.isTrue()))
                    .fetch();
        } else {
            // 子科目
            subjects = bqf.selectFrom(qSubject)
                    .where(qSubject.accountSetsId.eq(queryVo.getAccountSetsId())
                            .and(qSubject.parentId.eq(queryVo.getSubjectId())))
                    .fetch();
        }

        if (CollUtil.isEmpty(subjects)) {
            // 没有明细科目时，查询自己
            subjects.add(subject);
        }

        // 过滤出已经使用过的科目
        List<Long> subjectIdList = bqf.selectFrom(qBalance)
                .select(qBalance.subjectId).distinct()
                .where(qBalance.accountSetsId.eq(queryVo.getAccountSetsId())
                        .and(qBalance.subjectId.in(subjects.stream().map(Subject::getId).collect(Collectors.toList())))
                        .and(qBalance.date.between(queryVo.getStart(), queryVo.getEnd())))
                .fetch();
        // 剔除未使用的科目
        subjects = subjects.stream().filter(s -> subjectIdList.contains(s.getId())).collect(Collectors.toList());

        MultiColumnLedgerVo beginBalanceVo = new MultiColumnLedgerVo();
        beginBalanceVo.setVoucherDate(queryVo.getStart());
        beginBalanceVo.setSummary("期初余额");
        beginBalanceVo.setBalanceDirection(subject.getBalanceDirection());

        // 期初
        Tuple tuple = bqf.selectFrom(qBalance)
                .select(qBalance.beginBalance, qBalance.beginCreditBalance, qBalance.beginDebitBalance)
                .where(qBalance.accountSetsId.eq(queryVo.getAccountSetsId())
                        .and(qBalance.currencyId.eq(queryVo.getLocalCurrencyId()))
                        .and(qBalance.auxiliaryDetailId.eq(0L))
                        .and(qBalance.year.eq(queryVo.getStart().getYear()))
                        .and(qBalance.period.eq(queryVo.getStart().getMonthValue()))
                        .and(qBalance.subjectId.eq(subject.getId())))
                .fetchFirst();

        // 具体科目期初
        Map<String, BigDecimal> subjectAmount = bqf.selectFrom(qBalance)
                .select(qSubject.code, qBalance.beginBalance)
                .join(qSubject).on(qSubject.id.eq(qBalance.subjectId).and(qBalance.accountSetsId.eq(qSubject.accountSetsId)))
                .where(qBalance.accountSetsId.eq(queryVo.getAccountSetsId())
                        .and(qBalance.currencyId.eq(queryVo.getLocalCurrencyId()))
                        .and(qBalance.auxiliaryDetailId.eq(0L))
                        .and(qBalance.year.eq(queryVo.getStart().getYear()))
                        .and(qBalance.period.eq(queryVo.getStart().getMonthValue()))
                        .and(qBalance.subjectId.in(subjectIdList)))
                .fetch().parallelStream().collect(Collectors.toMap(t -> t.get(qSubject.code), t -> Optional.ofNullable(t.get(qBalance.beginBalance)).orElse(BigDecimal.ZERO)));
        beginBalanceVo.setSubjectAmount(subjectAmount);

        MultiColumnLedgerVo startYearTotal = new MultiColumnLedgerVo();
        if (tuple != null) {
            BigDecimal beginBalance = Optional.ofNullable(tuple.get(qBalance.beginBalance)).orElse(BigDecimal.ZERO);
            BigDecimal beginCreditBalance = Optional.ofNullable(tuple.get(qBalance.beginCreditBalance)).orElse(BigDecimal.ZERO);
            BigDecimal beginDebitBalance = Optional.ofNullable(tuple.get(qBalance.beginDebitBalance)).orElse(BigDecimal.ZERO);
            beginBalanceVo.setBalance(beginBalance);
            beginBalanceVo.setDebitAmount(beginDebitBalance);
            beginBalanceVo.setCreditAmount(beginCreditBalance);
            startYearTotal.setBalance(beginBalance);
            startYearTotal.setVoucherDate(queryVo.getStart());
            startYearTotal.setCreditAmount(beginCreditBalance);
            startYearTotal.setDebitAmount(beginDebitBalance);
        }

        List<MultiColumnLedgerVo> list = new ArrayList<>();
        list.add(beginBalanceVo);

        List<Tuple> details = bqf.selectFrom(qVoucherDetails)
                .select(qVoucherDetails, qVoucher.word, qVoucher.code, qSubject.balanceDirection, qSubject.code)
                .join(qVoucher).on(qVoucher.accountSetsId.eq(qVoucherDetails.accountSetsId).and(qVoucher.id.eq(qVoucherDetails.voucherId)))
                .join(qSubject).on(qSubject.accountSetsId.eq(qVoucherDetails.accountSetsId).and(qSubject.id.eq(qVoucherDetails.subjectId)))
                .where(qVoucherDetails.accountSetsId.eq(queryVo.getAccountSetsId())
                        .and(qVoucherDetails.voucherDate.between(queryVo.getStart(), queryVo.getEnd()))
                        .and(qSubject.code.startsWith(subject.getCode())))
                .orderBy(qVoucherDetails.voucherDate.asc(), qVoucher.word.asc(), qVoucher.code.asc(), qVoucherDetails.id.asc())
                .fetch();
        List<MultiColumnLedgerVo> result = new ArrayList<>();

        BigDecimal startBalance = beginBalanceVo.getBalance();
        for (Tuple detail : details) {
            MultiColumnLedgerVo dto = multiColumnLedgerVoMapper.toDto(detail.get(qVoucherDetails));
            dto.setWord(detail.get(qVoucher.word) + "-" + detail.get(qVoucher.code));
            dto.setSubjectCode(detail.get(qSubject.code));
            dto.setBalanceDirection(detail.get(qSubject.balanceDirection));
            dto.getSubjectAmount().put(dto.getSubjectCode(), dto.getAmount());
            switch (detail.get(qSubject.balanceDirection)) {
                case "借":
                    dto.setBalance(NumberUtil.add(startBalance, NumberUtil.sub(dto.getDebitAmount(), dto.getCreditAmount())));
                    break;
                case "贷":
                    dto.setBalance(NumberUtil.add(startBalance, NumberUtil.sub(dto.getCreditAmount(), dto.getDebitAmount())));
                    break;
            }
            startBalance = dto.getBalance();

            result.add(dto);
        }

        Map<Integer, Map<Integer, List<MultiColumnLedgerVo>>> collect =
                result.parallelStream().collect(Collectors.groupingBy(MultiColumnLedgerVo::getYear, Collectors.groupingBy(MultiColumnLedgerVo::getMonth)));

        // 最终集合
        List<MultiColumnLedgerVo> finalResult = new ArrayList<>();
        collect.forEach((year, monthMap) -> {
            if (startYearTotal.getVoucherDate().getYear() != year) {
                startYearTotal.setDebitAmount(BigDecimal.ZERO);
                startYearTotal.setCreditAmount(BigDecimal.ZERO);
            }
            monthMap.forEach((month, dataList) -> {
                finalResult.addAll(dataList);
                // 期间日期
                LocalDate voucherDate = dataList.get(dataList.size() - 1).getVoucherDate();
                // 插入本期合计
                MultiColumnLedgerVo periodTotal = getMultiColumnLedgerVo(voucherDate.withDayOfMonth(voucherDate.lengthOfMonth()), dataList.get(dataList.size() - 1), "本期合计", dataList.stream().map(MultiColumnLedgerVo::getCreditAmount).reduce(BigDecimal::add).orElse(BigDecimal.ZERO), dataList.stream().map(MultiColumnLedgerVo::getDebitAmount).reduce(BigDecimal::add).orElse(BigDecimal.ZERO));
                // 本期合计明细科目金额
                Map<String, BigDecimal> subjectPeriodTotal = dataList.parallelStream().collect(Collectors.groupingBy(MultiColumnLedgerVo::getSubjectCode,
                        Collectors.mapping(d -> {
                            if (d.getSubjectAmount().isEmpty()) {
                                return BigDecimal.ZERO;
                            }
                            return d.getSubjectAmount().values().iterator().next();
                        }, Collectors.reducing(BigDecimal.ZERO, BigDecimal::add))));
                periodTotal.setSubjectAmount(subjectPeriodTotal);

                finalResult.add(periodTotal);

                // 合计本年累计
                startYearTotal.setCreditAmount(startYearTotal.getCreditAmount().add(periodTotal.getCreditAmount()));
                startYearTotal.setDebitAmount(startYearTotal.getDebitAmount().add(periodTotal.getDebitAmount()));

                // 插入本年累计
                MultiColumnLedgerVo yearTotal = getMultiColumnLedgerVo(periodTotal.getVoucherDate(), periodTotal, "本年累计", startYearTotal.getCreditAmount(), startYearTotal.getDebitAmount());
                //合并科目金额
                subjectPeriodTotal.forEach((subjectId, val) -> subjectAmount.merge(subjectId, val, BigDecimal::add));
                yearTotal.setSubjectAmount(new HashMap<>(subjectAmount));
                finalResult.add(yearTotal);

                // 期末余额行
                if (queryVo.getShowBalance()) {
                    MultiColumnLedgerVo endBalance = getMultiColumnLedgerVo(periodTotal.getVoucherDate(), periodTotal, "期末余额", BigDecimal.ZERO, BigDecimal.ZERO);
                    endBalance.setSubjectAmount(new HashMap<>(subjectAmount));
                    finalResult.add(endBalance);
                }
            });
        });

        // 子科目时，需要汇总下级科目
        if (queryVo.getSubjectShow() == 2) {
            for (MultiColumnLedgerVo vo : finalResult) {
                Map<String, BigDecimal> newSubjectAmount = new HashMap<>();
                for (Subject s : subjects) {
                    newSubjectAmount.put(s.getCode(), vo.getSubjectAmount().keySet().parallelStream()
                            .filter(code -> code.startsWith(s.getCode()))
                            .map(code -> vo.getSubjectAmount().get(code))
                            .reduce(BigDecimal::add).orElse(BigDecimal.ZERO));
                }
                vo.setSubjectAmount(newSubjectAmount);
            }
        }

        list.addAll(finalResult);
        return Tuples.of(list, subject, subjects);
    }

    /**
     * 账龄分析
     * @param queryVo
     */
    public List<AgeAnalysisVo> ageAnalysis(AgeAnalysisQueryVo queryVo) throws InterruptedException {
        Subject subject = subjectService.getById(new PrimaryKey(queryVo.getSubjectId(), queryVo.getAccountSetsId()));
        List<Subject> subjects = new ArrayList<>();
        List<AgeAnalysisVo> list = new ArrayList<>();
        if (!subject.getLeaf()) {
            BlazeJPAQuery<Long> leafCount = bqf.selectFrom(qParentSubject)
                    .select(qParentSubject.count())
                    .where(qParentSubject.accountSetsId.eq(qSubject.accountSetsId)
                            .and(qParentSubject.status.isTrue())
                            .and(qParentSubject.parentId.eq(qSubject.id)));
            subjects = bqf.selectFrom(qSubject)
                    .where(qSubject.accountSetsId.eq(queryVo.getAccountSetsId())
                            .and(qSubject.code.startsWith(subject.getCode()))
                            .and(qSubject.code.ne(subject.getCode()))
                            .and(leafCount.eq(0L)))
                    .fetch();

            list.addAll(getAgeAnalysisVos(queryVo, subjects.stream().filter(s -> CollUtil.isEmpty(s.getAuxiliaryAccounting())).toList()));
            for (Subject s : subjects.stream().filter(s -> CollUtil.isNotEmpty(s.getAuxiliaryAccounting())).toList()) {
                if (s.getAuxiliaryAccounting().contains((long) AccountingCategoryType.CUSTOMER.ordinal())) {
                    list.addAll(getAgeAnalysisVos(s, queryVo, AccountingCategoryType.CUSTOMER));
                }
                if (s.getAuxiliaryAccounting().contains((long) AccountingCategoryType.VENDOR.ordinal())) {
                    list.addAll(getAgeAnalysisVos(s, queryVo, AccountingCategoryType.VENDOR));
                }
            }
        } else if (CollUtil.isNotEmpty(subject.getAuxiliaryAccounting())) {
            if (subject.getAuxiliaryAccounting().contains((long) AccountingCategoryType.CUSTOMER.ordinal())) {
                list.addAll(getAgeAnalysisVos(subject, queryVo, AccountingCategoryType.CUSTOMER));
            }
            if (subject.getAuxiliaryAccounting().contains((long) AccountingCategoryType.VENDOR.ordinal())) {
                list.addAll(getAgeAnalysisVos(subject, queryVo, AccountingCategoryType.VENDOR));
            }
        } else {
            subjects.add(subject);
            list = getAgeAnalysisVos(queryVo, subjects);
        }

        // 排除期末期初余额为0的记录
        if (queryVo.getIgnoreZero()) {
            list = list.stream().filter(s -> s.getTotal().compareTo(BigDecimal.ZERO) != 0).toList();
        }

        if (CollUtil.isNotEmpty(list)) {
            // 计算合计
            BigDecimal initial = list.stream().map(AgeAnalysisVo::getInitial).reduce(BigDecimal::add).orElse(BigDecimal.ZERO);
            BigDecimal totalDebit = list.stream().map(AgeAnalysisVo::getDebit).reduce(BigDecimal::add).orElse(BigDecimal.ZERO);
            BigDecimal totalCredit = list.stream().map(AgeAnalysisVo::getCredit).reduce(BigDecimal::add).orElse(BigDecimal.ZERO);
            BigDecimal total = list.stream().map(AgeAnalysisVo::getTotal).reduce(BigDecimal::add).orElse(BigDecimal.ZERO);
            BigDecimal diff = list.stream().map(AgeAnalysisVo::getTotal).filter(voTotal -> NumberUtil.isLess(voTotal, BigDecimal.ZERO))
                    .map(BigDecimal::abs).reduce(BigDecimal::add).orElse(BigDecimal.ZERO);
            list.parallelStream().forEach(vo -> {
                if (vo.getTotal().compareTo(BigDecimal.ZERO) != 0 && total.compareTo(BigDecimal.ZERO) != 0) {
                    vo.setTotalPercent(NumberUtil.mul(NumberUtil.div(vo.getTotal(), total), BigDecimal.valueOf(100)));
                }
                BooleanExpression expression = qVoucherDetails.accountSetsId.eq(queryVo.getAccountSetsId())
                        .and(qVoucherDetails.voucherDate.loe(queryVo.getEnd()))
                        .and(qVoucherDetails.subjectId.eq(vo.getSubjectId()));
                if (CollUtil.isNotEmpty(vo.getAuxiliaryDetailIds())) {
                    expression = expression.and(qVoucherDetails.auxiliaryDetailId.in(vo.getAuxiliaryDetailIds()));
                }
                if (vo.getBalanceDirection().equals("借")) {
                    expression = expression.and(qVoucherDetails.debitAmount.gt(BigDecimal.ZERO));
                } else {
                    expression = expression.and(qVoucherDetails.creditAmount.gt(BigDecimal.ZERO));
                }

                // 计算明细按账龄时间区间落点分组
                Map<Integer, List<VoucherDetails>> collect = bqf.selectFrom(qVoucherDetails)
                        .select(qVoucherDetails.voucherDate, qVoucherDetails.debitAmount, qVoucherDetails.subjectId, qVoucherDetails.creditAmount, qVoucherDetails.voucherId, qVoucherDetails.auxiliaryDetailId)
                        .where(expression)
                        .orderBy(qVoucherDetails.voucherDate.asc(), qVoucherDetails.id.desc())
                        .fetch().parallelStream().map(tuple -> {
                            VoucherDetails voucherDetails = new VoucherDetails();
                            voucherDetails.setVoucherDate(tuple.get(qVoucherDetails.voucherDate));
                            voucherDetails.setDebitAmount(tuple.get(qVoucherDetails.debitAmount));
                            voucherDetails.setCreditAmount(tuple.get(qVoucherDetails.creditAmount));
                            voucherDetails.setVoucherId(tuple.get(qVoucherDetails.voucherId));
                            voucherDetails.setSubjectId(tuple.get(qVoucherDetails.subjectId));
                            voucherDetails.setAuxiliaryDetailId(tuple.get(qVoucherDetails.auxiliaryDetailId));
                            return voucherDetails;
                        }).collect(Collectors.groupingBy(d -> {
                            long daysDiff = ChronoUnit.DAYS.between(d.getVoucherDate(), queryVo.getEnd());
                            return queryVo.getDays().stream().filter(day -> daysDiff < day).findFirst().orElse(queryVo.getMaxDay() + 1);
                        }));
                // 期末余额
                AtomicReference<BigDecimal> endBalance = new AtomicReference<>(vo.getTotal());
                for (Integer day : queryVo.getEachDays()) {
                    List<VoucherDetails> details = collect.get(day);
                    if (CollUtil.isNotEmpty(details)) {
                        BigDecimal debit = details.stream().map(VoucherDetails::getDebitAmount).reduce(BigDecimal::add).orElse(BigDecimal.ZERO);
                        BigDecimal credit = details.stream().map(VoucherDetails::getCreditAmount).reduce(BigDecimal::add).orElse(BigDecimal.ZERO);
                        vo.addAges(day, debit, credit, endBalance);
                    }
                }
            });

            AgeAnalysisVo aggregate = new AgeAnalysisVo();
            aggregate.setCode("合计");
            aggregate.setInitial(initial);
            aggregate.setDebit(totalDebit);
            aggregate.setCredit(totalCredit);
            aggregate.setTotal(total);
            if (total.compareTo(BigDecimal.ZERO) != 0) {
                aggregate.setTotalPercent(NumberUtil.mul(NumberUtil.div(total, total), BigDecimal.valueOf(100)));
            }
            final ArrayList<AgeAnalysisVo> ageAnalysisVos = new ArrayList<>(list);
            queryVo.getEachDays().parallelStream().forEach(day -> {
                BigDecimal amount = ageAnalysisVos.stream().filter(vo -> vo.getAges().containsKey(day))
                        .map(vo -> vo.getAges().get(day).getAmount()).reduce(BigDecimal::add).orElse(BigDecimal.ZERO);
                AgeAnalysisVo.AgeDetail detail = new AgeAnalysisVo.AgeDetail();
                detail.setAmount(amount);
                if (amount.compareTo(BigDecimal.ZERO) != 0) {
                    detail.setPercent(NumberUtil.mul(NumberUtil.div(amount, aggregate.getTotal().add(diff)), BigDecimal.valueOf(100)));
                }
                aggregate.getAges().put(day, detail);
            });
            ageAnalysisVos.add(aggregate);
            return ageAnalysisVos;
        }
        return new ArrayList<>();
    }

    private List<AgeAnalysisVo> getAgeAnalysisVos(Subject subject, AgeAnalysisQueryVo queryVo, AccountingCategoryType categoryType) {
        List<AgeAnalysisVo> list;
        // 查询期间的辅助项数据
        List<Long> auxiliaryDetailId = bqf.selectFrom(qBalance)
                .select(qBalance.auxiliaryDetailId)
                .where(qBalance.accountSetsId.eq(queryVo.getAccountSetsId())
                        .and(qBalance.currencyId.eq(queryVo.getLocalCurrencyId()))
                        .and(qBalance.auxiliaryDetailId.ne(0L))
                        .and(qBalance.date.between(queryVo.getStart(), queryVo.getEnd())))
                .fetch();
        // 期初辅助信息
        List<AuxiliaryDetail> auxiliaryList = lazyDao.findBySql("auxiliaryList", Dict.create()
                .set("accountSetsId", queryVo.getAccountSetsId())
                .set("auxiliaryDetailId", auxiliaryDetailId)
                .set("categoryType", categoryType.name()), AuxiliaryDetail.class);

        // 客户关联的辅助项ID组
        Map<Long, Set<Long>> customerAdIdList = auxiliaryList.parallelStream().collect(Collectors.groupingBy(s -> s.getDetails().stream().filter(d -> d.getCategoryType().equals(categoryType)).findFirst().get().getValue(),
                Collectors.mapping(AbsEntity::getId, Collectors.toSet())));
        // 客户辅助明细
        List<AuxiliaryDetail.Detail> customerDetailList = auxiliaryList.parallelStream().map(s -> s.getDetails().stream().filter(d -> d.getCategoryType().equals(categoryType)).findFirst().get()).toList();
        AuxiliaryDataService dataService = SpringUtil.getBean(categoryType.dataService);
        List<? extends AcDetails> customerList = dataService.loadListByIdList(queryVo.getAccountSetsId(), customerDetailList.stream().map(AuxiliaryDetail.Detail::getValue).collect(Collectors.toSet()));
        list = customerList.stream().map(s -> {
            AgeAnalysisVo vo = new AgeAnalysisVo();
            vo.setAuxiliary(true);
            vo.setAuxiliaryDetailIds(customerAdIdList.get(s.getId()));
            vo.setSubjectId(subject.getId());
            vo.setBalanceDirection(subject.getBalanceDirection());
            vo.setDetailId(s.getId());
            vo.setCode(s.getCode());
            vo.setName(s.getName());
            Tuple tuple = bqf.selectFrom(qBalance)
                    .select(qBalance.beginBalance.sum(), qBalance.endBalance.sum(), qBalance.debit.sum(), qBalance.credit.sum())
                    .where(qBalance.accountSetsId.eq(queryVo.getAccountSetsId())
                            .and(qBalance.currencyId.eq(queryVo.getLocalCurrencyId()))
                            .and(qBalance.subjectId.eq(queryVo.getSubjectId()))
                            .and(qBalance.auxiliaryDetailId.in(customerAdIdList.get(s.getId())))
                            .and(qBalance.year.eq(queryVo.getStart().getYear()))
                            .and(qBalance.period.eq(queryVo.getStart().getMonthValue()))).fetchFirst();
            if (tuple != null) {
                vo.setInitial(tuple.get(qBalance.beginBalance.sum()));
                vo.setDebit(tuple.get(qBalance.debit.sum()));
                vo.setCredit(tuple.get(qBalance.credit.sum()));
                vo.setTotal(tuple.get(qBalance.endBalance.sum()));
            }

            if (!queryVo.isEqualDate()) {
                // 期末
                BigDecimal endBalance = bqf.selectFrom(qBalance)
                        .select(qBalance.endBalance.sum())
                        .where(qBalance.accountSetsId.eq(queryVo.getAccountSetsId())
                                .and(qBalance.subjectId.eq(queryVo.getSubjectId()))
                                .and(qBalance.currencyId.eq(queryVo.getLocalCurrencyId()))
                                .and(qBalance.auxiliaryDetailId.in(customerAdIdList.get(s.getId())))
                                .and(qBalance.year.eq(queryVo.getEnd().getYear()))
                                .and(qBalance.period.eq(queryVo.getEnd().getMonthValue())))
                        .fetchFirst();
                vo.setTotal(endBalance);
                // 借贷合计
                Tuple tuple1 = bqf.selectFrom(qBalance)
                        .select(qBalance.debit.sum(), qBalance.credit.sum())
                        .where(qBalance.accountSetsId.eq(queryVo.getAccountSetsId())
                                .and(qBalance.subjectId.eq(queryVo.getSubjectId()))
                                .and(qBalance.currencyId.eq(queryVo.getLocalCurrencyId()))
                                .and(qBalance.auxiliaryDetailId.in(customerAdIdList.get(s.getId())))
                                .and(qBalance.date.between(queryVo.getStart(), queryVo.getEnd())))
                        .fetchFirst();
                BigDecimal debit = tuple1.get(qBalance.debit.sum());
                BigDecimal credit = tuple1.get(qBalance.credit.sum());
                vo.setCredit(credit);
                vo.setDebit(debit);
            }
            return vo;
        }).toList();
        return list;
    }

    private List<AgeAnalysisVo> getAgeAnalysisVos(AgeAnalysisQueryVo queryVo, List<Subject> subjects) {
        List<AgeAnalysisVo> list = subjects.stream().map(s -> {
            AgeAnalysisVo vo = new AgeAnalysisVo();
            vo.setSubjectId(s.getId());
            vo.setBalanceDirection(s.getBalanceDirection());
            vo.setCode(s.getCode());
            vo.setName(s.getName());
            return vo;
        }).toList();

        // 将集合转换成字典
        Map<Long, AgeAnalysisVo> voMap = list.stream().collect(Collectors.toMap(AgeAnalysisVo::getSubjectId, a -> a));
        // 期初
        bqf.selectFrom(qBalance)
                .select(qBalance.subjectId, qBalance.beginBalance, qBalance.endBalance, qBalance.debit, qBalance.credit)
                .where(qBalance.accountSetsId.eq(queryVo.getAccountSetsId())
                        .and(qBalance.auxiliaryDetailId.eq(0L))
                        .and(qBalance.currencyId.eq(queryVo.getLocalCurrencyId()))
                        .and(qBalance.subjectId.in(subjects.stream().map(Subject::getId).collect(Collectors.toList())))
                        .and(qBalance.year.eq(queryVo.getStart().getYear()))
                        .and(qBalance.period.eq(queryVo.getStart().getMonthValue())))
                .fetch().parallelStream().forEach(tuple -> {
                    Long subjectId = tuple.get(qBalance.subjectId);
                    BigDecimal beginBalance = tuple.get(qBalance.beginBalance);
                    BigDecimal endBalance = tuple.get(qBalance.endBalance);
                    BigDecimal debit = tuple.get(qBalance.debit);
                    BigDecimal credit = tuple.get(qBalance.credit);
                    AgeAnalysisVo vo = voMap.get(subjectId);
                    if (vo != null) {
                        vo.setInitial(beginBalance);
                        vo.setTotal(endBalance);
                        vo.setCredit(credit);
                        vo.setDebit(debit);
                    }
                });

        if (!queryVo.isEqualDate()) {
            // 期末
            bqf.selectFrom(qBalance)
                    .select(qBalance.subjectId, qBalance.endBalance)
                    .where(qBalance.accountSetsId.eq(queryVo.getAccountSetsId())
                            .and(qBalance.auxiliaryDetailId.eq(0L))
                            .and(qBalance.currencyId.eq(queryVo.getLocalCurrencyId()))
                            .and(qBalance.subjectId.in(subjects.stream().map(Subject::getId).collect(Collectors.toList())))
                            .and(qBalance.year.eq(queryVo.getEnd().getYear()))
                            .and(qBalance.period.eq(queryVo.getEnd().getMonthValue())))
                    .fetch().parallelStream().forEach(tuple -> {
                        Long subjectId = tuple.get(qBalance.subjectId);
                        BigDecimal endBalance = tuple.get(qBalance.endBalance);
                        AgeAnalysisVo vo = voMap.get(subjectId);
                        if (vo != null) {
                            vo.setTotal(endBalance);
                        }
                    });
            // 借贷合计
            bqf.selectFrom(qBalance)
                    .select(qBalance.subjectId, qBalance.debit.sum(), qBalance.credit.sum())
                    .where(qBalance.accountSetsId.eq(queryVo.getAccountSetsId())
                            .and(qBalance.auxiliaryDetailId.eq(0L))
                            .and(qBalance.currencyId.eq(queryVo.getLocalCurrencyId()))
                            .and(qBalance.subjectId.in(subjects.stream().map(Subject::getId).collect(Collectors.toList())))
                            .and(qBalance.date.between(queryVo.getStart(), queryVo.getEnd())))
                    .fetch().parallelStream().forEach(tuple -> {
                        Long subjectId = tuple.get(qBalance.subjectId);
                        BigDecimal debit = tuple.get(qBalance.debit.sum());
                        BigDecimal credit = tuple.get(qBalance.credit.sum());
                        AgeAnalysisVo vo = voMap.get(subjectId);
                        if (vo != null) {
                            vo.setCredit(credit);
                            vo.setDebit(debit);
                        }
                    });
        }
        return list;
    }

    private MultiColumnLedgerVo getMultiColumnLedgerVo(LocalDate periodTotal, MultiColumnLedgerVo periodTotal1, String summary, BigDecimal startYearTotal, BigDecimal startYearTotal1) {
        MultiColumnLedgerVo yearTotal = new MultiColumnLedgerVo();
        // 插入本年合计
        yearTotal.setVoucherDate(periodTotal);
        yearTotal.setBalance(periodTotal1.getBalance());
        yearTotal.setSummary(summary);
        yearTotal.setCreditAmount(startYearTotal);
        yearTotal.setDebitAmount(startYearTotal1);
        yearTotal.setBalanceDirection(periodTotal1.getBalanceDirection());
        return yearTotal;
    }
}
