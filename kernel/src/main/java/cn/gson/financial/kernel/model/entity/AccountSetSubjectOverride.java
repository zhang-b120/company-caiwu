package cn.gson.financial.kernel.model.entity;

import cn.gson.financial.kernel.model.hibernate.AbsEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.DynamicInsert;
import org.hibernate.annotations.DynamicUpdate;

import java.time.LocalDateTime;

/**
 * 账套级科目覆写。
 * <p>FN-NEW-CARRYOVER-OVERRIDES-PERSISTENCE：会计可为每个账套指定「业务含义 → 实际科目」
 * 的映射（如「主营业务收入」用 5001 vs 6001）。XW 结转 / preflight 优先用本表，兜底走默认匹配。
 * <p>复合唯一约束 (account_sets_id, biz_meaning) 保证同账套同业务含义只能有一条映射。
 */
@Getter
@Setter
@Entity
@Table(uniqueConstraints = {
        @UniqueConstraint(name = "uk_account_set_biz",
                columnNames = {"accountSetsId", "bizMeaning"})
})
@DynamicInsert
@DynamicUpdate
@Comment("账套级科目覆写")
public class AccountSetSubjectOverride extends AbsEntity {

    @Comment("业务含义（如「主营业务收入」「应交税费-销项税额」）")
    @Column(nullable = false, length = 64)
    private String bizMeaning;

    @Comment("映射到的科目 ID")
    @Column(nullable = false)
    private Long subjectId;

    @Comment("冗余字段：科目编码")
    @Column(length = 32)
    private String subjectCode;

    @Comment("冗余字段：科目全名")
    @Column(length = 128)
    private String subjectName;

    @Comment("更新人（BT 编辑器写回时填；OpenAPI 匿名写入时为 null）")
    @Column(length = 64)
    private String updatedBy;

    /**
     * FN-NEW-CONFIG-INIT-SOURCE-FIELD：来源标记。
     * 与 {@link AccountSetProfile#source} 同语义（4 值）：
     * auto_matched / accountant_custom（默认） / accountant_reviewed / system_suggested。
     */
    @Comment("来源：auto_matched/accountant_custom/accountant_reviewed/system_suggested")
    @Column(nullable = false, length = 32)
    @ColumnDefault("'accountant_custom'")
    private String source;

    @Comment("创建时间")
    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Comment("更新时间")
    @Column(nullable = false)
    private LocalDateTime updatedAt;
}
