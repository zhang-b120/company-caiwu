package cn.gson.financial.timedTasks.domain.resp;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 发票销项/进项汇总结果
 * 用于前端按账套展示省份、企业以及税额情况
 */
@Data
public class InvoiceTaxSummaryResp {

    /**
     * 账套ID
     */
    private Long accountSetsId;

    /**
     * 省份（如 beijing、shandong）
     */
    private String province;

    /**
     * 公司名称
     */
    private String companyName;

    /**
     * 销项税额合计
     */
    private BigDecimal outputTaxSum;

    /**
     * 进项税额合计
     */
    private BigDecimal inputTaxSum;

    /**
     * 销进项差额 = 销项 - 进项
     */
    private BigDecimal taxDiff;

    /**
     * 是否需要找进项：需要找进项 / 不需要
     */
    private String needInputFlag;

    /**
     * 取票结果（任务表 result 字段）
     */
    private Integer ticketResult;

    /**
     * 取票失败原因
     */
    private String failureReason;

    /**
     * 更新时间（取票任务结束时间 end_time）
     */
    private LocalDateTime updateTime;
}

