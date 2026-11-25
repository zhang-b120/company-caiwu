package cn.gson.financial.timedTasks.domain.resp;

import lombok.Data;

import java.time.LocalDate;

/**
 * 报税任务响应类（包含税号）
 */
@Data
public class TimedTaskTaxWithCreditCodeResp {
    private Long id;
    private Long accountSetsId;
    private String vatType;
    private String taxType;
    private LocalDate checkDate;
    private String creditCode;
    private String province;
    private String companyName;
}

