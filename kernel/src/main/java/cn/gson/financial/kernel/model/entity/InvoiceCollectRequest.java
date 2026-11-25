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
 * FN-LIVE-T3 / FN-U（140）开票收集请求 —— <b>唯一开票任务表</b>（退役 invoice_task 搭桥）。
 * <p>
 * 140 拍板（2026-05-31）：以后只用这一张表当开票任务表，状态 3 态，信息齐直接进"待开票"，
 * BT 直接读本表领任务、开完回写发票号 + 文件路径。<b>不再搭桥建 {@link InvoiceTask}</b>。
 * <ul>
 *   <li><b>3 态</b>：{@code INCOMPLETE}（填写不完整）/ {@code PENDING_ISSUE}（待开票）/ {@code ISSUED}（开票完成）</li>
 *   <li><b>领取锁</b>（{@code lockOwner}/{@code lockedAt}）：BT 开票前原子领取，杜绝同一条被两端各开一次真票；超时自动可再领</li>
 *   <li><b>失败计数</b>（{@code failCount}/{@code lastError}）：失败留 {@code PENDING_ISSUE}，累计到上限不再被领取（parked 转人工）</li>
 *   <li>字段长度 / 精度沿用发票模板，<b>不放宽</b>（真开票被电税拒）</li>
 * </ul>
 */
@Getter
@Setter
@Entity
@Table(name = "invoice_collect_request",
        indexes = {
                @Index(name = "uk_order_no", columnList = "order_no", unique = true),
                @Index(name = "uk_receipt_no", columnList = "receipt_no", unique = true),
                @Index(name = "idx_status_created", columnList = "status, created_at")
        })
@Comment("开票收集请求（FN-LIVE-T3）")
public class InvoiceCollectRequest implements Serializable {

    @Id
    @GenericGenerator(name = "CosIdGenerator", strategy = "cn.gson.financial.kernel.model.hibernate.CosIdGenerator")
    @GeneratedValue(generator = "CosIdGenerator")
    @Comment("主键（雪花 ID）")
    private Long id;

    @Comment("订单号（业务幂等键，UNIQUE）")
    @Column(name = "order_no", length = 64, nullable = false)
    private String orderNo;

    @Comment("开票明细 JSON（PM 规范 11 字段：name/code/spec/unit/qty/price/amount/taxRate/discount/policy/coal）")
    @Lob
    @Column(name = "items_json", columnDefinition = "LONGTEXT", nullable = false)
    private String itemsJson;

    @Comment("抬头-公司名（INCOMPLETE 时 NULL，补抬头时填）")
    @Column(name = "buyer_company", length = 100)
    private String buyerCompany;

    @Comment("抬头-税号（INCOMPLETE 时 NULL）")
    @Column(name = "buyer_tax_no", length = 32)
    private String buyerTaxNo;

    @Comment("抬头-电话（INCOMPLETE 时 NULL）")
    @Column(name = "buyer_phone", length = 20)
    private String buyerPhone;

    @Comment("抬头-邮箱（INCOMPLETE 时 NULL）")
    @Column(name = "buyer_email", length = 100)
    private String buyerEmail;

    @Comment("抬头-地址（选填）")
    @Column(name = "buyer_address", length = 100)
    private String buyerAddress;

    @Comment("抬头-开户银行（选填）")
    @Column(name = "buyer_bank", length = 100)
    private String buyerBank;

    @Comment("抬头-银行账号（选填）")
    @Column(name = "buyer_bank_account", length = 50)
    private String buyerBankAccount;

    @Comment("状态：INCOMPLETE 填写不完整 / PENDING_ISSUE 待开票 / ISSUED 开票完成 / ISSUE_FAILED 开票失败（终态·不重试） / CANCELED 已取消 / FACE_AUTH_PENDING 待扫脸（中间态）")
    @Column(name = "status", length = 32, nullable = false)
    @ColumnDefault("'INCOMPLETE'")
    private String status = STATUS_INCOMPLETE;

    @Comment("回执号（INCOMPLETE 时 NULL，补抬头转 PENDING_ISSUE 时生成；UNIQUE）")
    @Column(name = "receipt_no", length = 64)
    private String receiptNo;

    @Comment("来源 scan-h5/api/manual")
    @Column(name = "source", length = 32)
    private String source;

    /**
     * FN-TAX-T1（171）整单级含税标志，下发 BT 透传填税局（BT 顶层字段同名）。
     * <ul>
     *   <li>{@code '2'} 含税（默认）：价税合计 = Σ amount；税额 = Σ amount×rate/(1+rate)</li>
     *   <li>{@code '1'} 不含税：不含税额 = Σ amount；税额 = Σ amount×rate；价税合计 = 两者和</li>
     * </ul>
     * 红线：默认 '2' 含税——老客户端/存量行不传一律按含税，行为不突变；只有显式 '1' 才走不含税。
     */
    @Comment("含税标志 '2' 含税(默认)/'1' 不含税（整单级，下发 BT 透传填税局）")
    @Column(name = "tax_inclusion", columnDefinition = "CHAR(1)", nullable = false)
    @ColumnDefault("'2'")
    private String taxInclusion = TAX_INCLUSIVE;

    /**
     * FN-INV-T1（173）票种：{@code GENERAL} 普票（默认）/ {@code SPECIAL} 专票。
     * 整单级，下发 BT 顶层字段 {@code invoiceType}；存量行/老客户端不传一律 GENERAL。
     */
    @Comment("票种 GENERAL 普票(默认)/SPECIAL 专票（整单级，下发 BT invoiceType）")
    @Column(name = "invoice_type", length = 16, nullable = false)
    @ColumnDefault("'GENERAL'")
    private String invoiceType = INVOICE_TYPE_GENERAL;

    /**
     * FN-INV-T1（173）发票载体：{@code '01'} 电子（默认）/ {@code '02'} 纸质。
     * 整单级，下发 BT 顶层字段 {@code invoiceCarrier}；存量行/老客户端不传一律 '01'。
     */
    @Comment("发票载体 '01' 电子(默认)/'02' 纸质（整单级，下发 BT invoiceCarrier）")
    @Column(name = "invoice_carrier", columnDefinition = "CHAR(2)", nullable = false)
    @ColumnDefault("'01'")
    private String invoiceCarrier = CARRIER_ELECTRONIC;

    /**
     * FN-INV-T1（173）特定业务（BT 8 项中文枚举）：白酒/客运场站服务/建筑服务/货物运输服务/
     * 不动产销售/拖拉机和联合收割机/不动产经营租赁服务/旅客运输服务；不选 = NULL。
     * 下发 BT 顶层字段 {@code specialBusiness}；null 可不下发。
     */
    @Comment("特定业务（BT 8 项中文枚举，不选为 NULL，下发 BT specialBusiness）")
    @Column(name = "special_business", length = 32)
    private String specialBusiness;

    /**
     * FN-INV-T1（173）差额征税：{@code 差额征税-全额开票} / {@code 差额征税-差额开票}；不选 = NULL。
     * 仅透传标志（BT 把标志填给税局，差额计税由税局侧处理，sumItems 不改）。
     * 下发 BT 顶层字段 {@code differentialTax}；null 可不下发。
     */
    @Comment("差额征税（差额征税-全额开票/差额征税-差额开票，不选为 NULL，下发 BT differentialTax）")
    @Column(name = "differential_tax", length = 32)
    private String differentialTax;

    /**
     * FN2-T1（135）商家账套 id；扫码直开必填，由 invoice-print 在确认打印时带过来。
     * bridge 据此读 AccountSets → 解析省份 → invoice_task.region_code，
     * 同时去掉 132 的 financial.scan-direct.default-account-sets-id=1 占位。
     */
    @Comment("商家账套 id（扫码直开必填）")
    @Column(name = "account_sets_id")
    private Long accountSetsId;

    // ════════════════════════════════════════════════════════════════
    // FN-U（140）单表统一：回写结果 + 并发护栏
    // FN-SLIM：删 region_code/customer_id/request_amount/request_tax_amount（金额现算，省份查 137）
    // ════════════════════════════════════════════════════════════════

    @Comment("回写：发票代码（ISSUED 时）")
    @Column(name = "invoice_code", length = 32)
    private String invoiceCode;

    @Comment("回写：发票号码（ISSUED 时）")
    @Column(name = "invoice_number", length = 32)
    private String invoiceNumber;

    @Comment("回写：一组文件 URL JSON 数组（首个=主发票 PDF，含 OFD/XML/二维码等；FN-DLV-T1 起为中心存储 URL）")
    @Lob
    @Column(name = "invoice_files", columnDefinition = "LONGTEXT")
    private String invoiceFiles;

    /** FN-DLV-T1（182）电税官方下载链接（二维码 URL），作客户交付备用；可空。 */
    @Comment("回写：电税官方下载链接（qrUrl，客户交付备用）")
    @Column(name = "qr_url", length = 1024)
    private String qrUrl;

    /** 248 Part B：扫脸认证二维码图 URL（服务器截图上传后绑定；桌面端取此显示给操作员手机扫）。<b>区别于 {@link #qrUrl} 交付 QR</b>。 */
    @Comment("248：扫脸认证二维码图 URL（服务器截图上传后绑定；桌面端取此显示给操作员手机扫）")
    @Column(name = "face_auth_qr_url", length = 1024)
    private String faceAuthQrUrl;

    /** 268/270：开票存证截图 URL（258 成功页/266 失败现场，上传成功回写；可空）。<b>区别于 {@link #qrUrl} 交付 QR、{@link #faceAuthQrUrl} 扫脸 QR</b>。 */
    @Comment("268/270：开票存证截图 URL（258/266 截图，上传成功回写）")
    @Column(name = "evidence_url", length = 1024)
    private String evidenceUrl;

    /** 248 Part B：进入待扫脸的时刻（供超时/过期判断）。 */
    @Comment("248：进入待扫脸的时刻（供超时/过期判断）")
    @Column(name = "face_auth_at")
    private LocalDateTime faceAuthAt;

    @Comment("开票完成时间")
    @Column(name = "issued_at")
    private LocalDateTime issuedAt;

    /**
     * FN-QTA-T1（163）本票开具后剩余可开票额度（162 成功页抓取，BT 已去千分位）。
     * 账套粒度时间序列快照——"某账套当前额度" = 该账套最近一条 ISSUED 行（按 {@link #issuedAt}）的本列值。
     */
    @Comment("本票开具后剩余可开票额度（162 成功页抓取）")
    @Column(name = "quota_after_issue", precision = 18, scale = 2)
    private BigDecimal quotaAfterIssue;

    /**
     * FN-QTA-T1（163）开票成功页 发票简要信息+额度信息 原样 JSON 快照（中文键名，原样存档）。
     * 快照档案不校验结构，消费端自行解析。
     */
    @Comment("开票成功页 发票简要信息+额度信息 原样JSON快照")
    @Lob
    @Column(name = "issued_brief_json", columnDefinition = "LONGTEXT")
    private String issuedBriefJson;

    @Comment("领取锁：谁领的（BT 实例标识）")
    @Column(name = "lock_owner", length = 64)
    private String lockOwner;

    @Comment("领取锁：领取时刻（超时自动可再领）")
    @Column(name = "locked_at")
    private LocalDateTime lockedAt;

    @Comment("失败计数：到上限不再被领取（parked 转人工）")
    @Column(name = "fail_count", nullable = false)
    @ColumnDefault("0")
    private Integer failCount = 0;

    @Comment("最后一次开票失败原因（截断 512）")
    @Column(name = "last_error", length = 512)
    private String lastError;

    @Comment("最后一次尝试开票时刻")
    @Column(name = "last_attempt_at")
    private LocalDateTime lastAttemptAt;

    @Comment("创建时间（毫秒精度）")
    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private LocalDateTime createdAt;

    @Comment("更新时间")
    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    // ── 3 态枚举（DB 存枚举值，UI 显示中文）─────────────────────────
    /** 填写不完整：明细已存、抬头未齐 / 缺商家账套 */
    public static final String STATUS_INCOMPLETE = "INCOMPLETE";
    /** 待开票：商家+客户信息全齐，等 BT 开 */
    public static final String STATUS_PENDING_ISSUE = "PENDING_ISSUE";
    /** 开票完成：真票已开、发票号+文件已回写 */
    public static final String STATUS_ISSUED = "ISSUED";
    /** FN-1S 开票失败·终态：开票只跑一次，BT 回写失败即置此态，不再被领取/重试（杜绝多开） */
    public static final String STATUS_ISSUE_FAILED = "ISSUE_FAILED";
    /** FN-Q（225）软取消·终态：待开/未齐/失败任务被人工取消（留痕）。CANCELED 天然不在 findClaimableTasks(PENDING_ISSUE) → 自动移出待开队列 */
    public static final String STATUS_CANCELED = "CANCELED";
    /**
     * 248 Part B：待扫脸·中间态——服务器 headless 开票遇扫脸弹窗，截二维码存库等桌面端扫。
     * 天然不在 findClaimableTasks(PENDING_ISSUE) → 不被重复领（照 225 CANCELED 范式）。
     * 扫完出票经 /result → ISSUED；卡住可经 cancel → CANCELED 清理。
     */
    public static final String STATUS_FACE_AUTH_PENDING = "FACE_AUTH_PENDING";

    // ── 含税标志（FN-TAX-T1，171；与 BT 顶层 taxInclusion 同口径）──────
    /** 含税（默认）：提交体不传 / null / 非法值一律落此值 */
    public static final String TAX_INCLUSIVE = "2";
    /** 不含税：仅当显式传 '1' 时 */
    public static final String TAX_EXCLUSIVE = "1";

    // ── 票种区（FN-INV-T1，173；与 BT 顶层 DTO 同口径）──────────────
    /** 票种：普票（默认）*/
    public static final String INVOICE_TYPE_GENERAL = "GENERAL";
    /** 票种：专用发票 */
    public static final String INVOICE_TYPE_SPECIAL = "SPECIAL";
    /** 发票载体：电子（默认）*/
    public static final String CARRIER_ELECTRONIC = "01";
    /** 发票载体：纸质 */
    public static final String CARRIER_PAPER = "02";

    /** @deprecated 140 改 3 态后旧值 COLLECTED 映射为 {@link #STATUS_PENDING_ISSUE}，保留别名避免引用处全炸 */
    @Deprecated
    public static final String STATUS_COLLECTED = STATUS_PENDING_ISSUE;
    /** @deprecated 140 改 3 态后旧值 PENDING_HEADER 映射为 {@link #STATUS_INCOMPLETE} */
    @Deprecated
    public static final String STATUS_PENDING_HEADER = STATUS_INCOMPLETE;
}
