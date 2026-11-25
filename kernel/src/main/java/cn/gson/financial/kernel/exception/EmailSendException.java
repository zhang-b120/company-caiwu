package cn.gson.financial.kernel.exception;

/**
 * FN-T3 邮件发送失败异常。
 * <p>
 * 由 {@link cn.gson.financial.kernel.service.EmailService} 抛出。
 * 上层（listener / notify 端点）决定进 DLQ 还是回 HTTP 400。
 */
public class EmailSendException extends RuntimeException {
    public EmailSendException(String msg, Throwable cause) {
        super(msg, cause);
    }

    public EmailSendException(String msg) {
        super(msg);
    }
}
