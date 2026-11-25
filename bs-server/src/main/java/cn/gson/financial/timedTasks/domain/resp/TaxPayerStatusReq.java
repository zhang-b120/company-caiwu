package cn.gson.financial.timedTasks.domain.resp;

import lombok.Data;

@Data
public class TaxPayerStatusReq {
    /**
     * 账套id
     */
    private Long accountSetsId;
    /**
     * 税号
     */
    private String creditCode;
    /**
     * 手机号标志 1-手机号1, 2-手机号2, 3-手机号3
     */
    private String flag;
    /**
     * 手机号状态（要设置的手机号值）
     */
    private String status;
}
