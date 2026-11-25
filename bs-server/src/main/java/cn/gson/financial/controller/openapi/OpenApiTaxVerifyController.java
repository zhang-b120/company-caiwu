package cn.gson.financial.controller.openapi;

import cn.dev33.satoken.annotation.SaIgnore;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.task.workbench.service.TaskWorkbenchService;
import cn.hutool.core.util.StrUtil;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 开放 API — 税务核验结果收口（FN-TAX-VERIFY-PROFILE-WIRE 改造 A）。
 * 路径前缀 {@code /openapi/v1/tax-verify}。
 *
 * <p>BT 采完税务核验后直推 {@code POST /openapi/v1/tax-verify/result}（不走 OcTask 队列）：
 * <ol>
 *   <li>复用 {@link TaskWorkbenchService#parseVerifyResult}/{@link TaskWorkbenchService#saveVerifyResult}
 *       与 {@code /oc/callback} 同口径写 {@code task_verify_result}；</li>
 *   <li>并入账套画像 {@code AccountSetProfile} 的 {@code tax_verify} section（已落库，复用 upsert）；</li>
 *   <li>核验资格 vs {@code AccountSets.vatType} 比对，不一致返/存 {@code vatTypeMismatch} 告警（<b>绝不自动改</b> vatType）。</li>
 * </ol>
 *
 * <p>编排逻辑全在 {@link TaskWorkbenchService#ingestTaxVerifyResult}（同一事务），本控制器只做参数校验 + 透传。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/openapi/v1/tax-verify")
@SaIgnore
public class OpenApiTaxVerifyController {

    private final TaskWorkbenchService taskWorkbenchService;

    /** 核验截图根目录（与 UploadController/OcCallbackController 同源配置）。 */
    @Value("${oc.screenshot-root:E:\\qykj\\file\\oc-screenshots}")
    private String ocScreenshotRoot;

    /**
     * 接收 BT 采集的核验结果。
     *
     * <p>入参 {@code {accountSetsId, period, status, error_msg?, execute_time?, result:{query_qualification,
     * query_status, query_should_declare, query_current_unreported, query_arrears}}}
     * （{@code status}/{@code result} 结构对齐现有 verify_tax 回调）。
     */
    @PostMapping("/result")
    public JsonResult result(@RequestBody TaxVerifyReq req) {
        if (req == null || req.getAccountSetsId() == null) {
            return JsonResult.failure("accountSetsId 不能为空");
        }
        if (StrUtil.isBlank(req.getPeriod())) {
            return JsonResult.failure("period 不能为空（yyyy-MM）");
        }
        if (StrUtil.isBlank(req.getStatus())) {
            return JsonResult.failure("status 不能为空");
        }

        // 组装与 verify_tax 回调同形的 body，交由共用解析/落库
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", req.getStatus());
        if (req.getErrorMsg() != null) body.put("error_msg", req.getErrorMsg());
        if (req.getExecuteTime() != null) body.put("execute_time", req.getExecuteTime());
        if (req.getResult() != null) body.put("result", req.getResult());

        try {
            Map<String, Object> data = taskWorkbenchService.ingestTaxVerifyResult(
                    req.getAccountSetsId(), req.getPeriod().trim(), body);
            return JsonResult.successful(data);
        } catch (IllegalArgumentException e) {
            // 账套不存在 / 缺 result 字段等业务校验
            return JsonResult.failure(e.getMessage());
        } catch (Exception e) {
            log.error("税务核验结果收口失败 accountSetsId={} period={}", req.getAccountSetsId(), req.getPeriod(), e);
            return JsonResult.failure("核验结果处理失败：" + e.getMessage());
        }
    }

    /**
     * FN-SHOW-TAX-VERIFY-RESULT：读核验结果（BT 渲染采集节点核验结果用）。
     * <p>{@code GET /openapi/v1/tax-verify/result?accountSetsId=&period=}。复用 {@code buildVerifyResultDto}；
     * 无核验记录返 {@code data:null}（不报错）；含 {@code vatTypeMismatch}/{@code vatTypeMismatchMsg}；
     * period 小规模季末月归一；{@code screenshotUrls} 已代理化为本控制器 {@code /screenshot} 端点。
     */
    @GetMapping("/result")
    public JsonResult getResult(@RequestParam Long accountSetsId,
                                @RequestParam(required = false) String period) {
        if (accountSetsId == null) return JsonResult.failure("accountSetsId 不能为空");
        if (StrUtil.isBlank(period)) return JsonResult.failure("period 不能为空（yyyy-MM）");
        try {
            // dto 可能为 null（账套不存在 / 未跑过核验）→ data:null，BT 据此显示"无核验记录"，不报错
            Map<String, Object> dto = taskWorkbenchService.getTaxVerifyResultDto(accountSetsId, period.trim());
            return JsonResult.successful(dto);
        } catch (Exception e) {
            log.error("查询核验结果失败 accountSetsId={} period={}", accountSetsId, period, e);
            return JsonResult.failure("查询核验结果失败：" + e.getMessage());
        }
    }

    /**
     * 核验截图代理（@SaIgnore，BT 可直达）。原始 {@code /oc-screenshots/**} 受 Sa-Token 登录拦截、
     * BT openapi 不可直达，故经此代理流式返回。安全：仅限 {@code oc.screenshot-root} 目录内（canonical 前缀校验防路径穿越）。
     */
    @GetMapping("/screenshot")
    public ResponseEntity<byte[]> screenshot(@RequestParam String name) {
        if (StrUtil.isBlank(name)) return ResponseEntity.badRequest().build();
        File root = new File(ocScreenshotRoot);
        File file = new File(root, name);
        try {
            String canonical = file.getCanonicalPath();
            if (!canonical.startsWith(root.getCanonicalPath() + File.separator)
                    && !canonical.equals(root.getCanonicalPath())) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();   // 路径穿越
            }
        } catch (IOException e) {
            return ResponseEntity.badRequest().build();
        }
        if (!file.exists() || !file.isFile()) return ResponseEntity.notFound().build();
        try {
            byte[] bytes = Files.readAllBytes(file.toPath());
            HttpHeaders headers = new HttpHeaders();
            headers.add("Cache-Control", "max-age=2592000");
            headers.setContentType(guessImageType(name));
            return new ResponseEntity<>(bytes, headers, HttpStatus.OK);
        } catch (IOException e) {
            log.warn("读取核验截图失败: {}", name, e);
            return ResponseEntity.notFound().build();
        }
    }

    private static MediaType guessImageType(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".png")) return MediaType.IMAGE_PNG;
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return MediaType.IMAGE_JPEG;
        if (n.endsWith(".gif")) return MediaType.IMAGE_GIF;
        return MediaType.APPLICATION_OCTET_STREAM;
    }

    @Data
    public static class TaxVerifyReq {
        private Long accountSetsId;
        private String period;
        private String status;
        private String errorMsg;
        private String executeTime;
        /** 对齐 verify_tax 回调的 result：{query_qualification, query_status, query_should_declare, ...} */
        private Map<String, Object> result;
    }
}
