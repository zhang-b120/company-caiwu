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

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 账套画像（FN-NEW-ACCOUNT-SET-PROFILE）。
 * <p>每个账套的特殊业务 / 结转 / 税务规则双层存放：
 * <ul>
 *   <li>{@link #rules} JSON：结构化规则，xiaowei 端 rule_template 定义并执行，FN 透传不解析。</li>
 *   <li>{@link #prompt} TEXT：自然语言提示词，LLM 用，FN 透传不解析。</li>
 * </ul>
 *
 * <p>6 个 {@code section_type}：
 * {@code business_rule / carryover_rule / note / industry / exception / tax_policy}。
 *
 * <p>UNIQUE {@code (account_sets_id, section_type, section_key)} 防重；section_key 同类型多条用的子键，
 * 约定空规则填空串 {@code ''}（NOT NULL DEFAULT ''），让 UNIQUE 工作。
 */
@Getter
@Setter
@Entity
@Table(uniqueConstraints = {
        @UniqueConstraint(name = "uk_acc_section",
                columnNames = {"accountSetsId", "sectionType", "sectionKey"})
})
@DynamicInsert
@DynamicUpdate
@Comment("账套画像")
public class AccountSetProfile extends AbsEntity {

    // ACCOUNT-PROFILE-V2：6 块 invoice_rule/bank_rule/enterprise_tax/subject_aux/carryover_accrual
    // （+ carryover_rule 兼容 V5 结转模板、tax_verify 系统）。旧 business_rule/industry/tax_policy/note/exception 已迁移/废弃。
    @Comment("画像类别（invoice_rule/bank_rule/enterprise_tax/subject_aux/carryover_accrual/carryover_rule/tax_verify）")
    @Column(nullable = false, length = 32)
    private String sectionType;

    @Comment("同类型多条用的子键（约定空时用 default 或空串）")
    @Column(nullable = false, length = 64)
    @ColumnDefault("''")
    private String sectionKey;

    @Comment("简短标题")
    @Column(length = 128)
    private String title;

    @Comment("结构化规则 JSON（xiaowei 端 rule_template，FN 透传不解析）")
    @Column(columnDefinition = "json")
    private String rules;

    @Comment("自然语言提示词（LLM 用，FN 透传不解析）")
    @Column(columnDefinition = "TEXT")
    private String prompt;

    @Comment("优先级（数字大优先）")
    @Column(nullable = false)
    @ColumnDefault("0")
    private Integer priority;

    @Comment("是否启用（true=启用，false=软删）。ACCOUNT-PROFILE-V2 过渡期与 status 同步（enabled = status='active'），后续废弃")
    @Column(nullable = false)
    @ColumnDefault("1")
    private Boolean enabled;

    /**
     * ACCOUNT-PROFILE-V2：状态三态，统一"生效/待会计确认/软删"，替代 enabled（过渡期同步）。
     * <ul>
     *   <li>{@code active}         — 生效（做账取数命中）</li>
     *   <li>{@code pending_review} — 待会计确认（学习/AI 建议，做账不取、BT 标黄）</li>
     *   <li>{@code disabled}       — 软删/停用</li>
     * </ul>
     */
    @Comment("状态：active(生效)/pending_review(待确认)/disabled(软删)")
    @Column(nullable = false, length = 16)
    @ColumnDefault("'active'")
    private String status = "active";

    @Comment("置信度 0-1（学习/AI 建议用，降级链判高/低置信）；会计录入为 null")
    @Column(precision = 4, scale = 3)
    private java.math.BigDecimal confidence;

    @Comment("生效起始日（可选，xiaowei 按 period 过滤）")
    private LocalDate effectiveFrom;

    @Comment("生效结束日（可选）")
    private LocalDate effectiveTo;

    @Comment("更新人 ID（BT 编辑器写回时填）")
    private Long updatedBy;

    /**
     * FN-NEW-CONFIG-INIT-SOURCE-FIELD：来源标记。
     * <ul>
     *   <li>{@code auto_matched}        — FN/XW 自动匹配生成（LLM 初始化产出）</li>
     *   <li>{@code accountant_custom}   — 会计在 BT 端自定义新增（<b>默认</b>，PUT 不传 source 时按此存）</li>
     *   <li>{@code accountant_reviewed} — 会计审核过（原 auto 被 review 后升级为信任）</li>
     *   <li>{@code system_suggested}    — 系统建议（如 LLM 推荐但未自动应用，等会计确认）</li>
     * </ul>
     * 优先级（高→低）：accountant_custom &gt; accountant_reviewed &gt; system_suggested &gt; auto_matched
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
