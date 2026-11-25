package cn.gson.financial.timedTasks.domain.req;

import lombok.Data;

/**
 * 检查报税状态请求类
 */
@Data
public class CheckTaxStatusReq {
    /**
     * 账期（格式：yyyy-MM-dd）
     */
    private String checkDate;
    
    /**
     * 账套ID
     */
    private Long accountSetsId;
    
    /**
     * 税种
     */
    private String taxType;
}

