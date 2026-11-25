package cn.gson.financial.kernel.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.*;

import java.time.LocalDateTime;

/**
 * 
 * <p><b>Copyright © 2010-2022开发公司All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.kernel.model.entity</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2022年10月22日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@Getter
@Setter
@Entity
@DynamicInsert
@DynamicUpdate
@Comment("管理员")
public class Admin {
    @Id
    @GenericGenerator(name = "CosIdGenerator", strategy = "cn.gson.financial.kernel.model.hibernate.CosIdGenerator")
    @GeneratedValue(generator = "CosIdGenerator")
    @Comment("主键")
    private Long id;

    @Comment("管理员姓名")
    private String name;

    @Comment("管理员电话")
    @Column(length = 18, unique = true)
    private String telephone;

    @Comment("管理员密码")
    @Column(length = 64, nullable = false)
    private String password;
    @Comment("最后登录IP")
    private String lastIp;

    @Comment("是否超级管理员")
    @ColumnDefault("0")
    private Boolean master;

    @Comment("是否超级管理员")
    @ColumnDefault("1")
    private Boolean enabled;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createTime;

    private LocalDateTime lastLoginTime;
}
