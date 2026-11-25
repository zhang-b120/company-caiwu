package cn.gson.financial.kernel.service;

import cn.gson.financial.kernel.exception.EmailSendException;
import cn.gson.financial.kernel.exception.NotificationException;
import cn.gson.financial.kernel.model.entity.InvoiceTask;
import cn.gson.financial.kernel.model.repo.InvoiceTaskRepository;
import org.dromara.sms4j.api.entity.SmsResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * FN-T4 InvoiceNotificationService 单测：覆盖 applicant_phone / applicant_email
 * 各种空/非空组合下短信和邮件 service 的调用次数。
 */
class InvoiceNotificationServiceTest {

    private SmsService smsService;
    private EmailService emailService;
    private InvoiceTaskRepository repo;
    private InvoiceNotificationService svc;

    @BeforeEach
    void setUp() {
        smsService = mock(SmsService.class);
        emailService = mock(EmailService.class);
        repo = mock(InvoiceTaskRepository.class);
        svc = new InvoiceNotificationService(smsService, emailService, repo);

        when(smsService.sendInvoiceDelivered(anyString(), anyString())).thenReturn(new SmsResponse());
    }

    private InvoiceTask build(String phone, String email, String name) {
        InvoiceTask t = new InvoiceTask();
        t.setId(1234L);
        t.setStatus(InvoiceTask.STATUS_SUCCESS);
        t.setInvoiceNumber("12345678");
        t.setPdfUrl("https://x/y.pdf");
        t.setApplicantPhone(phone);
        t.setApplicantEmail(email);
        t.setApplicantName(name);
        return t;
    }

    @Test
    void notifyApplicant_phoneOnly_callsSmsNotEmail() {
        when(repo.findById(1234L)).thenReturn(Optional.of(build("13800138000", null, "张三")));

        svc.notifyApplicant(1234L);

        verify(smsService).sendInvoiceDelivered("13800138000", "12345678");
        verify(emailService, never()).sendInvoiceDelivered(any(), any(), any(), any());
    }

    @Test
    void notifyApplicant_emailOnly_callsEmailNotSms() {
        when(repo.findById(1234L)).thenReturn(Optional.of(build(null, "a@b.com", "张三")));

        svc.notifyApplicant(1234L);

        verify(smsService, never()).sendInvoiceDelivered(any(), any());
        verify(emailService).sendInvoiceDelivered("a@b.com", "张三", "12345678", "https://x/y.pdf");
    }

    @Test
    void notifyApplicant_bothEmpty_skipsSilently() {
        when(repo.findById(1234L)).thenReturn(Optional.of(build(null, null, null)));

        svc.notifyApplicant(1234L);

        verifyNoInteractions(smsService);
        verifyNoInteractions(emailService);
    }

    @Test
    void notifyApplicant_bothPresent_callsBoth() {
        when(repo.findById(1234L)).thenReturn(Optional.of(build("13800138000", "a@b.com", "张三")));

        svc.notifyApplicant(1234L);

        verify(smsService).sendInvoiceDelivered("13800138000", "12345678");
        verify(emailService).sendInvoiceDelivered("a@b.com", "张三", "12345678", "https://x/y.pdf");
    }

    @Test
    void notifyApplicant_emailFailButSmsOk_doesNotThrow() {
        when(repo.findById(1234L)).thenReturn(Optional.of(build("13800138000", "a@b.com", "张三")));
        doThrow(new EmailSendException("SMTP down")).when(emailService)
                .sendInvoiceDelivered(any(), any(), any(), any());

        // 部分成功不抛
        svc.notifyApplicant(1234L);

        verify(smsService).sendInvoiceDelivered("13800138000", "12345678");
        verify(emailService).sendInvoiceDelivered("a@b.com", "张三", "12345678", "https://x/y.pdf");
    }

    @Test
    void notifyApplicant_allFail_throwsNotificationException() {
        when(repo.findById(1234L)).thenReturn(Optional.of(build("13800138000", "a@b.com", "张三")));
        when(smsService.sendInvoiceDelivered(anyString(), anyString()))
                .thenThrow(new RuntimeException("sms fail"));
        doThrow(new EmailSendException("smtp fail")).when(emailService)
                .sendInvoiceDelivered(any(), any(), any(), any());

        NotificationException ex = assertThrows(NotificationException.class,
                () -> svc.notifyApplicant(1234L));
        assertTrue(ex.getMessage().contains("短信失败"));
        assertTrue(ex.getMessage().contains("邮件失败"));
    }

    @Test
    void notifyApplicant_taskNotFound_throwsIllegalArgument() {
        when(repo.findById(999L)).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> svc.notifyApplicant(999L));
    }

    @Test
    void notifyApplicant_blankPhoneTreatedAsEmpty() {
        when(repo.findById(1234L)).thenReturn(Optional.of(build("   ", "a@b.com", "张三")));

        svc.notifyApplicant(1234L);

        verify(smsService, never()).sendInvoiceDelivered(any(), any());
        verify(emailService).sendInvoiceDelivered(any(), any(), any(), any());
    }

    @Test
    void markNotified_setsTimestamp() {
        InvoiceTask t = build("13800138000", null, "张三");
        when(repo.findById(1234L)).thenReturn(Optional.of(t));

        svc.markNotified(1234L);

        assertNotNull(t.getNotifiedAt());
        verify(repo).save(t);
    }
}
