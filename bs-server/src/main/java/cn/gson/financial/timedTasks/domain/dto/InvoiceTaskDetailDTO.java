package cn.gson.financial.timedTasks.domain.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * @Author zhb
 * @Date 2025-10-29
 * @Description
 */
@Data
public class InvoiceTaskDetailDTO {

    private Long accountSetsId;

    private String companyName;

    //结果
    private Integer result;

    /**
     * 失败原因
     */
    private String failureReason;

    //发票类型
    private String invoiceType;

    //发票数量
    private Integer count;

    //金额
    private BigDecimal amount;

    //税额
    private BigDecimal taxAmount;

    //原因
    private String reason;

    //系统取的发票数量
    private Integer systemCount;

    //系统取的金额
    private BigDecimal systemAmount;

    //系统取的税额
    private BigDecimal systemTaxAmount;
}
