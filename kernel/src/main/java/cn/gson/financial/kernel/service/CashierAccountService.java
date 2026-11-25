package cn.gson.financial.kernel.service;

import cn.gson.financial.kernel.config.AccountSetsProcessor;
import cn.gson.financial.kernel.config.BackupProcessor;
import cn.gson.financial.kernel.model.dto.CashierAccountDto;
import cn.gson.financial.kernel.model.entity.*;
import cn.gson.financial.kernel.model.hibernate.PrimaryKey;
import cn.gson.financial.kernel.model.repo.CashierAccountRepository;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson.JSON;
import com.querydsl.core.types.dsl.BooleanExpression;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 
 * <p><b>Copyright © 2010-2022开发公司All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.kernel.service</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2022年10月25日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CashierAccountService extends AbsService implements AccountSetsProcessor, BackupProcessor {

    private final CashierAccountRepository cashierAccountRepository;

    private final QCashierAccount qCashierAccount = QCashierAccount.cashierAccount;

    private final QCashierJournal qCashierJournal = QCashierJournal.cashierJournal;

    private final QSubject qSubject = QSubject.subject;

    private final QCurrency qCurrency = QCurrency.currency;

    public List<CashierAccountDto> list(Long accountSetsId, String filter) {
        BooleanExpression expression = qCashierAccount.accountSetsId.eq(accountSetsId);
        if (StrUtil.isNotEmpty(filter)) {
            expression = expression.and(qCashierAccount.code.contains(filter).or(qCashierAccount.name.contains(filter)));
        }
        return bqf.selectFrom(qCashierAccount)
                .select(qCashierAccount, qSubject.name, qSubject.code, qCurrency.name)
                .leftJoin(qSubject).on(qSubject.id.eq(qCashierAccount.subjectId).and(qSubject.accountSetsId.eq(accountSetsId)))
                .leftJoin(qCurrency).on(qCurrency.id.eq(qCashierAccount.currencyId).and(qCurrency.accountSetsId.eq(accountSetsId)))
                .where(expression)
                .orderBy(qCashierAccount.code.asc())
                .fetch().stream().collect(ArrayList::new, (list, tuple) -> {
                    CashierAccountDto dto = BeanUtil.toBean(tuple.get(qCashierAccount), CashierAccountDto.class);
                    dto.setSubjectCode(tuple.get(qSubject.code));
                    dto.setSubjectName(tuple.get(qSubject.name));
                    dto.setCurrencyName(tuple.get(qCurrency.name));
                    list.add(dto);
                }, List::addAll);
    }

    @Transactional
    public CashierAccount save(CashierAccount cashierAccount) {
        BooleanExpression codeExp = qCashierAccount.code.eq(cashierAccount.getCode())
                .and(qCashierAccount.type.eq(cashierAccount.getType()))
                .and(qCashierAccount.accountSetsId.eq(cashierAccount.getAccountSetsId()));
        BooleanExpression cardNumberExp = null, nameExp = null;
        if (StrUtil.isNotEmpty(cashierAccount.getName())) {
            nameExp = qCashierAccount.name.eq(cashierAccount.getName())
                    .and(qCashierAccount.type.eq(cashierAccount.getType()))
                    .and(qCashierAccount.accountSetsId.eq(cashierAccount.getAccountSetsId()));
        }
        if (StrUtil.isNotEmpty(cashierAccount.getCardNumber())) {
            cardNumberExp = qCashierAccount.cardNumber.eq(cashierAccount.getCardNumber())
                    .and(qCashierAccount.type.eq(cashierAccount.getType()))
                    .and(qCashierAccount.accountSetsId.eq(cashierAccount.getAccountSetsId()));
        }


        if (cashierAccount.getId() != null) {
            codeExp = codeExp.and(qCashierAccount.id.ne(cashierAccount.getId()));
            if (nameExp != null) {
                nameExp = nameExp.and(qCashierAccount.id.ne(cashierAccount.getId()));
            }
            if (cardNumberExp != null) {
                cardNumberExp = cardNumberExp.and(qCashierAccount.id.ne(cashierAccount.getId()));
            }
        }

        Assert.isTrue(bqf.selectFrom(qCashierAccount).where(codeExp).fetchCount() == 0, "编码已存在~");
        if (nameExp != null) {
            Assert.isTrue(bqf.selectFrom(qCashierAccount).where(nameExp).fetchCount() == 0, "账户名称已存在~");
        }

        if (cardNumberExp != null && CashierAccount.CashierAccountType.银行存款.equals(cashierAccount.getType())) {
            Assert.isTrue(bqf.selectFrom(qCashierAccount).where(cardNumberExp).fetchCount() == 0, "银行卡号已存在~");
        }

        return cashierAccountRepository.save(cashierAccount);
    }

    @Transactional
    public void delete(Long accountSetsId, Long cashierAccountId) {
        Assert.isTrue(bqf.selectFrom(qCashierJournal)
                              .where(qCashierJournal.accountSetsId.eq(accountSetsId)
                                      .and(qCashierJournal.accountId.eq(cashierAccountId)))
                              .fetchCount() == 0, "账户已被使用不能删除~");
        jqf.delete(qCashierAccount)
                .where(qCashierAccount.accountSetsId.eq(accountSetsId).and(qCashierAccount.id.eq(cashierAccountId)))
                .execute();
    }

    @Transactional
    @Override
    public void clear(Long accountSetsId) {
        jqf.delete(qCashierAccount)
                .where(qCashierAccount.accountSetsId.eq(accountSetsId))
                .execute();
    }

    @Override
    public void init(AccountSets accountSets, AccountSetsTemplate accountSetsTemplate) {
        cashierAccountRepository.saveAll(
                bqf.selectFrom(qCashierAccount)
                        .where(qCashierAccount.accountSetsId.eq(accountSetsTemplate.getId()))
                        .fetch().stream().map(s -> {
                            bqf.detach(s);
                            CashierAccount bean = BeanUtil.toBean(s, CashierAccount.class);
                            bean.setAccountSetsId(accountSets.getId());
                            return bean;
                        }).collect(Collectors.toList()));
    }

    @Override
    public String backup(Long accountSetsId) {
        return JSON.toJSONString(bqf.selectFrom(qCashierAccount)
                .where(qCashierAccount.accountSetsId.eq(accountSetsId)).fetch());
    }

    @Override
    public Class<?> backupType() {
        return CashierAccount.class;
    }

    @Override
    public void recover(Long accountSetsId, String json) {
        List<CashierAccount> entityList = JSON.parseArray(json, CashierAccount.class);
        for (CashierAccount entity : entityList) {
            entity.setAccountSetsId(accountSetsId);
        }
        cashierAccountRepository.saveAll(entityList);
    }

    public CashierAccount loadItem(PrimaryKey primaryKey) {
        return cashierAccountRepository.findById(primaryKey).orElse(null);
    }
}
