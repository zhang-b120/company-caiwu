package cn.gson.financial.agent.initialbalance.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaIgnore;
import cn.gson.financial.agent.initialbalance.dto.InitialBalanceImportResponse;
import cn.gson.financial.agent.initialbalance.service.InitialBalanceApplyService;
import cn.gson.financial.agent.initialbalance.service.InitialBalanceImportAgentService;
import cn.gson.financial.controller.InitialBalanceTemplateExportService;
import cn.gson.financial.kernel.annotation.DueCheck;
import cn.gson.financial.kernel.annotation.SessionAccountSet;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.exception.ServiceException;
import cn.gson.financial.kernel.model.entity.AccountSets;
import cn.gson.financial.kernel.service.CurrencyService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import cn.hutool.core.collection.CollUtil;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 科目期初导入（对接外部 /import 接口）
 * <p>
 * apply：仅上传科目余额表（balanceFile）和可选 period，后端用当前账套期初导出作为账套期初文件调工作流，再将结果写入当前账套。
 */
@RestController
@RequestMapping("/agent/initial-balance-import")
@Slf4j
@RequiredArgsConstructor
public class InitialBalanceImportTestController {

    private static final int MIN_ACCOUNTS_FILE_BYTES = 500;

    /** Postman 等可直接调用的 /import 契约与工作流一致，用于本地测试（含 code_rule） */
    private static final String IMPORT_MOCK_PATH = "/import";

    private final InitialBalanceImportAgentService initialBalanceImportAgentService;
    private final InitialBalanceApplyService initialBalanceApplyService;
    private final CurrencyService currencyService;
    private final InitialBalanceTemplateExportService initialBalanceTemplateExportService;

    /**
     * Mock 工作流 /import 接口，供 Postman 直接测试（参数名与工作流一致）。
     * <p>
     * 接收：balance_file、default_accounts_file、period、code_rule（均为表单，后两者可选）。
     * 返回：与工作流相同的 InitialBalanceImportResponse 结构（当前为成功 + 空数据）。
     * <p>
     * Postman 示例：POST http://localhost:8080/agent/initial-balance-import/import
     * form-data: balance_file=文件, default_accounts_file=文件, period=2025-01, code_rule=[4,3,3,2]
     */
    @PostMapping(IMPORT_MOCK_PATH)
    @SaIgnore
    public InitialBalanceImportResponse importMock(
            @RequestParam(value = "balance_file", required = false) MultipartFile balanceFile,
            @RequestParam(value = "default_accounts_file", required = false) MultipartFile accountsFile,
            @RequestParam(value = "period", required = false) String period,
            @RequestParam(value = "code_rule", required = false) String codeRule) {
        log.info("Mock /import 收到请求: period={}, code_rule={}, balance_file={}, default_accounts_file={}",
                period, codeRule,
                balanceFile != null && !balanceFile.isEmpty() ? balanceFile.getOriginalFilename() : null,
                accountsFile != null && !accountsFile.isEmpty() ? accountsFile.getOriginalFilename() : null);
        InitialBalanceImportResponse response = new InitialBalanceImportResponse();
        response.setSuccess(true);
        response.setMessage("Mock 成功，已接收 code_rule 等参数，可用于 Postman 联调");
        InitialBalanceImportResponse.ResponseData data = new InitialBalanceImportResponse.ResponseData();
        data.setNewSubjects(new ArrayList<>());
        data.setInitialBalances(new ArrayList<>());
        data.setPeriod(period != null ? period : "2025-01");
        response.setData(data);
        return response;
    }

    /**
     * 测试期初导入：仅调用工作流接口，不写入账套
     */
    @PostMapping("/test")
    @SaIgnore
    public JsonResult testImport(
            @RequestParam("balanceFile") MultipartFile balanceFile,
            @RequestParam("accountsFile") MultipartFile accountsFile,
            @RequestParam(value = "period", required = false) String period,
            @RequestParam(value = "codeRule", required = false) String codeRule) {
        try {
            InitialBalanceImportResponse result = initialBalanceImportAgentService.importInitialBalance(
                    balanceFile, accountsFile, period, codeRule);
            return JsonResult.successful(result);
        } catch (IllegalArgumentException e) {
            log.warn("期初导入参数错误: {}", e.getMessage());
            return JsonResult.failure(e.getMessage());
        } catch (IOException e) {
            log.error("期初导入文件读取失败", e);
            return JsonResult.failure("文件读取失败: " + e.getMessage());
        } catch (Exception e) {
            log.error("期初导入调用失败", e);
            return JsonResult.failure("导入失败: " + e.getMessage());
        }
    }

    /**
     * 导入到当前账套：仅上传科目余额表（balanceFile）和可选 period。
     * 后端用当前账套期初导出作为账套期初文件调工作流，再将 newSubjects、initialBalances 写入当前账套。
     * 若当前账套已结账则拒绝；若当前账套无期初数据则提示先维护期初。
     */
    @PostMapping("/apply")
    @SaCheckLogin
    @DueCheck
    @SaCheckPermission("setting-initialbalance-canimport")
    public JsonResult applyToCurrentAccountSet(
            @RequestParam("balanceFile") MultipartFile balanceFile,
            @RequestParam(value = "period", required = false) String period,
            @SessionAccountSet AccountSets accountSets) {
        if (accountSets == null) {
            return JsonResult.failure("请先选择账套");
        }
        try {
            Long localCurrencyId = currencyService.fetchLocal(accountSets.getId()).getId();
            byte[] accountsBytes = initialBalanceTemplateExportService.exportToBytes(accountSets, localCurrencyId);
            if (accountsBytes == null || accountsBytes.length < MIN_ACCOUNTS_FILE_BYTES) {
                return JsonResult.failure("当前账套暂无期初数据，请先在期初页维护期初后再导入");
            }
            String codeRule = formatEncodingAsCodeRule(accountSets.getEncoding());
            // 分批调用工作流，多批返回在 Agent 内合并为一份；下面一块处理（只做一次写入账套）
            InitialBalanceImportResponse result = initialBalanceImportAgentService.importInitialBalanceInBatches(
                    balanceFile.getBytes(),
                    balanceFile.getOriginalFilename() != null ? balanceFile.getOriginalFilename() : "balance.xlsx",
                    accountsBytes,
                    "当前账套期初.xlsx",
                    period,
                    codeRule);
            InitialBalanceApplyService.ApplyResult applyResult = initialBalanceApplyService.applyToAccountSet(accountSets, result);
            JsonResult json = JsonResult.successful()
                    .data("newSubjectsCount", applyResult.getNewSubjectsCount())
                    .data("initialBalancesCount", applyResult.getInitialBalancesCount())
                    .data("message", result.getMessage());
            if (result.getData() != null) {
                String normalizedPeriod = normalizePeriod(result.getData().getPeriod());
                json.data("newSubjects", result.getData().getNewSubjects())
                    .data("initialBalances", result.getData().getInitialBalances())
                    .data("period", normalizedPeriod)
                    .data("ambiguousMatches", result.getData().getAmbiguousMatches())
                    .data("level1OnlyBalances", result.getData().getLevel1OnlyBalances())
                    .data("autoMappings", result.getData().getAutoMappings());
            }
            if (applyResult.getInvalidEncodingNewSubjects() != null && !applyResult.getInvalidEncodingNewSubjects().isEmpty()) {
                json.data("invalidEncodingNewSubjects", applyResult.getInvalidEncodingNewSubjects());
            }
            if (applyResult.getSkippedBalanceCodes() != null && !applyResult.getSkippedBalanceCodes().isEmpty()) {
                json.data("skippedBalanceCodes", applyResult.getSkippedBalanceCodes());
            }
            return json;
        } catch (ServiceException e) {
            log.warn("期初导入到账套失败: {}", e.getMessage());
            return JsonResult.failure(e.getMessage());
        } catch (IllegalArgumentException e) {
            log.warn("期初导入参数错误: {}", e.getMessage());
            return JsonResult.failure(e.getMessage());
        } catch (IOException e) {
            log.error("期初导入文件读取失败", e);
            return JsonResult.failure("文件读取失败: " + e.getMessage());
        } catch (Exception e) {
            log.error("期初导入到账套失败", e);
            return JsonResult.failure("导入失败: " + e.getMessage());
        }
    }

    /** 规范化工作流返回的 period 字符串，去除首尾多余的双引号（如 "\"2026-01-01\"" -> "2026-01-01"） */
    private static String normalizePeriod(String period) {
        if (period == null || period.isEmpty()) return period;
        String s = period.trim();
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            return s.substring(1, s.length() - 1).trim();
        }
        return s;
    }

    /** 将账套编码规则 List 转为工作流参数 code_rule，如 [4,3,3,2] 或 4-3-3-2 */
    private static String formatEncodingAsCodeRule(List<Integer> encoding) {
        if (CollUtil.isEmpty(encoding)) {
            return null;
        }
        String inner = encoding.stream().map(String::valueOf).collect(Collectors.joining(","));
        return "[" + inner + "]";
    }

    /**
     * 调试用：仅调用工作流并返回「便于对比」的科目与期初汇总，不写库。
     * 返回：工作流原始结果 + summary（科目编码列表、期初编码与金额列表），便于与 Excel 或账套导出做对比。
     */
    @PostMapping("/debug-compare")
    @SaIgnore
    public JsonResult debugCompare(
            @RequestParam("balanceFile") MultipartFile balanceFile,
            @RequestParam("accountsFile") MultipartFile accountsFile,
            @RequestParam(value = "period", required = false) String period,
            @RequestParam(value = "codeRule", required = false) String codeRule) {
        try {
            InitialBalanceImportResponse result = initialBalanceImportAgentService.importInitialBalance(
                    balanceFile, accountsFile, period, codeRule);
            CompareSummary summary = buildCompareSummary(result);
            return JsonResult.successful()
                    .data("raw", result)
                    .data("summary", summary);
        } catch (IllegalArgumentException e) {
            log.warn("期初导入参数错误: {}", e.getMessage());
            return JsonResult.failure(e.getMessage());
        } catch (IOException e) {
            log.error("期初导入文件读取失败", e);
            return JsonResult.failure("文件读取失败: " + e.getMessage());
        } catch (Exception e) {
            log.error("期初导入调试调用失败", e);
            return JsonResult.failure("调试失败: " + e.getMessage());
        }
    }

    private static CompareSummary buildCompareSummary(InitialBalanceImportResponse result) {
        CompareSummary s = new CompareSummary();
        if (result == null || result.getData() == null) {
            s.setMessage(result != null ? result.getMessage() : "无返回数据");
            return s;
        }
        InitialBalanceImportResponse.ResponseData data = result.getData();
        if (data.getNewSubjects() != null) {
            s.setNewSubjectCodes(data.getNewSubjects().stream()
                    .map(n -> n.getCode() == null ? "" : n.getCode().trim())
                    .filter(c -> !c.isEmpty())
                    .sorted()
                    .collect(Collectors.toList()));
            s.setNewSubjectCodeToName(data.getNewSubjects().stream()
                    .filter(n -> n.getCode() != null && !n.getCode().trim().isEmpty())
                    .collect(Collectors.toMap(n -> n.getCode().trim(), n -> n.getName() != null ? n.getName() : "", (a, b) -> a)));
        } else {
            s.setNewSubjectCodes(new ArrayList<>());
        }
        if (data.getInitialBalances() != null) {
            s.setBalanceCodes(data.getInitialBalances().stream()
                    .map(b -> b.getCode() == null ? "" : b.getCode().trim())
                    .filter(c -> !c.isEmpty())
                    .distinct()
                    .sorted()
                    .collect(Collectors.toList()));
            List<CompareSummary.BalanceRow> rows = new ArrayList<>();
            for (InitialBalanceImportResponse.InitialBalance b : data.getInitialBalances()) {
                if (b.getCode() == null || b.getCode().trim().isEmpty()) continue;
                CompareSummary.BalanceRow row = new CompareSummary.BalanceRow();
                row.setCode(b.getCode().trim());
                row.setBeginBalance(b.getBeginBalance());
                row.setYearTotalDebit(b.getYearTotalDebit());
                row.setYearTotalCredit(b.getYearTotalCredit());
                row.setYearTotal(b.getYearTotal());
                rows.add(row);
            }
            s.setBalanceRows(rows);
        } else {
            s.setBalanceCodes(new ArrayList<>());
            s.setBalanceRows(new ArrayList<>());
        }
        if (data.getAmbiguousMatches() != null) {
            s.setAmbiguousMatches(data.getAmbiguousMatches());
        }
        s.setSuccess(Boolean.TRUE.equals(result.getSuccess()));
        s.setMessage(result.getMessage());
        return s;
    }

    @Data
    public static class CompareSummary {
        private Boolean success;
        private String message;
        /** 工作流返回的「新科目」编码列表（已排序，便于与文件对比） */
        private List<String> newSubjectCodes;
        /** 新科目 code -> name */
        private java.util.Map<String, String> newSubjectCodeToName;
        /** 工作流返回的期初涉及科目编码（去重、排序） */
        private List<String> balanceCodes;
        /** 工作流返回的期初明细（code + 金额字段），便于与 Excel 或账套导出逐行对比 */
        private List<BalanceRow> balanceRows;
        /** 模糊匹配：同名科目有多个系统编码时的说明，需人工核对 */
        private List<InitialBalanceImportResponse.AmbiguousMatch> ambiguousMatches;

        @Data
        public static class BalanceRow {
            private String code;
            private java.math.BigDecimal beginBalance;
            private java.math.BigDecimal yearTotalDebit;
            private java.math.BigDecimal yearTotalCredit;
            private java.math.BigDecimal yearTotal;
        }
    }
}
