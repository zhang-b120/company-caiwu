package cn.gson.financial.kernel.service;/* Copyright © 2020 Yuech and/or its affiliates. All rights reserved. */

import cn.gson.financial.kernel.common.CopierCopyUtils;
import cn.gson.financial.kernel.model.entity.BankStatement;
import cn.gson.financial.kernel.model.entity.BankStatementDetail;
import cn.gson.financial.kernel.model.entity.QBankStatement;
import cn.gson.financial.kernel.model.entity.QBankStatementDetail;
import cn.gson.financial.kernel.model.repo.BankStatementDetailRepository;
import cn.gson.financial.kernel.model.ua.request.GetSummaryDataReq;
import cn.gson.financial.kernel.model.ua.resp.GetBankStatementDetailListResp;
import cn.gson.financial.kernel.model.ua.resp.GetSummaryDataResp;
import com.querydsl.core.types.dsl.BooleanExpression;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.ObjectUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * @Author zhb
 * @Date 2025-05-20
 * @Description
 */
@Service
@RequiredArgsConstructor
public class BankStatementDetailService extends AbsService{

    private final BankStatementDetailRepository bankStatementDetailRepository;
    private final QBankStatement qBankStatement = QBankStatement.bankStatement;
    private final QBankStatementDetail qBankStatementDetail = QBankStatementDetail.bankStatementDetail;
    private final InvoiceService invoiceService;

    /**
     * 获取汇总数据
     * @param req
     * @return
     */
    public GetSummaryDataResp uaGetSummaryData(GetSummaryDataReq req) {
        GetSummaryDataResp resp = new GetSummaryDataResp();
        if(ObjectUtils.isNotEmpty(req.getAccountSetsId())){
            BooleanExpression expression = qBankStatement.accountSetsId.eq(req.getAccountSetsId());
            if (ObjectUtils.isNotEmpty(req.getBeginDate())) {
                expression = expression.and(qBankStatementDetail.tradeDate.after(req.getBeginDate()));
            }
            if (ObjectUtils.isNotEmpty(req.getEndDate())) {
                expression = expression.and(qBankStatementDetail.tradeDate.before(req.getEndDate()));
            }
            List<BankStatementDetail> bankStatementDetailList = bqf.selectFrom(qBankStatementDetail)
                    .leftJoin(qBankStatement).on(qBankStatement.id.eq(qBankStatementDetail.bankStatementId)
                            .and(qBankStatement.isDelete.eq(0)))
                    .where(qBankStatement.accountSetsId.eq(req.getAccountSetsId())
                            .and(qBankStatementDetail.tradeDate.between(req.getBeginDate(), req.getEndDate()))
                            .and(qBankStatementDetail.isDelete.eq(0))
                            .and(qBankStatementDetail.tradeAmount.isNotNull()))
                    .fetch();
            if(bankStatementDetailList.size() > 0){
                BigDecimal payAmount = bankStatementDetailList.stream()
                        .filter(f -> "往账".equals(f.getTransactionType()))
                        .map(BankStatementDetail::getTradeAmount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                BigDecimal receiveAmount =bankStatementDetailList.stream()
                        .filter(f -> "来账".equals(f.getTransactionType()))
                        .map(BankStatementDetail::getTradeAmount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                resp.setPayAmount(payAmount);
                resp.setReceiveAmount(receiveAmount);
                resp.setBalanceAmount(receiveAmount.subtract(payAmount));
            }
            BigDecimal invoiceAmount = invoiceService.getSummaryData(req.getAccountSetsId(), req.getBeginDate().toLocalDate(), req.getEndDate().toLocalDate());
            if(invoiceAmount != null){
                resp.setInvoiceAmount(invoiceAmount);
            }
        }
        return resp;
    }

    /**
     * 获取明细列表
     * @param bankStatementId
     * @return
     */
    public List<GetBankStatementDetailListResp> uaGetBankStatementDetailList(Long bankStatementId){
        List<GetBankStatementDetailListResp> getBankStatementDetailList = bqf.selectFrom(qBankStatementDetail)
                .where(qBankStatementDetail.bankStatementId.eq(bankStatementId).and(qBankStatementDetail.isDelete.eq(0)))
                .fetch()
                .stream()
                .map(item -> {
                    GetBankStatementDetailListResp resp = CopierCopyUtils.convertTo(item, GetBankStatementDetailListResp.class);
                    return resp;
                }).collect(Collectors.toList());
        return getBankStatementDetailList;
    }

    @Transactional
    public void uaDeleteBankStatementDetail(Long bankStatementId) {
        jqf.update(qBankStatementDetail).set(qBankStatementDetail.isDelete, 1)
                .where(qBankStatementDetail.bankStatementId.eq(bankStatementId))
                .execute();
    }
}
