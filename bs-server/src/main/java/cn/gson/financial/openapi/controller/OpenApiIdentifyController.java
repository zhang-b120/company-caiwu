package cn.gson.financial.openapi.controller;

import cn.dev33.satoken.annotation.SaIgnore;
import cn.gson.financial.kernel.controller.JsonResult;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.*;

/**
 * 开放 API — AI 识别（FN-AL-01 BL-DAY3-05）
 * <p>
 * 路径：/openapi/v1/identify
 * <p>
 * 对应 wx 端 financial-mock.identifyBankStatement / identifyInvoice。
 * 字段对齐 wx/utils/financial-mock.js#165-190。
 * <p>
 * 本端点是 "fileId 入参 → AI 识别结果" 的同步接口；
 * 现有 BankStatementAiController 是 multipart 文件流的两步链路（ai-upload + ai-process），
 * 参数形态不同。本端点是包装层：
 * <ul>
 *   <li>客户端通过 /ua/fileInfo/upload 上传得到 fileId</li>
 *   <li>本端点按 fileId 取文件 → 调 BankStatementAiService.aiUpload(...) → 返回归一结构</li>
 * </ul>
 * <p>
 * TODO 当前先返回与 wx mock 等价的固定结构，让 wx 端可切 USE_MOCK=false 联调；
 * 后续接 FileInfoService + BankStatementAiService。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/openapi/v1/identify")
@SaIgnore
public class OpenApiIdentifyController {

    /**
     * 银行流水 AI 识别
     * <p>POST /openapi/v1/identify/bank
     */
    @PostMapping("/bank")
    public JsonResult bank(@RequestBody IdentifyReq req) {
        if (isBlank(req.getFileId())) return JsonResult.failure("fileId 不能为空");
        if (req.getAccountSetId() == null && req.getAccountSetsId() == null)
            return JsonResult.failure("accountSetId 不能为空");

        Long asId = req.getAccountSetId() != null ? req.getAccountSetId() : req.getAccountSetsId();
        log.info("[identify/bank] fileId={}, accountSetId={}", req.getFileId(), asId);

        // TODO 接 FileInfoService.getById(fileId) + BankStatementAiService.aiUpload(...)
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("fileId", req.getFileId());
        data.put("accountSetId", asId);
        data.put("identified", true);
        data.put("rows", 86);
        data.put("summary", "已识别 86 条流水：入账 52 笔（共 ¥320,400）/ 出账 34 笔（共 ¥158,200）");
        data.put("warnings", List.of("第 7 行金额格式异常，建议人工复核", "第 41 行摘要为空"));
        return JsonResult.successful(data);
    }

    /**
     * 发票 AI 识别
     * <p>POST /openapi/v1/identify/invoice
     */
    @PostMapping("/invoice")
    public JsonResult invoice(@RequestBody IdentifyReq req) {
        if (isBlank(req.getFileId())) return JsonResult.failure("fileId 不能为空");
        if (req.getAccountSetId() == null && req.getAccountSetsId() == null)
            return JsonResult.failure("accountSetId 不能为空");

        Long asId = req.getAccountSetId() != null ? req.getAccountSetId() : req.getAccountSetsId();
        log.info("[identify/invoice] fileId={}, accountSetId={}", req.getFileId(), asId);

        // TODO 接 FileInfoService + 发票 AI 识别引擎
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("fileId", req.getFileId());
        data.put("accountSetId", asId);
        data.put("identified", true);

        List<Map<String, Object>> invoices = new ArrayList<>();
        invoices.add(invoiceRow("202604120001", 1280.00, "XX 服务有限公司", "本公司", 0.06, "2026-04-12"));
        invoices.add(invoiceRow("202604130011", 5800.00, "YY 商贸",         "本公司", 0.13, "2026-04-13"));
        data.put("invoices", invoices);
        data.put("summary", "已识别 2 张发票");
        return JsonResult.successful(data);
    }

    private Map<String, Object> invoiceRow(String invoiceNo, double amount, String seller, String buyer,
                                           double taxRate, String date) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("invoiceNo", invoiceNo);
        m.put("amount", amount);
        m.put("seller", seller);
        m.put("buyer", buyer);
        m.put("taxRate", taxRate);
        m.put("date", date);
        return m;
    }

    private static boolean isBlank(String s) { return s == null || s.trim().isEmpty(); }

    /**
     * AI 识别请求（accountSetId / accountSetsId 双名兼容 —— BL-DAY3-06）
     */
    @Data
    public static class IdentifyReq {
        private String fileId;
        private Long accountSetId;
        private Long accountSetsId;
    }
}
