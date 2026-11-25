package cn.gson.financial.kernel.model.repo;/* Copyright © 2020 Yuech and/or its affiliates. All rights reserved. */

import cn.gson.financial.kernel.model.entity.BankStatementDetail;
import cn.gson.financial.kernel.model.hibernate.PrimaryKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.querydsl.QuerydslPredicateExecutor;

import java.util.List;

/**
 * @Author zhb
 * @Date 2025-05-20
 * @Description
 */
public interface BankStatementDetailRepository extends JpaRepository<BankStatementDetail, PrimaryKey>, QuerydslPredicateExecutor<BankStatementDetail> {

    List<BankStatementDetail> findByBankStatementIdAndIsDeleteOrderByTradeDate(Long bankStatementId, int isDelete);

    List<BankStatementDetail> findByAccountSetIdAndIsDeleteOrderByTradeDate(Long accountSetId, int isDelete);

    List<BankStatementDetail> findByCashierJournalIdInAndIsDelete(List<Long> cashierJournalIds, int isDelete);

    List<BankStatementDetail> findByBankStatementIdAndIsDelete(Long bankStatementId, int isDelete);
}

