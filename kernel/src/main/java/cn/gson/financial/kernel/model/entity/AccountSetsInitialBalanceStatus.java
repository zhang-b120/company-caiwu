package cn.gson.financial.kernel.model.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.Comment;

import java.time.LocalDateTime;

/**
 * 期初录入状态表（跟账套走，不跟账期）。
 * status（展示用）：-1 未加入 / 0 未执行 / 1 进行中 / 2 失败 / 3 成功
 * result（执行结果）：-1 未加入 / 0 未执行 / 1 成功 / 2 失败
 */
@Data
@Entity
@Table(name = "account_sets_initial_balance_status", uniqueConstraints = {
        @UniqueConstraint(name = "uk_as_initial_balance_account_sets", columnNames = {"account_sets_id"})
})
@Comment("期初录入状态表（按账套一行）")
public class AccountSetsInitialBalanceStatus {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Comment("主键")
    private Long id;

    @Column(name = "account_sets_id", nullable = false)
    @Comment("账套ID")
    private Long accountSetsId;

    @Column(name = "status")
    @Comment("期初状态：-1 未加入 0 未执行 1 进行中 2 失败 3 成功")
    private Integer status = -1;

    @Column(name = "result")
    @Comment("期初执行结果：-1 未加入 0 未执行 1 成功 2 失败")
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

