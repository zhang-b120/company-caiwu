package cn.gson.financial.kernel.model.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.Comment;

import java.time.LocalDateTime;

/**
 * 取票状态子表（按发票种类一行）。
 * status（展示用）：-1 未加入 / 0 未执行 / 1 进行中 / 2 失败 / 3 成功
 * result（执行结果）：-1 未加入 / 0 未执行 / 1 成功 / 2 失败
 */
@Data
@Entity
@Table(name = "account_sets_invoice_status_item")
@Comment("取票状态子表（按发票种类一行）")
public class AccountSetsInvoiceStatusItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Comment("主键")
    private Long id;

    @Column(name = "invoice_status_id", nullable = false)
    @Comment("取票主表ID")
    private Long invoiceStatusId;

    @Column(name = "account_sets_id")
    @Comment("账套ID（冗余便于查询）")
    private Long accountSetsId;

    @Column(name = "invoice_type", length = 100)
    @Comment("发票种类")
    private String invoiceType;

    @Column(name = "screenshot_url", length = 255)
    @Comment("取票截图URL")
    private String screenshotUrl;

    @Column(name = "output_tax_total", precision = 18, scale = 2)
    @Comment("销项税额合计")
    private java.math.BigDecimal outputTaxTotal;

    @Column(name = "input_tax_total", precision = 18, scale = 2)
    @Comment("进项税额合计")
    private java.math.BigDecimal inputTaxTotal;

    @Column(name = "diff_tax_total", precision = 18, scale = 2)
    @Comment("销进项差额：销项-进项")
    private java.math.BigDecimal diffTaxTotal;

    @Column(name = "status")
    @Comment("取票状态：-1 未加入 0 未执行 1 进行中 2 失败 3 成功")
    private Integer status = -1;

    @Column(name = "result")
    @Comment("取票执行结果：-1 未加入 0 未执行 1 成功 2 失败")
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

    @Column(name = "deleted")
    @Comment("是否删除：0 否 1 是")
    private Integer deleted = 0;
}

