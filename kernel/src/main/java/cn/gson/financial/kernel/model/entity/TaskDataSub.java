package cn.gson.financial.kernel.model.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Comment;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 获取数据子任务表（步骤3详情）
 *
 * <p>每个账期固定 3 条：invoice（发票）/ bank（银行流水）/ salary（工资）。
 * task_period.step_data 的值由本表 3 条状态中最差值聚合而来：
 * error > running > pending > done。</p>
 *
 * <pre>
 * sub_type 对应的 OC 任务类型（oc_task.task_type）：
 *   invoice → download_invoice  （OC从税局下载发票，回调后系统调 JSON 导入接口）
 *   bank    → parse_bank        （OC解析银行流水导入文件，回调后系统写入流水表）
 *   salary  → copy_salary       （OC从工资系统复制工资数据，回调后系统写入工资表）
 * </pre>
 */
@Getter
@Setter
@Entity
@Table(name = "task_data_sub",
        uniqueConstraints = @UniqueConstraint(columnNames = {"task_period_id", "sub_type"}))
@Comment("获取数据子任务-步骤3详情")
public class TaskDataSub implements Serializable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Comment("主键，自增")
    private Long id;

    @Comment("关联 task_period.id")
    @Column(name = "task_period_id", nullable = false)
    private Long taskPeriodId;

    @Comment("子任务类型：invoice（下载发票）/ bank（银行流水）/ salary（工资）")
    @Column(name = "sub_type", length = 20, nullable = false)
    private String subType;

    @Comment("执行此子任务的 oc_task.id，用于追溯 OC 实例、原始结果、重试记录")
    @Column(name = "oc_task_id")
    private Long ocTaskId;

    @Comment("子任务状态：pending / running / done / error / skipped")
    @Column(length = 20, nullable = false)
    @ColumnDefault("'pending'")
    private String status = "pending";

    @Comment("结果摘要（JSON），各子任务字段不同。invoice：{count}；bank：{count}；salary：{count}")
    @Column(name = "result_json", columnDefinition = "TEXT")
    private String resultJson;

    @Comment("OC 截图URL列表（JSON数组，如 [\"url1\",\"url2\"]）")
    @Column(name = "screenshot_urls", columnDefinition = "TEXT")
    private String screenshotUrls;

    @Comment("错误信息")
    @Column(name = "error_msg", columnDefinition = "TEXT")
    private String errorMsg;

    @Comment("最后执行时间")
    @Column(name = "executed_at")
    private LocalDateTime executedAt;
}
