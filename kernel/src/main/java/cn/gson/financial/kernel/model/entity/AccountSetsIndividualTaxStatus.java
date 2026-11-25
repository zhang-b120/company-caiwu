package cn.gson.financial.kernel.model.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.Comment;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 个税状态主表（按账套 + 账期一行）。
 * status（展示用）：-1 未加入 / 0 未执行 / 1 进行中 / 2 失败 / 3 成功
 * result（执行结果）：-1 未加入 / 0 未执行 / 1 成功 / 2 失败
 */
@Data
@Entity
@Table(name = "account_sets_individual_tax_status", uniqueConstraints = {
        @UniqueConstraint(name = "uk_as_individual_tax_status_as_period", columnNames = {"account_sets_id", "period"})
})
@Comment("个税状态主表（按账套+账期一行）")
public class AccountSetsIndividualTaxStatus {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Comment("主键")
    private Long id;

    @Column(name = "account_sets_id", nullable = false)
    @Comment("账套ID")
    private Long accountSetsId;

    @Column(name = "period", nullable = false)
    @Comment("账期（月份首日）")
    private LocalDate period;

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

