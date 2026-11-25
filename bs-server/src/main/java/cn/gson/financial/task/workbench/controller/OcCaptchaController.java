package cn.gson.financial.task.workbench.controller;

import cn.dev33.satoken.annotation.SaIgnore;
import cn.dev33.satoken.stp.StpUtil;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.model.entity.OcCaptchaRequest;
import cn.gson.financial.kernel.model.repo.OcCaptchaRequestRepository;
import cn.gson.financial.kernel.model.repo.UserAccountSetsRepository;
import cn.gson.financial.kernel.model.vo.UserVo;
import cn.gson.financial.task.workbench.service.CaptchaSseService;
import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static cn.gson.financial.kernel.common.Constants.S_USER_KEY;

/**
 * OpenClaw 验证码 / 二维码交互接口
 *
 * <p>登录流程中，OC 遇到图形验证码、短信验证码或二维码时调用此接口，
 * 由财务人员在弹窗中填写/扫码后，OC 通过轮询拿到结果并继续登录。</p>
 *
 * <pre>
 * OC 推送请求    → POST /oc/captcha/push         （返回 captcha_id）
 * OC 轮询结果    → GET  /oc/captcha/result/{id}
 * 前端 SSE 订阅  → GET  /task-workbench/captcha/subscribe （实时推送 captcha_new 事件）
 * 前端获取待填写  → GET  /task-workbench/captcha/pending   （收到 SSE 事件后调用）
 * 前端提交答案   → POST /task-workbench/captcha/submit/{id}
 * </pre>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class OcCaptchaController {

    private final OcCaptchaRequestRepository captchaRepo;
    private final cn.gson.financial.kernel.tool.RedisUtil redisUtil;
    private final CaptchaSseService captchaSseService;
    private final UserAccountSetsRepository userAccountSetsRepo;

    @Value("${oc.callback.token:}")
    private String ocCallbackToken;

    // ─────────────────────────────────────────────────────────────────────
    // OC 侧接口（需要 Token，跳过 SaToken 鉴权）
    // ─────────────────────────────────────────────────────────────────────

    /**
     * POST /oc/captcha/push
     * OC 遇到验证码/二维码时推送到财务系统，由财务人员填写。
     *
     * <p>请求体（application/json）：
     * <pre>
     * {
     *   "task_id":        1001,          // 关联的 oc_task.id（可选）
     *   "account_set_id": 456,           // 账套 ID（必填，前端展示公司名用）
     *   "type":           "captcha",     // captcha / sms / qrcode
     *   "image_data":     "data:image/png;base64,...",  // 验证码图片或二维码图片（Base64）
     *   "message":        "请输入图形验证码"  // 提示文字（可选）
     * }
     * </pre>
     *
     * <p>响应：
     * <pre>
     * { "code": 200, "data": { "captcha_id": 88 } }
     * </pre>
     */
    @SaIgnore
    @PostMapping("/oc/captcha/push")
    public JsonResult push(@RequestBody Map<String, Object> body) {
        Long accountSetId = body.get("account_set_id") != null
                ? Long.valueOf(body.get("account_set_id").toString()) : null;
        if (accountSetId == null) {
            return JsonResult.failure("account_set_id 必填");
        }
        String type = (String) body.getOrDefault("type", "captcha");
        if (!List.of("captcha", "sms", "qrcode").contains(type)) {
            return JsonResult.failure("type 只能为 captcha / sms / qrcode");
        }

        OcCaptchaRequest req = new OcCaptchaRequest();
        req.setAccountSetId(accountSetId);
        req.setTaskId(body.get("task_id") != null ? Long.valueOf(body.get("task_id").toString()) : null);
        req.setType(type);
        req.setImageData((String) body.get("image_data"));
        req.setMessage((String) body.get("message"));
        req.setProvince((String) body.get("province"));
        req.setProxyUsername((String) body.get("proxy_username"));
        req.setStatus("pending");
        req.setExpiresAt(LocalDateTime.now().plusMinutes(30));
        captchaRepo.save(req);

        log.info("OC 推送验证码请求: captcha_id={}, accountSetId={}, type={}, province={}, proxyUsername={}",
                req.getId(), accountSetId, type, req.getProvince(), req.getProxyUsername());

        // SSE 实时通知有权限的前端用户
        captchaSseService.notifyNewCaptcha(accountSetId);

        return JsonResult.successful(Map.of("captcha_id", req.getId()));
    }

    /**
     * GET /oc/captcha/result/{id}
     * OC 轮询此接口等待财务人员填写结果（建议每 3 秒轮询一次）。
     *
     * <p>响应（status=pending 时继续等待，submitted 时读取 code，expired 时重新推送）：
     * <pre>
     * { "code": 200, "data": { "status": "submitted", "code": "8k3z" } }
     * { "code": 200, "data": { "status": "pending" } }
     * { "code": 200, "data": { "status": "expired" } }
     * </pre>
     */
    @SaIgnore
    @GetMapping("/oc/captcha/result/{id}")
    @Transactional
    public JsonResult result(@PathVariable Long id) {
        OcCaptchaRequest req = captchaRepo.findById(id).orElse(null);
        if (req == null) {
            return JsonResult.failure("captcha_id 不存在");
        }
        // 检查是否已超时
        if ("pending".equals(req.getStatus()) && LocalDateTime.now().isAfter(req.getExpiresAt())) {
            req.setStatus("expired");
            captchaRepo.save(req);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", req.getStatus());
        if ("submitted".equals(req.getStatus())) {
            data.put("code", req.getCode());
        }
        return JsonResult.successful(data);
    }

    // ─────────────────────────────────────────────────────────────────────
    // 前端侧接口（需要系统登录鉴权）
    // ─────────────────────────────────────────────────────────────────────

    /**
     * GET /task-workbench/captcha/subscribe
     * 前端通过 EventSource 订阅此 SSE 端点，有新验证码时实时收到 captcha_new 事件。
     * 只推送当前用户有权限的账套的验证码请求。
     */
    @GetMapping(value = "/task-workbench/captcha/subscribe", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribe() {
        UserVo user = StpUtil.getSession().getModel(S_USER_KEY, UserVo.class);
        return captchaSseService.subscribe(user.getId());
    }

    /**
     * GET /task-workbench/captcha/pending
     * 只返回当前用户有权限的账套的待填写验证码请求。
     */
    @GetMapping("/task-workbench/captcha/pending")
    public JsonResult pending() {
        UserVo user = StpUtil.getSession().getModel(S_USER_KEY, UserVo.class);
        List<Long> myAstIds = userAccountSetsRepo.findAccountSetIdsByUserId(user.getId());

        List<Map<String, Object>> list = captchaRepo.findByStatusOrderByCreatedAtAsc("pending")
                .stream()
                .filter(r -> myAstIds.contains(r.getAccountSetId()))
                .map(r -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", r.getId());
                    m.put("accountSetId", r.getAccountSetId());
                    m.put("taskId", r.getTaskId());
                    m.put("type", r.getType());
                    m.put("imageData", r.getImageData());
                    m.put("message", r.getMessage());
                    m.put("province", r.getProvince());
                    m.put("proxyUsername", r.getProxyUsername());
                    m.put("createdAt", r.getCreatedAt());
                    m.put("expiresAt", r.getExpiresAt());
                    return m;
                })
                .collect(Collectors.toList());
        return JsonResult.successful(list);
    }

    /**
     * POST /task-workbench/captcha/submit/{id}
     * 财务人员填写完验证码后，前端调用此接口提交。
     * 二维码场景（type=qrcode）用户扫码后点"已完成扫码"，code 传空字符串即可。
     *
     * <p>请求体：
     * <pre>
     * { "code": "8k3z" }
     * </pre>
     */
    @PostMapping("/task-workbench/captcha/submit/{id}")
    @Transactional
    public JsonResult submit(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body) {
        OcCaptchaRequest req = captchaRepo.findById(id).orElse(null);
        if (req == null) {
            return JsonResult.failure("请求不存在");
        }
        if ("expired".equals(req.getStatus())) {
            return JsonResult.failure("验证码已超时，请让 OpenClaw 重新获取");
        }
        if ("submitted".equals(req.getStatus())) {
            return JsonResult.failure("已提交，请勿重复操作");
        }
        String code = body.get("code") != null ? body.get("code").toString() : "";
        req.setCode(code);
        req.setStatus("submitted");
        req.setSubmittedAt(LocalDateTime.now());
        captchaRepo.save(req);

        // 代理通道短信验证码：同步存入 Redis，供 OC 通过 /oc/proxy/smsCode 获取
        if ("sms".equals(req.getType()) && StrUtil.isNotBlank(req.getProxyUsername()) && StrUtil.isNotBlank(code)) {
            redisUtil.set("PROXY_" + req.getProxyUsername(), code, 300);
            log.info("代理通道验证码已存入Redis: proxyUsername={}", req.getProxyUsername());
        }

        log.info("财务人员提交验证码: captcha_id={}, type={}", id, req.getType());
        return JsonResult.successful();
    }

    // ─────────────────────────────────────────────────────────────────────

    private boolean checkToken(String authorization) {
        if (StrUtil.isBlank(ocCallbackToken)) return true;
        if (StrUtil.isBlank(authorization)) return false;
        String token = authorization.startsWith("Bearer ") ? authorization.substring(7) : authorization;
        return ocCallbackToken.equals(token);
    }
}
