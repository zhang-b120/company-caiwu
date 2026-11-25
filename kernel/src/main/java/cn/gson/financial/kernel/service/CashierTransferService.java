package cn.gson.financial.kernel.service;

import cn.gson.financial.kernel.config.AccountSetsProcessor;
import cn.gson.financial.kernel.config.BackupProcessor;
import cn.gson.financial.kernel.config.VoucherProcessor;
import cn.gson.financial.kernel.controller.Page;
import cn.gson.financial.kernel.controller.PageResults;
import cn.gson.financial.kernel.model.dto.CashierTransferDto;
import cn.gson.financial.kernel.model.entity.*;
import cn.gson.financial.kernel.model.hibernate.PrimaryKey;
import cn.gson.financial.kernel.model.repo.CashierAccountRepository;
import cn.gson.financial.kernel.model.repo.CashierTransferRepository;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import com.alibaba.fastjson.JSON;
import com.blazebit.persistence.PagedList;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.Tuple;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 
 * <p><b>Copyright © 2010-2021开发公司All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : (CashierTransfer)</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2022-11-09 19:57:45</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CashierTransferService extends AbsService implements AccountSetsProcessor, VoucherProcessor, BackupProcessor {

    private static final QCashierTransfer qCashierTransfer = QCashierTransfer.cashierTransfer;
    private final CashierTransferRepository cashierTransferRepository;
    private final QCashierAccount qInCashierAccount = new QCashierAccount("InCashier");
    private final QCashierAccount qOutCashierAccount = new QCashierAccount("OutCashier");
    private final QVoucher qVoucher = QVoucher.voucher;
    private final QCashierJournal qCashierJournal = QCashierJournal.cashierJournal;

    private final CashierJournalService cashierJournalService;

    private final CashierAccountRepository cashierAccountRepository;

    @Transactional
    public CashierTransfer save(CashierTransfer cashierTransfer, LocalDate enableDate) {
        if (cashierTransfer.getPrimaryKey() != null) {
            CashierTransfer original = cashierTransferRepository.getReferenceById(cashierTransfer.getPrimaryKey());
            BeanUtil.copyProperties(cashierTransfer, original, CopyOptions.create().ignoreNullValue());
            cashierTransfer = cashierTransferRepository.save(original);

            jqf.delete(qCashierJournal)
                    .where(qCashierJournal.cashierTransferId.eq(cashierTransfer.getId()))
                    .execute();
        } else {
            cashierTransfer = cashierTransferRepository.save(cashierTransfer);
        }

        CashierAccount inAccount = cashierAccountRepository.getReferenceById(new PrimaryKey(cashierTransfer.getInAccountId(), cashierTransfer.getAccountSetsId()));
        CashierAccount outAccount = cashierAccountRepository.getReferenceById(new PrimaryKey(cashierTransfer.getOutAccountId(), cashierTransfer.getAccountSetsId()));

        CashierJournal inCashierJournal = new CashierJournal();
        inCashierJournal.setDebit(cashierTransfer.getAmount());
        inCashierJournal.setType(inAccount.getType());
        inCashierJournal.setDigest(cashierTransfer.getDigest());
        inCashierJournal.setBillDate(cashierTransfer.getTransferDate());
        inCashierJournal.setAccountSetsId(cashierTransfer.getAccountSetsId());
        inCashierJournal.setAccountId(cashierTransfer.getInAccountId());
        inCashierJournal.setInit(false);
        inCashierJournal.setCashierTransferId(cashierTransfer.getId());

        CashierJournal outCashierJournal = new CashierJournal();
        outCashierJournal.setCredit(cashierTransfer.getAmount());
        outCashierJournal.setType(outAccount.getType());
        outCashierJournal.setDigest(cashierTransfer.getDigest());
        outCashierJournal.setBillDate(cashierTransfer.getTransferDate());
        outCashierJournal.setAccountSetsId(cashierTransfer.getAccountSetsId());
        outCashierJournal.setAccountId(cashierTransfer.getOutAccountId());
        outCashierJournal.setInit(false);
        outCashierJournal.setCashierTransferId(cashierTransfer.getId());

        cashierJournalService.save(inCashierJournal, enableDate);
        cashierJournalService.save(outCashierJournal, enableDate);

        return cashierTransfer;
    }

    public PageResults<CashierTransferDto> list(Long accountSetId, Page page, TransferQuery query) {
        PagedList<Tuple> pagedList = bqf.selectFrom(qCashierTransfer)
                .select(qCashierTransfer, qInCashierAccount.code, qInCashierAccount.name, qOutCashierAccount.code, qOutCashierAccount.name, qVoucher.word, qVoucher.code)
                .leftJoin(qInCashierAccount).on(qInCashierAccount.id.eq(qCashierTransfer.inAccountId).and(qInCashierAccount.accountSetsId.eq(accountSetId)))
                .leftJoin(qOutCashierAccount).on(qOutCashierAccount.id.eq(qCashierTransfer.outAccountId).and(qOutCashierAccount.accountSetsId.eq(accountSetId)))
                .leftJoin(qVoucher).on(qVoucher.id.eq(qCashierTransfer.voucherId).and(qVoucher.accountSetsId.eq(accountSetId)))
                .where(query.getBuilder().and(qCashierTransfer.accountSetsId.eq(accountSetId)))
                .orderBy(qCashierTransfer.transferDate.desc(), qCashierTransfer.id.desc())
                .fetchPage(page.getOffset(), page.getOffsetEnd());

        List<CashierTransferDto> cashierTransferDtos = pagedList.stream().collect(ArrayList::new, (list, tuple) -> {
            CashierTransferDto dto = BeanUtil.toBean(tuple.get(qCashierTransfer), CashierTransferDto.class);
            dto.setInAccount(tuple.get(qInCashierAccount.code) + "-" + tuple.get(qInCashierAccount.name));
            dto.setOutAccount(tuple.get(qOutCashierAccount.code) + "-" + tuple.get(qOutCashierAccount.name));
            if (tuple.get(qVoucher.word) != null) {
                dto.setVoucherTitle(tuple.get(qVoucher.word) + "-" + tuple.get(qVoucher.code));
            }
            list.add(dto);
        }, List::addAll);
        return new PageResults<>(cashierTransferDtos, page, pagedList.getTotalSize());
    }

    @Transactional
    public void delete(PrimaryKey primaryKey) {
        jqf.delete(qCashierJournal)
                .where(qCashierJournal.cashierTransferId.eq(primaryKey.getId())
                        .and(qCashierJournal.accountSetsId.eq(primaryKey.getAccountSetsId())))
                .execute();

        cashierTransferRepository.deleteById(primaryKey);
    }

    @Transactional
    @Override
    public void clear(Long accountSetsId) {
        jqf.delete(qCashierTransfer)
                .where(qCashierTransfer.accountSetsId.eq(accountSetsId))
                .execute();
    }

    @Override
    public void voucherDeleted(Long voucherId, Long accountSetId) {
        jqf.update(qCashierTransfer)
                .setNull(qCashierTransfer.voucherId)
                .where(qCashierTransfer.voucherId.eq(voucherId))
                .execute();
    }

    @Override
    public String backup(Long accountSetsId) {
        return JSON.toJSONString(bqf.selectFrom(qCashierTransfer)
                .where(qCashierTransfer.accountSetsId.eq(accountSetsId)).fetch());
    }

    @Override
    public Class<?> backupType() {
        return CashierTransfer.class;
    }

    @Override
    public void recover(Long accountSetsId, String json) {
        List<CashierTransfer> assetsList = JSON.parseArray(json, CashierTransfer.class);
        for (CashierTransfer assets : assetsList) {
            assets.setAccountSetsId(accountSetsId);
            cashierTransferRepository.save(assets);
        }
    }

    @Setter
    @Getter
    public static class TransferQuery {

        private Long outAccountId;
        private Long inAccountId;
        private LocalDate start;
        private LocalDate end;

        public BooleanBuilder getBuilder() {
            BooleanBuilder builder = new BooleanBuilder();
            if (outAccountId != null) {
                builder.and(qCashierTransfer.outAccountId.eq(outAccountId));
            }

            if (inAccountId != null) {
                builder.and(qCashierTransfer.inAccountId.eq(inAccountId));
            }

            if (start != null && end != null) {
                builder.and(qCashierTransfer.transferDate.between(start, end));
            } else if (start != null) {
                builder.and(qCashierTransfer.transferDate.goe(start));
            } else if (end != null) {
                builder.and(qCashierTransfer.transferDate.loe(end));
            }

            return builder;
        }
    }
}

