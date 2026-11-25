package cn.gson.financial.controller.openapi;

import cn.dev33.satoken.annotation.SaIgnore;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.util.*;

/**
 * FN-FLOW-SCREENSHOT-ENDPOINT：报税（flow）截图上传。前缀 {@code /openapi/v1/flow}，@SaIgnore + 内部 token。
 *
 * <p><b>上传</b> {@code POST /openapi/v1/flow/declare-screenshot}（multipart：accountSetsId / period / screenshots[]）
 * → 存盘到与核验/vat/iit 截图相同的目录（{@code oc.screenshot-root}）→ 返相对 URL 列表 {@code /oc-screenshots/{name}}。
 *
 * <p><b>读取</b>：直接复用现成 <b>公开</b> 代理 {@code GET /oc-screenshots/**}（{@code UploadController}，无 @SaCheckLogin，
 * 注解式鉴权下即公开；image/* 与 application/pdf 走 inline content-type，renderer &lt;img&gt; + PDF 可直接加载）——
 * 与核验截图 {@code screenshotUrls} <b>同口径同目录</b>，无需另起 GET 端点。BT 把返回的相对 URL 写进
 * {@code flow_declare_item.screenshot_urls}，renderer 拼 FN baseUrl 即可加载。
 */
@Slf4j
@RestController
@RequestMapping("/openapi/v1/flow")
@SaIgnore
public class OpenApiFlowScreenshotController {

    /** 截图存储目录（与 OC/核验/vat/iit 截图共用，由 /oc-screenshots/** 公开代理喂出）。 */
    @Value("${oc.screenshot-root:E:\\qykj\\file\\oc-screenshots}")
    private String screenshotRoot;

    /** 与上传方（BT）约定的内部 token；未配置则放行（开发环境，与 oc.callback.token / rpa.poll.token 同策略）。 */
    @Value("${flow.upload.token:}")
    private String flowUploadToken;

    /** 单文件上限（与 OC 截图一致 10MB，超大截图拒收，避免占满磁盘）。 */
    static final long MAX_FILE_BYTES = 10L * 1024 * 1024;

    @PostMapping(value = "/declare-screenshot", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public JsonResult uploadDeclareScreenshot(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(value = "accountSetsId", required = false) Long accountSetsId,
            @RequestParam(value = "period", required = false) String period,
            @RequestParam(value = "screenshots", required = false) List<MultipartFile> screenshots) {
        if (!checkToken(authorization)) {
            return JsonResult.failure("Token 验证失败", 401);
        }
        if (screenshots == null || screenshots.isEmpty()
                || screenshots.stream().allMatch(f -> f == null || f.isEmpty())) {
            return JsonResult.failure("screenshots 不能为空");
        }

        File dir = new File(screenshotRoot);
        if (!dir.exists() && !dir.mkdirs()) {
            log.error("[FN-FLOW-SCREENSHOT] 创建截图目录失败: {}", screenshotRoot);
            return JsonResult.failure("截图目录不可写：" + screenshotRoot);
        }

        // 文件名带账套/账期前缀便于溯源；唯一后缀防重名覆盖
        String prefix = "flow-" + (accountSetsId != null ? accountSetsId : "na")
                + "-" + (StrUtil.isBlank(period) ? "na" : period.trim().replace("/", "-"));

        List<String> urls = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (MultipartFile file : screenshots) {
            if (file == null || file.isEmpty()) continue;
            if (file.getSize() > MAX_FILE_BYTES) {
                skipped.add(StrUtil.nullToDefault(file.getOriginalFilename(), "?") + "（超过 10MB）");
                continue;
            }
            try {
                String orig = StrUtil.nullToDefault(file.getOriginalFilename(), "screenshot.png");
                String ext = orig.contains(".") ? orig.substring(orig.lastIndexOf('.') + 1) : "png";
                String savedName = prefix + "-" + UUID.randomUUID().toString().replace("-", "") + "." + ext;
                file.transferTo(new File(dir, savedName));
                // 返相对 URL（与核验截图同口径）；renderer 拼 FN baseUrl 加载
                urls.add("/oc-screenshots/" + savedName);
                log.info("[FN-FLOW-SCREENSHOT] 已存: accountSetsId={} period={} -> {}", accountSetsId, period, savedName);
            } catch (Exception e) {
                log.warn("[FN-FLOW-SCREENSHOT] 保存失败 orig={}: {}", file.getOriginalFilename(), e.getMessage());
                skipped.add(StrUtil.nullToDefault(file.getOriginalFilename(), "?") + "（保存失败）");
            }
        }

        if (urls.isEmpty()) {
            return JsonResult.failure("截图保存失败：" + (skipped.isEmpty() ? "无有效文件" : String.join("；", skipped)));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("urls", urls);          // 相对 URL 列表，写进 flow_declare_item.screenshot_urls
        data.put("count", urls.size());
        if (!skipped.isEmpty()) data.put("skipped", skipped);
        return JsonResult.successful(data);
    }

    private boolean checkToken(String authorization) {
        if (StrUtil.isBlank(flowUploadToken)) return true; // 未配置 → 放行（开发）
        if (StrUtil.isBlank(authorization)) return false;
        String token = authorization.startsWith("Bearer ") ? authorization.substring(7) : authorization;
        return flowUploadToken.equals(token);
    }
}
