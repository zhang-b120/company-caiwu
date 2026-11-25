package cn.gson.financial.kernel.service;/* Copyright © 2020 Yuech and/or its affiliates. All rights reserved. */

import cn.gson.financial.kernel.model.entity.*;
import cn.gson.financial.kernel.model.ua.resp.GetAccountProgressResp;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ObjectUtils;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;

/**
 * @Author zhb
 * @Date 2025-05-29
 * @Description
 */
@Slf4j
@Service
public class CommonService extends AbsService{

    private final QSalary qSalary = QSalary.salary;
    private final QInvoice qInvoice = QInvoice.invoice;
    private final QBankStatement qBankStatement = QBankStatement.bankStatement;
    private final QTaxReportResult qTaxReportResult = QTaxReportResult.taxReportResult;
    private final QFileInfo qFileInfo = QFileInfo.fileInfo;

    /**
     * 获取记账进度
     * @param accountSetsId
     * @param period
     * @return
     */
    public GetAccountProgressResp getAccountProgress(Long accountSetsId, LocalDate period) {
        LocalDate start = period.with(TemporalAdjusters.firstDayOfMonth());
        LocalDate end = period.with(TemporalAdjusters.lastDayOfMonth());
        LocalDateTime startTime = start.atStartOfDay();
        LocalDateTime endTime = end.atTime(23, 59, 59);

        GetAccountProgressResp resp = new GetAccountProgressResp();
        Integer count = 3;
        //发票
        // FN-FIX-OPENAPI-INVOICE-PERIOD-ASSIGNMENT-DATE：账套进度的发票存在性按「做账归属期」判断
        // （assignmentDate 优先 + tradeDate 兜底），与内部口径一致。
        Invoice invoice = bqf.selectFrom(qInvoice)
                .where(qInvoice.accountSetsId.eq(accountSetsId),
                        cn.gson.financial.kernel.model.query.InvoicePeriodPredicate.byPeriod(qInvoice, start, end))
                .limit(1)
                .fetchOne();
        if(ObjectUtils.isNotEmpty(invoice)) {
            resp.setInvoice(Boolean.TRUE);
            count -= 1;
        }
        //工资
        Salary salary = bqf.selectFrom(qSalary)
                .where(qSalary.accountSetsId.eq(accountSetsId).and(qSalary.period.eq(period)).and(qSalary.state.eq(Boolean.TRUE)))
                .limit(1)
                .fetchOne();
        if(ObjectUtils.isNotEmpty(salary)) {
            resp.setSalary(Boolean.TRUE);
            count -= 1;
        }
        //流水
        FileInfo fileInfo = bqf.selectFrom(qFileInfo)
                .where(qFileInfo.accountSetsId.eq(accountSetsId)
                        .and(qFileInfo.accountSetPeriod.eq(period))
                        .and(qFileInfo.isDelete.eq(0))
                        .and(qFileInfo.type.eq("bankStatement"))
                )
                .limit(1)
                .fetchOne();
        if(ObjectUtils.isNotEmpty(fileInfo)) {
            resp.setBankStatement(Boolean.TRUE);
            count -= 1;
        }
        //报税
        TaxReportResult taxReportResult = bqf.selectFrom(qTaxReportResult)
                .where(qTaxReportResult.accountSetId.eq(accountSetsId).and(qTaxReportResult.taxTime.eq(period)))
                .limit(1)
                .fetchOne();
        if(ObjectUtils.isNotEmpty(taxReportResult) && taxReportResult.getAllCount() == taxReportResult.getSuccessCount()){
            resp.setTaxDeclare(Boolean.TRUE);
        }
        resp.setCount(count);
        return resp;
    }
}