package cn.gson.financial.timedTasks.domain.resp;

import lombok.Data;

@Data
public class TimedTaskDeclareSelfInspectionResp {
    /**
     * 账套id
     */
    private Long accountSetsId;
    /**
     *增值税类型
     */
    private String vatType;
    /**
     * 省份
     */
    private String province;
    /**
     * 统一社会信用代码
     */
    private String creditCode;

}
