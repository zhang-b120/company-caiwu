package cn.gson.financial.timedTasks.domain.resp;

import lombok.Data;

/**
 * 检查报税状态响应类
 */
@Data
public class CheckTaxStatusResp {
    /**
     * 报税状态：1表示已申报（报税任务result为1或报税表tax_state为"已申报"），0表示未申报
     */
    private Integer status;
}

