package cn.gson.financial.kernel.model.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.GenericGenerator;
import org.hibernate.annotations.UpdateTimestamp;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * FN-04 开票任务
 * <p>
 * 客户申请 → 客服审核 → 批准 → 执行（RPA 跑国税开票系统） →
 * 成功（SUCCESS）/ 失败（FAILED）/ 红字待开（RED_PENDING）
 * <p>
 * 状态机：
 *   CUSTOMER_REQUESTED → CS_REVIEWING → APPROVED → EXECUTING → SUCCESS / FAILED / RED_PENDING
 * <p>
 * 业务约束：
 *   - 所有任务都必须 APPROVED → EXECUTING（不能跳过审核）
 *   - 红字发票（is_red_invoice=true）额外要求：必须确实经过 CS_REVIEWING（reviewer_id != null）
 *     才能进 EXECUTING，防止"先批后审"的内部捷径
 * <p>
 * 给 BT-15 / WX-01 / FM-04 提供共享的开票任务模型 + 状态机 + REST API。
 */
@Getter
@Setter
@Entity
@Table(name = "invoice_task",
        indexes = {
                @Index(name = "idx_status", columnList = "status"),
                @Index(name = "idx_customer", columnList = "customer_id"),
                @Index(name = "idx_account_sets_status", columnList = "account_sets_id, status"),
                @Index(name = "idx_red_original", columnList = "original_invoice_id"),
                @Index(name = "uk_task_order_no", columnList = "order_no", unique = true)
        })
@Comment("开票任务（FN-04）")
public class InvoiceTask implements Serializable {

    @Id
    @GenericGenerator(name = "CosIdGenerator", strategy = "cn.gson.financial.kernel.model.hibernate.CosIdGenerator")
    @GeneratedValue(generator = "CosIdGenerator")
    @Comment("主键（雪花 ID）")
    private Long id;

    @Comment("账套 ID")
    @Column(name = "account_sets_id", nullable = false)
    private Long accountSetsId;

    /**
     * FN-INV-T1（132）：关联 collect 的订单号；扫码直开任务必填，历史 WX/审核流任务可空。
     * uk_task_order_no UNIQUE 保证同订单只产生一条任务（幂等）。
     */
    @Comment("关联 invoice_collect_request.order_no（扫码直开必填）")
    @Column(name = "order_no", length = 64)
    private String orderNo;

    /**
     * FN2-T2（135）：账套省份代码，bridge 从 AccountSets.province 解析后盖上；
     * brower-tax 据此选哪个省的电子税务局登录。
     */
    @Comment("账套省份代码（如\"四川\"），BT 据此选电税局")
    @Column(name = "region_code", length = 16)
    private String regionCode;

    /**
     * FN2-T3（135）：任务来源；扫码直开任务置 {@code scan-direct}，旧 CS 审核流置 {@code cs-review}/null。
     * 替代 132 借 {@code request_remark="scan-direct"} 的字符串约定 — 强类型 + 索引/过滤友好，
     * 防 BT 端覆盖 request_remark 破坏区分。{@code GET /invoice-task?source=} 据此过滤防开错票。
     */
    public static final String SOURCE_SCAN_DIRECT = "scan-direct";
    @Comment("任务来源 scan-direct（扫码直开）/cs-review（旧审核流）/null（历史）")
    @Column(name = "source", length = 32)
    private String source;

    /** FN2-T2（135）：customer_id 改可空，扫码 bridge 按购方税号 match Customer.creditCode 不到时为 NULL */
    @Comment("客户 ID（扫码场景 match 不到时可空）")
    @Column(name = "customer_id")
    private Long customerId;

    @Comment("发票类别 NORMAL/SPECIAL/ELECTRONIC/...")
    @Column(name = "invoice_type", length = 32, nullable = false)
    @ColumnDefault("'NORMAL'")
    private String invoiceType = "NORMAL";

    @Comment("是否红字发票")
    @Column(name = "is_red_invoice", nullable = false)
    @ColumnDefault("0")
    private Boolean isRedInvoice = Boolean.FALSE;

    @Comment("红字关联的原蓝字发票 id")
    @Column(name = "original_invoice_id")
    private Long originalInvoiceId;

    @Comment("状态机")
    @Column(name = "status", length = 32, nullable = false)
    @ColumnDefault("'CUSTOMER_REQUESTED'")
    private String status = "CUSTOMER_REQUESTED";

    @Comment("申请金额（含税）")
    @Column(name = "request_amount", precision = 18, scale = 2)
    private BigDecimal requestAmount;

    @Comment("申请税额")
    @Column(name = "request_tax_amount", precision = 18, scale = 2)
    private BigDecimal requestTaxAmount;

    @Comment("明细 JSON（货物/服务/单价/数量/税率）")
    @Lob
    @Column(name = "items_json", columnDefinition = "LONGTEXT")
    private String itemsJson;

    @Comment("购买方名称")
    @Column(name = "purchaser_name", length = 255)
    private String purchaserName;

    @Comment("购买方统一社会信用代码")
    @Column(name = "purchaser_credit_code", length = 64)
    private String purchaserCreditCode;

    @Comment("申请备注")
    @Column(name = "request_remark", columnDefinition = "TEXT")
    private String requestRemark;

    @Comment("审核员")
    @Column(name = "reviewer_id")
    private Long reviewerId;

    @Comment("审核时间")
    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @Comment("审核意见")
    @Column(name = "review_comment", columnDefinition = "TEXT")
    private String reviewComment;

    @Comment("RPA worker 标识")
    @Column(name = "executor", length = 64)
    private String executor;

    @Comment("开成功后回填发票代码")
    @Column(name = "invoice_code", length = 32)
    private String invoiceCode;

    @Comment("开成功后回填发票号码")
    @Column(name = "invoice_number", length = 32)
    private String invoiceNumber;

    @Comment("失败原因")
    @Lob
    @Column(name = "error_msg", columnDefinition = "LONGTEXT")
    private String errorMsg;

    @Comment("RED_PENDING 原因")
    @Column(name = "red_pending_reason", length = 255)
    private String redPendingReason;

    /**
     * 开票成功后 BT/RPA 回写的 PDF URL（或本地存储路径）。
     * FN-FIX-01 引入，配合 BT-FIX-B3-03 / WX-FIX-04 让客户能在 wx 拿到 PDF。
     * 长度 512 — 容纳一般 https URL 或内网相对路径。
     */
    @Comment("PDF URL（BT 开票成功后回写）")
    @Column(name = "pdf_url", length = 512)
    private String pdfUrl;

    // ─── FN-INVOICE-01 扩展字段 ───────────────────────────────────

    @Comment("AI 推断结果 JSON（goodsCode/taxRate/invoiceType/reason）")
    @Lob
    @Column(name = "ai_classification", columnDefinition = "LONGTEXT")
    private String aiClassification;

    @Comment("开票草稿 JSON（18 字段，用户审核前）")
    @Lob
    @Column(name = "draft_data", columnDefinition = "LONGTEXT")
    private String draftData;

    @Comment("真提交开票时间（RPA 成功后回填）")
    @Column(name = "issued_at")
    private LocalDateTime issuedAt;

    @Comment("RPA 执行日志 JSON（步骤/截图/错误）")
    @Lob
    @Column(name = "rpa_run_log", columnDefinition = "LONGTEXT")
    private String rpaRunLog;

    // ─── FN-T1 申请人联系方式（开票完成后短信+邮件通知用）───────────────

    @Comment("申请人手机号（短信通知用）")
    @Column(name = "applicant_phone", length = 32)
    private String applicantPhone;

    @Comment("申请人邮箱（邮件通知用，可带 PDF 链接）")
    @Column(name = "applicant_email", length = 128)
    private String applicantEmail;

    @Comment("申请人姓名（短信/邮件抬头用）")
    @Column(name = "applicant_name", length = 64)
    private String applicantName;

    @Comment("申请人通知发送时刻（幂等用 FN-T5）")
    @Column(name = "notified_at")
    private LocalDateTime notifiedAt;

    // ─── FN4-T2 开票项目 / 税收分类编码 / 税率（与 invoice-print 字段对齐）───

    @Comment("开票项目名（来自 invoice-print 字典）")
    @Column(name = "item_name", length = 128)
    private String itemName;

    @Comment("商品和服务税收分类编码 19 位")
    @Column(name = "item_code", length = 20)
    private String itemCode;

    @Comment("税率（小数，如 0.0600 = 6%）")
    @Column(name = "tax_rate", precision = 5, scale = 4)
    private BigDecimal taxRate;

    // ─── FN8-T1 红冲发票字段（与 invoice-print IP8-T1 字段名对齐）───

    @Comment("是否已发起红冲申请")
    @Column(name = "is_red_invoice_requested", nullable = false)
    @ColumnDefault("0")
    private Boolean isRedInvoiceRequested = Boolean.FALSE;

    @Comment("红冲源任务 id（红字发票任务指向被红冲的蓝字任务）")
    @Column(name = "red_letter_of_task_id")
    private Long redLetterOfTaskId;

    @Comment("红字发票号")
    @Column(name = "red_invoice_number", length = 32)
    private String redInvoiceNumber;

    @Comment("红字发票 PDF URL")
    @Column(name = "red_pdf_url", length = 512)
    private String redPdfUrl;

    /**
     * 乐观锁版本，防止 RPA worker 并发抢同一条记录导致脏写。
     */
    @Version
    @Column(name = "version", nullable = false)
    @ColumnDefault("0")
    private Long version = 0L;

    @Comment("创建时间")
    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private LocalDateTime createdAt;

    @Comment("更新时间")
    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Comment("提交执行时间（进入 EXECUTING 时回填）")
    @Column(name = "submitted_at")
    private LocalDateTime submittedAt;

    // ═══════════════════════════════════════════════════════════════
    // 状态机常量
    // ═══════════════════════════════════════════════════════════════
    public static final String STATUS_CUSTOMER_REQUESTED = "CUSTOMER_REQUESTED";
    public static final String STATUS_CS_REVIEWING        = "CS_REVIEWING";
    public static final String STATUS_APPROVED            = "APPROVED";
    public static final String STATUS_EXECUTING           = "EXECUTING";
    public static final String STATUS_SUCCESS             = "SUCCESS";
    public static final String STATUS_FAILED              = "FAILED";
    public static final String STATUS_RED_PENDING         = "RED_PENDING";
}
