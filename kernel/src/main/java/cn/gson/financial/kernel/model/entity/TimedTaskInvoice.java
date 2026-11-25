package cn.gson.financial.kernel.model.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Date;

@Data
@Entity // 标记该类为实体类
@Table(name = "timed_task_invoice") // 指定实体类对应的数据库表名为 timed_task
public class TimedTaskInvoice {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    /**
     * 所属账期
     */
    @Column(name = "check_date")
    private Date checkDate;
    /**
     * 账套id
     */
    @Column(name = "account_sets_id")
    private Long accountSetsId;
    /**
     * 执行结果
     */
    @Column(name = "result")
    private Integer result;
    /**
     * 执行状态
     */
    @Column(name = "state")
    private Integer state;
    /**
     * 失败原因
     */
    @Column(name = "failure_reason")
    private String failureReason;

    /**
     * 创建时间
     */
    @Column(name = "create_time")
    private LocalDateTime createTime;

    /**
     * 结束时间
     */
    @Column(name = "end_time")
    private LocalDateTime endTime;

}
