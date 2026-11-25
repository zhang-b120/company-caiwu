package cn.gson.financial.kernel.model.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 任务工作台 - 账期任务主表
 *
 * <p>每个账套每个账期一条记录，汇总 8 大步骤的整体状态。
 * 各步骤的明细数据存放在对应的子表中，主表仅保留聚合状态。</p>
 *
 * <pre>
 * 步骤映射：
 *   step_verify     → task_verify_result（1:1，核验税务结果明细）
 *   step_init       → 主表字段即可，无子表
 *   step_data       → task_data_sub（1:N，发票/银行/工资子任务）
 *   step_bookkeep   → task_bookkeep_sub（1:N，发票/银行/工资/资产凭证子任务）
 *   step_tax_national → task_tax_item（1:N，每个税种的申报记录）
 *   step_tax_iit    → task_iit_sub（1:N，A表/B表/C表）
 *   step_payment    → task_payment_item（1:N，每个税种的缴款状态）
 *   step_annual_report → 无子表（工商年报，按年申报，非必做置 na）
 * </pre>
 *
 * <pre>
 * 状态枚举（所有步骤通用）：
 *   pending  - 未开始
 *   running  - 执行中
 *   done     - 完成
 *   error    - 失败/异常
 *   skipped  - 跳过（如本期无需申报）
 *   na       - 不适用（如当前账套无此税种）
 * </pre>
 */
@Getter
@Setter
@Entity
@Table(name = "task_period",
        uniqueConstraints = @UniqueConstraint(columnNames = {"account_set_id", "period"}))
@Comment("任务工作台-账期任务主表")
public class TaskPeriod implements Serializable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Comment("主键，自增")
    private Long id;

    @Comment("账套ID，关联 account_sets.id")
    @Column(name = "account_set_id", nullable = false)
    private Long accountSetId;

    @Comment("账期，格式 YYYY-MM，如 2025-12")
    @Column(length = 7, nullable = false)
    private String period;

    // ──────── 步骤1：核验税务 ────────
    @Comment("核验税务状态。明细见 task_verify_result")
    @Column(name = "step_verify", length = 20, nullable = false)
    @ColumnDefault("'pending'")
    private String stepVerify = "pending";

    // ──────── 步骤2：导入期初 ────────
    @Comment("导入期初状态。无子表，结果字段直接在此")
    @Column(name = "step_init", length = 20, nullable = false)
    @ColumnDefault("'pending'")
    private String stepInit = "pending";

    @Comment("期初是否已上传（核验结果写入）：yes / no / unknown")
    @Column(name = "init_uploaded", length = 10)
    private String initUploaded;

    // ──────── 步骤3：获取数据 ────────
    @Comment("获取数据整体状态（由子任务最差值聚合）。明细见 task_data_sub")
    @Column(name = "step_data", length = 20, nullable = false)
    @ColumnDefault("'pending'")
    private String stepData = "pending";

    // ──────── 步骤4：做账 ────────
    @Comment("做账整体状态（由子任务最差值聚合）。明细见 task_bookkeep_sub")
    @Column(name = "step_bookkeep", length = 20, nullable = false)
    @ColumnDefault("'pending'")
    private String stepBookkeep = "pending";

    // STAGE-STATUS-PERSIST：结转/关账完成状态（关账成功内联写 done；独立于做账 step_bookkeep）
    @Comment("结转/关账完成（pending/running/done/error/skipped）")
    @Column(name = "step_carry", length = 20, nullable = false)
    @ColumnDefault("'pending'")
    private String stepCarry = "pending";

    // STAGE-STATUS-PERSIST：月度归档完成状态（报税后归档；区别于年报 step_tax_annual/step_annual_report）
    @Comment("月度归档完成（报税后归档；pending/running/done/error/skipped）")
    @Column(name = "step_archive", length = 20, nullable = false)
    @ColumnDefault("'pending'")
    private String stepArchive = "pending";

    // ──────── 步骤5：国税申报 ────────
    @Comment("国税申报整体状态。明细见 task_tax_item。首先执行 fetch_tax_items 收集税目，再逐个申报")
    @Column(name = "step_tax_national", length = 20, nullable = false)
    @ColumnDefault("'pending'")
    private String stepTaxNational = "pending";

    @Comment("国税税目是否已收集：yes / no。fetch_tax_items 任务完成后置 yes，之后才逐个下发申报任务")
    @Column(name = "tax_items_fetched", length = 5)
    @ColumnDefault("'no'")
    private String taxItemsFetched = "no";

    // ──────── 步骤6：个税申报 ────────
    @Comment("个税申报整体状态。明细见 task_iit_sub（综合所得 + 经营所得）")
    @Column(name = "step_tax_iit", length = 20, nullable = false)
    @ColumnDefault("'pending'")
    private String stepTaxIit = "pending";

    // ──────── 步骤7：缴费查询 ────────
    @Comment("缴费查询整体状态。明细见 task_payment_item")
    @Column(name = "step_payment", length = 20, nullable = false)
    @ColumnDefault("'pending'")
    private String stepPayment = "pending";

    // ──────── 步骤8：工商年报 ────────
    @Comment("工商年报状态。对应 OC 任务类型 annual_business_report。" +
            "工商年报按自然年申报，非每月必做，不需要时置 na")
    @Column(name = "step_annual_report", length = 20, nullable = false)
    @ColumnDefault("'na'")
    private String stepAnnualReport = "na";

    // ──────── 步骤9：税务年报 ────────
    @Comment("税务年报状态（财务报表年报 + 企业所得税年报）。" +
            "税务年报按自然年申报，任意月份可下发。明细见 task_tax_annual_item")
    @Column(name = "step_tax_annual", length = 20, nullable = false)
    @ColumnDefault("'pending'")
    private String stepTaxAnnual = "pending";

    // ──────── 申报完成标记 ────────
    @Comment("本征期申报是否完成（人工标记）：false=未完成，true=已完成")
    @Column(name = "declare_completed", nullable = false)
    @ColumnDefault("false")
    private Boolean declareCompleted = false;

    // ──────── 步骤"已核查"标记（人工核查，与执行状态完全独立） ────────
    @Comment("核验税务-已核查标记（人工）：false=未核查，true=已核查")
    @Column(name = "step_verify_checked", nullable = false)
    @ColumnDefault("false")
    private Boolean stepVerifyChecked = false;

    @Comment("导入期初-已核查标记（人工）")
    @Column(name = "step_init_checked", nullable = false)
    @ColumnDefault("false")
    private Boolean stepInitChecked = false;

    @Comment("获取数据-已核查标记（人工）")
    @Column(name = "step_data_checked", nullable = false)
    @ColumnDefault("false")
    private Boolean stepDataChecked = false;

    @Comment("做账-已核查标记（人工）")
    @Column(name = "step_bookkeep_checked", nullable = false)
    @ColumnDefault("false")
    private Boolean stepBookkeepChecked = false;

    @Comment("国税申报-已核查标记（人工）")
    @Column(name = "step_tax_national_checked", nullable = false)
    @ColumnDefault("false")
    private Boolean stepTaxNationalChecked = false;

    @Comment("个税申报-已核查标记（人工）")
    @Column(name = "step_tax_iit_checked", nullable = false)
    @ColumnDefault("false")
    private Boolean stepTaxIitChecked = false;

    @Comment("缴费-已核查标记（人工）")
    @Column(name = "step_payment_checked", nullable = false)
    @ColumnDefault("false")
    private Boolean stepPaymentChecked = false;

    @Comment("税务年报-已核查标记（人工）")
    @Column(name = "step_tax_annual_checked", nullable = false)
    @ColumnDefault("false")
    private Boolean stepTaxAnnualChecked = false;

    // ──────── 运维/调度字段 ────────
    @Comment("指定 OpenClaw 实例编号（如 OC-01），为空则由任意可用实例处理")
    @Column(name = "oc_instance_id", length = 32)
    private String ocInstanceId;

    @Comment("最近出错的步骤名称（如 step_data），用于界面高亮")
    @Column(name = "error_step", length = 32)
    private String errorStep;

    @Comment("最近错误信息")
    @Column(name = "error_msg", columnDefinition = "TEXT")
    private String errorMsg;

    @Comment("记录创建时间")
    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Comment("最后更新时间")
    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    // FN-PREDECLARE-CONFIRM-FLAG：预申报（报税前出申报表预览给财务确认）已确认标记 + 审计
    @Comment("预申报财务已确认（报税前 gate 持久化/防重报）")
    @Column(name = "pre_declare_confirmed", nullable = false)
    @ColumnDefault("false")
    private Boolean preDeclareConfirmed = false;

    @Comment("预申报确认时间")
    @Column(name = "pre_declare_confirmed_at")
    private LocalDateTime preDeclareConfirmedAt;

    @Comment("预申报确认操作人（BT 传入或系统标识；@SaIgnore 端点取不到登录人时回退）")
    @Column(name = "pre_declare_confirmed_by", length = 64)
    private String preDeclareConfirmedBy;
}
