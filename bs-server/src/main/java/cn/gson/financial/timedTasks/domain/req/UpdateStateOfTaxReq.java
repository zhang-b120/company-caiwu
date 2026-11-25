package cn.gson.financial.timedTasks.domain.req;

import lombok.Data;

@Data
public class UpdateStateOfTaxReq {
    /**
     * 报税任务表id
     */
    private Long id;
    /**
     * 失败原因
     */
    private String failureReason;
    /**
     * 图片
     */
    private String imgUrl;
    /**
     * 执行结果 0 未执行 1成功 2失败
     */
    private Integer result;
    /**
     * 执行状态 0 未执行 1执行完成
     */
    private Integer state;
}
