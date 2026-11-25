package cn.gson.financial.kernel.service;

import cn.gson.financial.kernel.config.AccountSetsProcessor;
import cn.gson.financial.kernel.config.BackupProcessor;
import cn.gson.financial.kernel.model.entity.QReportCashFlowDraft;
import cn.gson.financial.kernel.model.entity.ReportCashFlowDraft;
import cn.gson.financial.kernel.model.repo.ReportCashFlowDraftRepository;
import com.alibaba.fastjson.JSON;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * <p>***************************************</p>
 * <ul style="margin:15px;">
 * <li>Description : financial</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2024年12月06日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * <p>***************************************</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReportCashFlowDraftService extends AbsService implements BackupProcessor, AccountSetsProcessor {

    private final ReportCashFlowDraftRepository flowDraftRepository;

    private final QReportCashFlowDraft qReportCashFlowDraft = QReportCashFlowDraft.reportCashFlowDraft;

    /**
     *
     * @param accountSetsId
     * @param period
     * @return
     */
    public List<ReportCashFlowDraft> list(Long accountSetsId, LocalDate period) {
        return bqf.selectFrom(qReportCashFlowDraft)
                .where(qReportCashFlowDraft.accountSetsId.eq(accountSetsId)
                        .and(qReportCashFlowDraft.period.eq(period)))
                .fetch();
    }

    @Transactional
    public void save(Long accountSetsId, LocalDate period, List<ReportCashFlowDraft> reportCashFlowDrafts) {
        jqf.delete(qReportCashFlowDraft)
                .where(qReportCashFlowDraft.accountSetsId.eq(accountSetsId)
                        .and(qReportCashFlowDraft.period.eq(period)))
                .execute();

        for (ReportCashFlowDraft draft : reportCashFlowDrafts) {
            draft.setAccountSetsId(accountSetsId);
            draft.setPeriod(period);
        }

        flowDraftRepository.saveAll(reportCashFlowDrafts);
    }

    @Override
    public void clear(Long accountSetsId) {
        jqf.delete(qReportCashFlowDraft)
                .where(qReportCashFlowDraft.accountSetsId.eq(accountSetsId))
                .execute();
    }

    @Override
    public String backup(Long accountSetsId) {
        return JSON.toJSONString(bqf.selectFrom(qReportCashFlowDraft)
                .where(qReportCashFlowDraft.accountSetsId.eq(accountSetsId))
                .fetch());
    }

    @Override
    public Class<?> backupType() {
        return ReportCashFlowDraft.class;
    }

    @Override
    public void recover(Long accountSetsId, String json) {
        List<ReportCashFlowDraft> salaryFields = com.alibaba.fastjson2.JSON.parseArray(json, ReportCashFlowDraft.class);
        salaryFields.forEach(s -> s.setAccountSetsId(accountSetsId));
        flowDraftRepository.saveAll(salaryFields);
    }

    @Transactional
    public void delete(Long accountSetsId, LocalDate period) {
        jqf.delete(qReportCashFlowDraft)
                .where(qReportCashFlowDraft.accountSetsId.eq(accountSetsId)
                        .and(qReportCashFlowDraft.period.eq(period)))
                .execute();
    }
}
