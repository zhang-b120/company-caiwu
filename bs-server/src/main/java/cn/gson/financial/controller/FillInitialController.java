package cn.gson.financial.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.gson.financial.agent.initialbalance.dto.FillInitialResponse;
import cn.gson.financial.agent.initialbalance.service.FillInitialApplyService;
import cn.gson.financial.agent.initialbalance.service.FillInitialHttpClient;
import cn.gson.financial.kernel.annotation.DueCheck;
import cn.gson.financial.kernel.annotation.SessionAccountSet;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.exception.ServiceException;
import cn.gson.financial.kernel.model.entity.AccountSets;
import com.alibaba.fastjson.JSON;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;

/**
 * 期初填充接口：前端上传「科目余额表」+「当前账套期初模板」，转发到 8081 脚本处理。
 * fill-initial：仅返回脚本 JSON；fill-initial/apply：调用脚本后写入当前账套（新增科目+期初），并返回未匹配科目、excel_file 等。
 */
@RestController
@RequestMapping("/api")
@Slf4j
@RequiredArgsConstructor
public class FillInitialController {

    private final FillInitialHttpClient fillInitialHttpClient;
    private final FillInitialApplyService fillInitialApplyService;

    /**
     * 上传模板文件和科目余额表，调用 8081 期初填充接口，将脚本处理完的 JSON 直接返回给前端。
     * <p>
     * template_file：当前账套的期初模板（可从 GET /agent/initial-balance-import/export-accounts-file 下载后上传）
     * balance_file：前端上传的科目余额表
     */
    @PostMapping(value = "/fill-initial", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> fillInitial(
            @RequestParam("template_file") MultipartFile templateFile,
            @RequestParam("balance_file") MultipartFile balanceFile) throws Exception {
        File t = File.createTempFile("template_", ".xlsx");
        File b = File.createTempFile("balance_", ".xlsx");
        try {
            templateFile.transferTo(t);
            balanceFile.transferTo(b);
            String json = fillInitialHttpClient.fillInitial(t, b);
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(json);
        } finally {
            t.delete();
            b.delete();
        }
    }

    /**
     * 上传模板文件和科目余额表，调用 8081 期初填充接口，将结果应用到当前账套：
     * 1）已填充科目中 isNew 的新增为科目；2）已填充科目全部写入期初；3）未匹配科目、excel_file 等返回前端。
     * <p>
     * template_file：当前账套的期初模板（可从 GET /agent/initial-balance-import/export-accounts-file 下载后上传）
     * balance_file：前端上传的科目余额表
     */
    @PostMapping(value = "/fill-initial/apply", produces = MediaType.APPLICATION_JSON_VALUE)
    @SaCheckLogin
    @DueCheck
    @SaCheckPermission("setting-initialbalance-canimport")
    public JsonResult fillInitialApply(
            @RequestParam("template_file") MultipartFile templateFile,
            @RequestParam("balance_file") MultipartFile balanceFile,
            @SessionAccountSet AccountSets accountSets) throws Exception {
        if (accountSets == null) {
            return JsonResult.failure("请先选择账套");
        }
        File t = File.createTempFile("template_", ".xlsx");
        File b = File.createTempFile("balance_", ".xlsx");
        try {
            templateFile.transferTo(t);
            balanceFile.transferTo(b);
            String json = fillInitialHttpClient.fillInitial(t, b);
            FillInitialResponse fillResponse = JSON.parseObject(json, FillInitialResponse.class);
            if (fillResponse == null) {
                return JsonResult.failure("fill-initial 返回数据解析失败");
            }
            FillInitialApplyService.FillInitialApplyResult result = fillInitialApplyService.applyToAccountSet(accountSets, fillResponse);
            return JsonResult.successful()
                    .data("message", result.getMessage())
                    .data("filledCount", result.getFilledCount())
                    .data("addedCount", result.getAddedCount())
                    .data("newSubjectsCount", result.getNewSubjectsCount())
                    .data("initialBalancesCount", result.getInitialBalancesCount())
                    .data("unmatched", result.getUnmatched())
                    .data("excelFile", result.getExcelFile())
                    .data("invalidEncodingNewSubjects", result.getInvalidEncodingNewSubjects());
        } catch (ServiceException e) {
            log.warn("期初填充应用到账套失败: {}", e.getMessage());
            return JsonResult.failure(e.getMessage());
        } finally {
            t.delete();
            b.delete();
        }
    }
}
