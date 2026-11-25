package cn.gson.financial.kernel.model.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.Comment;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 报税状态子表（每条报税类型一行）。
 * status（展示用）：-1 未加入 / 0 未执行 / 1 进行中 / 2 失败 / 3 成功
 * result（执行结果）：-1 未加入 / 0 未执行 / 1 成功 / 2 失败
 */
@Data
@Entity
@Table(name = "account_sets_tax_status_item")
@Comment("报税状态子表（每条报税类型一行）")
public class AccountSetsTaxStatusItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Comment("主键（对应原表 tax_id）")
    private Long id;

    @Column(name = "tax_status_id", nullable = false)
    @Comment("报税主表ID")
    private Long taxStatusId;

    @Column(name = "account_sets_id")
    @Comment("账套ID（冗余便于查询）")
    private Long accountSetsId;

    @Column(name = "company_name", length = 100)
    @Comment("账套名称（可冗余）")
    private String companyName;

    @Column(name = "tax_type", length = 100)
    @Comment("报税类型/税种")
    private String taxType;

    @Column(name = "tax_state", length = 100)
    @Comment("原始报税状态文案")
    private String taxState;

    @Column(name = "tax_end_time")
    @Comment("报税截止时间")
    private LocalDate taxEndTime;

    @Column(name = "status")
    @Comment("报税状态：-1 未加入 0 未执行 1 进行中 2 失败 3 成功")
    private Integer status = -1;

    @Column(name = "result")
    @Comment("报税执行结果：-1 未加入 0 未执行 1 成功 2 失败")
    private Integer result = -1;

    @Column(name = "screenshot_url", length = 255)
    @Comment("报税截图URL")
    private String screenshotUrl;

    @Column(name = "result_message", length = 500)
    @Comment("结果描述或失败原因")
    private String resultMessage;

    @Column(name = "create_time")
    @Comment("创建时间")
    private LocalDateTime createTime;

    @Column(name = "update_time")
    @Comment("更新时间")
    private LocalDateTime updateTime;

    @Column(name = "deleted")
    @Comment("是否删除：0 否 1 是")
    private Integer deleted = 0;
}

