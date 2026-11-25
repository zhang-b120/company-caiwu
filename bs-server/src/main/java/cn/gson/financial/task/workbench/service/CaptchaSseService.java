package cn.gson.financial.task.workbench.service;

import cn.gson.financial.kernel.model.repo.OcCaptchaRequestRepository;
import cn.gson.financial.kernel.model.repo.UserAccountSetsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 验证码 SSE 推送服务
 * <p>
 * 前端通过 EventSource 订阅，当 OC 推送新验证码时实时通知前端弹窗，
 * 替代原来每 5 秒一次的轮询。
 * <p>
 * 只通知拥有对应账套权限的用户。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CaptchaSseService {

    private final OcCaptchaRequestRepository captchaRepo;
    private final UserAccountSetsRepository userAccountSetsRepo;

    /** emitter -> userId 映射，用于按用户权限过滤推送 */
    private final Map<SseEmitter, Long> emitterUserMap = new ConcurrentHashMap<>();

    /**
     * 前端订阅验证码事件流，绑定当前用户 ID
     */
    public SseEmitter subscribe(Long userId) {
        SseEmitter emitter = new SseEmitter(3600_000L); // 1 小时超时，前端自动重连
        emitterUserMap.put(emitter, userId);
        emitter.onCompletion(() -> emitterUserMap.remove(emitter));
        emitter.onTimeout(() -> emitterUserMap.remove(emitter));
        emitter.onError(e -> emitterUserMap.remove(emitter));
        return emitter;
    }

    /**
     * OC 推送新验证码时调用，只通知拥有该账套权限的用户
     */
    public void notifyNewCaptcha(Long accountSetId) {
        String data = "{\"accountSetId\":" + accountSetId + "}";
        for (Map.Entry<SseEmitter, Long> entry : emitterUserMap.entrySet()) {
            SseEmitter emitter = entry.getKey();
            Long userId = entry.getValue();
            try {
                List<Long> userAstIds = userAccountSetsRepo.findAccountSetIdsByUserId(userId);
                if (userAstIds.contains(accountSetId)) {
                    emitter.send(SseEmitter.event().name("captcha_new").data(data));
                }
            } catch (Exception e) {
                emitterUserMap.remove(emitter);
            }
        }
    }

    /**
     * 每 30 秒发送心跳，防止代理/网关断开空闲连接
     */
    @Scheduled(fixedRate = 30_000)
    public void heartbeat() {
        for (SseEmitter emitter : emitterUserMap.keySet()) {
            try {
                emitter.send(SseEmitter.event().comment("heartbeat"));
            } catch (Exception e) {
                emitterUserMap.remove(emitter);
            }
        }
    }

    /**
     * 每分钟清理过期的 pending 验证码记录
     */
    @Scheduled(fixedRate = 60_000)
    @Transactional
    public void expireOverdue() {
        int count = captchaRepo.expireOverdue(LocalDateTime.now());
        if (count > 0) {
            log.info("已清理 {} 条过期验证码记录", count);
        }
    }
}
