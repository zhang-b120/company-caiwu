package cn.gson.financial.kernel.model.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Entity // 标记该类为实体类
@Table(name = "timed_task_payment") // 指定实体类对应的数据库表名
public class TimedTaskPayment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 账套id
     */
    @Column(name = "account_sets_id")
    private Long accountSetsId;

    /**
     * 总税额
     */
    @Column(name = "total_taxes")
    private String totalTaxes;

    /**
     * 图片URL
     */
    @Column(name = "img_url", columnDefinition = "text")
    private String imgUrl;

    /**
     * 代缴税款项目HTML
     */
    @Column(name = "tax_payment_html", columnDefinition = "text")
    private String taxPaymentHtml;
    /**
     * 账期
     */
    @Column(name = "check_date")
    private LocalDate checkDate;

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
     * 失败原因
     */
    @Column(name = "failure_reason")
    private String failureReason;

    /**
     * 修改日期
     */
    @Column(name = "update_time")
    private LocalDateTime updateTime;
}
