package cn.gson.financial.kernel.model.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.CreationTimestamp;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * OpenClaw 任务队列
 * 财务系统通过此表向 OpenClaw 下发任务，OpenClaw 轮询执行并回写结果
 */
@Getter
@Setter
@Entity
@Table(name = "oc_task")
@Comment("OpenClaw任务队列")
public class OcTask implements Serializable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Comment("主键，自增，OpenClaw回调时携带")
    private Long id;

    @Comment("任务类型：" +
            "verify_tax（核验税务）/ " +
            "download_invoice（下载发票）/ " +
            "parse_bank（银行流水解析）/ " +
            "copy_salary（工资复制）/ " +
            "fetch_tax_items（收集国税税目）/ " +
            "vat_declare（国税申报，payload含税种明细）/ " +
            "iit_declare（个税申报，payload含iit_password，回调通过iit_type区分：salary=综合所得A表/business=经营所得B/C表）/ " +
            "query_payment（缴费查询）/ " +
            "annual_business_report（工商年报）/ " +
            "tax_annual（税务年报，含财务报表年报+企业所得税年报）")
    @Column(name = "task_type", length = 32, nullable = false)
    private String taskType;

    @Comment("目标客户账套ID")
    @Column(name = "account_set_id", nullable = false)
    private Long accountSetId;

    @Comment("账期，格式 YYYY-MM")
    @Column(length = 7, nullable = false)
    private String period;

    @Comment("关联的 task_period.id")
    @Column(name = "task_period_id")
    private Long taskPeriodId;

    @Comment("队列状态：queued / running / done / failed")
    @Column(length = 20, nullable = false)
    @ColumnDefault("'queued'")
    private String status = "queued";

    @Comment("任务附加参数（JSON），各任务类型字段不同")
    @Column(name = "payload_json", columnDefinition = "TEXT")
    private String payloadJson;

    @Comment("OpenClaw回写的结果（JSON）")
    @Column(name = "result_json", columnDefinition = "TEXT")
    private String resultJson;

    @Comment("OpenClaw上传的截图URL（适用申报类任务）")
    @Column(name = "screenshot_url", length = 512)
    private String screenshotUrl;

    @Comment("指定的 OpenClaw 实例编号（如 OC-01、OC-02），为空则由任意可用实例处理")
    @Column(name = "oc_instance_id", length = 32)
    private String ocInstanceId;

    @Comment("已重试次数，默认0")
    @Column(name = "retry_count", nullable = false)
    @ColumnDefault("0")
    private Integer retryCount = 0;

    @Comment("创建时间")
    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Comment("进入 running（被 OC 领取启动）的时刻；queued 阶段为空。"
            + "用于 running 超时回收计时——只从“真正开始执行”算起，不含排队等待时间，避免误杀排队中的任务")
    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Comment("完成时间")
    @Column(name = "finished_at")
    private LocalDateTime finishedAt;
}
