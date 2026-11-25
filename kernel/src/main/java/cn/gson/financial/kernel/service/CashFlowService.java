package cn.gson.financial.kernel.service;

import cn.gson.financial.kernel.model.entity.*;
import cn.gson.financial.kernel.model.enums.AccountingCategoryType;
import cn.gson.financial.kernel.model.hibernate.PrimaryKey;
import cn.gson.financial.kernel.model.repo.CashFlowRepository;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 
 * <p><b>Copyright © 2010-2021开发公司All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : (CashFlow)</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2023-04-05 14:03:35</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CashFlowService extends AbsAuxiliaryDataService<CashFlow> {

    private final QCashFlow qCashFlow = QCashFlow.cashFlow;

    private final CashFlowRepository cashFlowRepository;

    @Override
    public void init(AccountSets accountSets, AccountSetsTemplate accountSetsTemplate) {
        List<CashFlow> flows = bqf.selectFrom(qCashFlow)
                .where(qCashFlow.accountSetsId.eq(accountSetsTemplate.getId()))
                .fetch();

        if (CollUtil.isNotEmpty(flows)) {
            cashFlowRepository.saveAll(flows.stream().map(s -> {
                bqf.detach(s);
                CashFlow bean = BeanUtil.toBean(s, CashFlow.class);
                bean.setCategoryId((long) AccountingCategoryType.CASH_FLOW.ordinal());
                bean.setCategoryType(AccountingCategoryType.CASH_FLOW);
                bean.setAccountSetsId(accountSets.getId());
                return bean;
            }).collect(Collectors.toList()));
        }
    }

    @Override
    public void clear(Long accountSetsId) {
        jqf.delete(qCashFlow)
                .where(qCashFlow.accountSetsId.eq(accountSetsId))
                .execute();
    }

    @Override
    public String backup(Long accountSetsId) {
        return JSON.toJSONString(bqf.selectFrom(qCashFlow)
                .where(qCashFlow.accountSetsId.eq(accountSetsId)).fetch());
    }

    @Override
    public Class<CashFlow> backupType() {
        return CashFlow.class;
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
        if (StrUtil.isNotEmpty(json)) {
            List<CashFlow> javaList = JSONArray.parseArray(json).toJavaList(backupType());
            javaList.forEach(s -> s.setAccountSetsId(accountSetsId));
            cashFlowRepository.saveAll(javaList);
        }
    }

    @Override
    public JpaRepository<CashFlow, PrimaryKey> getRepository() {
        return cashFlowRepository;
    }

    @Override
    public QAcDetails getQ() {
        return qCashFlow._super;
    }

    @Override
    public AccountingCategoryType getType() {
        return AccountingCategoryType.CASH_FLOW;
    }
}

