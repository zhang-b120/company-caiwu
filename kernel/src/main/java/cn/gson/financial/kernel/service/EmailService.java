package cn.gson.financial.kernel.service;

import cn.gson.financial.kernel.exception.EmailSendException;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * FN-T3 邮件通知服务。
 * <p>
 * 当前仅提供 {@link #sendInvoiceDelivered}，发"您的发票已开具"邮件，
 * 正文含发票号 + PDF 链接。后续可扩展其他业务邮件。
 * <p>
 * SMTP 配置走 {@code spring.mail.*}（见 application.yml / 各 profile 文件）。
 * dev/未配置时 {@link #mailSender} 为 null（spring-boot-starter-mail 在
 * {@code spring.mail.host} 缺失时不会自动装配 JavaMailSender），此时
 * sendInvoiceDelivered 会抛 {@link EmailSendException}，由上层决定是否
 * 转成"applicant_email 为空"等价处理或进 DLQ。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailService {

    /**
     * spring-boot-starter-mail 自动装配。若 spring.mail.host 缺失则不会注入，
     * 我们用 ObjectProvider 风格延迟取，避免启动报错。
     */
    private final org.springframework.beans.factory.ObjectProvider<JavaMailSender> mailSenderProvider;

    @Value("${spring.mail.username:}")
    private String fromAddress;

    /**
     * 发件人显示名（可选）；为空则只用 fromAddress。
     */
    @Value("${app.mail.from-name:财务系统通知}")
    private String fromName;

    @PostConstruct
    void logConfig() {
        boolean hasSender = mailSenderProvider.getIfAvailable() != null;
        log.info("[FN-T3] EmailService 初始化：mailSender={}, fromAddress={}",
                hasSender ? "已注入" : "未配置（spring.mail.host 缺失，发邮件会抛异常）",
                fromAddress == null || fromAddress.isBlank() ? "未配" : fromAddress);
    }

    /**
     * 发送"您的发票已开具"邮件。
     *
     * @param to            收件人邮箱，必填
     * @param applicantName 申请人姓名（用于抬头"尊敬的 X 您好"），可空
     * @param invoiceNumber 发票号码，必填
     * @param pdfUrl        发票 PDF URL，可空（空则不在正文里给链接）
     * @throws EmailSendException 发送失败（SMTP 未配置 / 收件人非法 / SMTP 通信失败）
     */
    public void sendInvoiceDelivered(String to, String applicantName,
                                     String invoiceNumber, String pdfUrl) {
        if (to == null || to.isBlank()) {
            throw new EmailSendException("收件人邮箱不能为空");
        }
        if (invoiceNumber == null || invoiceNumber.isBlank()) {
            throw new EmailSendException("invoiceNumber 不能为空");
        }
        JavaMailSender mailSender = mailSenderProvider.getIfAvailable();
        if (mailSender == null) {
            throw new EmailSendException("JavaMailSender 未配置（spring.mail.host 缺失）");
        }
        if (fromAddress == null || fromAddress.isBlank()) {
            throw new EmailSendException("发件人地址未配置（spring.mail.username 缺失）");
        }

        SimpleMailMessage msg = new SimpleMailMessage();
        msg.setFrom(buildFrom());
        msg.setTo(to.trim());
        msg.setSubject("【发票通知】您的发票已开具");
        msg.setText(buildBody(applicantName, invoiceNumber, pdfUrl));

        try {
            mailSender.send(msg);
            log.info("[FN-T3] 发票送达邮件已发：to={}, invoiceNumber={}", to, invoiceNumber);
        } catch (Exception ex) {
            log.warn("[FN-T3] 邮件发送失败：to={}, invoiceNumber={}, err={}",
                    to, invoiceNumber, ex.getMessage());
            throw new EmailSendException("邮件发送失败：" + ex.getMessage(), ex);
        }
    }

    /**
     * FN6-T1 发送"开票请求被驳回"邮件。
     *
     * @param to            收件人邮箱，必填
     * @param applicantName 申请人姓名（可空，空时用"客户"）
     * @param comment       驳回原因（可空，空时正文里不显示原因段）
     * @throws EmailSendException 发送失败
     */
    public void sendInvoiceRejected(String to, String applicantName, String comment) {
        if (to == null || to.isBlank()) {
            throw new EmailSendException("收件人邮箱不能为空");
        }
        JavaMailSender mailSender = mailSenderProvider.getIfAvailable();
        if (mailSender == null) {
            throw new EmailSendException("JavaMailSender 未配置（spring.mail.host 缺失）");
        }
        if (fromAddress == null || fromAddress.isBlank()) {
            throw new EmailSendException("发件人地址未配置（spring.mail.username 缺失）");
        }

        SimpleMailMessage msg = new SimpleMailMessage();
        msg.setFrom(buildFrom());
        msg.setTo(to.trim());
        msg.setSubject("【开票通知】您的开票请求被驳回，请重新提交");
        msg.setText(buildRejectedBody(applicantName, comment));

        try {
            mailSender.send(msg);
            log.info("[FN6-T1] 驳回邮件已发：to={}", to);
        } catch (Exception ex) {
            log.warn("[FN6-T1] 驳回邮件发送失败：to={}, err={}", to, ex.getMessage());
            throw new EmailSendException("邮件发送失败：" + ex.getMessage(), ex);
        }
    }

    /**
     * FN8-T2 发送"发票已红冲（作废）"邮件。
     *
     * @param to                    收件人邮箱，必填
     * @param applicantName         申请人姓名（可空）
     * @param originalInvoiceNumber 原蓝字发票号（被红冲的）
     * @param redInvoiceNumber      红字发票号
     * @param redPdfUrl             红字发票 PDF URL（可空）
     */
    public void sendRedLetterIssued(String to, String applicantName,
                                    String originalInvoiceNumber, String redInvoiceNumber,
                                    String redPdfUrl) {
        if (to == null || to.isBlank()) {
            throw new EmailSendException("收件人邮箱不能为空");
        }
        JavaMailSender mailSender = mailSenderProvider.getIfAvailable();
        if (mailSender == null) {
            throw new EmailSendException("JavaMailSender 未配置（spring.mail.host 缺失）");
        }
        if (fromAddress == null || fromAddress.isBlank()) {
            throw new EmailSendException("发件人地址未配置（spring.mail.username 缺失）");
        }

        SimpleMailMessage msg = new SimpleMailMessage();
        msg.setFrom(buildFrom());
        msg.setTo(to.trim());
        msg.setSubject("【开票通知】您的发票已红冲（作废）");
        msg.setText(buildRedLetterBody(applicantName, originalInvoiceNumber, redInvoiceNumber, redPdfUrl));

        try {
            mailSender.send(msg);
            log.info("[FN8-T2] 红冲邮件已发：to={}, redInvoiceNumber={}", to, redInvoiceNumber);
        } catch (Exception ex) {
            log.warn("[FN8-T2] 红冲邮件发送失败：to={}, err={}", to, ex.getMessage());
            throw new EmailSendException("邮件发送失败：" + ex.getMessage(), ex);
        }
    }

    private String buildFrom() {
        if (fromName != null && !fromName.isBlank()) {
            return fromName + " <" + fromAddress + ">";
        }
        return fromAddress;
    }

    static String buildBody(String applicantName, String invoiceNumber, String pdfUrl) {
        StringBuilder sb = new StringBuilder();
        sb.append("尊敬的 ").append(applicantName == null || applicantName.isBlank() ? "客户" : applicantName).append(" 您好：\n\n");
        sb.append("您申请的发票已开具完成。\n\n");
        sb.append("发票号码：").append(invoiceNumber).append("\n");
        if (pdfUrl != null && !pdfUrl.isBlank()) {
            sb.append("PDF 下载：").append(pdfUrl).append("\n");
        }
        sb.append("\n如有疑问请联系您的客户经理。\n\n— 财务系统");
        return sb.toString();
    }

    static String buildRejectedBody(String applicantName, String comment) {
        StringBuilder sb = new StringBuilder();
        sb.append("尊敬的 ").append(applicantName == null || applicantName.isBlank() ? "客户" : applicantName).append(" 您好：\n\n");
        sb.append("您的开票请求未能通过审核，已被驳回。\n\n");
        if (comment != null && !comment.isBlank()) {
            sb.append("驳回原因：").append(comment).append("\n\n");
        }
        sb.append("请联系商家核对发票信息后重新提交。\n\n— 财务系统");
        return sb.toString();
    }

    static String buildRedLetterBody(String applicantName, String originalInvoiceNumber,
                                     String redInvoiceNumber, String redPdfUrl) {
        StringBuilder sb = new StringBuilder();
        sb.append("尊敬的 ").append(applicantName == null || applicantName.isBlank() ? "客户" : applicantName).append(" 您好：\n\n");
        sb.append("您的发票已被红冲（作废）。原蓝字发票如有需要可作废，红字发票即为冲销凭证。\n\n");
        if (originalInvoiceNumber != null && !originalInvoiceNumber.isBlank()) {
            sb.append("原发票号码：").append(originalInvoiceNumber).append("\n");
        }
        if (redInvoiceNumber != null && !redInvoiceNumber.isBlank()) {
            sb.append("红字发票号：").append(redInvoiceNumber).append("\n");
        }
        if (redPdfUrl != null && !redPdfUrl.isBlank()) {
            sb.append("红字 PDF 下载：").append(redPdfUrl).append("\n");
        }
        sb.append("\n如有疑问请联系您的客户经理。\n\n— 财务系统");
        return sb.toString();
    }
}
