package cn.gson.financial.kernel.model.individualTax.resp;

import lombok.Data;

import java.util.List;

/**
 * 个税列表响应包装类
 * 包含列表数据和统计数据
 */
@Data
public class IndividualTaxListResp {
    /**
     * 个税列表数据
     */
    private List<IndividualTaxSelectResp> list;

    /**
     * 审核通过数（在任务列表中且已执行的记录中，审核状态为1的数量）
     */
    private Integer auditedCount;

    /**
     * 需要报个税的总账套数（排除individualTax不为空的账套）
     */
    private Integer totalAccountSetsCount;
}

