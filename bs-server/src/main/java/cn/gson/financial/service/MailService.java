package cn.gson.financial.service;

import cn.hutool.core.lang.Assert;
import jakarta.annotation.Resource;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeUtility;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.mail.MailProperties;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

/**
 * 
 * <ul style="margin:15px;">
 * <li>Description : financial</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2024年06月21日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@Service
@Slf4j
public class MailService {
    @Resource
    private JavaMailSender javaMailSender;

    @Resource
    private MailProperties mailProperties;

    public void sendMailCode(String email, MimeMessageHelper message, String fromName) {
        Assert.notEmpty(email, "邮箱不能为空");
//        // 邮件对象（邮件模板，根据自身业务修改）
//        SimpleMailMessage message = new SimpleMailMessage();
//        message.setSubject(subject);
//        message.setText("尊敬的用户您好!\n\n欢迎您加入青艺科技务大家庭。 \n\n 您本次的校验验证码为: \n\n" + code + "\n\n有效期5分钟，请不要把验证码信息泄露给其他人，如非本人忽略操作");
//        message.setTo(email);
        try {
            // 对方看到的发送人（发件人的邮箱，根据实际业务进行修改，一般填写的是企业邮箱,*号位置写发件者邮箱）
            message.setFrom(new InternetAddress(MimeUtility.encodeText(fromName) + "<" + mailProperties.getUsername() + ">").toString());
            // 发送邮件
            javaMailSender.send(message.getMimeMessage());
        } catch (Exception e) {
            log.error("邮件【{}】发送失败~", email, e);
        }
    }

    public MimeMessageHelper mimeMessageHelper() throws MessagingException {
        MimeMessage mimeMessage = javaMailSender.createMimeMessage();
        return new MimeMessageHelper(mimeMessage, true);
    }
}
