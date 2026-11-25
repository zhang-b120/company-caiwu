package cn.gson.financial.kernel.model.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.GenericGenerator;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * FN-NEW-AUDIT-LOG 凭证审核日志。
 * <p>
 * 物理表名 fxy_financial_voucher_audit_log。
 * <p>
 * action 取值：
 * <ul>
 *   <li>REJECT — 审核员拒绝 AI 凭证</li>
 *   <li>EDIT — 修改任意字段（含 reviewStatus 切换）</li>
 *   <li>TEACHING_RULE_DRAFT — 提交教学规则草稿给 AI 学习</li>
 * </ul>
 */
@Getter
@Setter
@Entity
@Table(name = "voucher_audit_log",
        indexes = {
                @Index(name = "idx_voucher", columnList = "voucher_id"),
                @Index(name = "idx_account_sets_period", columnList = "account_sets_id, period"),
                @Index(name = "idx_action_modified_at", columnList = "action, modified_at")
        })
@Comment("凭证审核日志（FN-NEW-AUDIT-LOG）")
public class VoucherAuditLog implements Serializable {

    public static final String ACTION_REJECT               = "REJECT";
    public static final String ACTION_EDIT                 = "EDIT";
    public static final String ACTION_TEACHING_RULE_DRAFT  = "TEACHING_RULE_DRAFT";

    @Id
    @GenericGenerator(name = "CosIdGenerator", strategy = "cn.gson.financial.kernel.model.hibernate.CosIdGenerator")
    @GeneratedValue(generator = "CosIdGenerator")
    @Comment("主键（雪花 ID）")
    private Long id;

    @Comment("凭证 ID")
    @Column(name = "voucher_id", nullable = false)
    private Long voucherId;

    @Comment("账套 ID")
    @Column(name = "account_sets_id", nullable = false)
    private Long accountSetsId;

    @Comment("账期 yyyy-MM")
    @Column(name = "period", length = 7)
    private String period;

    @Comment("REJECT / EDIT / TEACHING_RULE_DRAFT")
    @Column(name = "action", length = 32, nullable = false)
    private String action;

    @Lob
    @Comment("AI 生成的原始凭证 JSON（首次创建时存档）")
    @Column(name = "ai_original_data", columnDefinition = "LONGTEXT")
    private String aiOriginalData;

    @Lob
    @Comment("修改前快照")
    @Column(name = "`before`", columnDefinition = "LONGTEXT")
    private String beforeSnapshot;

    @Lob
    @Comment("修改后快照")
    @Column(name = "`after`", columnDefinition = "LONGTEXT")
    private String afterSnapshot;

    @Comment("变更摘要（人类可读）")
    @Column(name = "diff_summary", columnDefinition = "TEXT")
    private String diffSummary;

    @Comment("拒绝/修改原因分类")
    @Column(name = "reason_category", length = 64)
    private String reasonCategory;

    @Comment("原因描述")
    @Column(name = "reason_text", columnDefinition = "TEXT")
    private String reasonText;

    @Comment("教学规则草稿")
    @Column(name = "teaching_rule", columnDefinition = "TEXT")
    private String teachingRule;

    @Comment("操作者（user.id；0=SYSTEM）")
    @Column(name = "modified_by")
    private Long modifiedBy;

    @Comment("操作时间")
    @CreationTimestamp
    @Column(name = "modified_at", updatable = false, nullable = false)
    private LocalDateTime modifiedAt;
}
