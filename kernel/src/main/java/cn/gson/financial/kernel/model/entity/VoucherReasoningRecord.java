package cn.gson.financial.kernel.model.entity;

import cn.gson.financial.kernel.model.hibernate.AbsEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.DynamicInsert;
import org.hibernate.annotations.DynamicUpdate;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 凭证推理记录（明细）。FN-NEW-BOOKKEEPING-RUN-RECORD-PERSISTENCE。
 * <p>本次做账生成的每张凭证写一行，记录推理过程 / 数据来源 / 应用规则 / 分录等。
 * 与 {@link BookkeepingRunRecord} 通过 {@code runId} 关联。
 * <p>所有 JSON 字段（{@code dataSources / appliedRules / appliedPrompts / entries / sourceInvoices}）
 * 由 xiaowei 端定义内部结构，FN 透传不解析。其中 {@code entries} 字段可含 xiaowei 的
 * {@code entries_used} 子字段，FN 不做额外约束。
 */
@Getter
@Setter
@Entity
@Table
@DynamicInsert
@DynamicUpdate
@Comment("凭证推理记录")
public class VoucherReasoningRecord extends AbsEntity {

    @Comment("关联 bookkeeping_run_record.run_id")
    @Column(nullable = false, length = 64)
    private String runId;

    @Comment("账期 YYYY-MM（冗余）")
    @Column(nullable = false, length = 10)
    private String period;

    @Comment("凭证 ID — post 后回写；推理阶段为 null")
    private Long voucherId;

    @Comment("凭证类型：sales / cost_carry / surtax / income_tax 等")
    @Column(nullable = false, length = 32)
    private String voucherKind;

    @Comment("凭证摘要 / 标题")
    @Column(length = 255)
    private String voucherTitle;

    @Comment("产出方：xiaowei_rule / xiaowei_llm / accountant_manual 等 7 种")
    @Column(length = 32)
    private String producedBy;

    @Comment("置信度 0.00-1.00")
    @Column(precision = 3, scale = 2)
    private BigDecimal confidence;

    @Comment("中文计算公式")
    @Column(columnDefinition = "TEXT")
    private String formulaCn;

    @Comment("数据来源")
    @Column(columnDefinition = "json")
    private String dataSources;

    @Comment("中文推理文本")
    @Column(columnDefinition = "TEXT")
    private String reasoningText;

    @Comment("应用的账套规则")
    @Column(columnDefinition = "json")
    private String appliedRules;

    @Comment("应用的 LLM 提示词")
    @Column(columnDefinition = "json")
    private String appliedPrompts;

    /** xiaowei 端可含 entries_used 子字段，FN 透传 */
    @Comment("借贷分录")
    @Column(columnDefinition = "json")
    private String entries;

    @Comment("关联单据 ID 列表")
    @Column(columnDefinition = "json")
    private String sourceInvoices;

    @Comment("记录创建时间")
    @Column(nullable = false)
    private LocalDateTime createdAt;
}
