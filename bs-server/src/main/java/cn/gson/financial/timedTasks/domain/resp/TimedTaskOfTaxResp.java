package cn.gson.financial.timedTasks.domain.resp;

import lombok.Data;

@Data
public class TimedTaskOfTaxResp {
    private Long accountSetsId;
    private String companyName;
    private String taxType;
    private String province;
    private String creditCode;
    private String sort;
}
