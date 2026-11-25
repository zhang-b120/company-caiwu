package cn.gson.financial.task.workbench.controller;

import cn.dev33.satoken.annotation.SaIgnore;
import cn.gson.financial.kernel.config.AppConfig;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.model.entity.OcOperationLog;
import cn.gson.financial.kernel.model.repo.OcOperationLogRepository;
import cn.gson.financial.kernel.tool.RedisUtil;
import cn.gson.financial.task.workbench.service.TaskWorkbenchService;
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.TypeReference;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.util.*;
import java.util.UUID;

/**
 * OpenClaw 回调控制器
 * OpenClaw 执行完任务后调用这里的接口回写结果
 * 同时提供 OpenClaw 轮询待执行任务的接口
 */
@Slf4j
@SaIgnore
@RestController
@RequiredArgsConstructor
public class OcCallbackController {

    private final TaskWorkbenchService taskWorkbenchService;
    private final AppConfig appConfig;
    private final RedisUtil redisUtil;
    private final OcOperationLogRepository ocOperationLogRepository;

    /** 与 OpenClaw 约定的共享 Token，存储在配置文件中 */
    @Value("${oc.callback.token:}")
    private String ocCallbackToken;

    /** OC 截图本地存储目录 */
    @Value("${oc.screenshot-root:E:\\qykj\\file\\oc-screenshots}")
    private String ocScreenshotRoot;

    /** 截图 URL 的公网基础地址（含服务名前缀） */
    @Value("${oc.screenshot-base-url:http://150.139.141.142:8888/api}")
    private String ocScreenshotBaseUrl;

    // ─────────────────────────────────────────────────────────────────────
    // OpenClaw 查询下一个待处理企业的登录信息
    // ─────────────────────────────────────────────────────────────────────

    /**
     * GET /oc/tasks/next-account?oc_instance_id=OC-01&task_type=iit
     * OpenClaw 调用此接口获取下一个待处理企业的登录信息。
     * <p>
     * 返回当前 oc_instance_id 下最优先的、还有 queued 任务的企业信息及登录凭证。
     * 若所有企业的任务均已完成，返回空 data。
     * <p>
     * task_type 可选值：
     * <ul>
     *   <li>{@code iit} 或 {@code iit_declare} — 仅匹配个税类任务（iit_declare / query_payment_iit），
     *       返回时额外附带 {@code iit_password} 字段</li>
     *   <li>{@code national} — 仅匹配国税类任务（排除个税类）</li>
     *   <li>不传或为空 — 匹配所有任务类型（兼容旧行为）</li>
     * </ul>
     * <p>
     * 响应示例（task_type=iit）：
     * <pre>
     * {
     *   "code": 200,
     *   "data": {
     *     "account_set_id": 456,
     *     "company_name": "XX有限公司",
     *     "credit_code": "91110000...",
     *     "individual_tax_code": "...",
     *     "province": "广东",
     *     "vat_type": "小规模",
     *     "login_type": "officer",
     *     "officer_name": "张三",
     *     "phone": "138xxxx1234",
     *     "password": "abc123",
     *     "iit_password": "IIT_PWD_123"
     *   }
     * }
     * </pre>
     */
    /**
     * GET /oc/tasks/next-account
     * <p>
     * 新增参数：
     * <ul>
     *   <li>{@code login_credential} — 登录凭证值，用于过滤任务。
     *       officer/enterprise 时为手机号，proxy 时为代理账号，legal_person 时为身份证号。
     *       不传则不过滤（兼容旧行为）。</li>
     *   <li>{@code login_type} — 凭证类型（officer / enterprise / proxy / legal_person），
     *       配合 login_credential 确定从 payload 哪个字段提取凭证进行比对。
     *       不传时由 payload 内的 login_type 字段自动推断。</li>
     * </ul>
     */
    @GetMapping("/oc/tasks/next-account")
    public JsonResult getNextAccount(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(value = "oc_instance_id") List<String> ocInstanceIds,
            @RequestParam(value = "task_type", required = false) String taskType,
            @RequestParam(value = "count", defaultValue = "1") int count,
            @RequestParam(value = "province", required = false) String province,
            @RequestParam(value = "exclude_credential_provinces", required = false) List<String> excludeCredentialProvinces,
            @RequestParam(value = "max_count", required = false) Integer maxCount,
            @RequestParam(value = "time_window", required = false) Integer timeWindow,
            @RequestParam(value = "login_credential", required = false) String loginCredential,
            @RequestParam(value = "login_type", required = false) String loginType,
            @RequestParam(value = "allowed_provinces", required = false) List<String> allowedProvinces) {
        if (!checkToken(authorization)) {
            return JsonResult.failure("Token 验证失败", 401);
        }
        Set<String> excluded = excludeCredentialProvinces != null ? new java.util.HashSet<>(excludeCredentialProvinces) : new java.util.HashSet<>();
        // 仅当 task_type=national 时，allowed_provinces 才作为账套省份白名单生效；
        // 其他 task_type（如 iit）或留空时，allowed_provinces 被强制视为空，避免影响个税/缴费等不依赖省份的任务调度
        List<String> effectiveAllowedProvinces =
                "national".equalsIgnoreCase(taskType) && allowedProvinces != null && !allowedProvinces.isEmpty()
                        ? allowedProvinces
                        : null;
        try {
            if (count <= 1) {
                Map<String, Object> account = taskWorkbenchService.getNextPendingAccount(
                        ocInstanceIds, taskType, province, excluded, maxCount, timeWindow, loginCredential, loginType,
                        effectiveAllowedProvinces);
                return JsonResult.successful(account);
            }
            List<Map<String, Object>> accounts = taskWorkbenchService.getNextPendingAccounts(
                    ocInstanceIds, taskType, count, excluded, maxCount, timeWindow, loginCredential, loginType,
                    effectiveAllowedProvinces);
            return JsonResult.successful(accounts);
        } catch (Exception e) {
            log.error("获取下一个待处理企业失败", e);
            return JsonResult.failure("获取企业信息失败：" + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // OpenClaw 获取下一条待执行任务
    // ─────────────────────────────────────────────────────────────────────

    /**
     * GET /oc/tasks/next-task?oc_instance_id=OC-01&account_set_id=456
     * OpenClaw 登录成功后调用此接口获取该企业下的下一条可执行任务。
     * <p>
     * 每次只返回一条任务（已通过前置依赖检查的最早任务）。
     * 返回 null 表示该企业当前没有可执行的任务了。
     * <p>
     * 响应示例：
     * <pre>
     * {
     *   "code": 200,
     *   "data": {
     *     "id": 123,
     *     "taskType": "verify_tax",
     *     "accountSetId": 456,
     *     "period": "2026-02",
     *     "status": "queued",
     *     "companyName": "XX有限公司",
     *     "creditCode": "91110000...",
     *     "payload": { ... }
     *   }
     * }
     * </pre>
     */
    @GetMapping("/oc/tasks/next-task")
    public JsonResult getNextTask(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(value = "oc_instance_id") List<String> ocInstanceIds,
            @RequestParam(value = "account_set_id") Long accountSetId,
            @RequestParam(value = "task_type", required = false) String taskType) {
        if (!checkToken(authorization)) {
            return JsonResult.failure("Token 验证失败", 401);
        }
        try {
            Map<String, Object> task = taskWorkbenchService.getNextPendingTask(ocInstanceIds, accountSetId, taskType);
            return JsonResult.successful(task);
        } catch (Exception e) {
            log.error("获取下一条待执行任务失败", e);
            return JsonResult.failure("获取任务失败：" + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // OpenClaw 更新任务状态
    // ─────────────────────────────────────────────────────────────────────

    /**
     * POST /oc/tasks/start
     * OpenClaw 从任务清单中选定要执行的任务后，调用此接口将其标记为 running。
     * <p>
     * 请求体：
     * <pre>
     * {
     *   "task_ids":      [1, 2, 3],   // 要启动的任务 ID 列表（必填）
     *   "oc_instance_id": "oc-01"     // OC 实例编号（可选）
     * }
     * </pre>
     */
    @PostMapping("/oc/tasks/start")
    public JsonResult startTasks(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody Map<String, Object> body) {
        if (!checkToken(authorization)) {
            return JsonResult.failure("Token 验证失败", 401);
        }
        Object rawIds = body.get("task_ids");
        if (!(rawIds instanceof List) || ((List<?>) rawIds).isEmpty()) {
            return JsonResult.failure("task_ids 不能为空");
        }
        List<Long> taskIds = ((List<?>) rawIds).stream()
                .map(id -> Long.valueOf(id.toString()))
                .collect(java.util.stream.Collectors.toList());
        String ocInstanceId = (String) body.get("oc_instance_id");
        try {
            int updated = taskWorkbenchService.startOcTasks(taskIds, ocInstanceId);
            return JsonResult.successful(java.util.Map.of("updated", updated));
        } catch (Exception e) {
            log.error("启动任务失败", e);
            return JsonResult.failure("启动任务失败：" + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // OpenClaw 登录/切换身份失败通知
    // ─────────────────────────────────────────────────────────────────────

    /**
     * POST /oc/tasks/login-or-switch-failed
     * OC 登录失败或切换身份失败时调用，批量取消该企业的 queued 任务。
     * <p>
     * 请求体：
     * <pre>
     * {
     *   "account_set_id": 456,           // 必填
     *   "period":         "2026-03",     // 可选，不传则取消该账套下所有账期的任务
     *   "error_msg":      "登录超时"     // 可选
     * }
     * </pre>
     */
    @PostMapping("/oc/tasks/login-or-switch-failed")
    public JsonResult loginOrSwitchFailed(@RequestBody Map<String, Object> body) {
        Long accountSetId = Long.valueOf(body.get("account_set_id").toString());
        String period = (String) body.get("period");
        String errorMsg = (String) body.get("error_msg");
        try {
            Map<String, Object> result = taskWorkbenchService.failQueuedTasksOnLoginFailure(accountSetId, period, errorMsg);
            return JsonResult.successful(result);
        } catch (Exception e) {
            log.error("登录/切换身份失败处理异常: accountSetId={}", accountSetId, e);
            return JsonResult.failure("处理失败：" + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // OpenClaw 获取代理通道短信验证码
    // ─────────────────────────────────────────────────────────────────────

    /**
     * GET /oc/proxy/smsCode?proxy_username=xxx
     * OpenClaw 代理通道登录时，轮询此接口获取财务人员输入的短信验证码。
     * <p>
     * 验证码由前端调用 {@code POST /api/tax/login/proxy/smsCode} 保存到 Redis，
     * key 为 {@code PROXY_} + proxy_username，有效期 5 分钟。
     * <p>
     * OC 建议每 3~5 秒轮询一次，获取到验证码后立即使用。
     * 验证码取出后会从 Redis 中删除，防止重复使用。
     * <p>
     * 响应示例（验证码已提交）：
     * <pre>
     * { "code": 200, "data": { "status": "submitted", "sms_code": "123456" } }
     * </pre>
     * 响应示例（等待中）：
     * <pre>
     * { "code": 200, "data": { "status": "pending" } }
     * </pre>
     */
    @GetMapping("/oc/proxy/smsCode")
    public JsonResult getProxySmsCode(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(value = "proxy_username") String proxyUsername) {
        if (!checkToken(authorization)) {
            return JsonResult.failure("Token 验证失败", 401);
        }
        if (StrUtil.isBlank(proxyUsername)) {
            return JsonResult.failure("proxy_username 不能为空");
        }
        try {
            String redisKey = "PROXY_" + proxyUsername;
            String code = redisUtil.get(redisKey);
            if (StrUtil.isNotBlank(code)) {
                // 取出后删除，防止重复使用
                redisUtil.del(redisKey);
                log.info("OC 获取代理通道验证码成功: proxy_username={}", proxyUsername);
                return JsonResult.successful(Map.of("status", "submitted", "sms_code", code.trim()));
            } else {
                return JsonResult.successful(Map.of("status", "pending"));
            }
        } catch (Exception e) {
            log.error("获取代理通道验证码失败: proxy_username={}", proxyUsername, e);
            return JsonResult.failure("获取验证码失败：" + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // 通用回调接口（OpenClaw 统一入口）
    // ─────────────────────────────────────────────────────────────────────

    /**
     * POST /oc/callback/  (multipart/form-data)
     * OC 直接上传截图文件流 + JSON 结果，无需提前上传 OSS。
     * <p>
     * 请求格式（multipart/form-data）：
     * <ul>
     *   <li>{@code data}        - 业务结果 JSON 字符串（必填），内容与 JSON 版本 body 完全一致</li>
     *   <li>{@code screenshots} - 截图文件（PNG/JPG），支持多个，每个不超过 10MB（可选）</li>
     * </ul>
     * <p>
     * 后端处理：将截图保存到服务器，生成可访问的 URL 后注入到 data.screenshot_urls，
     * 再走与 JSON 版本相同的处理逻辑。
     */
    @SaIgnore
    @PostMapping(value = "/oc/callback/", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public JsonResult genericCallbackMultipart(
            HttpServletRequest request,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestPart("data") String dataJson,
            @RequestPart(value = "screenshots", required = false) List<MultipartFile> screenshots) {
        if (!checkToken(authorization)) {
            return JsonResult.failure("Token 验证失败", 401);
        }
        Map<String, Object> body;
        try {
            body = JSON.parseObject(dataJson, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return JsonResult.failure("data 字段 JSON 解析失败：" + e.getMessage());
        }
        // 保存截图文件，生成可访问 URL，并按文件名替换 result.*.screenshots 中的本地路径
        if (screenshots != null && !screenshots.isEmpty()) {
            File screenshotDir = new File(ocScreenshotRoot);
            if (!screenshotDir.exists()) screenshotDir.mkdirs();
            // 文件名（不含路径） → 服务器可访问 URL
            Map<String, String> filenameToUrl = new java.util.LinkedHashMap<>();
            for (MultipartFile file : screenshots) {
                if (file == null || file.isEmpty()) continue;
                try {
                    String origName = StrUtil.isBlank(file.getOriginalFilename()) ? "screenshot.png" : file.getOriginalFilename();
                    String ext = origName.contains(".") ? origName.substring(origName.lastIndexOf('.') + 1) : "png";
                    String savedName = UUID.randomUUID().toString().replace("-", "") + "." + ext;
                    File dest = new File(screenshotDir, savedName);
                    file.transferTo(dest);
                    String url = ocScreenshotBaseUrl + "/oc-screenshots/" + savedName;
                    // 以原始文件名（basename）为 key，方便与 result.*.screenshots 中的本地路径匹配
                    filenameToUrl.put(origName, url);
                    log.info("OC截图已保存: {} -> {}", origName, dest.getAbsolutePath());
                } catch (Exception e) {
                    log.warn("保存OC截图失败: {}", e.getMessage());
                }
            }
            if (!filenameToUrl.isEmpty()) {
                List<String> allUrls = new ArrayList<>(filenameToUrl.values());
                // 1. 对有 result.*.screenshots 嵌套结构的回调（如 vat_declare），替换本地路径
                if (body.get("result") instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> resultMap = (Map<String, Object>) body.get("result");
                    for (Object subObj : resultMap.values()) {
                        if (!(subObj instanceof Map)) continue;
                        @SuppressWarnings("unchecked")
                        Map<String, Object> sub = (Map<String, Object>) subObj;
                        Object shotPaths = sub.get("screenshots");
                        if (!(shotPaths instanceof List)) continue;
                        List<String> replaced = new ArrayList<>();
                        for (Object path : (List<?>) shotPaths) {
                            String pathStr = path.toString();
                            String basename = pathStr;
                            int slash = Math.max(pathStr.lastIndexOf('/'), pathStr.lastIndexOf('\\'));
                            if (slash >= 0) basename = pathStr.substring(slash + 1);
                            String url = filenameToUrl.get(basename);
                            replaced.add(url != null ? url : pathStr);
                        }
                        sub.put("screenshots", replaced);
                    }
                }
                // 2. 始终将截图 URL 列表注入到 screenshot_urls，供 iit_declare 等扁平结构回调使用
                body.put("screenshot_urls", allUrls);
            }
            // 递归扫描整个 body，将所有 screenshots / img_urls / screenshot_urls 字段
            // 中的本地路径替换为服务器可访问 URL，覆盖所有任务类型
            if (!filenameToUrl.isEmpty()) {
                replaceLocalPathsDeep(body, filenameToUrl);
            }
        }
        // 兜底：对 JSON 中仍残留的本地路径（file:///、E:\... 等），
        // 尝试按 basename 在截图目录匹配已上传文件，或直接用 basename 构建 URL
        File screenshotDir = new File(ocScreenshotRoot);
        if (!screenshotDir.exists()) screenshotDir.mkdirs();
        convertLocalImgUrlsDeep(body, screenshotDir);
        return doGenericCallback(body);
    }

    /**
     * 递归扫描整个 body，将所有截图字段（screenshots/img_urls/screenshot_urls）
     * 中的本地文件路径复制到截图目录并替换为 HTTP URL。
     * 覆盖所有任务类型（vat_declare/iit_declare/payment/verify_tax 等）。
     * 仅当路径指向本机可读文件时生效，已是 HTTP URL 则跳过。
     */
    private void convertLocalImgUrls(Map<String, Object> body, HttpServletRequest request) {
        File screenshotDir = new File(ocScreenshotRoot);
        if (!screenshotDir.exists()) screenshotDir.mkdirs();
        convertLocalImgUrlsDeep(body, screenshotDir);
    }

    @SuppressWarnings("unchecked")
    private void convertLocalImgUrlsDeep(Object node, File screenshotDir) {
        if (node instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) node;
            for (String key : List.of("screenshots", "img_urls", "screenshot_urls")) {
                Object val = map.get(key);
                if (!(val instanceof List)) continue;
                List<String> replaced = new ArrayList<>();
                for (Object pathObj : (List<?>) val) {
                    String path = pathObj.toString();
                    if (path.startsWith("http://") || path.startsWith("https://")) {
                        replaced.add(path);
                        continue;
                    }
                    // 提取 basename（兼容 Windows/Linux 路径和 file:/// URI）
                    String cleanPath = path.startsWith("file:///") ? path.substring(8) : path;
                    String basename = cleanPath;
                    int slash = Math.max(cleanPath.lastIndexOf('/'), cleanPath.lastIndexOf('\\'));
                    if (slash >= 0) basename = cleanPath.substring(slash + 1);

                    // 1. 先检查截图目录里是否已有同名文件（OC 先上传过 /oc/upload/screenshot）
                    File existsInDir = new File(screenshotDir, basename);
                    if (existsInDir.exists() && existsInDir.isFile()) {
                        String url = ocScreenshotBaseUrl + "/oc-screenshots/" + basename;
                        replaced.add(url);
                        log.info("OC截图按文件名匹配: {} -> {}", path, url);
                        continue;
                    }
                    // 2. 再尝试按完整路径读取本地文件（OC 与后端同机部署时可用）
                    try {
                        File src = new File(cleanPath);
                        if (src.exists() && src.isFile()) {
                            String ext = basename.contains(".") ? basename.substring(basename.lastIndexOf('.') + 1) : "png";
                            String savedName = UUID.randomUUID().toString().replace("-", "") + "." + ext;
                            File dest = new File(screenshotDir, savedName);
                            java.nio.file.Files.copy(src.toPath(), dest.toPath());
                            String url = ocScreenshotBaseUrl + "/oc-screenshots/" + savedName;
                            replaced.add(url);
                            log.info("OC截图本地路径已转换: {} -> {}", path, url);
                            continue;
                        }
                    } catch (Exception e) {
                        log.warn("OC截图本地路径转换失败: {}, error={}", path, e.getMessage());
                    }
                    // 3. 都找不到，用 basename 构建 URL（文件可能稍后到达或已上传到 oc-screenshots）
                    String fallbackUrl = ocScreenshotBaseUrl + "/oc-screenshots/" + basename;
                    replaced.add(fallbackUrl);
                    log.warn("OC截图文件未找到，使用 basename 构建 URL: {} -> {}", path, fallbackUrl);
                }
                map.put(key, replaced);
            }
            for (Object child : map.values()) convertLocalImgUrlsDeep(child, screenshotDir);
        } else if (node instanceof List) {
            for (Object item : (List<?>) node) convertLocalImgUrlsDeep(item, screenshotDir);
        }
    }

    /** 递归遍历整个 body，将所有截图字段中的本地路径替换为服务器 URL */
    @SuppressWarnings("unchecked")
    private void replaceLocalPathsDeep(Object node, Map<String, String> filenameToUrl) {
        if (node instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) node;
            for (String key : List.of("screenshots", "img_urls", "screenshot_urls")) {
                replaceLocalPaths(map, key, filenameToUrl);
            }
            // 继续递归子节点
            for (Object val : map.values()) {
                replaceLocalPathsDeep(val, filenameToUrl);
            }
        } else if (node instanceof List) {
            for (Object item : (List<?>) node) {
                replaceLocalPathsDeep(item, filenameToUrl);
            }
        }
    }

    /** 将 map 中指定 key 的本地路径列表替换为服务器 URL */
    private void replaceLocalPaths(Map<String, Object> sub, String key, Map<String, String> filenameToUrl) {
        Object paths = sub.get(key);
        if (!(paths instanceof List)) return;
        List<String> replaced = new ArrayList<>();
        for (Object path : (List<?>) paths) {
            String pathStr = path.toString();
            String basename = pathStr;
            int slash = Math.max(pathStr.lastIndexOf('/'), pathStr.lastIndexOf('\\'));
            if (slash >= 0) basename = pathStr.substring(slash + 1);
            String url = filenameToUrl.get(basename);
            replaced.add(url != null ? url : pathStr);
        }
        sub.put(key, replaced);
    }

    /**
     * 通用回调核心处理：根据 task_type 分发到对应处理逻辑。
     */
    private JsonResult doGenericCallback(Map<String, Object> body) {
        String taskType = (String) body.get("task_type");
        if (taskType == null || taskType.isBlank()) {
            return JsonResult.failure("task_type 不能为空");
        }
        // 规范化 status：OC 可能传 "success"→"done"，"error"→"failed"
        String rawStatus = (String) body.get("status");
        if ("success".equals(rawStatus)) {
            body.put("status", "done");
        } else if ("error".equals(rawStatus)) {
            body.put("status", "failed");
        }
        try {
            log.info("收到通用回调: task_type={}, task_id={}, status={}",
                    taskType, body.get("task_id"), body.get("status"));
            switch (taskType) {
                case "verify_tax" -> taskWorkbenchService.handleVerifyTaxCallback(body);
                case "download_invoice",
                        "download_invoice_in", "download_invoice_out",
                        "download_invoice_checked", "download_invoice_unchecked"
                        -> taskWorkbenchService.handleDownloadInvoiceCallback(body);
                case "vat_declare_pre" -> taskWorkbenchService.handleVatDeclarePreCallback(body);
                case "vat_declare", "vat_declare_post" -> taskWorkbenchService.handleVatDeclareCallback(body);
                // FN-NEW-DECLARE-TRIGGER-API：申报查询类回调通用落库（result_json + 截图 + 状态）
                case "current_declaration_query" -> taskWorkbenchService.handleDeclarationQueryCallback(body);
                case "iit_declare" -> taskWorkbenchService.handleIitDeclareCallback(body);
                case "query_payment_national" -> taskWorkbenchService.handleQueryPaymentNationalCallback(body);
                case "query_payment_iit" -> taskWorkbenchService.handleQueryPaymentIitCallback(body);
                case "query_payment" -> taskWorkbenchService.handleQueryPaymentCallback(body);
                case "check_invoice" -> taskWorkbenchService.handleCheckInvoiceCallback(body);
                case "tax_annual" -> taskWorkbenchService.handleTaxAnnualCallback(body);
                case "tax_annual_verify" -> taskWorkbenchService.handleTaxAnnualVerifyCallback(body);
                default -> { return JsonResult.failure("未知的 task_type: " + taskType); }
            }
            return JsonResult.successful();
        } catch (Exception e) {
            log.error("处理通用回调失败: task_type={}", taskType, e);
            // 回调处理失败，主事务已回滚，用新事务把 oc_task 标记为 failed
            try {
                Object rawId = body.get("task_id");
                if (rawId != null) {
                    Long taskId = Long.valueOf(rawId.toString());
                    taskWorkbenchService.markOcTaskFailed(taskId, e.getMessage());
                }
            } catch (Exception ex) {
                log.error("标记 oc_task failed 失败: task_id={}", body.get("task_id"), ex);
            }
            return JsonResult.failure("处理回调失败：" + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // OpenClaw 文件上传接口（RPA 执行后直接推文件）
    // ─────────────────────────────────────────────────────────────────────

    /**
     * POST /oc/upload/unchecked-invoice
     * 保存未勾选发票（仿 /timedTasks/saveUnCheckInvoice）
     */
    @SaIgnore
    @PostMapping(value = "/oc/upload/unchecked-invoice", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public void saveUnCheckInvoice(
            @RequestParam("file") MultipartFile file,
            @RequestParam("accountSetsId") Long accountSetsId,
            @RequestParam("invoiceType") String invoiceType,
            @RequestParam(value = "ocTaskId", required = false) Long ocTaskId) throws Exception {
        taskWorkbenchService.saveUnCheckInvoice(
                file.getInputStream(),
                accountSetsId,
                invoiceType,
                ocTaskId
        );
    }

    /**
     * POST /oc/upload/invoice
     * OpenClaw 通过 RPA 下载发票后，将发票 Excel 直接上传至财务系统解析入库。
     * <p>
     * 此接口替代原有纯元数据回调的用途：上传文件后同时完成 OcTask 回写和 task_data_sub 状态更新。
     * <p>
     * 参数（multipart/form-data）：
     * <ul>
     *   <li>{@code file}           - 发票 Excel 文件（税务局导出格式，含"发票基础信息"Sheet）</li>
     *   <li>{@code task_id}        - OcTask.id（必填，用于更新任务状态）</li>
     *   <li>{@code account_set_id} - 账套 ID</li>
     *   <li>{@code period}         - 账期 YYYY-MM</li>
     *   <li>{@code import_mode}    - 可选，{@code month}（默认）/ {@code season}（按季度）</li>
     *   <li>{@code input_count}    - 可选，进项发票张数（OpenClaw 统计后附带）</li>
     *   <li>{@code output_count}   - 可选，销项发票张数</li>
     *   <li>{@code check_status}   - 可选，勾选认证状态：done / skipped</li>
     *   <li>{@code error_msg}      - 可选，若部分失败可附带说明</li>
     * </ul>
     */
    @PostMapping("/oc/upload/invoice")
    public JsonResult ocUploadInvoice(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam("file") MultipartFile file,
            @RequestParam("task_id") Long taskId,
            @RequestParam("account_set_id") Long accountSetId,
            @RequestParam("period") String period,
            @RequestParam(value = "import_mode", defaultValue = "month") String importMode,
            @RequestParam(value = "input_count", required = false) Integer inputCount,
            @RequestParam(value = "output_count", required = false) Integer outputCount,
            @RequestParam(value = "check_status", required = false) String checkStatus,
            @RequestParam(value = "error_msg", required = false) String errorMsg) {
        if (!checkToken(authorization)) {
            return JsonResult.failure("Token 验证失败", 401);
        }
        if (file == null || file.isEmpty()) {
            return JsonResult.failure("未收到发票文件");
        }
        try {
            log.info("收到OC发票文件上传: task_id={}, accountSetId={}, period={}", taskId, accountSetId, period);
            Map<String, Object> result = taskWorkbenchService.ocUploadInvoice(
                    file, taskId, accountSetId, period, importMode,
                    inputCount, outputCount, checkStatus, errorMsg);
            boolean ok = Boolean.TRUE.equals(result.get("success"));
            return ok ? JsonResult.successful(result) : JsonResult.failure((String) result.get("msg"));
        } catch (Exception e) {
            log.error("OC发票文件上传处理失败: task_id={}", taskId, e);
            return JsonResult.failure("处理失败：" + e.getMessage());
        }
    }

    /**
     * POST /oc/import/invoice
     * OpenClaw 以 JSON 格式上报发票数据（无需上传 Excel 文件），用于测试或对接非标准导出场景。
     * <p>
     * 适用场景：
     * <ul>
     *   <li>OpenClaw 测试阶段直接传入模拟发票数据，无需真实 Excel</li>
     *   <li>税务局提供 JSON/API 格式数据时的对接</li>
     * </ul>
     * <p>
     * 请求体（application/json）：
     * <pre>
     * {
     *   "task_id":        123,           // OcTask.id（必填）
     *   "account_set_id": 456,           // 账套 ID（必填）
     *   "period":         "2026-02",     // 账期 YYYY-MM（必填）
     *   "check_status":   "done",        // 勾选认证状态，可选：done/skipped
     *   "error_msg":      "",            // 部分失败说明（可选）
     *   "invoices": [                    // 发票列表（必填，可为空数组表示本月无票）
     *     {
     *       "invoice_type":   "增值税专用发票",  // 发票票种（newCategory）
     *       "trade_date":     "2026-02-15",      // 开票日期 YYYY-MM-DD
     *       "invoice_code":   "3100202210",      // 发票代码（可为空）
     *       "invoice_number": "12345678",        // 发票号码（与 net_number 二选一）
     *       "net_number":     "",                // 数电发票号码
     *       "seller_id":      "91310000...",     // 销方识别号
     *       "seller_name":    "某供应商有限公司",
     *       "buyer_id":       "91330100...",     // 购方识别号
     *       "buyer_name":     "我方公司名称",
     *       "state":          "正常",            // 发票状态：正常/已作废/已红冲-全额/已红冲-部分
     *       "amount_ex_tax":  1000.00,           // 金额（不含税）
     *       "tax_amount":     130.00,            // 税额
     *       "total_amount":   1130.00,           // 价税合计
     *       "remark":         "",                // 备注
     *       "items": [                           // 货物清单（可为空）
     *         {
     *           "goods_name":   "电脑",
     *           "specification":"ThinkPad X1",
     *           "unit":         "台",
     *           "quantity":     1.0,
     *           "amount":       1000.00,
     *           "tax_rate":     0.13,
     *           "tax":          130.00
     *         }
     *       ]
     *     }
     *   ]
     * }
     * </pre>
     * <p>
     * 成功响应：
     * <pre>
     * {
     *   "success": true,
     *   "total": 5,
     *   "inputCount": 3,
     *   "outputCount": 2,
     *   "msg": "OC下载发票完成：共导入 5 张（进项 3 / 销项 2）"
     * }
     * </pre>
     */
    @PostMapping("/oc/import/invoice")
    public JsonResult ocImportInvoiceJson(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody Map<String, Object> body) {
        if (!checkToken(authorization)) {
            return JsonResult.failure("Token 验证失败", 401);
        }
        Long taskId = body.get("task_id") != null ? Long.valueOf(body.get("task_id").toString()) : null;
        Long accountSetId = body.get("account_set_id") != null ? Long.valueOf(body.get("account_set_id").toString()) : null;
        String period = (String) body.get("period");
        if (accountSetId == null || StrUtil.isBlank(period)) {
            return JsonResult.failure("account_set_id 和 period 为必填项");
        }
        try {
            log.info("收到OC发票JSON导入: task_id={}, accountSetId={}, period={}", taskId, accountSetId, period);
            Map<String, Object> result = taskWorkbenchService.ocImportInvoiceJson(taskId, accountSetId, period, body);
            boolean ok = Boolean.TRUE.equals(result.get("success"));
            return ok ? JsonResult.successful(result) : JsonResult.failure((String) result.get("msg"));
        } catch (Exception e) {
            log.error("OC发票JSON导入失败: task_id={}", taskId, e);
            return JsonResult.failure("处理失败：" + e.getMessage());
        }
    }

    /**
     * POST /oc/upload/verify-screenshot
     * OpenClaw 完成税务核验后，上传截图文件至财务系统存档，并附带核验结果。
     * <p>
     * 与 {@code /api/oc/callback/verify-tax} 的区别：此接口直接接收截图文件（二进制），
     * 服务端负责将截图保存/上传 OSS 后再写入 OcTask；而回调接口接收的是 OSS URL。
     * 两者可择一使用，也可先调用此接口上传截图拿到 URL，再调用回调接口传结果。
     * <p>
     * 参数（multipart/form-data）：
     * <ul>
     *   <li>{@code screenshot}     - 截图文件（PNG/JPG）</li>
     *   <li>{@code task_id}        - OcTask.id</li>
     *   <li>{@code account_set_id} - 账套 ID</li>
     *   <li>{@code period}         - 账期</li>
     *   <li>{@code status}         - done / failed</li>
     *   <li>{@code login_ok}       - true / false</li>
     *   <li>{@code tax_status}     - normal / abnormal / unknown</li>
     *   <li>{@code error_msg}      - 失败原因（可选）</li>
     * </ul>
     */
    @PostMapping("/oc/upload/verify-screenshot")
    public JsonResult ocUploadVerifyScreenshot(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam("screenshot") MultipartFile screenshot,
            @RequestParam("task_id") Long taskId,
            @RequestParam("account_set_id") Long accountSetId,
            @RequestParam("period") String period,
            @RequestParam(value = "status", defaultValue = "done") String status,
            @RequestParam(value = "login_ok", defaultValue = "true") boolean loginOk,
            @RequestParam(value = "tax_status", defaultValue = "normal") String taxStatus,
            @RequestParam(value = "error_msg", required = false) String errorMsg) {
        if (!checkToken(authorization)) {
            return JsonResult.failure("Token 验证失败", 401);
        }
        try {
            log.info("收到OC核验截图上传: task_id={}, accountSetId={}, status={}", taskId, accountSetId, status);
            Map<String, Object> result = taskWorkbenchService.ocUploadVerifyScreenshot(
                    screenshot, taskId, accountSetId, period, status, loginOk, taxStatus, errorMsg);
            boolean ok = Boolean.TRUE.equals(result.get("success"));
            return ok ? JsonResult.successful(result) : JsonResult.failure((String) result.get("msg"));
        } catch (Exception e) {
            log.error("OC核验截图处理失败: task_id={}", taskId, e);
            return JsonResult.failure("处理失败：" + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // 通用截图上传
    // ─────────────────────────────────────────────────────────────────────

    /**
     * POST /oc/upload/screenshot
     * 通用截图上传接口，保存文件到服务器并返回可访问的 URL 列表。
     * <p>只负责存文件、返回 URL，不更新任务状态。
     * OC 拿到 URL 后在各回调接口中携带 URL 一并上报结果。</p>
     *
     * <h3>请求参数（multipart/form-data）</h3>
     * <table border="1">
     *   <tr><th>参数</th><th>类型</th><th>必填</th><th>说明</th></tr>
     *   <tr><td>task_id</td><td>Long</td><td>否</td><td>OcTask.id（仅用于日志追踪）</td></tr>
     *   <tr><td>screenshots</td><td>File[]</td><td>✅</td><td>截图文件（PNG/JPG，每个 ≤10MB）</td></tr>
     * </table>
     *
     * <h3>响应</h3>
     * <pre>{ "code": 200, "data": { "urls": ["http://server/oc-screenshots/xxx.png", ...] } }</pre>
     */
    @PostMapping({"/oc/upload/screenshot"})
    public JsonResult ocUploadScreenshot(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(value = "task_id", required = false) Long taskId,
            @RequestParam(value = "screenshots", required = false) List<MultipartFile> screenshots) {
        if (!checkToken(authorization)) {
            return JsonResult.failure("Token 验证失败", 401);
        }
        try {
            log.info("收到OC截图上传: task_id={}, 文件数={}",
                    taskId, screenshots == null ? 0 : screenshots.size());
            List<String> urls = new ArrayList<>();
            if (screenshots != null && !screenshots.isEmpty()) {
                File screenshotDir = new File(ocScreenshotRoot);
                if (!screenshotDir.exists()) screenshotDir.mkdirs();
                for (MultipartFile file : screenshots) {
                    if (file == null || file.isEmpty()) continue;
                    try {
                        String origName = StrUtil.isBlank(file.getOriginalFilename())
                                ? UUID.randomUUID().toString().replace("-", "") + ".png"
                                : file.getOriginalFilename();
                        file.transferTo(new File(screenshotDir, origName));
                        urls.add(ocScreenshotBaseUrl + "/oc-screenshots/" + origName);
                        log.info("截图已保存: {}/{}", ocScreenshotRoot, origName);
                    } catch (Exception e) {
                        log.warn("截图保存失败: {}", e.getMessage());
                    }
                }
            }
            return JsonResult.successful(Map.of("urls", urls));
        } catch (Exception e) {
            log.error("截图上传处理失败: task_id={}", taskId, e);
            return JsonResult.failure("处理失败：" + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // 个税申报截图上传 + 回调
    // ─────────────────────────────────────────────────────────────────────

    /**
     * POST /oc/upload/iit-screenshot
     * OpenClaw 完成个税申报后，上传截图文件并回写申报结果，同时更新任务状态。
     * 支持多文件上传，单个文件不超过 10MB。
     * <p>
     * 参数（multipart/form-data）：
     * <ul>
     *   <li>{@code screenshots}    - 截图文件（PNG/JPG），支持多个，必填</li>
     *   <li>{@code task_id}        - OcTask.id，必填</li>
     *   <li>{@code account_set_id} - 账套 ID，必填</li>
     *   <li>{@code period}         - 账期 YYYY-MM，必填</li>
     *   <li>{@code status}         - done / failed / partial_failed，必填</li>
     *   <li>{@code iit_type}       - salary（代扣代缴）/ business（经营所得），默认 salary</li>
     *   <li>{@code error_msg}      - 失败原因（可选）</li>
     * </ul>
     */
    @PostMapping("/oc/upload/iit-screenshot")
    public JsonResult ocUploadIitScreenshot(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam("screenshots") List<MultipartFile> screenshots,
            @RequestParam("task_id") Long taskId,
            @RequestParam("account_set_id") Long accountSetId,
            @RequestParam("period") String period,
            @RequestParam("status") String status,
            @RequestParam(value = "iit_type", defaultValue = "salary") String iitType,
            @RequestParam(value = "error_msg", required = false) String errorMsg) {
        if (!checkToken(authorization)) {
            return JsonResult.failure("Token 验证失败", 401);
        }
        if (screenshots == null || screenshots.isEmpty() || screenshots.stream().allMatch(MultipartFile::isEmpty)) {
            return JsonResult.failure("未收到截图文件");
        }
        try {
            log.info("收到OC个税截图上传: task_id={}, accountSetId={}, iit_type={}, status={}, 文件数={}",
                    taskId, accountSetId, iitType, status, screenshots.size());
            Map<String, Object> result = taskWorkbenchService.ocUploadIitScreenshots(
                    screenshots, taskId, accountSetId, period, status, iitType, errorMsg);
            boolean ok = Boolean.TRUE.equals(result.get("success"));
            return ok ? JsonResult.successful(result) : JsonResult.failure((String) result.get("msg"));
        } catch (Exception e) {
            log.error("OC个税截图处理失败: task_id={}", taskId, e);
            return JsonResult.failure("处理失败：" + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // OC 运行日志
    // ─────────────────────────────────────────────────────────────────────

    /**
     * POST /oc/operation-log
     * OC 每次执行操作后调用，记录运行日志
     */
    @PostMapping("/oc/operation-log")
    public JsonResult addOperationLog(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody Map<String, Object> body) {
        if (!checkToken(authorization)) {
            return JsonResult.failure("Token 验证失败", 401);
        }
        String province = (String) body.get("province");
        String loginCredential = (String) body.get("login_credential");
        String operationType = (String) body.get("operation_type");
        if (StrUtil.isBlank(province) || StrUtil.isBlank(loginCredential) || StrUtil.isBlank(operationType)) {
            return JsonResult.failure("province、login_credential、operation_type 不能为空");
        }
        OcOperationLog record = new OcOperationLog();
        record.setProvince(province);
        record.setLoginCredential(loginCredential);
        record.setOperationType(operationType);
        ocOperationLogRepository.save(record);
        return JsonResult.successful();
    }

    /**
     * GET /oc/operation-log/excluded-provinces?login_credential=xxx&max_count=10&time_window=60
     * 查询需要排除的省份：loginCredential 相同、省份相同，从最后一次操作时间往前推 timeWindow 分钟内的记录数 >= maxCount
     */
    @GetMapping("/oc/operation-log/excluded-provinces")
    public JsonResult getExcludedProvinces(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam("login_credential") String loginCredential,
            @RequestParam("max_count") int maxCount,
            @RequestParam("time_window") int timeWindow) {
        if (!checkToken(authorization)) {
            return JsonResult.failure("Token 验证失败", 401);
        }
        List<String> excluded = ocOperationLogRepository.findExcludedProvinces(loginCredential, maxCount, timeWindow);
        return JsonResult.successful(excluded);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Token 校验
    // ─────────────────────────────────────────────────────────────────────

    /** OC 传入的英文省份名 → 系统中文省份名映射 */
    private static final java.util.Map<String, String> PROVINCE_EN_TO_ZH = java.util.Map.ofEntries(
            java.util.Map.entry("beijing", "北京"),
            java.util.Map.entry("tianjin", "天津"),
            java.util.Map.entry("hebei", "河北"),
            java.util.Map.entry("shanxi", "山西"),
            java.util.Map.entry("neimenggu", "内蒙古"),
            java.util.Map.entry("liaoning", "辽宁"),
            java.util.Map.entry("jilin", "吉林"),
            java.util.Map.entry("heilongjiang", "黑龙江"),
            java.util.Map.entry("shanghai", "上海"),
            java.util.Map.entry("jiangsu", "江苏"),
            java.util.Map.entry("zhejiang", "浙江"),
            java.util.Map.entry("anhui", "安徽"),
            java.util.Map.entry("fujian", "福建"),
            java.util.Map.entry("jiangxi", "江西"),
            java.util.Map.entry("shandong", "山东"),
            java.util.Map.entry("henan", "河南"),
            java.util.Map.entry("hubei", "湖北"),
            java.util.Map.entry("hunan", "湖南"),
            java.util.Map.entry("guangdong", "广东"),
            java.util.Map.entry("guangxi", "广西"),
            java.util.Map.entry("hainan", "海南"),
            java.util.Map.entry("chongqing", "重庆"),
            java.util.Map.entry("sichuan", "四川"),
            java.util.Map.entry("guizhou", "贵州"),
            java.util.Map.entry("yunnan", "云南"),
            java.util.Map.entry("xizang", "西藏"),
            java.util.Map.entry("shaanxi", "陕西"),
            java.util.Map.entry("gansu", "甘肃"),
            java.util.Map.entry("qinghai", "青海"),
            java.util.Map.entry("ningxia", "宁夏"),
            java.util.Map.entry("xinjiang", "新疆")
    );

    private boolean checkToken(String authorization) {
        // 若未配置 token，跳过校验（开发环境）
        if (StrUtil.isBlank(ocCallbackToken)) {
            return true;
        }
        if (StrUtil.isBlank(authorization)) return false;
        String token = authorization.startsWith("Bearer ") ? authorization.substring(7) : authorization;
        return ocCallbackToken.equals(token);
    }
}
