package cn.gson.financial.timedTasks.domain.resp;

import lombok.Data;

@Data
public class TimedTaskOfPaymentResp {
    private String companyName;
    private Long accountSetsId;
    private String province;
    private String creditCode;
}
