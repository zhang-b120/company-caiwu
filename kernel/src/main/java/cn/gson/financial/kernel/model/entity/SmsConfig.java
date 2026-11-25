package cn.gson.financial.kernel.model.entity;

import cn.gson.financial.kernel.model.hibernate.CosIdGenerator;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.DynamicInsert;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.GenericGenerator;

import java.io.Serializable;

/**
 * 
 * <ul style="margin:15px;">
 * <li>Description : financial</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2024年05月22日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@Setter
@Getter
@Entity
@DynamicUpdate
@DynamicInsert
@Comment("短信配置")
public class SmsConfig implements Serializable {
    @Id
    @GenericGenerator(name = "CosIdGenerator", type = CosIdGenerator.class)
    @GeneratedValue(generator = "CosIdGenerator")
    @Comment("主键")
    private Long id;

    @Comment("厂商")
    @Column(length = 32, nullable = false)
    private String vendor;

    @Comment("签名")
    @Column(length = 64)
    private String signature;

    @Comment("验证码模板ID")
    @Column(length = 64)
    private String captchaTemplateId;

    @Comment("注册通知模板ID")
    @Column(length = 64)
    private String noticeTemplateId;

    @Comment("发票送达模板ID（FN-T4）")
    @Column(length = 64)
    private String invoiceDeliveredTemplateId;

    @Comment("开票被驳回模板ID（FN6-T1）")
    @Column(length = 64)
    private String invoiceRejectedTemplateId;

    @Comment("发票红冲模板ID（FN8-T2）")
    @Column(length = 64)
    private String redLetterTemplateId;

    @Comment("accessKeyId")
    @Column(length = 64)
    private String accessKeyId;

    @Comment("accessKeySecret")
    @Column(length = 128)
    private String accessKeySecret;

    @Comment("华为短信发送方")
    @Column(length = 128)
    private String sender;

    @Comment("腾讯短信SdkAppId")
    @Column(length = 128)
    private String sdkAppId;
}
