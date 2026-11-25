package cn.gson.financial.kernel.model.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.DynamicInsert;
import org.hibernate.annotations.DynamicUpdate;

import java.time.LocalDateTime;

/**
 * 税务登录记录表
 */
@Data
@Entity
@DynamicInsert
@DynamicUpdate
@Table(name = "tax_login_log")
@Comment("税务登录记录表")
public class TaxLoginLog {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Comment("主键")
    private Long id;
    
    @Column(name = "account_sets_id", nullable = false)
    @Comment("账套ID")
    private Long accountSetsId;
    
    @Column(name = "login_type", length = 20, nullable = false)
    @Comment("登录类型：PASSWORD-办税员密码, SMS-短信验证码, QRCODE-扫码登录")
    private String loginType;
    
    @Column(name = "phone_number", length = 20)
    @Comment("手机号")
    private String phoneNumber;
    
    @Column(name = "login_time", nullable = false)
    @Comment("登录时间")
    private LocalDateTime loginTime;
    
    @Column(name = "login_status", length = 20)
    @Comment("登录状态：SUCCESS-成功, FAILED-失败")
    private String loginStatus;
    
    @Column(name = "error_message", columnDefinition = "TEXT")
    @Comment("错误信息")
    private String errorMessage;
    
    @Column(name = "create_time")
    @Comment("创建时间")
    private LocalDateTime createTime;
}
