package cn.gson.financial.task.workbench.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 期初导入实时进度推送（SSE）
 * <p>
 * 使用方式：
 *   前端先订阅 GET /task-workbench/import-init/progress?accountSetId=xxx
 *   再触发   POST /task-workbench/import-init
 *   前端通过 EventSource 接收实时日志
 */
@Slf4j
@Service
public class ImportProgressService {

    /** accountSetId → SSE emitter */
    private final ConcurrentHashMap<Long, SseEmitter> emitters = new ConcurrentHashMap<>();

    /** accountSetId → 已产生的日志（用于 SSE 建立稍晚时回放） */
    private final ConcurrentHashMap<Long, List<String>> buffer = new ConcurrentHashMap<>();

    /**
     * 前端订阅进度流，返回 SseEmitter。
     * 如果订阅时已有缓存日志，立即回放给客户端。
     */
    public SseEmitter subscribe(Long accountSetId) {
        SseEmitter emitter = new SseEmitter(600_000L); // 10 分钟超时
        emitters.put(accountSetId, emitter);
        emitter.onCompletion(() -> emitters.remove(accountSetId));
        emitter.onTimeout(()   -> emitters.remove(accountSetId));
        emitter.onError(e      -> emitters.remove(accountSetId));

        // 回放已有日志（防止 SSE 连接比任务启动稍晚）
        List<String> existing = buffer.get(accountSetId);
        if (existing != null) {
            for (String msg : existing) {
                send(emitter, "log", msg);
            }
        }
        return emitter;
    }

    /** 任务开始时调用，清空旧缓存 */
    public void start(Long accountSetId) {
        buffer.put(accountSetId, new CopyOnWriteArrayList<>());
    }

    /** 推送一条进度日志 */
    public void log(Long accountSetId, String message) {
        buffer.computeIfAbsent(accountSetId, k -> new CopyOnWriteArrayList<>()).add(message);
        SseEmitter emitter = emitters.get(accountSetId);
        if (emitter != null) {
            send(emitter, "log", message);
        }
    }

    /** 任务结束时调用，推送完成事件并关闭连接 */
    public void complete(Long accountSetId, boolean success, String message) {
        SseEmitter emitter = emitters.get(accountSetId);
        if (emitter != null) {
            send(emitter, success ? "done" : "error", message);
            try { emitter.complete(); } catch (Exception ignored) {}
            emitters.remove(accountSetId);
        }
        buffer.remove(accountSetId);
    }

    private void send(SseEmitter emitter, String eventName, String data) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(data));
        } catch (Exception e) {
            log.debug("SSE 发送失败（客户端可能已断开）: {}", e.getMessage());
        }
    }
}
