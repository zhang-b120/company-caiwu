package cn.gson.financial.kernel.service;

import cn.gson.financial.kernel.exception.EmailSendException;
import cn.gson.financial.kernel.exception.NotificationException;
import cn.gson.financial.kernel.model.entity.InvoiceTask;
import cn.gson.financial.kernel.model.repo.InvoiceTaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * FN-T4 申请人通知服务 — /notify HTTP 端点和 TaskNotificationListener 共用同一份代码。
 * <p>
 * 业务规则：
 * <ul>
 *   <li>applicant_phone 非空 → 调 {@link SmsService#sendInvoiceDelivered}</li>
 *   <li>applicant_email 非空 → 调 {@link EmailService#sendInvoiceDelivered}</li>
 *   <li>任一通道失败：记录失败原因继续尝试另一通道，全部失败再抛 {@link NotificationException}</li>
 *   <li>两个通道都空：不视为失败，直接返回（无人需要通知）</li>
 * </ul>
 * <p>
 * 幂等通过 {@link #markNotified(Long)} + {@code invoice_task.notified_at} 实现，
 * 由调用方（FN-T5 /notify 端点）在 notify 之前 check、之后 mark。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InvoiceNotificationService {

    private final SmsService smsService;
    private final EmailService emailService;
    private final InvoiceTaskRepository invoiceTaskRepository;

    /**
     * 给申请人发短信 + 邮件。
     * <p>
     * 注意：本方法<b>不做幂等</b>（不 check notified_at），由调用方决定。
     * 也<b>不要求</b> task.status==SUCCESS（FAILED 拒单同样可能需要通知，
     * 状态校验留给调用方）。
     *
     * @throws NotificationException 全部通道都失败时
     * @throws IllegalArgumentException task 不存在
     */
    public void notifyApplicant(Long taskId) {
        InvoiceTask task = invoiceTaskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("invoice_task 不存在：" + taskId));

        String phone = trim(task.getApplicantPhone());
        String email = trim(task.getApplicantEmail());
        String name = trim(task.getApplicantName());
        String invoiceNumber = task.getInvoiceNumber();
        String pdfUrl = task.getPdfUrl();

        if (phone == null && email == null) {
            log.info("[FN-T4] task={} 申请人手机+邮箱都为空，跳过通知", taskId);
            return;
        }

        List<String> errors = new ArrayList<>();
        int succeeded = 0;
        int attempted = 0;

        if (phone != null) {
            attempted++;
            try {
                if (invoiceNumber == null || invoiceNumber.isBlank()) {
                    throw new IllegalStateException("invoiceNumber 为空，无法发短信");
                }
                smsService.sendInvoiceDelivered(phone, invoiceNumber);
                succeeded++;
                log.info("[FN-T4] task={} 短信已发：phone={}", taskId, phone);
            } catch (Exception ex) {
                log.warn("[FN-T4] task={} 短信发送失败：phone={}, err={}", taskId, phone, ex.getMessage());
                errors.add("短信失败：" + ex.getMessage());
            }
        }

        if (email != null) {
            attempted++;
            try {
                emailService.sendInvoiceDelivered(email, name, invoiceNumber, pdfUrl);
                succeeded++;
                log.info("[FN-T4] task={} 邮件已发：email={}", taskId, email);
            } catch (EmailSendException ex) {
                log.warn("[FN-T4] task={} 邮件发送失败：email={}, err={}", taskId, email, ex.getMessage());
                errors.add("邮件失败：" + ex.getMessage());
            } catch (Exception ex) {
                log.warn("[FN-T4] task={} 邮件发送异常：email={}, err={}", taskId, email, ex.getMessage());
                errors.add("邮件异常：" + ex.getMessage());
            }
        }

        if (succeeded == 0 && attempted > 0) {
            throw new NotificationException("task=" + taskId + " 通知发送全部失败：" + String.join("；", errors));
        }
        if (!errors.isEmpty()) {
            log.warn("[FN-T4] task={} 通知部分成功 ({}/{})：{}", taskId, succeeded, attempted,
                    String.join("；", errors));
        }
    }

    /**
     * FN6-T1 给申请人发"开票被驳回"通知。
     * <p>
     * 与 {@link #notifyApplicant(Long)} 同结构（短信 + 邮件并发，部分成功不抛、全失败抛
     * {@link NotificationException}），但<b>不走 notified_at 幂等</b> —— 驳回是事件性的，
     * 每次驳回都该独立发一次；重复发由调用方（invoice-print 端 IP6-T2 customer-decision
     * 端点只在 status:REQUESTED→REJECT 成功更新后调一次）控制。
     *
     * @param taskId  开票任务 id
     * @param comment 驳回原因（可空，空时模板里用占位）
     */
    public void notifyRejected(Long taskId, String comment) {
        InvoiceTask task = invoiceTaskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("invoice_task 不存在：" + taskId));

        String phone = trim(task.getApplicantPhone());
        String email = trim(task.getApplicantEmail());
        String name = trim(task.getApplicantName());

        if (phone == null && email == null) {
            log.info("[FN6-T1] task={} 申请人手机+邮箱都为空，跳过驳回通知", taskId);
            return;
        }

        List<String> errors = new ArrayList<>();
        int succeeded = 0;
        int attempted = 0;

        if (phone != null) {
            attempted++;
            try {
                smsService.sendInvoiceRejected(phone, comment);
                succeeded++;
                log.info("[FN6-T1] task={} 驳回短信已发：phone={}", taskId, phone);
            } catch (Exception ex) {
                log.warn("[FN6-T1] task={} 驳回短信发送失败：phone={}, err={}",
                        taskId, phone, ex.getMessage());
                errors.add("短信失败：" + ex.getMessage());
            }
        }

        if (email != null) {
            attempted++;
            try {
                emailService.sendInvoiceRejected(email, name, comment);
                succeeded++;
                log.info("[FN6-T1] task={} 驳回邮件已发：email={}", taskId, email);
            } catch (EmailSendException ex) {
                log.warn("[FN6-T1] task={} 驳回邮件发送失败：email={}, err={}",
                        taskId, email, ex.getMessage());
                errors.add("邮件失败：" + ex.getMessage());
            } catch (Exception ex) {
                log.warn("[FN6-T1] task={} 驳回邮件发送异常：email={}, err={}",
                        taskId, email, ex.getMessage());
                errors.add("邮件异常：" + ex.getMessage());
            }
        }

        if (succeeded == 0 && attempted > 0) {
            throw new NotificationException("task=" + taskId
                    + " 驳回通知发送全部失败：" + String.join("；", errors));
        }
        if (!errors.isEmpty()) {
            log.warn("[FN6-T1] task={} 驳回通知部分成功 ({}/{})：{}",
                    taskId, succeeded, attempted, String.join("；", errors));
        }
    }

    /**
     * FN8-T2 给申请人发"发票已红冲"通知。
     * <p>
     * 与 {@link #notifyRejected} 同结构 —— <b>不走 notified_at 幂等</b>，红冲是事件性的。
     * 取原发票号码（{@code invoice_number}）和红字号码（{@code red_invoice_number}）+ {@code red_pdf_url}。
     *
     * @param taskId 红冲后产生的红字任务 id（含 red_invoice_number / red_pdf_url）
     */
    public void notifyRedLetterIssued(Long taskId) {
        InvoiceTask task = invoiceTaskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("invoice_task 不存在：" + taskId));

        String phone = trim(task.getApplicantPhone());
        String email = trim(task.getApplicantEmail());
        String name = trim(task.getApplicantName());
        String originalInvoiceNumber = task.getInvoiceNumber();
        String redInvoiceNumber = task.getRedInvoiceNumber();
        String redPdfUrl = task.getRedPdfUrl();

        if (phone == null && email == null) {
            log.info("[FN8-T2] task={} 申请人手机+邮箱都为空，跳过红冲通知", taskId);
            return;
        }

        List<String> errors = new ArrayList<>();
        int succeeded = 0;
        int attempted = 0;

        if (phone != null) {
            attempted++;
            try {
                smsService.sendRedLetterIssued(phone, originalInvoiceNumber, redInvoiceNumber);
                succeeded++;
                log.info("[FN8-T2] task={} 红冲短信已发：phone={}", taskId, phone);
            } catch (Exception ex) {
                log.warn("[FN8-T2] task={} 红冲短信发送失败：phone={}, err={}",
                        taskId, phone, ex.getMessage());
                errors.add("短信失败：" + ex.getMessage());
            }
        }

        if (email != null) {
            attempted++;
            try {
                emailService.sendRedLetterIssued(email, name, originalInvoiceNumber, redInvoiceNumber, redPdfUrl);
                succeeded++;
                log.info("[FN8-T2] task={} 红冲邮件已发：email={}", taskId, email);
            } catch (EmailSendException ex) {
                log.warn("[FN8-T2] task={} 红冲邮件发送失败：email={}, err={}",
                        taskId, email, ex.getMessage());
                errors.add("邮件失败：" + ex.getMessage());
            } catch (Exception ex) {
                log.warn("[FN8-T2] task={} 红冲邮件发送异常：email={}, err={}",
                        taskId, email, ex.getMessage());
                errors.add("邮件异常：" + ex.getMessage());
            }
        }

        if (succeeded == 0 && attempted > 0) {
            throw new NotificationException("task=" + taskId
                    + " 红冲通知发送全部失败：" + String.join("；", errors));
        }
        if (!errors.isEmpty()) {
            log.warn("[FN8-T2] task={} 红冲通知部分成功 ({}/{})：{}",
                    taskId, succeeded, attempted, String.join("；", errors));
        }
    }

    /**
     * 标记任务已通知（设 notified_at = now）。幂等：再次 mark 会覆盖时间戳。
     */
    @Transactional
    public void markNotified(Long taskId) {
        InvoiceTask t = invoiceTaskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("invoice_task 不存在：" + taskId));
        t.setNotifiedAt(LocalDateTime.now());
        invoiceTaskRepository.save(t);
    }

    private static String trim(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
