package cn.gson.financial.kernel.model.entity;

import cn.gson.financial.kernel.model.hibernate.AbsEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.DynamicInsert;
import org.hibernate.annotations.DynamicUpdate;

/**
 * 
 * <ul style="margin:15px;">
 * <li>Description : 自动备份配置</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2024年06月21日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@DynamicInsert
@DynamicUpdate
@Getter
@Setter
@Entity
@Table(uniqueConstraints = {
        @UniqueConstraint(name = "uc_autobackupconfig", columnNames = {"accountSetsId"})
})
@NoArgsConstructor
@Comment("自动备份配置")
public class AutoBackupConfig extends AbsEntity {

    @Comment("是否启用自动备份")
    @ColumnDefault("b'0'")
    private Boolean enabled;

    @Comment("备份发送邮箱")
    private String mail;

    @Comment("每月备份日")
    private Integer backupDay;
}
