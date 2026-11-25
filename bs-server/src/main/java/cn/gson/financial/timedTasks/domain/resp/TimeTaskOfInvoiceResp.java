package cn.gson.financial.timedTasks.domain.resp;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDate;

@Data
public class TimeTaskOfInvoiceResp implements Serializable {
    /**
     * 单位名称
     */
    private String companyName;
    /**
     * 省份
     */
    private String province;
    /**
     * 信用代码
     */
    private String creditCode;
    /**
     * 增值税种类
     * 小规模纳税人、一般纳税人
     */
    private String vatType;
    /**
     * 账套id
     */
    private Long accountSetsId; // 账户集合ID字段

    /**
     * 任务ID
     */
    private Long taskId;

    /**
     * 取票开始时间
     */
    private LocalDate checkDate;
}
