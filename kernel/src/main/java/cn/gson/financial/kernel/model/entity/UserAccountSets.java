package cn.gson.financial.kernel.model.entity;

import cn.gson.financial.kernel.common.Roles;
import cn.gson.financial.kernel.model.hibernate.AbsEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.DynamicInsert;
import org.hibernate.annotations.DynamicUpdate;

@Getter
@Setter
@Entity
@Table(uniqueConstraints = {
        @UniqueConstraint(columnNames = {"accountSetsId", "userId"})
}, indexes = {
        // 任务工作台子查询 WHERE userId IN (...) 反向走 userId
        @Index(name = "ix_uas_user_id", columnList = "userId, accountSetsId")
})
@DynamicUpdate
@DynamicInsert
@Comment("用户账套")
public class UserAccountSets extends AbsEntity {

    @Comment("用户ID")
    private Long userId;

    /**
     * 账套角色
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Roles roleType;

    @Comment("角色ID")
    private Long roleId;

    @Comment("是否是当前账套")
    @ColumnDefault("b'1'")
    @Column(nullable = false)
    private Boolean current;

    @Comment("是否是创建者")
    @ColumnDefault("b'1'")
    @Column(nullable = false)
    private Boolean creator;
}
