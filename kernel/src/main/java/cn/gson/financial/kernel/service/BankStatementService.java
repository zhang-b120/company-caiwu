package cn.gson.financial.kernel.service;/* Copyright © 2020 Yuech and/or its affiliates. All rights reserved. */

import cn.gson.financial.kernel.common.CopierCopyUtils;
import cn.gson.financial.kernel.controller.Page;
import cn.gson.financial.kernel.controller.PageResults;
import cn.gson.financial.kernel.model.entity.*;
import cn.gson.financial.kernel.model.repo.BankStatementDetailRepository;
import cn.gson.financial.kernel.model.repo.BankStatementRepository;
import cn.gson.financial.kernel.model.ua.resp.GetBankStatementListResp;
import cn.gson.financial.kernel.model.ua.resp.GetUserInfoResp;
import cn.gson.financial.kernel.model.vo.ImportBankStatementVo;
import com.blazebit.persistence.PagedList;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.Tuple;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * @Author zhb
 * @Date 2025-05-19
 * @Description
 */
@Service
@RequiredArgsConstructor
public class BankStatementService extends AbsService{

    private final BankStatementRepository bankStatementRepository;
    private final BankStatementDetailRepository bankStatementDetailRepository;
    private final QBankStatement qBankStatement = QBankStatement.bankStatement;
    private final UserService userService;
    private final QUser qUser = QUser.user;
    private final BankStatementDetailService bankStatementDetailService;

    /**
     * 导入流水数据
     * @param importBankStatementVoList
     * @param accountSetsId
     */
    public void importData(List<ImportBankStatementVo> importBankStatementVoList, Long accountSetsId) {

        GetUserInfoResp getUserInfoResp = userService.uaGetUserInfo(accountSetsId);
        BankStatement saveBankStatement = new BankStatement();
        saveBankStatement.setAccountSetsId(accountSetsId);
        saveBankStatement.setCreateId(getUserInfoResp.getUserId());
        saveBankStatement.setCreateTime(LocalDateTime.now());
        saveBankStatement.setIsDelete(0);
        BankStatement bankStatement = bankStatementRepository.save(saveBankStatement);
        if(importBankStatementVoList.size() > 0){
            List<BankStatementDetail> bankStatementDetailList =
                    importBankStatementVoList.stream()
                            .map(item -> {
                                BankStatementDetail bankStatementDetail = CopierCopyUtils.convertTo(item, BankStatementDetail.class);
                                bankStatementDetail.setBankStatementId(bankStatement.getId());
                                bankStatementDetail.setIsDelete(0);
                                return bankStatementDetail;
                            })
                            .collect(Collectors.toList());
            bankStatementDetailRepository.saveAll(bankStatementDetailList);
        }
    }


    public PageResults uaGetBankStatementList(Long accountSetsId, Page page) {
        PagedList<Tuple> tuples = bqf.selectFrom(qBankStatement)
                .select(qBankStatement.id, qBankStatement.createTime, qUser.realName)
                .leftJoin(qUser).on(qBankStatement.createId.eq(qUser.id))
                .where(qBankStatement.accountSetsId.eq(accountSetsId).and(qBankStatement.isDelete.eq(0)))
                .orderBy(qBankStatement.createTime.desc(), qBankStatement.id.desc())
                .fetchPage(page.getOffset(), page.getOffsetEnd());

        return new PageResults(tuples.stream().map(tuple -> {
            GetBankStatementListResp resp = new GetBankStatementListResp();
            resp.setId(tuple.get(qBankStatement.id));
            resp.setCreateTime(tuple.get(qBankStatement.createTime));
            resp.setRealName(tuple.get(qUser.realName));
            return resp;
        }).collect(Collectors.toList()), page, tuples.getTotalSize());
    }

    @Transactional
    public void uaDeleteBankStatement(Long bankStatementId) {
        jqf.update(qBankStatement).set(qBankStatement.isDelete, 1)
                .where(qBankStatement.id.eq(bankStatementId))
                .execute();
        bankStatementDetailService.uaDeleteBankStatementDetail(bankStatementId);
    }

    public static class QueryVo {
        private QBankStatement qBankStatement = QBankStatement.bankStatement;
        private BooleanBuilder builder = new BooleanBuilder();

        public BooleanBuilder query() {
            return builder;
        }
    }
}