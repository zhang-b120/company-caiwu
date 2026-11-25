package cn.gson.financial.kernel.exception;

/**
 * FN-T4 申请人通知发送失败异常。
 * <p>
 * 由 {@link cn.gson.financial.kernel.service.InvoiceNotificationService#notifyApplicant} 抛出，
 * 调用方决定是否进 DLQ 或回 HTTP 4xx。
 * <p>
 * 短信失败原因 + 邮件失败原因都会拼到 message 里（最大努力都试，全失败再抛）。
 */
public class NotificationException extends RuntimeException {
    public NotificationException(String msg) {
        super(msg);
    }

    public NotificationException(String msg, Throwable cause) {
        super(msg, cause);
    }
}
