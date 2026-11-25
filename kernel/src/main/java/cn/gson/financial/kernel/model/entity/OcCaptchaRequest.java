package cn.gson.financial.kernel.model.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.CreationTimestamp;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * OpenClaw 验证码/二维码交互请求表
 * OC 遇到验证码或二维码时向此表写入请求，前端弹窗让财务人员填写后回传给 OC
 */
@Getter
@Setter
@Entity
@Table(name = "oc_captcha_request")
@Comment("OpenClaw验证码/二维码交互请求")
public class OcCaptchaRequest implements Serializable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Comment("主键")
    private Long id;

    @Comment("关联的 oc_task.id，可为空（OC 未绑定任务时）")
    @Column(name = "task_id")
    private Long taskId;

    @Comment("账套 ID")
    @Column(name = "account_set_id", nullable = false)
    private Long accountSetId;

    @Comment("类型：captcha（图形验证码）/ sms（短信验证码）/ qrcode（二维码扫码）")
    @Column(length = 20, nullable = false)
    private String type;

    @Comment("验证码图片（Base64 或 URL），二维码场景传二维码图片")
    @Column(name = "image_data", columnDefinition = "TEXT")
    private String imageData;

    @Comment("省份（代理通道验证码时展示用）")
    @Column(length = 20)
    private String province;

    @Comment("代理通道用户名/手机号（验证码提交后存入 Redis 的 key）")
    @Column(name = "proxy_username", length = 50)
    private String proxyUsername;

    @Comment("提示信息（如请输入短信验证码，已发送至 138****0000）")
    @Column(length = 255)
    private String message;

    @Comment("状态：pending（待填写）/ submitted（已提交）/ expired（已超时）")
    @Column(length = 20, nullable = false)
    @ColumnDefault("'pending'")
    private String status = "pending";

    @Comment("用户填写的内容（验证码文字；二维码场景为空，confirmed 即可）")
    @Column(length = 64)
    private String code;

    @Comment("创建时间")
    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Comment("用户提交时间")
    @Column(name = "submitted_at")
    private LocalDateTime submittedAt;

    @Comment("超时时间，默认创建后 5 分钟")
    @Column(name = "expires_at")
    private LocalDateTime expiresAt;
}
