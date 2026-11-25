package cn.gson.financial.timedTasks.domain.resp;

import lombok.Data;

@Data
public class AgentChannelInfoResp {

    /**
     * 公司名称
     */
    private String companyName;

    /**
     * 省份
     */
    private String province;

    /**
     * 统一社会信用代码
     */
    private String creditCode;
}
