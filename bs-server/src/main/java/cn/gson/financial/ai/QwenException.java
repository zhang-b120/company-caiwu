package cn.gson.financial.ai;

/**
 * FN-AI-T1（185）— Qwen 调用可识别异常。
 *
 * <p>{@link QwenClient} 在以下场景抛出，供上层（{@code InvoiceSuggestService}）捕获后<b>降级</b>
 * （返 {@code degraded:true} 不阻断开票），而非裸抛 500：
 * <ul>
 *   <li>api-key 未配置（环境变量缺失）</li>
 *   <li>HTTP 超时 / 网络错误 / 非 2xx</li>
 *   <li>响应不是合法 JSON / 缺少 choices</li>
 * </ul>
 */
public class QwenException extends RuntimeException {

    public QwenException(String message) {
        super(message);
    }

    public QwenException(String message, Throwable cause) {
        super(message, cause);
    }
}
