package cn.gson.financial.kernel.event;

import cn.gson.financial.kernel.model.entity.InvoiceTask;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * FN-05 开票任务完成事件
 * <p>
 * 仅在 {@link cn.gson.financial.kernel.service.InvoiceTaskService#complete} 把
 * status 推到 SUCCESS / FAILED / RED_PENDING 三种终态时 publishEvent。
 * <p>
 * 异步监听者：
 *   - wx 推送（SUCCESS 时把发票 PDF 推给客户；FAILED 时通知客户重提）
 *   - 企微推送（SUCCESS/FAILED 通知销售）
 *   - finmaster 工单（RED_PENDING / FAILED 时建工单让客服跟进）
 */
@Getter
public class InvoiceTaskCompletedEvent extends ApplicationEvent {

    private final Long taskId;
    private final Long accountSetsId;
    private final Long customerId;
    private final boolean isRedInvoice;
    private final Long originalInvoiceId;

    /** 终态：SUCCESS / FAILED / RED_PENDING（业务约束保证只这 3 种） */
    private final String finalStatus;

    /** SUCCESS 时回填 */
    private final String invoiceCode;
    private final String invoiceNumber;

    /** FAILED 时回填 */
    private final String errorMsg;

    /** RED_PENDING 时回填 */
    private final String redPendingReason;

    /**
     * 开票成功后的 PDF URL（FN-FIX-01）。SUCCESS 时通常非空，WX 拿这个字段下载 PDF 推客户。
     * FAILED / RED_PENDING 时一般为 null。
     */
    private final String pdfUrl;

    /** FN-T4：申请人联系方式（监听器可直接用，避免回查库）*/
    private final String applicantPhone;
    private final String applicantEmail;
    private final String applicantName;

    public InvoiceTaskCompletedEvent(InvoiceTask task) {
        super(task.getId());
        this.taskId = task.getId();
        this.accountSetsId = task.getAccountSetsId();
        this.customerId = task.getCustomerId();
        this.isRedInvoice = Boolean.TRUE.equals(task.getIsRedInvoice());
        this.originalInvoiceId = task.getOriginalInvoiceId();
        this.finalStatus = task.getStatus();
        this.invoiceCode = task.getInvoiceCode();
        this.invoiceNumber = task.getInvoiceNumber();
        this.errorMsg = task.getErrorMsg();
        this.redPendingReason = task.getRedPendingReason();
        this.pdfUrl = task.getPdfUrl();
        this.applicantPhone = task.getApplicantPhone();
        this.applicantEmail = task.getApplicantEmail();
        this.applicantName = task.getApplicantName();
    }

    public boolean isSuccess() {
        return InvoiceTask.STATUS_SUCCESS.equals(finalStatus);
    }

    public boolean isFailed() {
        return InvoiceTask.STATUS_FAILED.equals(finalStatus);
    }

    public boolean isRedPending() {
        return InvoiceTask.STATUS_RED_PENDING.equals(finalStatus);
    }
}
