package cn.gson.financial.kernel.model.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.Comment;

import java.time.LocalDateTime;

/**
 * 个税状态子表（按个税种类一行）。
 * status（展示用）：-1 未加入 / 0 未执行 / 1 进行中 / 2 失败 / 3 成功
 * result（执行结果）：-1 未加入 / 0 未执行 / 1 成功 / 2 失败
 */
@Data
@Entity
@Table(name = "account_sets_individual_tax_status_item")
@Comment("个税状态子表（按个税种类一行）")
public class AccountSetsIndividualTaxStatusItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Comment("主键")
    private Long id;

    @Column(name = "individual_tax_status_id", nullable = false)
    @Comment("个税主表ID")
    private Long individualTaxStatusId;

    @Column(name = "account_sets_id")
    @Comment("账套ID（冗余便于查询）")
    private Long accountSetsId;

    @Column(name = "tax_type", length = 100)
    @Comment("个税种类")
    private String taxType;

    @Column(name = "screenshot_url", length = 255)
    @Comment("截图URL")
    private String screenshotUrl;

    @Column(name = "status")
    @Comment("个税状态：-1 未加入 0 未执行 1 进行中 2 失败 3 成功")
    private Integer status = -1;

    @Column(name = "result")
    @Comment("个税执行结果：-1 未加入 0 未执行 1 成功 2 失败")
    private Integer result = -1;

    @Column(name = "result_message", length = 500)
    @Comment("结果描述或失败原因")
    private String resultMessage;

    @Column(name = "create_time")
    @Comment("创建时间")
    private LocalDateTime createTime;

    @Column(name = "update_time")
    @Comment("更新时间")
    private LocalDateTime updateTime;
}

