package cn.gson.financial.bank.controller;

import cn.dev33.satoken.annotation.SaIgnore;
import cn.gson.financial.bank.service.BankStatementAiService;
import cn.gson.financial.kernel.controller.JsonResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * 银行流水 AI 接口
 *
 * POST /bank/statement/ai-upload  — 第一步：上传任意格式 Excel，AI 解析后存为留底
 * POST /bank/statement/ai-process — 第二步：从留底生成日记账和凭证
 * POST /bank/ai-import            — 兼容旧接口（直接从 Excel 处理，不留底）
 */
@Slf4j
@SaIgnore
@RestController
@RequestMapping("/bank")
@RequiredArgsConstructor
public class BankStatementAiController {

    private final BankStatementAiService bankStatementAiService;

    /**
     * 第一步：上传任意格式银行流水 Excel，AI 解析后存入 BankStatementDetail（留底）
     * 系统自动按 accountName 查找对应银行账户，找不到则在 1002 科目下自动新建
     *
     * @param file         银行流水 Excel 文件（任意格式）
     * @param accountSetId 账套 ID
     * @param accountName  银行账户名称（如"工商银行基本户"）
     * @param bankName     银行名称（如"工商银行"，可选）
     * @param cardNumber   银行账号（可选，用于唯一匹配或存档）
     * @param period       账期，格式 YYYY-MM（存档用，同时在返回值中透传给第二步）
     * @param userId       操作人 ID（可选，系统自动上传时不传，默认记为 0）
     * @return {bankStatementId, cashierAccountId, period, total, truncated?, msg?}
     */
    @PostMapping("/statement/ai-upload")
    public JsonResult aiUpload(
            @RequestParam("file") MultipartFile file,
            @RequestParam("accountSetId") Long accountSetId,
            @RequestParam("accountName") String accountName,
            @RequestParam(value = "bankName", required = false, defaultValue = "") String bankName,
            @RequestParam(value = "cardNumber", required = false, defaultValue = "") String cardNumber,
            @RequestParam(value = "period", required = false, defaultValue = "") String period,
            @RequestParam(value = "userId", required = false, defaultValue = "0") Long userId) {
        if (file == null || file.isEmpty()) {
            return JsonResult.failure("请上传银行流水 Excel 文件");
        }
        try {
            Map<String, Object> result = bankStatementAiService.aiUpload(
                    file, accountSetId, accountName, bankName, cardNumber, userId, period);
            return JsonResult.successful(result);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return JsonResult.failure(e.getMessage());
        } catch (Exception e) {
            log.error("银行流水上传失败", e);
            return JsonResult.failure("上传失败：" + e.getMessage());
        }
    }

    /**
     * 第二步：从留底（BankStatementDetail）生成日记账和凭证
     *
     * @param bankStatementId  第一步返回的批次 ID
     * @param accountSetId     账套 ID
     * @param cashierAccountId 对应银行账户 ID
     * @param period           账期，格式 YYYY-MM
     * @return {total, journalCount, voucherCount, unmatchedCount, noTemplateCount, newTemplateCount, msg?}
     */
    @PostMapping("/statement/ai-process")
    public JsonResult aiProcess(
            @RequestParam("bankStatementId") Long bankStatementId,
            @RequestParam("accountSetId") Long accountSetId,
            @RequestParam("cashierAccountId") Long cashierAccountId,
            @RequestParam("period") String period) {
        try {
            Map<String, Object> result = bankStatementAiService.processFromStatement(
                    bankStatementId, accountSetId, cashierAccountId, period);
            return JsonResult.successful(result);
        } catch (IllegalArgumentException e) {
            return JsonResult.failure(e.getMessage());
        } catch (Exception e) {
            log.error("银行流水 AI 处理失败", e);
            return JsonResult.failure("处理失败：" + e.getMessage());
        }
    }

    /**
     * 兼容旧接口：上传 Excel 直接处理，不经过留底
     */
    @PostMapping("/ai-import")
    public JsonResult aiImport(
            @RequestParam("file") MultipartFile file,
            @RequestParam("accountSetId") Long accountSetId,
            @RequestParam("cashierAccountId") Long cashierAccountId,
            @RequestParam("period") String period) {
        if (file == null || file.isEmpty()) {
            return JsonResult.failure("请上传银行流水 Excel 文件");
        }
        try {
            Map<String, Object> result = bankStatementAiService.importAndProcess(
                    file, accountSetId, cashierAccountId, period);
            return JsonResult.successful(result);
        } catch (IllegalArgumentException e) {
            return JsonResult.failure(e.getMessage());
        } catch (Exception e) {
            log.error("银行流水 AI 导入失败", e);
            return JsonResult.failure("导入失败：" + e.getMessage());
        }
    }
}
