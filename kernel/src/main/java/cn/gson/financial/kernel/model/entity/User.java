package cn.gson.financial.kernel.model.entity;

import cn.gson.financial.kernel.model.hibernate.CosIdGenerator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.*;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 *
 * <p><b>Copyright © 2010-2019开发公司All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.kernel.model.entity</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2019年08月29日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 *
 */
@Setter
@Getter
@Entity
@DynamicUpdate
@DynamicInsert
@Comment("用户")
public class User implements Serializable {
    @Id
    @GenericGenerator(name = "CosIdGenerator", type = CosIdGenerator.class)
    @GeneratedValue(generator = "CosIdGenerator")
    @Comment("主键")
    private Long id;

    /**
     * 电话
     */
    @Comment("电话")
    @Column(nullable = false, unique = true)
    private String mobile;

    /**
     * 密码
     */
    @Comment("密码")
    @JsonIgnore
    private String password;

    /**
     * 微信 unionId
     */
    @Comment("微信 unionId")
    private String unionId;

    /**
     * 验证码口令
     */
    @Comment("验证码口令")
    private String verifyCode;
    /**
     * 微信 openId
     */
    @Comment("微信 openId")
    private String openId;

    /**
     * 昵称
     */
    @Comment("昵称")
    private String nickname;

    /**
     * 头像
     */
    @Comment("头像")
    private String avatarUrl;

    /**
     * 真实姓名
     */
    @Comment("真实姓名")
    private String realName;

    @Comment("创建时间")
    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createDate;

    @Comment("最后活跃时间")
    private LocalDateTime lastUpdate;

    /**
     * 邮箱
     */
    @Comment("邮箱")
    private String email;

    /**
     * 可创建账套数
     */
    @Comment("可创建账套数")
    @Column(nullable = false)
    @ColumnDefault("999999")
    private Integer accountSetsNum;

    /**
     * 可关联用户数
     */
    @Comment("可关联用户数")
    @Column(nullable = false)
    @ColumnDefault("999999")
    private Integer userNum;

    /**
     * 是否可分配账套（直连数据库手工开放）
     */
    @Comment("是否可在分配账套页将账套分配给自己")
    @Column(nullable = false)
    @ColumnDefault("b'0'")
    private Boolean canAssignAccountSets;
}
