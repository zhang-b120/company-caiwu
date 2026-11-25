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
 * flow 新模型主轴：账套 × 账期一行，11 步状态聚合 + 预申报确认 gate。
 * <p>FN-FLOW-01。物理表名经 PhysicalNamingStrategy 加前缀 → {@code fxy_financial_flow_period}。
 * <p><b>彻底独立</b>：本表不回写 / 不同步 {@code task_period}，零耦合（2026-06-13 jianye 拍板）。
 * <p>{@code st_*} 状态取值：{@code pending/running/done/failed/skipped/na}；
 * 由各阶段明细行最差值聚合（FN-FLOW-03）。
 */
@Getter
@Setter
@Entity
@Table(name = "flow_period",
        uniqueConstraints = @UniqueConstraint(name = "uk_acc_period", columnNames = {"account_set_id", "period"}))
@Comment("流程主轴-账套账期任务")
public class FlowPeriod implements Serializable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Comment("账套ID")
    @Column(name = "account_set_id", nullable = false)
    private Long accountSetId;

    @Comment("账期 YYYY-MM")
    @Column(name = "period", length = 7, nullable = false)
    private String period;

    // ── 11 步状态：pending/running/done/failed/skipped/na ──

    @Comment("登录税局")
    @Column(name = "st_login", length = 20, nullable = false)
    @ColumnDefault("'pending'")
    private String stLogin = "pending";

    @Comment("核验")
    @Column(name = "st_verify", length = 20, nullable = false)
    @ColumnDefault("'pending'")
    private String stVerify = "pending";

    @Comment("期初导入")
    @Column(name = "st_init", length = 20, nullable = false)
    @ColumnDefault("'pending'")
    private String stInit = "pending";

    @Comment("采集")
    @Column(name = "st_collect", length = 20, nullable = false)
    @ColumnDefault("'pending'")
    private String stCollect = "pending";

    @Comment("做账")
    @Column(name = "st_book", length = 20, nullable = false)
    @ColumnDefault("'pending'")
    private String stBook = "pending";

    @Comment("结转/关账")
    @Column(name = "st_carry", length = 20, nullable = false)
    @ColumnDefault("'pending'")
    private String stCarry = "pending";

    @Comment("预申报验证")
    @Column(name = "st_predeclare", length = 20, nullable = false)
    @ColumnDefault("'pending'")
    private String stPredeclare = "pending";

    @Comment("国税申报")
    @Column(name = "st_declare_vat", length = 20, nullable = false)
    @ColumnDefault("'pending'")
    private String stDeclareVat = "pending";

    @Comment("个税申报")
    @Column(name = "st_declare_iit", length = 20, nullable = false)
    @ColumnDefault("'pending'")
    private String stDeclareIit = "pending";

    @Comment("缴款")
    @Column(name = "st_payment", length = 20, nullable = false)
    @ColumnDefault("'pending'")
    private String stPayment = "pending";

    @Comment("归档")
    @Column(name = "st_archive", length = 20, nullable = false)
    @ColumnDefault("'pending'")
    private String stArchive = "pending";

    // ── 预申报财务确认 gate（报税前闸门，防重报）──

    @Comment("预申报已确认")
    @Column(name = "predeclare_confirmed", nullable = false)
    @ColumnDefault("0")
    private Boolean predeclareConfirmed = false;

    @Column(name = "predeclare_confirmed_at")
    private LocalDateTime predeclareConfirmedAt;

    @Column(name = "predeclare_confirmed_by", length = 64)
    private String predeclareConfirmedBy;

    // ── 人工核查标记（各步 {step:bool}，灵活不加 11 列）──

    @Comment("人工已核查标记，形如 {\"verify\":true,\"book\":false}")
    @Column(name = "checks_json", columnDefinition = "json")
    private String checksJson;

    @Comment("当前所处步骤")
    @Column(name = "current_stage", length = 20)
    private String currentStage;

    @Comment("最近出错步骤")
    @Column(name = "error_stage", length = 20)
    private String errorStage;

    @Column(name = "error_msg", columnDefinition = "TEXT")
    private String errorMsg;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
