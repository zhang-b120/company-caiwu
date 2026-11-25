package cn.gson.financial.kernel.model.individualTax.dto;

import cn.hutool.core.bean.BeanUtil;
import lombok.Data;

import java.util.Map;

@Data
public class IndividualTaxSelectDto {
    /**
     * 用户 id
     */
    private Long userId;
    /**
     * 账期
     */
    private String period;
    /**
     * 是否有工资这个筛选条件 没有为0 有为1
     */
    private Integer isSalary;
    /**
     * 是否有人员这个筛选条件 没有为0 有为1
     */
    private Integer isEmployee;

    /**
     * 是否在任务列表筛选条件 不筛选为0 只看任务为1
     */
    private Integer isExist;

    /**
     * 结果筛选条件 不筛选为null 只看成功为2 只看失败为1
     */
    private Integer result;

    /**
     * 审核状态筛选条件 null-不筛选 0-未审核 1-审核通过 2-待更新
     */
    private Integer auditStatus;

    /**
     * 个税状态筛选：true-只显示能报个税的（individualTax为空）
     */
    private Boolean individualTaxCan;

    /**
     * 个税状态筛选：true-只显示不能报个税的（individualTax不为空）
     */
    private Boolean individualTaxCannot;

    public Map<String, Object> toMap() {
        return BeanUtil.beanToMap(this);
    }
}
