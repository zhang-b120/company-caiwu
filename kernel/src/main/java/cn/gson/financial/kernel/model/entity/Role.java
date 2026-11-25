package cn.gson.financial.kernel.model.entity;

import cn.gson.financial.kernel.model.hibernate.AbsEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * <li>Creation    : 2023年09月23日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@Getter
@Setter
@Entity
@DynamicUpdate
@DynamicInsert
@Comment("角色")
public class Role extends AbsEntity {

    @Comment("角色名称")
    private String name;

    @Comment("角色说明")
    private String describe;

    @Comment("状态")
    @Column(nullable = false)
    @ColumnDefault("b'1'")
    private Boolean enabled;

    @Comment("账套管理员")
    @Column(nullable = false)
    @ColumnDefault("b'0'")
    private Boolean astAdmin;

    @Comment("是否是系统默认角色")
    @Column(nullable = false)
    @ColumnDefault("b'0'")
    private Boolean systemDefault;

    @Comment("角色权限")
    @JdbcTypeCode(SqlTypes.JSON)
    private List<String> authorities;
}
