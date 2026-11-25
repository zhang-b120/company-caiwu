package cn.gson.financial.kernel.model.entity;

import cn.gson.financial.kernel.model.hibernate.CosIdGenerator;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.*;

/**
 *
 * <p><b>Copyright © 2010-2023 开发公司 All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.kernel.model.entity</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2023年07月25日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 *
 */
@DynamicInsert
@DynamicUpdate
@Getter
@Setter
@Entity
@NoArgsConstructor
@ToString
@Comment("系统配置")
public class AppConfig {
    @Id
    @GenericGenerator(name = "CosIdGenerator", type = CosIdGenerator.class)
    @GeneratedValue(generator = "CosIdGenerator")
    @Comment("主键")
    private Long id;

    @Comment("系统名称")
    private String name;

    @Comment("公司名称")
    private String company;

    @Comment("登录界面Logo")
    private String loginLogo;

    @Comment("登录界面标语")
    private String loginSlogan;

    @Comment("登录界面子标语")
    private String loginSubSlogan;

    @Comment("登录页左侧背景")
    private String loginLeftBg;

    @Comment("登录页左侧插画")
    private String loginLeftCh;

    @Comment("Copyright")
    private String copyright;

    @Comment("主页logo")
    private String homeLogo;

    @Comment("客服二维码")
    private String qrCode;

    @Comment("关于我们")
    @Column(columnDefinition = "text")
    private String about;

    @Comment("备案号")
    @Column(length = 32)
    private String icp;
    /**
     * 启用注册
     */
    @Comment("启用注册")
    @ColumnDefault("b'1'")
    @Column(nullable = false)
    private Boolean register = true;
    /**
     * 账套默认体验天数
     */
    @Comment("账套默认体验天数")
    @ColumnDefault("30")
    @Column(nullable = false)
    private Integer experienceDays = 9999999;
    /**
     * 用户默认授权账套数量
     */
    @Comment("用户默认授权账套数量")
    @ColumnDefault("2")
    @Column(nullable = false)
    private Integer accountSetsNum = 9999999;
    /**
     * 用户默认可关联用户数量
     */
    @Comment("用户默认可关联用户数量")
    @ColumnDefault("2")
    @Column(nullable = false)
    private Integer userNum = 9999999;

    /**
     * 顶部公告
     */
    @Comment("顶部公告")
    @ColumnDefault("''")
    @Column(nullable = false)
    private String notice = "";
}
