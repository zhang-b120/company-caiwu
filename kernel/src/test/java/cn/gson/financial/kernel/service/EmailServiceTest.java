package cn.gson.financial.kernel.service;

import cn.gson.financial.kernel.exception.EmailSendException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * FN-T3 EmailService 单测：mock JavaMailSender 验证收件人/主题/正文正确组装。
 */
class EmailServiceTest {

    private JavaMailSender mailSender;
    @SuppressWarnings("unchecked")
    private ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
    private EmailService svc;

    @BeforeEach
    void setUp() {
        mailSender = mock(JavaMailSender.class);
        when(provider.getIfAvailable()).thenReturn(mailSender);
        svc = new EmailService(provider);
        ReflectionTestUtils.setField(svc, "fromAddress", "notify@example.com");
        ReflectionTestUtils.setField(svc, "fromName", "财务系统通知");
    }

    @Test
    void sendInvoiceDelivered_buildsCorrectMessage() {
        svc.sendInvoiceDelivered("alice@example.com", "张三", "12345678",
                "https://example.com/invoice.pdf");

        ArgumentCaptor<SimpleMailMessage> cap = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(cap.capture());
        SimpleMailMessage msg = cap.getValue();
        assertArrayEquals(new String[]{"alice@example.com"}, msg.getTo());
        assertEquals("【发票通知】您的发票已开具", msg.getSubject());
        assertTrue(msg.getFrom().contains("notify@example.com"));
        assertTrue(msg.getFrom().contains("财务系统通知"));
        assertTrue(msg.getText().contains("张三"));
        assertTrue(msg.getText().contains("12345678"));
        assertTrue(msg.getText().contains("https://example.com/invoice.pdf"));
    }

    @Test
    void sendInvoiceDelivered_nullPdfUrl_omitsPdfLine() {
        svc.sendInvoiceDelivered("alice@example.com", "张三", "12345678", null);

        ArgumentCaptor<SimpleMailMessage> cap = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(cap.capture());
        assertFalse(cap.getValue().getText().contains("PDF 下载"));
    }

    @Test
    void sendInvoiceDelivered_blankApplicantName_usesDefault() {
        svc.sendInvoiceDelivered("alice@example.com", "  ", "12345678", null);

        ArgumentCaptor<SimpleMailMessage> cap = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(cap.capture());
        assertTrue(cap.getValue().getText().contains("尊敬的 客户"));
    }

    @Test
    void sendInvoiceDelivered_emptyTo_throws() {
        EmailSendException ex = assertThrows(EmailSendException.class,
                () -> svc.sendInvoiceDelivered("", "张三", "12345678", null));
        assertTrue(ex.getMessage().contains("收件人"));
        verify(mailSender, never()).send(any(SimpleMailMessage.class));
    }

    @Test
    void sendInvoiceDelivered_emptyInvoiceNumber_throws() {
        EmailSendException ex = assertThrows(EmailSendException.class,
                () -> svc.sendInvoiceDelivered("alice@example.com", "张三", "", null));
        assertTrue(ex.getMessage().contains("invoiceNumber"));
    }

    @Test
    void sendInvoiceDelivered_noJavaMailSender_throws() {
        when(provider.getIfAvailable()).thenReturn(null);
        EmailService svc2 = new EmailService(provider);
        ReflectionTestUtils.setField(svc2, "fromAddress", "notify@example.com");
        ReflectionTestUtils.setField(svc2, "fromName", "x");

        EmailSendException ex = assertThrows(EmailSendException.class,
                () -> svc2.sendInvoiceDelivered("alice@example.com", "张三", "12345678", null));
        assertTrue(ex.getMessage().contains("JavaMailSender"));
    }

    @Test
    void sendInvoiceDelivered_noFromAddress_throws() {
        ReflectionTestUtils.setField(svc, "fromAddress", "");
        EmailSendException ex = assertThrows(EmailSendException.class,
                () -> svc.sendInvoiceDelivered("alice@example.com", "张三", "12345678", null));
        assertTrue(ex.getMessage().contains("发件人"));
    }

    @Test
    void sendInvoiceDelivered_smtpThrows_wrappedAsEmailSendException() {
        doThrow(new org.springframework.mail.MailSendException("smtp dead"))
                .when(mailSender).send(any(SimpleMailMessage.class));

        EmailSendException ex = assertThrows(EmailSendException.class,
                () -> svc.sendInvoiceDelivered("alice@example.com", "张三", "12345678", null));
        assertTrue(ex.getMessage().contains("发送失败"));
    }
}
