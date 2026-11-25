package cn.gson.financial.timedTasks.domain.resp;

import lombok.Data;

@Data
public class AddTaxPayerResp {
    /**
     * 账套id
     */
    private Long accountSetsId;
    /**
     * 税号
     */
    private String creditCode;
    /**
     * 省份
     */
    private String province;
    /**
     * 公司名称
     */
    private String companyName;
}
