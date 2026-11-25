package cn.gson.financial.timedTasks.domain.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * @Author zhb
 * @Date 2025-10-30
 * @Description
 */
@Data
public class TaskInvoiceResultDTO {

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

    //截图
    private String invoiceImg;
}
