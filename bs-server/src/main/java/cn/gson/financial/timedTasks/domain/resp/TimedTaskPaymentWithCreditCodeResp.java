package cn.gson.financial.timedTasks.domain.resp;

import lombok.Data;

import java.time.LocalDate;

/**
 * 税款任务响应类（包含税号）
 */
@Data
public class TimedTaskPaymentWithCreditCodeResp {
    private Long id;
    private Long accountSetsId;
    private LocalDate checkDate;
    private String creditCode;
    private String province;
    private String companyName;
}

