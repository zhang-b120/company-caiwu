package cn.gson.financial.timedTasks.domain.req;

import lombok.Data;

import java.time.LocalDate;

@Data
public class TimedTaskDeclareSelfInspectionReq {

    /**
     * 统一社会信用代码
     */
    private String creditCode;

    /**
     * 自检结果截图
     */
    private String imgUrl;

    /**
     * 问题描述
     */
    private String problemDescription;

    /**
     * 自检账期
     */
    private LocalDate period;

    /**
     * 账套id
     */
    private Long accountSetsId;
}
