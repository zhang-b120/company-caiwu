package cn.gson.financial.kernel.service;

import cn.gson.financial.kernel.common.LocalDateRange;
import cn.gson.financial.kernel.config.AppConfig;
import cn.gson.financial.kernel.config.*;
import cn.gson.financial.kernel.controller.Page;
import cn.gson.financial.kernel.controller.PageResults;
import cn.gson.financial.kernel.exception.ServiceException;
import cn.gson.financial.kernel.model.dto.CurrencyDto;
import cn.gson.financial.kernel.model.dto.VoucherDetailsDto;
import cn.gson.financial.kernel.model.dto.VoucherDto;
import cn.gson.financial.kernel.model.entity.*;
import cn.gson.financial.kernel.model.enums.AccountingCategoryType;
import cn.gson.financial.kernel.model.enums.AttachmentType;
import cn.gson.financial.kernel.model.hibernate.BlazeJPAQueryFactory;
import cn.gson.financial.kernel.model.hibernate.PrimaryKey;
import cn.gson.financial.kernel.model.mapper.VoucherDetailsMapper;
import cn.gson.financial.kernel.model.mapper.VoucherMapper;
import cn.gson.financial.kernel.model.repo.*;
import cn.gson.financial.kernel.model.vo.SubjectVo;
import cn.gson.financial.kernel.model.vo.UserVo;
import cn.gson.financial.kernel.model.vo.VoucherImportVo;
import cn.gson.financial.kernel.tool.JxlsUtils;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.date.TimeInterval;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.lang.Dict;
import cn.hutool.core.util.NumberUtil;
import cn.hutool.core.util.ReflectUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.extra.spring.SpringUtil;
import com.alibaba.fastjson.JSON;
import com.blazebit.persistence.PagedList;
import com.blazebit.persistence.querydsl.BlazeJPAQuery;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.Tuple;
import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.core.types.dsl.NumberExpression;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import static org.springframework.transaction.annotation.Isolation.READ_UNCOMMITTED;

/**
 *
 * <p><b>Copyright © 2010-2019开发公司All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : ${PACKAGE_NAME}</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2019年07月30日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 *
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class VoucherService extends AbsService implements AccountSetsProcessor, BackupProcessor, ArchiveProcessor {
    private final VoucherMapper voucherMapper;
    private final VoucherDetailsMapper voucherDetailsMapper;
    private final List<VoucherProcessor> voucherProcessorList;
    private final VoucherDetailsRepository voucherDetailsRepository;
    private final VoucherRepository voucherRepository;
    private final CurrencyService currencyService;
    private final CheckoutService checkoutService;
    private final VoucherChangeLogService changeLogService;
    private final AccountSetsRepository accountSetsRepository;
    private final AuxiliaryDetailRepository auxiliaryDetailRepository;
    private final CheckoutRepository checkoutRepository;
    private final QCheckout qCheckout = QCheckout.checkout;
    private final QUser qUser = QUser.user;

    private final QAccountingCategory qAccountingCategory = QAccountingCategory.accountingCategory;
    private final QUser qAuditUser = new QUser("audit");
    private final QUser qUpdateUser = new QUser("updateUser");
    private final QUser qCashierUser = new QUser("cashierUser");
    private final static QVoucher qVoucher = QVoucher.voucher;

    private final static QSubject qSubject = QSubject.subject;

    private final static QCurrency qCurrency = QCurrency.currency;
    private final static QVoucherDetails qVoucherDetails = QVoucherDetails.voucherDetails;
    private final QAuxiliaryDetail qAuxiliaryDetail = QAuxiliaryDetail.auxiliaryDetail;

    private final SubjectService subjectService;

    private final AppConfig appConfig;

    public Long generateId() {
        return snowflakeId.generate();
    }
    private final QAccountSets qAccountSets = QAccountSets.accountSets;
    private final BalanceCalcService balanceCalcService;
    private final AttachmentService attachmentService;
    /**
     * 不分页查询凭证列表
     *
     * @param query 查询条件
     * @return 凭证列表
     */
    public List<VoucherDto> list(VoucherQuery query, Page page) {
        List<OrderSpecifier<?>> orders = getOrderSpecifiers(page);
        return queryTupleToVoucherDto(query.accountSetsId, getVoucherQuery(query).orderBy(orders.toArray(new OrderSpecifier<?>[]{})).fetch());
    }

    /**
     * 不分页查询凭证列表
     *
     * @param query 查询条件
     * @return 凭证列表
     */
    public List<VoucherDto> list(VoucherQuery query) {
        return queryTupleToVoucherDto(query.accountSetsId, getVoucherQuery(query).orderBy(qVoucher.year.asc(), qVoucher.month.asc(), qVoucher.word.asc(), qVoucher.code.asc(), qVoucher.id.asc()).fetch());
    }

    /**
     * 分页查询凭证列表
     *
     * @param query 查询条件
     * @param page  分页信息
     * @return 分页数据列表
     */
    public PageResults<VoucherDto> page(VoucherQuery query, Page page) {
        List<OrderSpecifier<?>> orders = getOrderSpecifiers(page);
        PagedList<Tuple> fetchPage = getVoucherQuery(query)
                .orderBy(orders.toArray(new OrderSpecifier<?>[]{}))
                .fetchPage(page.getOffset(), page.getOffsetEnd());
        return new PageResults<>(queryTupleToVoucherDto(query.accountSetsId, fetchPage), page, fetchPage.getTotalSize());
    }

    private List<OrderSpecifier<?>> getOrderSpecifiers(Page page) {
        List<OrderSpecifier<?>> orders = new ArrayList<>();
        if ("code".equals(page.getProperty())) {
            orders.add(qVoucher.year.asc());
            orders.add(qVoucher.month.asc());
            orders.add(qVoucher.word.asc());
            orders.add(ReflectUtil.invoke(qVoucher.code, page.getOrder()));
            orders.add(qVoucher.id.asc());
        } else {
            orders.add(ReflectUtil.invoke(qVoucher.year, page.getOrder()));
            orders.add(ReflectUtil.invoke(qVoucher.month, page.getOrder()));
            orders.add(ReflectUtil.invoke(qVoucher.voucherDate, page.getOrder()));
            orders.add(qVoucher.word.asc());
            orders.add(qVoucher.code.asc());
            orders.add(qVoucher.id.asc());
        }
        return orders;
    }

    /**
     * 获凭证字取最大的凭证号
     *
     * @param accountSetsId      账套ID
     * @param word               凭证字
     * @param currentAccountDate 期间
     * @return 当前可用最大凭证号
     */
    public int loadCode(Long accountSetsId, String word, LocalDate currentAccountDate) {
        Integer code = bqf.selectFrom(qVoucher)
                .select(qVoucher.code.max().add(1))
                .where(qVoucher.accountSetsId.eq(accountSetsId)
                        .and(qVoucher.word.eq(word))
                        .and(qVoucher.year.eq(currentAccountDate.getYear()))
                        .and(qVoucher.month.eq(currentAccountDate.getMonthValue())))
                .fetchFirst();
        return code == null ? 1 : code;
    }

    /**
     * 首页收入利润图表数据
     *
     * @param accountSetsId 账套ID
     * @param year          年费
     * @return
     */
    public List<Dict> getHomeReport(Long accountSetsId, Integer year) {
        return lazyDao.findBySql("selectHomeReport", Dict.create().set("accountSetsId", accountSetsId)
                .set("voucherYear", year), Dict.class);
    }

    /**
     * 首页费用数据
     *
     * @param accountSetsId
     * @param year
     * @param month
     * @return
     */
    public List<Dict> getCostReport(Long accountSetsId, int year, int month) {
        return lazyDao.findBySql("selectHomeCostReport", Dict.create().set("accountSetsId", accountSetsId)
                .set("voucherYear", year).set("voucherMonth", month), Dict.class);
    }

    /**
     * 首页现金数据
     *
     * @param accountSetsId
     * @param year
     * @param month
     * @return
     */
    public List<Dict> getCashReport(Long accountSetsId, int year, int month) {
        return lazyDao.findBySql("selectHomeCashReport", Dict.create().set("accountSetsId", accountSetsId)
                .set("voucherYear", year).set("voucherMonth", month), Dict.class);
    }

    /**
     * 断号整理
     *
     * @param accountSetsId 账套ID
     * @param year          期间年
     * @param month         期间月
     * @param isCheck
     */
    @Transactional
    public Map<String, List<Integer>> finishingOffNo(Long accountSetsId, Integer year, Integer month, boolean isCheck) {
        //当期的凭证字
        List<String> wordList = bqf.selectFrom(qVoucher)
                .select(qVoucher.word)
                .where(qVoucher.accountSetsId.eq(accountSetsId)
                        .and(qVoucher.year.eq(year))
                        .and(qVoucher.month.eq(month)))
                .groupBy(qVoucher.word)
                .fetch();
        Map<String, List<Integer>> gapList = new HashMap<>();
        if (isCheck) {
            Map<String, List<Integer>> listMap = bqf.selectFrom(qVoucher)
                    .select(qVoucher.code, qVoucher.word)
                    .where(qVoucher.accountSetsId.eq(accountSetsId)
                            .and(qVoucher.year.eq(year))
                            .and(qVoucher.month.eq(month)))
                    .orderBy(qVoucher.voucherDate.asc(), qVoucher.id.asc())
                    .fetch().stream().collect(Collectors.groupingBy(t -> t.get(qVoucher.word), Collectors.mapping(t -> t.get(qVoucher.code), Collectors.toList())));
            listMap.forEach((key, value) -> {
                // 查找断号
                List<Integer> gaps = findGaps(value);
                if (CollUtil.isNotEmpty(gaps)) {
                    gapList.put(key, gaps);
                }
            });
        } else {
            //分凭证字重新编号
            for (String word : wordList) {
                List<Long> voucherIdList = bqf.selectFrom(qVoucher)
                        .select(qVoucher.id)
                        .where(qVoucher.accountSetsId.eq(accountSetsId)
                                .and(qVoucher.word.eq(word))
                                .and(qVoucher.year.eq(year))
                                .and(qVoucher.month.eq(month)))
                        .orderBy(qVoucher.voucherDate.asc(), qVoucher.id.asc())
                        .fetch();
                for (int i = 0; i < voucherIdList.size(); i++) {
                    Long id = voucherIdList.get(i);
                    jqf.update(qVoucher)
                            .set(qVoucher.code, i + 1)
                            .where(qVoucher.id.eq(id).and(qVoucher.accountSetsId.eq(accountSetsId)))
                            .execute();
                }
            }
        }
        return gapList;
    }

    /**
     * 批量删除
     *
     * @param accountSets          账套
     * @param checkedVoucherIdList 待删除凭证ID
     * @return
     */
    @Transactional
    public List<LocalDate> batchDelete(AccountSets accountSets, List<Long> checkedVoucherIdList) {
        Long accountSetsId = accountSets.getId();
        BooleanExpression expression = qVoucher.accountSetsId.eq(accountSetsId)
                .and(qVoucher.locked.isFalse())
                .and(qVoucher.id.in(checkedVoucherIdList));
        if (accountSets.getVoucherReviewed()) {
            expression = expression.and(qVoucher.auditMemberId.isNull());
        }

        //可进行删除的凭证
        List<Long> voucherIdList = new ArrayList<>();
        LinkedHashSet<LocalDate> voucherDateSet = new LinkedHashSet<>();
        // FN-FIX-VOUCHER-DELETE-CASCADE：识别本次删除中的「结转损益凭证」，按其所属
        // (year, month) 把 Checkout.status 回退到 0（未结转）。否则下次想重新结转时
        // status=1 会触发 ALREADY_CARRIED 拒绝。
        Set<YearMonth> carryForwardPeriods = new HashSet<>();
        bqf.selectFrom(qVoucher)
                .select(qVoucher.id, qVoucher.voucherDate, qVoucher.carryForward, qVoucher.year, qVoucher.month)
                .where(expression)
                .fetch().forEach(tuple -> {
                    voucherIdList.add(tuple.get(qVoucher.id));
                    voucherDateSet.add(tuple.get(qVoucher.voucherDate).withDayOfMonth(1));
                    if (Boolean.TRUE.equals(tuple.get(qVoucher.carryForward))) {
                        Integer y = tuple.get(qVoucher.year);
                        Integer m = tuple.get(qVoucher.month);
                        if (y != null && m != null) {
                            carryForwardPeriods.add(YearMonth.of(y, m));
                        }
                    }
                });

        //清除没有凭证的结账期间
        if (CollUtil.isNotEmpty(voucherIdList)) {
            for (Long voucherId : voucherIdList) {
                jqf.delete(qVoucher)
                        .where(qVoucher.id.eq(voucherId).and(qVoucher.accountSetsId.eq(accountSetsId)))
                        .execute();
                // FN-FIX-VOUCHER-DELETE-CASCADE：触发 9 个 VoucherProcessor 清理反向引用
                //   - SalaryVoucherService → 删 salary_voucher 关联行
                //   - CashierJournalService → cashier_journal.voucher_id 置空（银行日记账解绑）
                //   - InvoiceService → invoice.voucher_id 置空（发票解绑）
                //   - CashierTransferService → cashier_transfer.voucher_id 置空
                //   - AssetsDetailService / AssetsDepreciationService / AssetsChangeService → 资产相关解绑
                //   - VoucherDetailsService → 删凭证明细
                //   - VoucherChangeLogService → 删变更日志
                voucherProcessorList.forEach(vp -> vp.voucherDeleted(voucherId, accountSetsId));
            }

            // 删除凭证时，只重算结账期间，不主动调整当前记账日期，避免影响会计日常操作
            checkoutService.reCreateCheckout(accountSets, false);

            // FN-FIX-VOUCHER-DELETE-CASCADE：联动 Checkout.status —— 任一被删凭证是结转损益时，
            // 把对应期间 status 回退到 0（未结转），让下次 profit-carry-auto 可以重做。
            for (YearMonth ym : carryForwardPeriods) {
                try {
                    checkoutService.updateStatus(accountSetsId, ym.getYear(), ym.getMonthValue(), 0);
                } catch (Exception e) {
                    log.warn("[FN-FIX-VOUCHER-DELETE-CASCADE] 回退 Checkout.status 失败 accountSetsId={} {}: {}",
                            accountSetsId, ym, e.getMessage());
                }
            }
        }
        return voucherDateSet.stream().sorted().collect(Collectors.toList());
    }

    /**
     * 根据当前 Id 获取上一条 ID
     *
     * @param accountSetsId 账套ID
     * @param code          凭证号
     * @param voucherDate   凭证日期
     * @return 上一个凭证ID
     */
    public Long getBeforeId(Long accountSetsId, Integer code, LocalDate voucherDate, String word) {
        Long voucherId = bqf.selectFrom(qVoucher)
                .select(qVoucher.id)
                .where(qVoucher.accountSetsId.eq(accountSetsId)
                        .and(qVoucher.year.eq(voucherDate.getYear()))
                        .and(qVoucher.month.eq(voucherDate.getMonthValue()))
                        .and(qVoucher.word.eq(word))
                        .and(qVoucher.code.lt(code))
                ).orderBy(qVoucher.code.desc(), qVoucher.id.desc()).fetchFirst();

        if (voucherId == null) {
            voucherDate = voucherDate.plusMonths(-1);
            voucherId = bqf.selectFrom(qVoucher)
                    .select(qVoucher.id)
                    .where(qVoucher.accountSetsId.eq(accountSetsId)
                            .and(qVoucher.year.eq(voucherDate.getYear()))
                            .and(qVoucher.month.eq(voucherDate.getMonthValue()))
                            .and(qVoucher.word.eq(word))
                    ).orderBy(qVoucher.code.desc(), qVoucher.id.desc()).fetchFirst();
        }

        return voucherId;
    }

    /**
     * 根据当前 Id 获取下一条 ID
     *
     * @param accountSetsId 账套ID
     * @param code          凭证号
     * @param voucherDate   凭证日期
     * @return 下一个凭证ID
     */
    public Long getNextId(Long accountSetsId, Integer code, LocalDate voucherDate, String word) {
        Long voucherId = bqf.selectFrom(qVoucher)
                .select(qVoucher.id)
                .where(qVoucher.accountSetsId.eq(accountSetsId)
                        .and(qVoucher.year.eq(voucherDate.getYear()))
                        .and(qVoucher.month.eq(voucherDate.getMonthValue()))
                        .and(qVoucher.word.eq(word))
                        .and(qVoucher.code.gt(code))
                ).orderBy(qVoucher.code.asc(), qVoucher.id.asc()).fetchFirst();

        if (voucherId == null) {
            voucherDate = voucherDate.plusMonths(1);
            voucherId = bqf.selectFrom(qVoucher)
                    .select(qVoucher.id)
                    .where(qVoucher.accountSetsId.eq(accountSetsId)
                            .and(qVoucher.year.eq(voucherDate.getYear()))
                            .and(qVoucher.month.eq(voucherDate.getMonthValue()))
                            .and(qVoucher.word.eq(word))
                    ).orderBy(qVoucher.code.asc(), qVoucher.id.asc()).fetchFirst();
        }

        return voucherId;
    }

    /**
     * 获取最近使用的摘要
     *
     * @param accountSetsId
     * @return
     */
    public List<String> getTopSummary(Long accountSetsId) {
        return lazyDao.findBySql("selectTopSummary", Dict.create().set("accountSetsId", accountSetsId), String.class);
    }

    /**
     * 审核
     *
     * @param accountSetsId 账套ID
     * @param checked       审核
     */
    @Transactional
    public void audit(Long accountSetsId, List<Long> checked, UserVo currentUser) {
        List<Voucher> vouchers = bqf.selectFrom(qVoucher)
                .where(qVoucher.id.in(checked)
                        .and(qVoucher.auditMemberId.isNull())
                        .and(qVoucher.accountSetsId.eq(accountSetsId)))
                .fetch();

        doAudit(accountSetsId, currentUser, vouchers);
    }

    @Transactional
    public void batchAudit(Long accountSetsId, UserVo currentUser, Integer year, Integer month) {
        List<Voucher> vouchers = bqf.selectFrom(qVoucher)
                .where(qVoucher.year.in(year)
                        .and(qVoucher.month.eq(month))
                        .and(qVoucher.auditMemberId.isNull())
                        .and(qVoucher.accountSetsId.eq(accountSetsId)))
                .fetch();
        doAudit(accountSetsId, currentUser, vouchers);
    }


    /**
     * FN-FIX-SALARY-AUTOGEN-REVIEW-STATUS：系统自动审核（APPROVED）。
     * <p>自动生成的凭证（工资 autogen / 任务做账自动做账）默认即审，与 XW 取消审批环节
     * （XW-FIX-NO-APPROVE-STEP）口径对齐——审核人记为系统用户 id=0（OpenAPI 自动），
     * 避免有工资账套关账时被 PENDING（audit_member_id IS NULL）卡住、被迫逐户手动审核。
     * <p>语义与 OpenApiVoucherController.applyReviewStatus(APPROVED) 一致：locked=true +
     * audit_member_id=0 + audit_date=now，仅作用于尚未审核（audit_member_id IS NULL）的凭证（幂等）。
     */
    @Transactional
    public void systemAudit(Long accountSetsId, List<Long> ids) {
        if (accountSetsId == null || CollUtil.isEmpty(ids)) {
            return;
        }
        jqf.update(qVoucher)
                .set(qVoucher.locked, true)
                .set(qVoucher.auditMemberId, 0L)
                .set(qVoucher.auditDate, LocalDateTime.now())
                .where(qVoucher.accountSetsId.eq(accountSetsId)
                        .and(qVoucher.id.in(ids))
                        .and(qVoucher.auditMemberId.isNull()))
                .execute();
    }

    private void doAudit(Long accountSetsId, UserVo currentUser, List<Voucher> vouchers) {
        if (CollUtil.isNotEmpty(vouchers)) {
            vouchers.stream().map(Voucher::getVoucherDate).distinct().forEach(vd -> {
                this.checkCheckOut(accountSetsId, vd.getYear(), vd.getMonthValue());
            });

            jqf.update(qVoucher)
                    .set(qVoucher.locked, true)
                    .set(qVoucher.auditMemberId, currentUser.getId())
                    .set(qVoucher.auditDate, LocalDateTime.now())
                    .where(qVoucher.accountSetsId.eq(accountSetsId)
                            .and(qVoucher.id.in(vouchers.stream().map(Voucher::getId).toList())))
                    .execute();
        }
    }

    /**
     * 反审核
     *
     * @param accountSetsId
     * @param checked
     */
    @Transactional
    public void cancelAudit(Long accountSetsId, Long[] checked, Integer year, Integer month) {
        this.checkCheckOut(accountSetsId, year, month);
        jqf.update(qVoucher)
                .set(qVoucher.locked, false)
                .setNull(qVoucher.auditMemberId)
                .setNull(qVoucher.auditDate)
                .where(qVoucher.accountSetsId.eq(accountSetsId)
                        .and(qVoucher.year.eq(year))
                        .and(qVoucher.month.eq(month))
                        .and(qVoucher.id.in(checked))
                        .and(qVoucher.auditMemberId.isNotNull()))
                .execute();
    }

    /**
     * 批量导入凭证
     *
     * @param voucherList
     * @return
     */
    @Transactional(isolation = READ_UNCOMMITTED)
    public LinkedHashSet<LocalDate> importVoucher(List<VoucherDto> voucherList, AccountSets accountSets) {
        LinkedHashSet<LocalDate> voucherDateList = new LinkedHashSet<>();
        for (VoucherDto voucher : voucherList) {
            this.save(voucher, accountSets, true, false);
            voucherDateList.add(voucher.getVoucherDate());
        }
        // 只重算结账期间，不调整当前记账日期，避免影响会计已选定的记账期间
        checkoutService.reCreateCheckout(accountSets, false);

        return voucherDateList;
    }

    /**
     * 非导入保存凭证
     *
     * @param voucherDto  凭证数据
     * @param accountSets 账套
     * @return
     */
    @Transactional
    public VoucherDto save(VoucherDto voucherDto, AccountSets accountSets, Boolean isSysAuto) {
        Voucher voucher = this.save(voucherDto, accountSets, false, isSysAuto);
        // 新增凭证时，只重算结账期间，不调整当前记账日期
        checkoutService.reCreateCheckout(accountSets, false);
        return voucherMapper.toDto(voucher);
    }

    /**
     * 更新凭证
     *
     * @param voucherDto
     * @param accountSets
     */
    @Transactional
    public Optional<Voucher> update(VoucherDto voucherDto, AccountSets accountSets) {
        CurrencyDto localCurrency = currencyService.fetchLocal(accountSets.getId());
        Optional<Voucher> original = voucherRepository.findById(new PrimaryKey(voucherDto.getId(), accountSets.getId()));
        original.ifPresent(voucher -> {
            Assert.isFalse(voucher.getLocked(), "期间已结账，不允许更新~");
            if (accountSets.getVoucherReviewed()) {
                Assert.isTrue(voucher.getAuditDate() == null, "凭证已审核，不允许更新~");
            }

            //检查凭证日期是否已经结账
            this.checkCheckOut(accountSets.getId(), voucherDto.getVoucherDate().getYear(), voucherDto.getVoucherDate().getMonthValue());

            Assert.notEmpty(voucherDto.getDetails(), "没有凭证明细~");

            voucherMapper.partialUpdate(voucherDto, voucher);
            this.setYearAndMonth(voucher, accountSets);
            this.checkCode(voucher);

            //更新总和
            BigDecimal debitAmount = voucherDto.getDetails().stream().map(VoucherDetailsDto::getDebitAmount)
                    .reduce(NumberUtil::add).orElse(BigDecimal.ZERO);
            BigDecimal creditAmount = voucherDto.getDetails().stream().map(VoucherDetailsDto::getCreditAmount)
                    .reduce(NumberUtil::add).orElse(BigDecimal.ZERO);
            voucher.setCreditAmount(creditAmount);
            voucher.setDebitAmount(debitAmount);
            Assert.isTrue(NumberUtil.equals(creditAmount, debitAmount), "凭证借贷金额不相等~，借方：{},贷方：{}", NumberUtil.round(debitAmount, 2), NumberUtil.round(creditAmount, 2));
            voucherRepository.save(voucher);

            //删除原有明细
            jqf.delete(qVoucherDetails)
                    .where(qVoucherDetails.voucherId.eq(voucher.getId()).and(qVoucherDetails.accountSetsId.eq(accountSets.getId())))
                    .execute();

            batchVoucherDetail(voucherDto.getDetails(), voucherMapper.toDto(voucher), localCurrency);
            voucherDto.setCreditAmount(creditAmount);
            voucherDto.setDebitAmount(debitAmount);
            changeLogService.saveLog(voucher.getId(), voucher.getAccountSetsId(), voucherDto);
        });
        return original;
    }

    public VoucherDto getOne(Long accountSetsId, Long voucherId) {
        VoucherQuery query = new VoucherQuery();
        query.setAccountSetsId(accountSetsId);
        query.setVoucherId(voucherId);
        VoucherDto voucherDto = queryTupeToVoucherDto(getVoucherQuery(query).fetchFirst());
        fetchAuxiliaryDetail(accountSetsId, Collections.singletonList(voucherDto));
        return voucherDto;
    }

    /**
     * 账套总凭证数
     *
     * @param accountSetsId 账套ID
     * @return
     */
    public long countByAccountSetsId(Long accountSetsId) {
        return bqf.selectFrom(qVoucher)
                .where(qVoucher.accountSetsId.eq(accountSetsId))
                .fetchCount();
    }

    /**
     * 期间凭证总数
     *
     * @param accountSetsId 账套
     * @param year          期间年
     * @param month         期间月
     * @return
     */
    public long count(Long accountSetsId, int year, int month) {
        return bqf.selectFrom(qVoucher)
                .where(qVoucher.accountSetsId.eq(accountSetsId)
                        .and(qVoucher.year.eq(year))
                        .and(qVoucher.month.eq(month)))
                .fetchCount();
    }

    /**
     * 删除账套清除数据
     *
     * @param accountSetsId
     */
    @Transactional
    @Override
    public void clear(Long accountSetsId) {
        // FN-FIX-INVOICE-STALE-VOUCHER #3 (防御性)：原来 bulk delete 不触发 voucherProcessorList 回调，
        // 导致 invoice/asset/cashier/salary 等关联表的 voucher_id 悬空。
        // 改为 single delete 循环，逐张触发 6 个 VoucherProcessor.voucherDeleted hook 清关联引用。
        List<Long> ids = bqf.selectFrom(QVoucher.voucher)
                .select(QVoucher.voucher.id)
                .where(QVoucher.voucher.accountSetsId.eq(accountSetsId))
                .fetch();
        for (Long vid : ids) {
            jqf.delete(QVoucher.voucher)
                    .where(QVoucher.voucher.id.eq(vid)
                            .and(QVoucher.voucher.accountSetsId.eq(accountSetsId)))
                    .execute();
            voucherProcessorList.forEach(vp -> vp.voucherDeleted(vid, accountSetsId));
        }
        jqf.delete(qVoucherDetails)
                .where(qVoucherDetails.accountSetsId.eq(accountSetsId))
                .execute();
    }

    @Override
    public String backup(Long accountSetsId) {
        VoucherQuery query = new VoucherQuery();
        query.setAccountSetsId(accountSetsId);
        return JSON.toJSONString(this.list(query));
    }

    @Override
    public Class<?> backupType() {
        return Voucher.class;
    }

    @Override
    public void recover(Long accountSetsId, String json) {
        List<VoucherDto> entityList = JSON.parseArray(json, VoucherDto.class);
        for (VoucherDto entity : entityList) {
            entity.setAccountSetsId(accountSetsId);
            voucherRepository.save(voucherMapper.toEntity(entity));

            for (VoucherDetailsDto detail : entity.getDetails()) {
                detail.setAccountSetsId(accountSetsId);
                voucherDetailsRepository.save(voucherDetailsMapper.toEntity(detail));
            }
        }
    }


    /**
     * 保存凭证
     *
     * @param voucherDto  凭证数据
     * @param accountSets 账套
     * @param imports     是否导入保存
     * @return
     */
    private Voucher save(VoucherDto voucherDto, AccountSets accountSets, boolean imports, Boolean isSysAuto) {
        Voucher entity = voucherMapper.toEntity(voucherDto);
        entity.setIsSysAuto(isSysAuto);
        // 红冲凭证不检查
        if (entity.getReverseVoucherId() == null) {
            //检查期间是否已经结账
            this.checkCheckOut(accountSets.getId(), entity.getVoucherDate().getYear(), entity.getVoucherDate().getMonthValue());
        }

        entity.setId(snowflakeId.generate());
        entity.setAccountSetsId(accountSets.getId());
        this.setYearAndMonth(entity, accountSets);

        // 插入凭证无需判断
        if (!voucherDto.getInsert()) {
            this.checkCode(entity);
        } else {
            // 判断一下原编号是否存在，不存在就直接插入了，存在就要更新后面的编号
            Long oldVoucher = bqf.selectFrom(qVoucher)
                    .select(qVoucher.id)
                    .where(qVoucher.accountSetsId.eq(entity.getAccountSetsId())
                            .and(qVoucher.year.eq(entity.getYear()))
                            .and(qVoucher.month.eq(entity.getMonth()))
                            .and(qVoucher.code.eq(entity.getCode()))
                            .and(qVoucher.word.eq(entity.getWord())))
                    .fetchFirst();
            if (oldVoucher != null) {
                List<Voucher> updateVoucher = new ArrayList<>();
                for (Voucher v : bqf.selectFrom(qVoucher)
                        .where(qVoucher.accountSetsId.eq(entity.getAccountSetsId())
                                .and(qVoucher.year.eq(entity.getYear()))
                                .and(qVoucher.month.eq(entity.getMonth()))
                                .and(qVoucher.code.goe(entity.getCode()))
                                .and(qVoucher.word.eq(entity.getWord())))
                        .fetch()) {
                    v.setCode(v.getCode() + 1);
                    updateVoucher.add(v);
                }
                // 更新编码
                if (CollUtil.isNotEmpty(updateVoucher)) {
                    voucherRepository.saveAll(updateVoucher);
                }
            }
        }

        //更新总和
        BigDecimal debitAmount = voucherDto.getDetails().stream().map(VoucherDetailsDto::getDebitAmount)
                .reduce(NumberUtil::add).orElse(BigDecimal.ZERO);
        BigDecimal creditAmount = voucherDto.getDetails().stream().map(VoucherDetailsDto::getCreditAmount)
                .reduce(NumberUtil::add).orElse(BigDecimal.ZERO);
        entity.setCreditAmount(creditAmount);
        entity.setDebitAmount(debitAmount);
        Assert.isTrue(NumberUtil.equals(creditAmount, debitAmount), "凭证借贷金额不相等~，借方：{},贷方：{}", NumberUtil.round(debitAmount, 2), NumberUtil.round(creditAmount, 2));
        voucherRepository.saveAndFlush(entity);
        CurrencyDto localCurrency = currencyService.fetchLocal(accountSets.getId());
        batchVoucherDetail(voucherDto.getDetails(), voucherMapper.toDto(entity), localCurrency);
        voucherDto.setCreditAmount(creditAmount);
        voucherDto.setDebitAmount(debitAmount);
        changeLogService.saveLog(entity.getId(), entity.getAccountSetsId(), voucherDto);
        return entity;
    }

    /**
     * 批量保存凭证明细
     *
     * @param detailsDtos
     * @param voucher
     */
    private void batchVoucherDetail(List<VoucherDetailsDto> detailsDtos, VoucherDto voucher, CurrencyDto localCurrency) {
        Assert.notEmpty(detailsDtos, "没有凭证明细~");
        List<VoucherDetails> voucherDetails = new ArrayList<>();
        boolean cash = false;
        for (int i = 0; i < detailsDtos.size(); i++) {
            VoucherDetailsDto vd = detailsDtos.get(i);
            String tips = voucher.getVoucherDate().format(DateTimeFormatter.ofPattern("yyyy-MM-dd")) + "，" + voucher.getWord() + voucher.getCode() + "，第" + (i + 1) + "行，";
            Subject subject = subjectService.getById(new PrimaryKey(vd.getSubjectId(), voucher.getAccountSetsId()));
            Assert.isTrue(subject != null, tips + "科目信息错误，请检查~");
            Assert.isTrue(subject.getLeaf(), tips + "科目：【" + subject.getCode() + "-" + subject.getFullName() + "】不是末级科目请检查~");

            if (subject.getCash()) {
                //标记为现金凭证
                cash = true;
            }

            vd.setVoucherDate(voucher.getVoucherDate());
            vd.setYear(voucher.getYear());
            vd.setMonth(voucher.getMonth());
            vd.setCarryForward(voucher.getCarryForward());
            vd.setVoucherId(voucher.getId());
            vd.setAccountSetsId(voucher.getAccountSetsId());
            // FN-FIX-VOUCHER-CURRENCY-NORMALIZE-ZERO：归一须同时认 null 与 0。
            // openapi 推凭证缺省币种被塞成 0（非 null，OpenApiVoucherController），旧逻辑只认 null →
            // detail 币种停在 0 → 余额表按 currency_id=0 分桶出幽灵行 → profit-check 假报 LOSS。
            // 此处是网页+openapi 全部凭证保存的唯一收口，一处归一覆盖所有调用方；
            // 外币明细（currency_id 为真实币种雪花 id，非 0/null）不受影响。
            if (vd.getCurrencyId() == null || vd.getCurrencyId() == 0L) {
                vd.setCurrencyId(localCurrency.getId());
            }
            VoucherDetails details = voucherDetailsMapper.toEntity(vd);
            //设置ID
            details.setId(snowflakeId.generate());

            if (details.getDebitAmount() != null && BigDecimal.ZERO.compareTo(details.getDebitAmount()) != 0) {
                details.setDebitNum(NumberUtil.round(details.num(), 5));
                details.setDebitAmountFor(vd.originalAmount());
                details.setCreditAmountFor(BigDecimal.ZERO);
            } else if (details.getCreditAmount() != null && BigDecimal.ZERO.compareTo(details.getCreditAmount()) != 0) {
                details.setCreditNum(NumberUtil.round(details.num(), 5));
                details.setCreditAmountFor(vd.originalAmount());
                details.setDebitAmountFor(BigDecimal.ZERO);
            }

            if (StrUtil.isNotEmpty(subject.getUnit())) {
                Assert.notNull(details.num(), tips + "科目：" + subject.getCode() + "，启用了数量核算，请填写数量~");
            }

            //存储辅助项
            if (CollUtil.isNotEmpty(subject.getRequiredAuxiliaryAccounting()) || CollUtil.isNotEmpty(vd.getAuxiliary())) {
                String tips2 = tips + "科目：" + subject.getCode() + "，辅助项信息不完整,请检查~";
                Assert.isTrue(CollUtil.isNotEmpty(vd.getAuxiliary()), tips2);
                Set<Long> categoryIds = vd.getAuxiliary().stream().map(AcDetails::getCategoryId).collect(Collectors.toSet());
                //必填辅助项
                Set<Long> required = subject.getAuxiliaryAccounting().stream().filter(id -> {
                    if (CollUtil.isEmpty(subject.getNonAuxiliaryAccounting())) {
                        return true;
                    }
                    return !subject.getNonAuxiliaryAccounting().contains(id);
                }).collect(Collectors.toSet());
                if (CollUtil.isNotEmpty(required)) {
                    Assert.isTrue(categoryIds.containsAll(required), tips2);
                }
                details.setAuxiliaryDetailId(getAuxiliaryDetailId(voucher.getAccountSetsId(), vd.getAuxiliary()));
            } else {
                // FN-FIX-AUX-NULL-VS-ZERO：无辅助统一写 0L（与 openapi 推送路径一致），
                // 避免"无辅助"语义在库里既有 NULL 又有 0、被余额/校验层劈成两个桶。
                details.setAuxiliaryDetailId(0L);
            }

            voucherDetails.add(details);
        }

        if (CollUtil.isNotEmpty(voucherDetails)) {
            // 标记现金凭证记录
            for (VoucherDetails v : voucherDetails) {
                v.setCashFlow(cash);
            }
            voucherDetailsRepository.saveAllAndFlush(voucherDetails);
        }
    }

    @Transactional
    public Long getAuxiliaryDetailId(Long accountSetsId, List<AcDetails> auxiliary) {
        //构建辅助项id组合键
        String calCol = auxiliary.stream()
                .sorted(Comparator.comparing(AcDetails::getCategoryId))
                .map(s -> String.valueOf(s.getId()))
                .collect(Collectors.joining("_"));
        //查询组合键是否存在
        Long detailId = bqf.selectFrom(qAuxiliaryDetail)
                .select(qAuxiliaryDetail.id)
                .where(qAuxiliaryDetail.calCol.eq(calCol).and(qAuxiliaryDetail.accountSetsId.eq(accountSetsId)))
                .fetchFirst();

        //存在就就直接赋值，不存则这创建
        if (detailId != null) {
            return detailId;
        } else {
            //创建辅助组合明细记录
            AuxiliaryDetail ad = new AuxiliaryDetail();
            ad.setDetails(auxiliary.stream().map(s -> {
                AuxiliaryDetail.Detail detail = new AuxiliaryDetail.Detail();
                detail.setCategoryId(s.getCategoryId());
                detail.setCategoryType(s.getCategoryType());
                detail.setValue(s.getId());
                return detail;
            }).collect(Collectors.toList()));
            ad.setCalCol(calCol);
            ad.setId(snowflakeId.generate());
            ad.setAccountSetsId(accountSetsId);
            auxiliaryDetailRepository.save(ad);
            return ad.getId();
        }
    }

    /**
     * 将查询的Tuple对象转换成VoucherDto
     *
     * @param tuple
     * @return
     */
    private VoucherDto queryTupeToVoucherDto(Tuple tuple) {
        Assert.notNull(tuple, "未查询到凭证~");
        VoucherDto voucherDto = voucherMapper.toDto(tuple.get(qVoucher));
        voucherDto.setCreateMemberName(tuple.get(qUser.realName.coalesce(qUser.nickname)));
        voucherDto.setCreateMemberMobile(tuple.get(qUser.mobile));
        voucherDto.setAuditMemberName(tuple.get(qAuditUser.realName.coalesce(qAuditUser.nickname)));
        voucherDto.setCashierName(tuple.get(qCashierUser.realName.coalesce(qCashierUser.nickname)));
        voucherDto.setLastUpdateMemberName(tuple.get(qUpdateUser.realName.coalesce(qUpdateUser.nickname)));
        return voucherDto;
    }

    /**
     * 构建凭证的查询对象
     *
     * @param query
     * @return
     */
    private BlazeJPAQuery<Tuple> getVoucherQuery(VoucherQuery query) {
        return bqf.selectFrom(qVoucher)
                .select(qVoucher, qUser.mobile, qUser.realName.coalesce(qUser.nickname),
                        qCashierUser.realName.coalesce(qCashierUser.nickname),
                        qUpdateUser.realName.coalesce(qUpdateUser.nickname),
                        qAuditUser.realName.coalesce(qAuditUser.nickname))
                .leftJoin(qAuditUser).on(qAuditUser.id.eq(qVoucher.auditMemberId))
                .leftJoin(qCashierUser).on(qCashierUser.id.eq(qVoucher.cashierId))
                .leftJoin(qUser).on(qUser.id.eq(qVoucher.createMember))
                .leftJoin(qUpdateUser).on(qUpdateUser.id.eq(qVoucher.lastUpdateMember))
                .where(query.getBuilder(bqf));
    }

    /**
     * 查询凭证关联辅助项
     *
     * @param accountSetsId
     * @param voucherList
     * @return
     */
    private List<VoucherDto> fetchAuxiliaryDetail(Long accountSetsId, List<VoucherDto> voucherList) {
        TimeInterval timer = DateUtil.timer();
        if (CollUtil.isEmpty(voucherList)) {
            return new ArrayList<>();
        }
        timer.start("voucherDetails");
        Set<Long> subjectIds = new HashSet<>();
        //查询凭证明细
        List<VoucherDetailsDto> voucherDetails = bqf.selectFrom(qVoucherDetails)
                .select(qVoucherDetails, qSubject.fullName, qCurrency.code, qCurrency.name, qSubject.code)
                .join(qSubject).on(qVoucherDetails.subjectId.eq(qSubject.id).and(qSubject.accountSetsId.eq(accountSetsId)))
                .leftJoin(qCurrency).on(qVoucherDetails.currencyId.eq(qCurrency.id).and(qCurrency.accountSetsId.eq(accountSetsId)))
                .where(qVoucherDetails.voucherId.in(voucherList.stream().map(VoucherDto::getId).collect(Collectors.toList()))
                        .and(qVoucherDetails.accountSetsId.eq(accountSetsId)))
                .orderBy(qVoucherDetails.voucherId.asc(), qVoucherDetails.id.asc())
                .fetch().stream().map(tuple -> {
                    VoucherDetailsDto dto = voucherDetailsMapper.toDto(tuple.get(qVoucherDetails));
                    subjectIds.add(dto.getSubjectId());
                    dto.setSubjectName(tuple.get(qSubject.fullName));
                    dto.setSubjectCode(tuple.get(qSubject.code));
                    dto.setCurrencyName(tuple.get(qCurrency.name));
                    dto.setCurrencyCode(tuple.get(qCurrency.code));
                    return dto;
                }).toList();
        timer.start("auxiliary");
        //所有辅助项
        Set<Long> auxiliaryDetailIdList = voucherDetails.stream().map(VoucherDetailsDto::getAuxiliaryDetailId).filter(Objects::nonNull).collect(Collectors.toSet());
        //辅助项值
        Map<Long, AcDetails> acDetailsMap = new HashMap<>();
        //辅助项目明细
        Map<Long, AuxiliaryDetail> auxiliaryDetailMap = new HashMap<>();
        if (CollUtil.isNotEmpty(auxiliaryDetailIdList)) {
            //获取辅助项明细数据
            auxiliaryDetailMap = bqf.selectFrom(qAuxiliaryDetail)
                    .where(qAuxiliaryDetail.id.in(auxiliaryDetailIdList).and(qAuxiliaryDetail.accountSetsId.eq(accountSetsId)))
                    .fetch().stream().collect(Collectors.toMap(AuxiliaryDetail::getId, it -> it));
            //辅助明细数据类型和id列表对应
            Map<AccountingCategoryType, Set<Long>> details = auxiliaryDetailMap.values()
                    .stream()
                    .flatMap(it -> it.getDetails().stream())
                    .collect(Collectors.groupingBy(AuxiliaryDetail.Detail::getCategoryType,
                            Collectors.mapping(AuxiliaryDetail.Detail::getValue, Collectors.toSet())));
            //通过不同类型获取相应类型数据
            details.forEach((type, idList) -> {
                AuxiliaryDataService dataService = SpringUtil.getBean(type.dataService);
                acDetailsMap.putAll(dataService.loadDataByIdList(accountSetsId, idList));
            });
        }
        timer.start("detailMap");
        Map<Long, SubjectVo> subjectVoMap = subjectService.loadById(subjectIds, accountSetsId);
        //按凭证分组
        Map<Long, List<VoucherDetailsDto>> detailMap = voucherDetails.stream().collect(Collectors.groupingBy(VoucherDetailsDto::getVoucherId));
        for (VoucherDto voucher : voucherList) {
            List<VoucherDetailsDto> voucherDetailsDtos = detailMap.get(voucher.getId());
            if (voucherDetailsDtos != null) {
                for (VoucherDetailsDto detail : voucherDetailsDtos) {
                    detail.setSubject(subjectVoMap.get(detail.getSubjectId()));
                    if (detail.getAuxiliaryDetailId() != null && detail.getAuxiliaryDetailId() != 0L && !auxiliaryDetailMap.isEmpty()) {
                        AuxiliaryDetail auxiliaryDetail = auxiliaryDetailMap.get(detail.getAuxiliaryDetailId());
                        if (auxiliaryDetail != null) {
                            detail.setAuxiliary(auxiliaryDetail.getDetails().stream()
                                    .map(d -> acDetailsMap.get(d.getValue()))
                                    .collect(Collectors.toList()));
                        } else {
                            log.error(detail.getAuxiliaryDetailId() + "不存在~,detailId" + detail.getId());
                        }
                    }
                }
                voucher.setDetails(voucherDetailsDtos);
            } else {
                log.info("没有明细的凭证：{}", voucher.getId());
            }
        }
        return voucherList;
    }

    /**
     * 将查询的Tuple列表转换成List VoucherDto
     *
     * @param tupleList
     * @return
     */
    private List<VoucherDto> queryTupleToVoucherDto(Long accountSetsId, List<Tuple> tupleList) {
        List<VoucherDto> voucherDtos = tupleList.stream().map(this::queryTupeToVoucherDto).collect(Collectors.toList());

        return fetchAuxiliaryDetail(accountSetsId, voucherDtos);
    }


    /**
     * 检查凭证号是否已使用
     *
     * @param entity 凭证
     */
    private void checkCode(Voucher entity) {
        BooleanExpression expression = qVoucher.accountSetsId.eq(entity.getAccountSetsId())
                .and(qVoucher.year.eq(entity.getYear()))
                .and(qVoucher.month.eq(entity.getMonth()))
                .and(qVoucher.code.eq(entity.getCode()))
                .and(qVoucher.word.eq(entity.getWord()));
        if (entity.getId() != null) {
            expression = expression.and(qVoucher.id.ne(entity.getId()));
        }

        if (voucherRepository.count(expression) > 0) {
            entity.setCode(this.loadCode(entity.getAccountSetsId(), entity.getWord(), entity.getVoucherDate()));
        }
    }

    /**
     * 检查期间是否已结账
     *
     * @param accountSetsId 账套
     * @param year          年
     * @param month         期间月
     */
    public void checkCheckOut(Long accountSetsId, Integer year, Integer month) {
        BooleanExpression expression = qCheckout.accountSetsId.eq(accountSetsId)
                .and(qCheckout.checkYear.eq(year))
                .and(qCheckout.checkMonth.eq(month))
                .and(qCheckout.status.eq(2));
        if (checkoutRepository.count(expression) > 0) {
            throw new ServiceException("亲,期间已结账，不允许操作凭证！");
        }
    }

    /**
     * 设置凭证日期
     *
     * @param entity 凭证
     * @param entity 账套
     */
    private void setYearAndMonth(Voucher entity, AccountSets accountSets) {
        entity.setYear(entity.getVoucherDate().getYear());
        entity.setMonth(entity.getVoucherDate().getMonthValue());

        if (!accountSets.getEnableDate().isEqual(entity.getVoucherDate()) && accountSets.getEnableDate().isAfter(entity.getVoucherDate())) {
            throw new ServiceException("亲,日期不能小于账套启用日期：" + accountSets.getEnableDate().format(DateTimeFormatter.ofPattern("yyyy-MM")));
        }
    }

    /**
     * 结转凭证
     *
     * @param accountSetsId
     * @param tplIds
     * @param year
     * @param month
     * @return
     */
    public Map<Long, Voucher> loadByCheckTplId(Long accountSetsId, List<Long> tplIds, Integer year, Integer month) {
        return bqf.selectFrom(qVoucher)
                .where(qVoucher.accountSetsId.eq(accountSetsId)
                        .and(qVoucher.checkTplId.in(tplIds))
                        .and(qVoucher.year.eq(year))
                        .and(qVoucher.month.eq(month)))
                .fetch().stream().collect(Collectors.toMap(tuple -> tuple.getCheckTplId(), tuple -> tuple));
    }

    /**
     * 加载结转损益凭证
     *
     * @param accountSetsId
     * @param year
     * @param month
     */
    public List<Voucher> loadProfitCheck(Long accountSetsId, Integer year, Integer month) {
        return bqf.selectFrom(qVoucher)
                .select(qVoucher.id, qVoucher.creditAmount)
                .where(qVoucher.accountSetsId.eq(accountSetsId)
                        .and(qVoucher.checkTplId.isNull())
                        .and(qVoucher.carryForward.isTrue().or(
                                bqf.selectFrom(qVoucherDetails)
                                        .select(qVoucherDetails.count())
                                        .where(qVoucher.accountSetsId.eq(qVoucherDetails.accountSetsId)
                                                .and(qVoucherDetails.voucherId.eq(qVoucher.id))
                                                .and(qVoucherDetails.summary.contains("结转损益")))
                                        .gt(0L))
                        ).and(qVoucher.year.eq(year))
                        .and(qVoucher.month.eq(month)))
                .fetch().stream().map(tuple -> {
                    Voucher voucher = new Voucher();
                    voucher.setId(tuple.get(qVoucher.id));
                    voucher.setCreditAmount(tuple.get(qVoucher.creditAmount));
                    return voucher;
                }).toList();
    }

    /**
     * 最后一次凭证日期
     *
     * @param accountSetsId
     * @return
     */
    public Optional<LocalDate> lastDate(Long accountSetsId) {
        return Optional.ofNullable(bqf.selectFrom(qVoucher)
                .select(qVoucher.voucherDate)
                .where(qVoucher.accountSetsId.eq(accountSetsId))
                .orderBy(qVoucher.voucherDate.desc())
                .fetchFirst());
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

            //辅助项
            List<AccountingCategory> categories = bqf.selectFrom(qAccountingCategory)
                    .where(qAccountingCategory.accountSetsId.eq(userVo.getAccountSets().getId()))
                    .orderBy(qAccountingCategory.id.asc())
                    .fetch();
            dict.set("categories", categories);

            for (Map.Entry<Integer, List<Checkout>> entry : listMap.entrySet()) {
                VoucherQuery query = new VoucherQuery();
                query.setAccountSetsId(userVo.getAccountSets().getId());

                //一年的
                query.setVoucherDate(new LocalDateRange(entry.getValue().get(entry.getValue().size() - 1).getCheckDate(), entry.getValue().get(0).getCheckDate()));
                List<VoucherDto> voucherList = this.list(query);
                List<Map<String, Object>> voucherRows = new ArrayList<>();
                voucherList.forEach(voucher -> {
                    for (VoucherDetailsDto d : voucher.getDetails()) {
                        Map<String, Object> mapData;
                        VoucherImportVo vo = new VoucherImportVo();
                        vo.setDate(voucher.getVoucherDate().format(DateTimeFormatter.ofPattern("yyyy-MM-dd")));
                        vo.setVoucherNumber(voucher.getWord() + "-" + voucher.getCode());
                        vo.setDigest(d.getSummary());
                        //待处理
                        vo.setSubjectCode(d.getSubjectCode());
                        vo.setSubjectTitle(d.getSubjectName());
                        vo.setForeignCurrencyCode(d.getCurrencyName());
                        vo.setDebitForeignCurrency(null);
                        vo.setDebitCurrency(d.getDebitAmount());
                        if (d.getDebitAmount() != null) {
                            if (NumberUtil.isGreater(d.getNum(), BigDecimal.ZERO)) {
                                vo.setDebitNumber(d.getNum());
                            }
                            if (NumberUtil.equals(d.getDebitAmount(), BigDecimal.ZERO)) {
                                vo.setDebitCurrency(null);
                            }
                        }

                        vo.setCreditForeignCurrency(null);
                        vo.setCreditCurrency(d.getCreditAmount());
                        if (d.getCreditAmount() != null) {
                            if (NumberUtil.isGreater(d.getNum(), BigDecimal.ZERO)) {
                                vo.setCreditNumber(d.getNum());
                            }
                            if (NumberUtil.equals(d.getCreditAmount(), BigDecimal.ZERO)) {
                                vo.setCreditCurrency(null);
                            }
                        }

                        vo.setMakingPeople(voucher.getCreateMemberName());
                        vo.setAuditor(voucher.getAuditMemberName());
                        vo.setAttachmentNum(voucher.getReceiptNum());
                        vo.setRemark(voucher.getRemark());

                        mapData = BeanUtil.beanToMap(vo);
                        for (AccountingCategory c : categories) {
                            mapData.put("code" + c.getId(), "");
                            mapData.put("name" + c.getId(), "");
                        }

                        if (CollUtil.isNotEmpty(d.getAuxiliary())) {
                            for (AcDetails acd : d.getAuxiliary()) {
                                mapData.put("code" + acd.getCategoryId(), acd.getCode());
                                mapData.put("name" + acd.getCategoryId(), acd.getName());
                            }
                        }
                        voucherRows.add(mapData);
                    }
                });

                dict.set("dataList", voucherRows);
                File archiveDir = appConfig.getUploadRoot("ast-" + userVo.getAccountSets().getId() + "/archive/" + entry.getKey() + "年");
                FileUtil.mkdir(archiveDir);
                String fileName = "04凭证列表(" + entry.getKey() + "年)_" + userVo.getAccountSets().getCompanyName() + ".xlsx";
                File docFile = new File(archiveDir, fileName);
                OutputStream os = new FileOutputStream(docFile);
                JxlsUtils.exportExcel("template/voucher-list.xlsx", os, dict);
            }

            return CompletableFuture.completedFuture(true);
        } catch (Exception e) {
            log.info("\n{}归档异常", userVo.getAccountSets().getId(), e);
            return CompletableFuture.completedFuture(false);
        }
    }

    /**
     * 查询凭证
     *
     * @param query
     * @return
     */
    public List<VoucherDto> batchLoad(Long accountSetsId, VoucherQuery query) {
        List<Tuple> tuples = getVoucherQuery(query)
                .orderBy(qVoucher.code.asc(), qVoucher.id.asc())
                .fetch();
        return queryTupleToVoucherDto(accountSetsId, tuples);
    }


    /**
     * 标记现金流量凭证
     *
     * @param accountSetsId 账套ID
     */
    @Transactional
    public void cashFlowTag(Long accountSetsId) {
        // 查询现金科目ID
        List<Long> cashSubjectIds = bqf.selectFrom(qSubject)
                .select(qSubject.id)
                .where(qSubject.accountSetsId.eq(accountSetsId)
                        .and(qSubject.cash.isTrue()))
                .fetch();
        BooleanExpression expression = qVoucherDetails.accountSetsId.eq(accountSetsId);

        // 重置所有凭证明细为非现金明细
        jqf.update(qVoucherDetails).set(qVoucherDetails.cashFlow, false)
                .where(expression)
                .execute();

        if (CollUtil.isNotEmpty(cashSubjectIds)) {
            // 查询出所有有现金科目的凭证
            List<Long> voucherIds = bqf.selectFrom(qVoucherDetails)
                    .select(qVoucherDetails.voucherId).distinct()
                    .where(expression.and(qVoucherDetails.subjectId.in(cashSubjectIds)))
                    .fetch();
            if (CollUtil.isNotEmpty(voucherIds)) {
                // 更新凭证明细的现金标记
                jqf.update(qVoucherDetails)
                        .set(qVoucherDetails.cashFlow, true)
                        .where(expression.and(qVoucherDetails.voucherId.in(voucherIds)))
                        .execute();
            }
        }
    }

    /**
     * 找出断号
     * @param numbers
     * @return
     */
    private List<Integer> findGaps(List<Integer> numbers) {
        if (numbers == null || numbers.isEmpty()) {
            return Collections.emptyList();
        }

        // 1. 排序
        Collections.sort(numbers);

        int max = CollUtil.max(numbers);

        // 3. 检查断号
        List<Integer> gaps = new ArrayList<>();
        for (int i = 1; i <= max; i++) {
            if (!numbers.contains(i)) {
                gaps.add(i);
            }
        }

        return gaps;
    }

    public static class VoucherQuery {
        public BooleanBuilder builder = new BooleanBuilder();
        public BooleanBuilder builder2 = new BooleanBuilder();

        public Long accountSetsId;
        public Integer codeStart;
        public Integer codeEnd;
        public Long createMember;
        public BigDecimal minAmount;
        public BigDecimal maxAmount;

        public Long subjectId;

        public String summary;

        public String word;

        public String remark;

        public BooleanBuilder getBuilder(BlazeJPAQueryFactory bqf) {
            if (codeStart != null && codeEnd != null) {
                if (codeStart < codeEnd) {
                    builder.and(qVoucher.code.between(codeStart, codeEnd));
                } else {
                    builder.and(qVoucher.code.between(codeEnd, codeStart));
                }
            } else if (codeStart != null) {
                builder.and(qVoucher.code.goe(codeStart));
            } else if (codeEnd != null) {
                builder.and(qVoucher.code.loe(codeEnd));
            }

            NumberExpression<BigDecimal> coalesce = qVoucherDetails.creditAmount.when(BigDecimal.ZERO).then(qVoucherDetails.debitAmount).otherwise(qVoucherDetails.creditAmount);
            if (maxAmount != null && minAmount != null) {
                if (minAmount.compareTo(maxAmount) < 0) {
                    builder2.and(coalesce.between(minAmount, maxAmount));
                } else {
                    builder2.and(coalesce.between(maxAmount, minAmount));
                }
            } else if (maxAmount != null) {
                builder2.and(coalesce.loe(maxAmount));
            } else if (minAmount != null) {
                builder2.and(coalesce.goe(minAmount));
            }

            if (subjectId != null || summary != null || maxAmount != null || minAmount != null) {
                builder.and(qVoucher.id.in(bqf.selectFrom(qVoucherDetails).select(qVoucherDetails.voucherId).where(builder2)));
            }

            return builder;
        }

        public void setSubjectId(Long subjectId) {
            this.subjectId = subjectId;
            if (subjectId != null) {
                builder2.and(qVoucherDetails.subjectId.eq(subjectId));
            }
        }

        public void setSummary(String summary) {
            this.summary = summary;
            if (StrUtil.isNotEmpty(summary)) {
                builder2.and(qVoucherDetails.summary.contains(summary));
            }
        }

        public void setCreateMember(Long createMember) {
            if (createMember != null) {
                builder.and(qVoucher.createMember.eq(createMember));
            }
        }

        public void setVoucherDate(LocalDateRange dateRange) {
            if (dateRange != null && !dateRange.isEmpty()) {
                if (dateRange.getStart() != null && dateRange.getStart().equals(dateRange.getEnd())) {
                    builder.and(qVoucher.year.eq(dateRange.getStart().getYear())
                            .and(qVoucher.month.eq(dateRange.getStart().getMonthValue())));
                    builder2.and(qVoucherDetails.year.eq(dateRange.getStart().getYear())
                            .and(qVoucherDetails.month.eq(dateRange.getStart().getMonthValue())));
                } else if (dateRange.getStart() != null && dateRange.getEnd() == null) {
                    builder.and(qVoucher.voucherDate.goe(dateRange.getStart()));
                    builder2.and(qVoucherDetails.voucherDate.goe(dateRange.getStart()));
                } else if (dateRange.getEnd() != null && dateRange.getStart() == null) {
                    builder.and(qVoucher.voucherDate.loe(dateRange.getEnd()));
                    builder2.and(qVoucherDetails.voucherDate.loe(dateRange.getEnd()));
                } else {
                    builder.and(qVoucher.voucherDate.between(dateRange.getStart(), dateRange.getEnd().withDayOfMonth(dateRange.getEnd().lengthOfMonth())));
                    builder2.and(qVoucherDetails.voucherDate.between(dateRange.getStart(), dateRange.getEnd().withDayOfMonth(dateRange.getEnd().lengthOfMonth())));
                }
            }
        }

        public void setWord(String word) {
            if (StrUtil.isNotEmpty(word)) {
                builder.and(qVoucher.word.eq(word));
            }
        }

        public void setRemark(String remark) {
            if (StrUtil.isNotEmpty(remark)) {
                builder.and(qVoucher.remark.contains(remark));
            }
        }

        public void setId(List<Long> idList) {
            if (CollUtil.isNotEmpty(idList)) {
                builder.and(qVoucher.id.in(idList));
            }
        }

        public void setYear(Integer year) {
            if (year != null) {
                builder.and(qVoucher.year.eq(year));
            }
        }

        public void setMonth(Integer month) {
            if (month != null) {
                builder.and(qVoucher.month.eq(month));
            }
        }

        public void setDateRange(LocalDate start, LocalDate end) {
            builder.and(qVoucher.voucherDate.between(start, end));
        }

        public void setAudit(Long audit) {
            if (audit != null) {
                if (audit == 1) {
                    builder.and(qVoucher.auditMemberId.isNotNull());
                } else if (audit == 2) {
                    builder.and(qVoucher.auditMemberId.isNull());
                }
            }
        }

        public void setAccountSetsId(Long accountSetsId) {
            builder.and(qVoucher.accountSetsId.eq(accountSetsId));
            builder2.and(qVoucherDetails.accountSetsId.eq(accountSetsId));
            this.accountSetsId = accountSetsId;
        }

        public void setVoucherId(Long voucherId) {
            if (voucherId != null) {
                builder.and(qVoucher.id.eq(voucherId));
            }
        }
    }
    @Transactional(rollbackFor = Exception.class)
    public Boolean test(){
        List<Voucher> fetchList = bqf.selectFrom(qVoucher)
                .where(qVoucher.isSysAuto.eq(true))
                .fetch();
        Map<Long, List<Voucher>> collect = fetchList.stream().collect(Collectors.groupingBy(Voucher::getAccountSetsId));
        collect.forEach((k, v) ->{
            AccountSets accountSets = bqf.selectFrom(qAccountSets)
                    .where(qAccountSets.id.eq(k))
                    .fetchFirst();
            List<Long> voucherIds = v.stream().map(Voucher::getId).toList();

            List<LocalDate> delVoucherDates = this.batchDelete(accountSets, voucherIds);
            //StpUtil.getSession().set(S_USER_KEY, this.userService.getUserVo(userVo.getId()));
            //更新科目余额
            Optional<LocalDate> min = delVoucherDates.stream().min(Comparator.comparing(d -> d));
            min.ifPresent(d -> balanceCalcService.calc(accountSets.getId(), YearMonth.from(d)));
            //解除附件关联
            attachmentService.relieveSource(accountSets.getId(), AttachmentType.voucher, voucherIds);
        });
        return Boolean.TRUE;
    }
}





