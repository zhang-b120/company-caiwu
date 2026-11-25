package cn.gson.financial.kernel.model.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.DynamicInsert;
import org.hibernate.annotations.DynamicUpdate;

import java.time.LocalDateTime;

/**
 * 办税员管理表（精简版：只保留手机号、密码、启用状态）
 */
@Data
@Entity
@DynamicInsert
@DynamicUpdate
@Table(name = "tax_login_account")
@Comment("办税员管理表")
public class TaxLoginAccount {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Comment("主键")
    private Long id;
    
    @Column(name = "account_sets_id", nullable = false)
    @Comment("账套ID")
    private Long accountSetsId;
    
    @Column(name = "login_type", length = 20, nullable = false)
    @ColumnDefault("'PASSWORD'")
    @Comment("登录类型：PASSWORD-办税员密码")
    private String loginType = "PASSWORD";
    
    @Column(name = "phone_number", length = 20, nullable = false)
    @Comment("手机号")
    private String phoneNumber;
    
    @Column(name = "password", length = 255, nullable = false)
    @Comment("密码（加密存储）")
    private String password;
    
    @Column(name = "status", length = 20)
    @ColumnDefault("'ACTIVE'")
    @Comment("状态：ACTIVE-激活, INACTIVE-停用")
    private String status = "ACTIVE";
    
    @Column(name = "create_time")
    @Comment("创建时间")
    private LocalDateTime createTime;
    
    @Column(name = "update_time")
    @Comment("更新时间")
    private LocalDateTime updateTime;
}
