package cn.gson.financial.kernel.model.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 定时任务报税项实体
 */
@Data
@Entity
@Table(name = "timed_task_tax_item")
public class TimedTaskTaxItem {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 账套id
     */
    @Column(name = "account_sets_id")
    private Long accountSetsId;

    /**
     * 执行状态 0是未执行 1是执行完成
     */
    @Column(name = "state", columnDefinition = "int DEFAULT 0")
    private Integer state;

    /**
     * 执行结果 2是失败 1是成功 0是未执行
     */
    @Column(name = "result", columnDefinition = "int")
    private Integer result;

    /**
     * 图片URL
     */
    @Column(name = "img_url", columnDefinition = "text")
    private String imgUrl;

    /**
     * 失败原因
     */
    @Column(name = "failure_reason")
    private String failureReason;

    /**
     * 账期
     */
    @Column(name = "check_date")
    private LocalDate checkDate;

    /**
     * 修改日期
     */
    @Column(name = "update_time")
    private LocalDateTime updateTime;
}

