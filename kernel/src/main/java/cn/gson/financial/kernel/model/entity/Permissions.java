package cn.gson.financial.kernel.model.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.*;
import org.hibernate.type.SqlTypes;

import java.util.List;

/**
 * 
 * <p><b>Copyright © 2010-2023 开发公司 All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.kernel.model.entity</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2023年10月12日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@Getter
@Setter
@Entity
@DynamicInsert
@DynamicUpdate
@Comment("权限")
public class Permissions {
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "CosIdGenerator")
    private Long id;

    @Comment("名称")
    @Column(nullable = false, length = 32)
    private String name;

    @Comment("全名称")
    @Column(nullable = false, length = 256)
    private String fullName;

    @Comment("权限码")
    @Column(nullable = false, length = 128)
    private String key;

    @Comment("级别")
    @Column(nullable = false)
    private Integer level;

    @Comment("权限标识")
    @Column(nullable = false, length = 128)
    private String identify;

    @Comment("上级")
    @Column(nullable = false)
    @ColumnDefault("0")
    private Long parentId;

    @Comment("显示位置")
    @Column(nullable = false)
    @ColumnDefault("0")
    private Integer pos;

    @Comment("关联权限")
    @JdbcTypeCode(SqlTypes.JSON)
    private List<Long> relate;
}
