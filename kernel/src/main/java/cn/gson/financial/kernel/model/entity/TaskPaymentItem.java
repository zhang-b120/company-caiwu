package cn.gson.financial.kernel.model.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.UpdateTimestamp;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 缴费查询税款明细表（步骤7详情）
 *
 * <p>OC 执行 query_payment 后，按税种逐行写入各税种的缴款状态。
 * task_period.step_payment 的聚合逻辑：
 * 只要有任意行 pay_status=unpaid/partial，则主表为 done（已完成查询但有未缴款项）。</p>
 *
 * <pre>
 * pay_status 说明：
 *   paid    - 已全额缴清
 *   unpaid  - 应缴未缴（税款存在但未缴）
 *   partial - 部分缴清
 *   na      - 本期该税种税额为零，无需缴纳
 * </pre>
 */
@Getter
@Setter
@Entity
@Table(name = "task_payment_item", indexes = {
        @Index(name = "ix_payment_item_tp", columnList = "task_period_id")
})
@Comment("缴费查询税款明细-步骤7详情")
public class TaskPaymentItem implements Serializable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Comment("主键")
    private Long id;

    @Comment("关联 task_period.id")
    @Column(name = "task_period_id", nullable = false)
    private Long taskPeriodId;

    @Comment("执行缴费查询的 oc_task.id，用于追溯 OC 实例和原始结果")
    @Column(name = "oc_task_id")
    private Long ocTaskId;

    @Comment("税种编码（与 task_tax_item.tax_code 一致）")
    @Column(name = "tax_code", length = 50, nullable = false)
    private String taxCode;

    @Comment("税种名称")
    @Column(name = "tax_name", length = 100, nullable = false)
    private String taxName;

    @Comment("应缴税额（元）")
    @Column(name = "tax_amount", precision = 14, scale = 2)
    private BigDecimal taxAmount;

    @Comment("已缴税额（元）")
    @Column(name = "paid_amount", precision = 14, scale = 2)
    private BigDecimal paidAmount;

    @Comment("未缴税额（元）= tax_amount - paid_amount")
    @Column(name = "unpaid_amount", precision = 14, scale = 2)
    private BigDecimal unpaidAmount;

    @Comment("缴款状态：paid / unpaid / partial / na")
    @Column(name = "pay_status", length = 20)
    private String payStatus;

    @Comment("缴款截止日期（税局显示的最后缴款日）")
    @Column(name = "pay_deadline")
    private LocalDate payDeadline;

    @Comment("OC 截图URL列表（JSON数组，缴费查询结果页截图）")
    @Column(name = "screenshot_urls", columnDefinition = "TEXT")
    private String screenshotUrls;

    @Comment("最后查询时间")
    @UpdateTimestamp
    @Column(name = "queried_at")
    private LocalDateTime queriedAt;
}
