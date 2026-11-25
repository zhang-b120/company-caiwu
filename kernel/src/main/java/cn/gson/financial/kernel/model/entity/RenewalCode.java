package cn.gson.financial.kernel.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.DynamicInsert;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.GenericGenerator;

import java.time.LocalDateTime;

/**
 * 
 * <p><b>Copyright © 2010-2022开发公司All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : 用户续期码</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2022年12月05日</li>
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
@Comment("激活码")
public class RenewalCode {
    @Id
    @GenericGenerator(name = "CosIdGenerator", strategy = "cn.gson.financial.kernel.model.hibernate.CosIdGenerator")
    @GeneratedValue(generator = "CosIdGenerator")
    private Long id;

    @Comment("类型")
    @Column(length = 16)
    private String type;

    @Comment("续期码")
    @Column(length = 32, unique = true)
    private String code;

    /**
     * 续期月份
     */
    @Comment("续期月数")
    private Integer month;

    /**
     * 使用用户
     */
    @Comment("续期用户")
    private Long userId;

    /**
     * 续期账套
     */
    @Comment("续期账套")
    private Long asId;

    /**
     * 使用日期
     */
    @Comment("续期时间")
    private LocalDateTime useTime;

    @Comment("发放渠道")
    private String channel;
}
