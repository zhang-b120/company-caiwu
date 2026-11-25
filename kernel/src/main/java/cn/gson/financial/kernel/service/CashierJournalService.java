package cn.gson.financial.kernel.service;

import cn.gson.financial.kernel.common.LocalDateRange;
import cn.gson.financial.kernel.common.NumberRange;
import cn.gson.financial.kernel.config.AccountSetsProcessor;
import cn.gson.financial.kernel.config.AccountingCategoryDetailsCheck;
import cn.gson.financial.kernel.config.BackupProcessor;
import cn.gson.financial.kernel.config.VoucherProcessor;
import cn.gson.financial.kernel.controller.Page;
import cn.gson.financial.kernel.controller.PageResults;
import cn.gson.financial.kernel.model.dto.CashierJournalDto;
import cn.gson.financial.kernel.model.dto.VoucherDto;
import cn.gson.financial.kernel.model.entity.*;
import cn.gson.financial.kernel.model.hibernate.PrimaryKey;
import cn.gson.financial.kernel.model.repo.CashierAccountRepository;
import cn.gson.financial.kernel.model.repo.CashierIoTypeRepository;
import cn.gson.financial.kernel.model.repo.CashierJournalRepository;
import cn.gson.financial.kernel.model.vo.SerialNumber;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.date.DatePattern;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.NumberUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson.JSON;
import com.blazebit.persistence.PagedList;
import com.blazebit.persistence.querydsl.BlazeJPAQuery;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.Tuple;
import com.querydsl.core.types.dsl.BooleanExpression;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;

/**
 * 
 * <p><b>Copyright © 2010-2021开发公司All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : (CashierJournal)</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2022-10-27 08:23:00</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CashierJournalService extends AbsService implements AccountSetsProcessor, VoucherProcessor, BackupProcessor, AccountingCategoryDetailsCheck {

    private static final QCashierJournal qCashierJournal = QCashierJournal.cashierJournal;
    private static final QCashierTransfer qCashierTransfer = QCashierTransfer.cashierTransfer;

    private static final QAccountingCategory qAccountingCategory = QAccountingCategory.accountingCategory;

    private static final QProject qProjectDetails = QProject.project;

    private static final QDepartment qDeptDetails = QDepartment.department;

    private static final QCashierIoType qCashierIoType = QCashierIoType.cashierIoType;

    private static final QVoucher qVoucher = QVoucher.voucher;

    private static final QSettlementType qSettlementType = QSettlementType.settlementType;

    private static final QCashierAccount qCashierAccount = QCashierAccount.cashierAccount;
    private static final QCashierAccount qCashierAccountOut = new QCashierAccount("out");

    private final CashierJournalRepository cashierJournalRepository;

    private final CashierAccountRepository accountRepository;

    private final CashierIoTypeRepository ioTypeRepository;
    @Lazy
    @Resource
    private VoucherService voucherService;

    @Transactional
    public CashierJournal save(CashierJournal cashierJournal, LocalDate enableDate) {
        if (cashierJournal.getPrimaryKey() != null) {
            CashierJournal original = cashierJournalRepository.getReferenceById(cashierJournal.getPrimaryKey());
            if (cashierJournal.getAccountingCategoryId() == null && original.getAccountingCategoryId() != null) {
                jqf.update(qCashierJournal)
                        .setNull(qCashierJournal.accountingCategoryId)
                        .setNull(qCashierJournal.accountingCategoryDetailsId)
                        .where(qCashierJournal.id.eq(original.getId()))
                        .execute();
            }
            if (cashierJournal.getProjectCategoryId() == null && original.getProjectCategoryId() != null) {
                jqf.update(qCashierJournal)
                        .setNull(qCashierJournal.projectCategoryId)
                        .setNull(qCashierJournal.projectCategoryDetailsId)
                        .where(qCashierJournal.id.eq(original.getId()))
                        .execute();
            }
            if (cashierJournal.getDeptCategoryId() == null && original.getDeptCategoryId() != null) {
                jqf.update(qCashierJournal)
                        .setNull(qCashierJournal.deptCategoryId)
                        .setNull(qCashierJournal.deptCategoryDetailsId)
                        .where(qCashierJournal.id.eq(original.getId()))
                        .execute();
            }

            if (!original.getBillDate().isEqual(cashierJournal.getBillDate())) {
                genCodeAndNumber(cashierJournal);
            }

            BeanUtil.copyProperties(cashierJournal, original, CopyOptions.create().ignoreNullValue());
            cashierJournal = cashierJournalRepository.save(original);
        } else {
            CashierAccount account = genCodeAndNumber(cashierJournal);

            if (cashierJournal.getCashierTransferId() != null) {
                if (cashierJournal.getCredit() != null) {
                    cashierJournal.setBalance(NumberUtil.add(account.getBalance(), cashierJournal.getCredit()));
                } else {
                    cashierJournal.setBalance(NumberUtil.sub(account.getBalance(), cashierJournal.getDebit()));
                }
            } else if (cashierJournal.getIoTypeId() != null) {
                CashierIoType ioType = ioTypeRepository.getReferenceById(new PrimaryKey(cashierJournal.getIoTypeId(), cashierJournal.getAccountSetsId()));
                switch (ioType.getType()) {
                    case 收:
                        cashierJournal.setBalance(NumberUtil.add(account.getBalance(), cashierJournal.getDebit()));
                        break;
                    case 支:
                        cashierJournal.setBalance(NumberUtil.sub(account.getBalance(), cashierJournal.getCredit()));
                        break;
                }
            } else {
                // ioTypeId 为空（AI未识别类别）：按借贷字段方向推断余额
                if (cashierJournal.getDebit() != null) {
                    cashierJournal.setBalance(NumberUtil.add(account.getBalance(), cashierJournal.getDebit()));
                } else if (cashierJournal.getCredit() != null) {
                    cashierJournal.setBalance(NumberUtil.sub(account.getBalance(), cashierJournal.getCredit()));
                }
            }
            account.setBalance(cashierJournal.getBalance());
            cashierJournal = cashierJournalRepository.save(cashierJournal);
            accountRepository.save(account);
        }
        this.updateBalance(cashierJournal.getAccountSetsId(), cashierJournal.getAccountId(), cashierJournal.getBillDate(), enableDate);
        return cashierJournal;
    }

    /**
     * 生成编号和序号
     *
     * @param cashierJournal
     * @return
     */
    private CashierAccount genCodeAndNumber(CashierJournal cashierJournal) {
        CashierAccount account = accountRepository.getReferenceById(new PrimaryKey(cashierJournal.getAccountId(), cashierJournal.getAccountSetsId()));
        cashierJournal.setCode(DateUtil.format(new Date(), DatePattern.PURE_DATETIME_MS_PATTERN));
        SerialNumber serialNumber = getLastSerialNumber(cashierJournal.getAccountSetsId(), account, cashierJournal.getAccountId(), cashierJournal.getBillDate());
        cashierJournal.setSerialNumber(serialNumber.getNumber());
        cashierJournal.setSerial(serialNumber.getSerial());
        return account;
    }

    /**
     * 获取最新的序列号
     *
     * @param account
     * @param accountId
     * @param billDate
     * @return
     */
    private SerialNumber getLastSerialNumber(Long accountSetsId, CashierAccount account, Long accountId, LocalDate billDate) {
        //获取最大序号
        Integer maxSerial = Optional.ofNullable(bqf.selectFrom(qCashierJournal)
                .select(qCashierJournal.serial.max())
                .where(qCashierJournal.type.eq(account.getType())
                        .and(qCashierJournal.accountSetsId.eq(accountSetsId))
                        .and(qCashierJournal.accountId.eq(accountId))
                        .and(qCashierJournal.billDate.eq(billDate))
                ).fetchFirst()).orElse(0) + 1;

        String number = account.getType().ordinal() + 1 + "-" + account.getCode() + "-" + billDate.format(DatePattern.PURE_DATE_FORMAT.getDateTimeFormatter()) + "-" + maxSerial;
        return new SerialNumber(number, maxSerial);
    }

    public PageResults<CashierJournalDto> list(Long accountSetsId, Long accountId, Page page, CashierJournalQuery query) {
        PagedList<Tuple> pagedList = getTupleBlazeJPAQuery(accountSetsId)
                .where(qCashierJournal.accountId.eq(accountId)
                        .and(qCashierJournal.init.isFalse())
                        .and(query.builder).and(qCashierJournal.accountSetsId.eq(accountSetsId)))
                .fetchPage(page.getOffset(), page.getOffsetEnd());
        return new PageResults<>(pagedList.stream()
                .collect(ArrayList::new, getListTupleBiConsumer(), List::addAll), page, pagedList.getTotalSize());
    }

    /**
     * 多帐户查询
     *
     * @param accountSetsId
     * @param page
     * @param query
     * @return
     */
    public PageResults<CashierJournalDto> multipleList(Long accountSetsId, Page page, CashierJournalMultipleQuery query) {
        PagedList<Tuple> pagedList = getTupleBlazeJPAQuery(accountSetsId)
                .where(query.builder.and(qCashierJournal.init.isFalse())
                        .and(qCashierJournal.accountSetsId.eq(accountSetsId)))
                .fetchPage(page.getOffset(), page.getOffsetEnd());
        return new PageResults<>(pagedList.stream()
                .collect(ArrayList::new, getListTupleBiConsumer(), List::addAll), page, pagedList.getTotalSize());
    }

    @Transactional
    public void delete(Long cashierJournalId, Long accountSetsId, LocalDate enableDate) {
        CashierJournal journal = bqf.selectFrom(qCashierJournal).where(qCashierJournal.accountSetsId.eq(accountSetsId).and(qCashierJournal.id.eq(cashierJournalId)))
                .fetchFirst();

        if (journal != null && journal.getVoucherId() == null) {
            //更新后面的序列号，向前移动
            List<CashierJournal> journals = bqf.selectFrom(qCashierJournal)
                    .where(qCashierJournal.billDate.eq(journal.getBillDate())
                            .and(qCashierJournal.accountId.eq(journal.getAccountId()))
                            .and(qCashierJournal.serial.gt(journal.getSerial()))
                    ).orderBy(qCashierJournal.serial.asc()).fetch();

            jqf.delete(qCashierJournal).where(qCashierJournal.id.eq(cashierJournalId)).execute();

            if (CollUtil.isNotEmpty(journals)) {
                for (CashierJournal cashierJournal : journals) {
                    cashierJournal.setSerial(cashierJournal.getSerial() - 1);
                    String serialNumber = cashierJournal.getSerialNumber();
                    String substring = serialNumber.substring(0, serialNumber.lastIndexOf("-"));
                    cashierJournal.setSerialNumber(substring + "-" + cashierJournal.getSerial());
                }
                cashierJournalRepository.saveAll(journals);
            }
            this.updateBalance(accountSetsId, journal.getAccountId(), journal.getBillDate(), enableDate);
        } else {
            log.error("日记账删除错误~,accountSetsId:{},cashierJournalId:{}", accountSetsId, cashierJournalId);
        }
    }

    /**
     * 更新所有记录的余额
     *
     * @param accountSetsId
     * @param cashierAccountId
     * @param billDate
     * @return
     */
    @Transactional
    public void updateBalance(Long accountSetsId, Long cashierAccountId, LocalDate billDate, LocalDate enableDate) {
        CashierJournalDto dto = this.beginRow(accountSetsId, cashierAccountId, billDate, enableDate);
        BooleanExpression expression = qCashierJournal.accountSetsId.eq(accountSetsId)
                .and(qCashierJournal.accountId.eq(cashierAccountId));
        if (dto != null && StrUtil.isNotEmpty(dto.getSerialNumber())) {
            expression = expression.and(qCashierJournal.serialNumber.goe(dto.getSerialNumber()));
        }
        List<CashierJournal> journalList = bqf.selectFrom(qCashierJournal)
                .where(expression)
                .orderBy(qCashierJournal.init.desc(), qCashierJournal.billDate.asc(), qCashierJournal.serial.asc())
                .fetch();

        for (int i = 1; i < journalList.size(); i++) {
            CashierJournal pre = journalList.get(i - 1);
            CashierJournal next = journalList.get(i);
            if (next.getDebit() != null) {
                next.setBalance(NumberUtil.add(pre.getBalance(), next.getDebit()));
            } else {
                next.setBalance(NumberUtil.sub(pre.getBalance(), next.getCredit()));
            }
        }

        cashierJournalRepository.saveAll(journalList);
    }

    @Transactional
    public CashierJournal init(Long accountSetsId, Long accountId, LocalDate currentAccountDate) {
        CashierJournal initRow = bqf.selectFrom(qCashierJournal)
                .where(qCashierJournal.accountId.eq(accountId)
                        .and(qCashierJournal.init.isTrue())
                        .and(qCashierJournal.billDate.eq(currentAccountDate))
                        .and(qCashierJournal.accountSetsId.eq(accountSetsId)))
                .fetchFirst();
        if (initRow == null) {
            Tuple tuple = bqf.selectFrom(qCashierJournal)
                    .select(qCashierJournal.debit.sum().coalesce(BigDecimal.ZERO), qCashierJournal.credit.sum().coalesce(BigDecimal.ZERO))
                    .where(qCashierJournal.accountSetsId.eq(accountSetsId).and(qCashierJournal.accountId.eq(accountId))
                            .and(qCashierJournal.billDate.lt(DateUtil.date(currentAccountDate).toSqlDate().toLocalDate())))
                    .groupBy(qCashierJournal.accountId)
                    .fetchFirst();
            BigDecimal balance = BigDecimal.ZERO;
            if (tuple != null) {
                balance = NumberUtil.sub(tuple.get(qCashierJournal.debit.sum()), tuple.get(qCashierJournal.credit.sum()));
            }
            initRow = new CashierJournal();
            initRow.setId(snowflakeId.generate());
            initRow.setDigest("初始化余额");
            initRow.setBillDate(currentAccountDate);
            initRow.setAccountSetsId(accountSetsId);
            initRow.setAccountId(accountId);
            initRow.setBalance(balance);
            initRow.setInit(true);
            cashierJournalRepository.saveAndFlush(initRow);
        }
        return initRow;
    }

    public List<CashierJournalDto> getByIdList(Long accountSetsId, Long[] journal) {
        return getTupleBlazeJPAQuery(accountSetsId)
                .where(qCashierJournal.accountSetsId.eq(accountSetsId).and(qCashierJournal.id.in(journal)))
                .fetch().stream().collect(ArrayList::new, getListTupleBiConsumer(), List::addAll);
    }

    private BlazeJPAQuery<Tuple> getTupleBlazeJPAQuery(Long accountSetsId) {
        return bqf.selectFrom(qCashierJournal)
                .select(qCashierJournal, qCashierIoType, qVoucher.code, qVoucher.word, qCashierAccount)
                .leftJoin(qCashierAccount).on(qCashierAccount.id.eq(qCashierJournal.accountId).and(qCashierAccount.accountSetsId.eq(qCashierJournal.accountSetsId)))
                .leftJoin(qVoucher).on(qVoucher.id.eq(qCashierJournal.voucherId).and(qVoucher.accountSetsId.eq(qCashierJournal.accountSetsId)))
                .leftJoin(qCashierIoType).on(qCashierIoType.id.eq(qCashierJournal.ioTypeId).and(qCashierIoType.accountSetsId.eq(qCashierJournal.accountSetsId)))
                .orderBy(qCashierJournal.billDate.desc(), qCashierJournal.serial.desc(), qCashierJournal.id.desc());
    }

    private BiConsumer<ArrayList<CashierJournalDto>, Tuple> getListTupleBiConsumer() {
        return (list, tuple) -> {
            CashierIoType ioType = tuple.get(qCashierIoType);
            CashierAccount account = tuple.get(qCashierAccount);
            CashierJournalDto cashierJournalDto = BeanUtil.toBean(tuple.get(qCashierJournal), CashierJournalDto.class);
            if (ioType != null) {
                cashierJournalDto.setIoTypeValue(ioType.getType() + "-" + ioType.getName());
                cashierJournalDto.setIoType(ioType.getType());
            }

            if (account != null) {
                cashierJournalDto.setAccountCode(account.getCode());
                cashierJournalDto.setAccountName(account.getName());
                cashierJournalDto.setAccountType(account.getType().name());
            }
            if (cashierJournalDto.getVoucherId() != null) {
                cashierJournalDto.setVoucherCode(tuple.get(qVoucher.word) + "-" + tuple.get(qVoucher.code));
            }

            //查询转账信息
            if (cashierJournalDto.getCashierTransferId() != null) {
                Tuple transferData = bqf.selectFrom(qCashierTransfer)
                        .select(qCashierTransfer.inAccountId,
                                qCashierAccountOut.name,
                                qCashierAccountOut.subjectId,
                                qCashierAccount.name,
                                qCashierAccount.subjectId,
                                qCashierTransfer.outAccountId)
                        .innerJoin(qCashierAccountOut).on(qCashierAccountOut.accountSetsId.eq(cashierJournalDto.getAccountSetsId()).and(qCashierAccountOut.id.eq(qCashierTransfer.outAccountId)))
                        .innerJoin(qCashierAccountOut).on(qCashierAccountOut.accountSetsId.eq(cashierJournalDto.getAccountSetsId()).and(qCashierAccountOut.id.eq(qCashierTransfer.outAccountId)))
                        .innerJoin(qCashierAccount).on(qCashierAccount.accountSetsId.eq(cashierJournalDto.getAccountSetsId()).and(qCashierAccount.id.eq(qCashierTransfer.inAccountId)))
                        .where(qCashierTransfer.accountSetsId.eq(cashierJournalDto.getAccountSetsId())
                                .and(qCashierTransfer.id.eq(cashierJournalDto.getCashierTransferId())))
                        .fetchFirst();
                if (transferData != null) {
                    cashierJournalDto.setInAccountId(transferData.get(qCashierTransfer.inAccountId));
                    cashierJournalDto.setOutAccountId(transferData.get(qCashierTransfer.outAccountId));
                    cashierJournalDto.setInAccountName(transferData.get(qCashierAccount.name));
                    cashierJournalDto.setOutAccountName(transferData.get(qCashierAccountOut.name));
                    cashierJournalDto.setInSubjectId(transferData.get(qCashierAccount.subjectId));
                    cashierJournalDto.setOutSubjectId(transferData.get(qCashierAccountOut.subjectId));
                }
            }
            list.add(cashierJournalDto);
        };
    }

    /**
     * 批量更新收支类别
     *
     * @param accountSetsId
     * @param ids
     * @param ioTypeId
     * @return
     */
    @Transactional
    public List<CashierJournal> batchUpdateIoType(Long accountSetsId, Long[] ids, Long ioTypeId) {
        CashierIoType cashierIoType = bqf.selectFrom(qCashierIoType)
                .where(qCashierIoType.id.eq(ioTypeId).and(qCashierIoType.accountSetsId.eq(accountSetsId)))
                .fetchFirst();
        List<CashierJournal> needUpdateBalance = new ArrayList<>();
        for (Long id : ids) {
            CashierJournal cashierJournal = bqf.selectFrom(qCashierJournal)
                    .where(qCashierJournal.id.eq(id).and(qCashierJournal.accountSetsId.eq(accountSetsId)))
                    .fetchFirst();
            if (updateIoType(cashierIoType, cashierJournal, accountSetsId)) {
                needUpdateBalance.add(cashierJournal);
            }
        }

        return needUpdateBalance;
    }

    /**
     * @param cashierIoType  新类型
     * @param cashierJournal 原对记录
     */
    private boolean updateIoType(CashierIoType cashierIoType, CashierJournal cashierJournal, Long accountSetsId) {
        if (cashierJournal.getIoTypeId() == null || !cashierIoType.getId().equals(cashierJournal.getIoTypeId())) {
            CashierIoType oldCashierIoType = null;
            if (cashierJournal.getIoTypeId() != null) {
                oldCashierIoType = bqf.selectFrom(qCashierIoType)
                        .where(qCashierIoType.id.eq(cashierJournal.getIoTypeId()).and(qCashierIoType.accountSetsId.eq(accountSetsId)))
                        .fetchFirst();
            }

            if (oldCashierIoType != null && oldCashierIoType.getType().equals(cashierIoType.getType())) {
                //收支方向相等是，只需更新类型ID
                jqf.update(qCashierJournal)
                        .set(qCashierJournal.ioTypeId, cashierIoType.getId())
                        .where(qCashierJournal.id.eq(cashierJournal.getId()).and(qCashierJournal.accountSetsId.eq(cashierJournal.getAccountSetsId())))
                        .execute();
            } else {
                switch (cashierIoType.getType()) {
                    case 收 -> {
                        BigDecimal debit = cashierJournal.getDebit();
                        if (debit == null) {
                            debit = cashierJournal.getCredit();
                        }
                        jqf.update(qCashierJournal)
                                .set(qCashierJournal.ioTypeId, cashierIoType.getId())
                                .setNull(qCashierJournal.credit)
                                .set(qCashierJournal.debit, debit)
                                .where(qCashierJournal.id.eq(cashierJournal.getId()).and(qCashierJournal.accountSetsId.eq(cashierJournal.getAccountSetsId())))
                                .execute();
                    }
                    case 支 -> {
                        BigDecimal credit = cashierJournal.getCredit();
                        if (credit == null) {
                            credit = cashierJournal.getDebit();
                        }
                        jqf.update(qCashierJournal)
                                .set(qCashierJournal.ioTypeId, cashierIoType.getId())
                                .setNull(qCashierJournal.debit)
                                .set(qCashierJournal.credit, credit)
                                .where(qCashierJournal.id.eq(cashierJournal.getId())
                                        .and(qCashierJournal.accountSetsId.eq(cashierJournal.getAccountSetsId())))
                                .execute();
                    }
                }

                return true;
            }
        }
        return false;
    }

    @Transactional
    public void batchUpdate(String type, CashierJournal journal) {
        CashierJournal reference = cashierJournalRepository.getReferenceById(journal.getPrimaryKey());
        switch (type) {
            case "setAccountingCategory" -> {
                reference.setAccountingCategoryId(journal.getAccountingCategoryId());
                reference.setAccountingCategoryDetailsId(journal.getAccountingCategoryDetailsId());
            }
            case "setProject" -> {
                reference.setProjectCategoryId(journal.getProjectCategoryId());
                reference.setProjectCategoryDetailsId(journal.getProjectCategoryDetailsId());
            }
            case "setDept" -> {
                reference.setDeptCategoryId(journal.getDeptCategoryId());
                reference.setDeptCategoryDetailsId(journal.getDeptCategoryDetailsId());
            }
            case "setSubject" -> {
                reference.setSubjectId(journal.getSubjectId());
            }
        }
        cashierJournalRepository.save(reference);
    }

    @Transactional
    @Override
    public void clear(Long accountSetsId) {
        jqf.delete(qCashierJournal)
                .where(qCashierJournal.accountSetsId.eq(accountSetsId))
                .execute();
    }

    @Override
    public void voucherDeleted(Long voucherId, Long accountSetId) {
        jqf.update(qCashierJournal)
                .setNull(qCashierJournal.voucherId)
                .where(qCashierJournal.voucherId.eq(voucherId))
                .execute();
    }

    @Override
    public String backup(Long accountSetsId) {
        return JSON.toJSONString(bqf.selectFrom(qCashierJournal)
                .where(qCashierJournal.accountSetsId.eq(accountSetsId)).fetch());
    }

    @Override
    public Class<?> backupType() {
        return CashierJournal.class;
    }

    @Override
    public void recover(Long accountSetsId, String json) {
        List<CashierJournal> entityList = JSON.parseArray(json, CashierJournal.class);
        for (CashierJournal entity : entityList) {
            entity.setAccountSetsId(accountSetsId);
            cashierJournalRepository.save(entity);
        }
    }

    @Transactional
    public void generateVoucher(VoucherDto voucherDto, AccountSets accountSets) {
        VoucherDto saved = voucherService.save(voucherDto, accountSets, false);

        if (CollUtil.isNotEmpty(voucherDto.getCashierTransferIds())) {
            jqf.update(qCashierJournal)
                    .set(qCashierJournal.voucherId, saved.getId())
                    .where(qCashierJournal.accountSetsId.eq(accountSets.getId())
                            .and(qCashierJournal.cashierTransferId.in(voucherDto.getCashierTransferIds())))
                    .execute();
            jqf.update(qCashierTransfer)
                    .set(qCashierTransfer.voucherId, saved.getId())
                    .where(qCashierTransfer.accountSetsId.eq(accountSets.getId())
                            .and(qCashierTransfer.id.in(voucherDto.getCashierTransferIds())))
                    .execute();
        }

        if (CollUtil.isNotEmpty(voucherDto.getJournalIds())) {
            jqf.update(qCashierJournal)
                    .set(qCashierJournal.voucherId, saved.getId())
                    .where(qCashierJournal.accountSetsId.eq(accountSets.getId())
                            .and(qCashierJournal.id.in(voucherDto.getJournalIds())))
                    .execute();
        }
    }

    @Override
    public boolean isAccountingCategoryDetailsUsed(AcDetails acdId) {
        return bqf.selectFrom(qCashierJournal)
                       .where(qCashierJournal.accountSetsId.eq(acdId.getAccountSetsId()).and(qCashierJournal.deptCategoryDetailsId.eq(acdId.getId())
                               .or(qCashierJournal.projectCategoryDetailsId.eq(acdId.getId()))
                               .or(qCashierJournal.accountingCategoryDetailsId.eq(acdId.getId()))))
                       .fetchCount() > 0;
    }

    /**
     * 获取账户日期期初余额
     *
     * @param accountSetsId
     * @param accountId
     * @param start
     * @param enableDate
     * @return
     */
    @Transactional
    public CashierJournalDto beginRow(Long accountSetsId, Long accountId, LocalDate start, LocalDate enableDate) {
        CashierJournal cashierJournal = bqf.selectFrom(qCashierJournal)
                .where(qCashierJournal.accountId.eq(accountId)
                        .and(qCashierJournal.accountSetsId.eq(accountSetsId))
                        .and(qCashierJournal.billDate.lt(start)))
                .orderBy(qCashierJournal.billDate.desc(), qCashierJournal.serial.desc(), qCashierJournal.serialNumber.desc())
                .fetchFirst();
        CashierJournalDto dto = new CashierJournalDto();
        dto.setDigest("初始余额");
        dto.setInit(true);
        dto.setEdit(false);
        if (cashierJournal == null) {
            cashierJournal = bqf.selectFrom(qCashierJournal)
                    .where(qCashierJournal.accountId.eq(accountId)
                            .and(qCashierJournal.accountSetsId.eq(accountSetsId))
                            .and(qCashierJournal.init.isTrue()))
                    .orderBy(qCashierJournal.billDate.desc())
                    .fetchFirst();
            dto.setEdit(true);

            //没有初始金额记录的时候，增加一行初始
            if (cashierJournal == null) {
                cashierJournal = this.init(accountSetsId, accountId, enableDate);
            }
        } else if (cashierJournal.getInit()) {
            dto.setEdit(true);
        }

        Assert.notNull(cashierJournal, "初始化账户失败~");

        dto.setId(cashierJournal.getId());
        dto.setBillDate(cashierJournal.getBillDate());
        dto.setBalance(cashierJournal.getBalance());
        dto.setEdit(bqf.selectFrom(qCashierJournal)
                            .where(qCashierJournal.accountId.eq(accountId)
                                    .and(qCashierJournal.init.isFalse())
                                    .and(qCashierJournal.accountSetsId.eq(accountSetsId)))
                            .fetchCount() == 0L);
        return dto;
    }

    public CashierJournal loadItem(PrimaryKey primaryKey) {
        Optional<CashierJournal> journal = cashierJournalRepository.findById(primaryKey);
        return journal.orElse(null);
    }

    /**
     * 账户合计金额
     *
     * @param accountSetsId
     * @param accountId
     * @param query
     */
    public CashierJournalDto total(Long accountSetsId, Long accountId, CashierJournalQuery query) {
        Tuple tuple = getTupleBlazeJPAQuery(accountSetsId)
                .select(qCashierJournal.debit.coalesce(BigDecimal.ZERO).sum().coalesce(BigDecimal.ZERO), qCashierJournal.credit.coalesce(BigDecimal.ZERO).sum().coalesce(BigDecimal.ZERO))
                .where(qCashierJournal.accountId.eq(accountId)
                        .and(qCashierJournal.init.isFalse())
                        .and(query.builder).and(qCashierJournal.accountSetsId.eq(accountSetsId)))
                .fetchFirst();
        CashierJournalDto cashierJournal = new CashierJournalDto();
        cashierJournal.setDebit(tuple.get(qCashierJournal.debit.coalesce(BigDecimal.ZERO).sum().coalesce(BigDecimal.ZERO)));
        cashierJournal.setCredit(tuple.get(qCashierJournal.credit.coalesce(BigDecimal.ZERO).sum().coalesce(BigDecimal.ZERO)));
        cashierJournal.setBalance(NumberUtil.sub(cashierJournal.getDebit(), cashierJournal.getCredit()));
        return cashierJournal;
    }

    /**
     * 工单 122 · L1-CASHIER · 多账户资金合计（免 accountId）。
     * <p>镜像 {@link #total} 的借贷 sum 逻辑，去掉 {@code accountId.eq} 限定、改用
     * {@link CashierJournalMultipleQuery} 的 builder（可按账户集合/日期/金额过滤），供 /cashier/all 取全部出纳账户合计。
     */
    public CashierJournalDto multipleTotal(Long accountSetsId, CashierJournalMultipleQuery query) {
        Tuple tuple = getTupleBlazeJPAQuery(accountSetsId)
                .select(qCashierJournal.debit.coalesce(BigDecimal.ZERO).sum().coalesce(BigDecimal.ZERO), qCashierJournal.credit.coalesce(BigDecimal.ZERO).sum().coalesce(BigDecimal.ZERO))
                .where(query.builder.and(qCashierJournal.init.isFalse())
                        .and(qCashierJournal.accountSetsId.eq(accountSetsId)))
                .fetchFirst();
        CashierJournalDto dto = new CashierJournalDto();
        dto.setDebit(tuple.get(qCashierJournal.debit.coalesce(BigDecimal.ZERO).sum().coalesce(BigDecimal.ZERO)));
        dto.setCredit(tuple.get(qCashierJournal.credit.coalesce(BigDecimal.ZERO).sum().coalesce(BigDecimal.ZERO)));
        dto.setBalance(NumberUtil.sub(dto.getDebit(), dto.getCredit()));
        return dto;
    }

    public boolean cashierCheck(Long accountSetsId, LocalDate start, LocalDate end) {
        return bqf.selectFrom(qCashierJournal)
                       .where(qCashierJournal.accountSetsId.eq(accountSetsId)
                               .and(qCashierJournal.init.isFalse())
                               .and(qCashierJournal.billDate.between(start, end))
                               .and(qCashierJournal.voucherId.isNull()))
                       .fetchCount() == 0;
    }

    public static class CashierJournalMultipleQuery {
        final BooleanBuilder builder = new BooleanBuilder();

        public void setAccount(List<Long> account) {
            if (CollUtil.isNotEmpty(account)) {
                builder.and(qCashierJournal.accountId.in(account));
            }
        }

        public void setDigest(String digest) {
            if (StrUtil.isNotEmpty(digest)) {
                builder.and(qCashierJournal.digest.contains(digest).or(qCashierJournal.remark.contains(digest)));
            }
        }

        public void setBillDate(LocalDateRange dateRange) {
            if (!dateRange.isEmpty()) {
                if (dateRange.isNotEmpty()) {
                    builder.and(qCashierJournal.billDate.between(dateRange.getStart(), dateRange.getEnd()));
                } else if (dateRange.getStart() != null) {
                    builder.and(qCashierJournal.billDate.goe(dateRange.getStart()));
                } else {
                    builder.and(qCashierJournal.billDate.loe(dateRange.getEnd()));
                }
            }
        }

        public void setAmount(NumberRange numberRange) {
            if (!numberRange.isEmpty()) {
                if (numberRange.isNotEmpty()) {
                    builder.and(qCashierJournal.debit.between(numberRange.getStart(), numberRange.getEnd())
                            .or(qCashierJournal.credit.between(numberRange.getStart(), numberRange.getEnd())));
                } else if (numberRange.getStart() != null) {
                    builder.and(qCashierJournal.debit.goe(numberRange.getStart())
                            .or(qCashierJournal.credit.goe(numberRange.getStart())));
                } else {
                    builder.and(qCashierJournal.debit.loe(numberRange.getEnd())
                            .or(qCashierJournal.credit.loe(numberRange.getEnd())));
                }
            }
        }
    }

    public static class CashierJournalQuery {
        final BooleanBuilder builder = new BooleanBuilder();

        public LocalDate start;
        public LocalDate end;


        public void setAcDetails(AcDetails acDetails) {
            if (acDetails != null) {
                builder.and(qCashierJournal.accountingCategoryDetailsId.eq(acDetails.getId())
                        .and(qCashierJournal.accountingCategoryId.eq(acDetails.getCategoryId())));
            }
        }

        public void setCode(String code) {
            if (StrUtil.isNotEmpty(code)) {
                builder.and(qCashierJournal.code.contains(code));
            }
        }

        public void setDeptCategoryDetailsId(Long deptCategoryDetailsId) {
            if (deptCategoryDetailsId != null) {
                builder.and(qCashierJournal.deptCategoryDetailsId.eq(deptCategoryDetailsId));
            }
        }

        public void setDigest(String digest) {
            if (StrUtil.isNotEmpty(digest)) {
                builder.and(qCashierJournal.digest.contains(digest));
            }
        }

        public void setEnd(LocalDate end) {
            this.end = end;
            if (end != null) {
                builder.and(qCashierJournal.billDate.loe(end));
            }
        }


        public void setIoTypeId(Long ioTypeId) {
            if (ioTypeId != null) {
                builder.and(qCashierJournal.ioTypeId.eq(ioTypeId));
            }
        }


        public void setMaxMoney(Double maxMoney) {
            if (maxMoney != null) {
                builder.and(qCashierJournal.debit.loe(maxMoney).or(qCashierJournal.credit.loe(maxMoney)));
            }
        }


        public void setMinMoney(Double minMoney) {
            if (minMoney != null) {
                builder.and(qCashierJournal.debit.goe(minMoney).or(qCashierJournal.credit.goe(minMoney)));
            }
        }


        public void setProjectCategoryDetailsId(Long projectCategoryDetailsId) {
            if (projectCategoryDetailsId != null) {
                builder.and(qCashierJournal.projectCategoryDetailsId.eq(projectCategoryDetailsId));
            }
        }


        public void setRemark(String remark) {
            if (StrUtil.isNotEmpty(remark)) {
                builder.and(qCashierJournal.remark.contains(remark));
            }
        }


        public void setSettlementTypeId(Long settlementTypeId) {
            if (settlementTypeId != null) {
                builder.and(qCashierJournal.settlementTypeId.eq(settlementTypeId));
            }
        }

        public void setStart(LocalDate start) {
            this.start = start;
            if (start != null) {
                builder.and(qCashierJournal.billDate.goe(start));
            }
        }

        public void setVouched(Integer vouched) {
            if (vouched != null) {
                switch (vouched) {
                    case 1 -> builder.and(qCashierJournal.voucherId.isNotNull());
                    case 2 -> builder.and(qCashierJournal.voucherId.isNull());
                }
            }
        }

    }
}

