package cn.gson.financial.agent.initialbalance.service;

import cn.gson.financial.agent.initialbalance.dto.FillInitialResponse;
import cn.gson.financial.agent.initialbalance.dto.InitialBalanceImportResponse;
import cn.gson.financial.kernel.exception.ServiceException;
import cn.gson.financial.kernel.model.entity.AccountSets;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 将 fill-initial 接口返回结果应用到当前账套：新增科目（filled_subjects 中 isNew）、写入期初（filled_subjects），未匹配科目返回前端。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FillInitialApplyService {

    private final InitialBalanceApplyService initialBalanceApplyService;

    /**
     * 将 fill-initial 返回结果应用到指定账套：先新增科目（filled_subjects 中 isNew），再写入期初（全部 filled_subjects）。
     *
     * @param accountSets 目标账套
     * @param fillResponse fill-initial 接口返回的 JSON 解析结果（success、filled_subjects 非空）
     * @return 应用结果：新增科目数、期初条数、未匹配科目、excel_file 等
     */
    @Transactional(rollbackFor = Exception.class)
    public FillInitialApplyResult applyToAccountSet(AccountSets accountSets, FillInitialResponse fillResponse) {
        if (fillResponse == null || !Boolean.TRUE.equals(fillResponse.getSuccess())) {
            throw new ServiceException("fill-initial 返回数据无效，无法导入");
        }
        List<FillInitialResponse.FilledSubject> filledSubjects = fillResponse.getFilled_subjects();
        if (CollUtil.isEmpty(filledSubjects)) {
            throw new ServiceException("fill-initial 未返回已填充科目，无法导入");
        }

        // 转为工作流 /import 的响应结构，复用现有 apply 逻辑
        InitialBalanceImportResponse response = toInitialBalanceImportResponse(fillResponse);
        InitialBalanceApplyService.ApplyResult applyResult = initialBalanceApplyService.applyToAccountSet(accountSets, response);

        FillInitialApplyResult result = new FillInitialApplyResult();
        result.setSuccess(true);
        result.setMessage(fillResponse.getMessage());
        result.setFilledCount(fillResponse.getFilled_count() != null ? fillResponse.getFilled_count() : filledSubjects.size());
        result.setAddedCount(fillResponse.getAdded_count() != null ? fillResponse.getAdded_count() : 0);
        result.setNewSubjectsCount(applyResult.getNewSubjectsCount());
        result.setInitialBalancesCount(applyResult.getInitialBalancesCount());
        result.setUnmatched(fillResponse.getUnmatched() != null ? fillResponse.getUnmatched() : Collections.emptyList());
        result.setExcelFile(fillResponse.getExcel_file());
        result.setInvalidEncodingNewSubjects(applyResult.getInvalidEncodingNewSubjects());
        return result;
    }

    /**
     * 将 fill-initial 返回结构转为 InitialBalanceImportResponse（newSubjects + initialBalances），供 applyToAccountSet 复用。
     */
    private static InitialBalanceImportResponse toInitialBalanceImportResponse(FillInitialResponse fillResponse) {
        List<FillInitialResponse.FilledSubject> filled = fillResponse.getFilled_subjects() != null
                ? fillResponse.getFilled_subjects() : Collections.emptyList();

        List<InitialBalanceImportResponse.NewSubject> newSubjects = filled.stream()
                .filter(f -> Boolean.TRUE.equals(f.getIsNew()))
                .map(f -> {
                    InitialBalanceImportResponse.NewSubject n = new InitialBalanceImportResponse.NewSubject();
                    n.setCode(f.getCode());
                    n.setName(f.getName());
                    n.setParentCode(f.getParentCode());
                    n.setBalanceDirection(StrUtil.emptyToDefault(f.getBalanceDirection(), "借"));
                    n.setType("");
                    return n;
                })
                .sorted(Comparator.comparingInt((InitialBalanceImportResponse.NewSubject n) -> StrUtil.nullToEmpty(n.getCode()).length())
                        .thenComparing(n -> StrUtil.nullToEmpty(n.getCode())))
                .collect(Collectors.toList());

        List<InitialBalanceImportResponse.InitialBalance> initialBalances = filled.stream()
                .map(f -> {
                    InitialBalanceImportResponse.InitialBalance ib = new InitialBalanceImportResponse.InitialBalance();
                    ib.setCode(f.getCode());
                    ib.setBeginBalance(f.getBeginBalance() != null ? f.getBeginBalance() : BigDecimal.ZERO);
                    ib.setYearTotalDebit(f.getDebitTotal() != null ? f.getDebitTotal() : BigDecimal.ZERO);
                    ib.setYearTotalCredit(f.getCreditTotal() != null ? f.getCreditTotal() : BigDecimal.ZERO);
                    ib.setBalanceDirection(StrUtil.emptyToDefault(f.getBalanceDirection(), "借"));
                    return ib;
                })
                .collect(Collectors.toList());

        InitialBalanceImportResponse.ResponseData data = new InitialBalanceImportResponse.ResponseData();
        data.setNewSubjects(newSubjects);
        data.setInitialBalances(initialBalances);

        InitialBalanceImportResponse response = new InitialBalanceImportResponse();
        response.setSuccess(true);
        response.setData(data);
        return response;
    }

    @lombok.Data
    public static class FillInitialApplyResult {
        private Boolean success;
        private String message;
        private Integer filledCount;
        private Integer addedCount;
        private Integer newSubjectsCount;
        private Integer initialBalancesCount;
        /** 未匹配科目，返回前端展示 */
        private List<FillInitialResponse.UnmatchedItem> unmatched;
        /** 生成的期初模板 Excel（base64 + filename），可选返回前端下载 */
        private FillInitialResponse.ExcelFile excelFile;
        /** 因不符合账套科目编码规则而未写入的新科目 */
        private List<InitialBalanceApplyService.ApplyResult.InvalidEncodingSubject> invalidEncodingNewSubjects;
    }
}
