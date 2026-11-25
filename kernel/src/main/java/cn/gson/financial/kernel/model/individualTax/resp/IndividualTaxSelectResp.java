package cn.gson.financial.kernel.model.individualTax.resp;

import lombok.Data;

@Data
public class IndividualTaxSelectResp {

    /**
     * 账套id
     */
    public Long accountSetsId;

    /**
     * 公司名
     */
    public String companyName;

    /**
     * 账期（上个月的第一天，格式：YYYY-MM-DD）
     */
    public String period;

    /**
     * 是否被计入执行列表 0 不存在 1 存在
     */
    public Integer isExist;


    /**
     * 存在的薪资个数
     */
    public Integer numSalary;


    /**
     * 存在的员工个数
     */
    private Integer numEmployee;


    /**
     * 任务的执行状态
     */
    private Integer result;

    /**
     * 失败原因
     */
    private String failedReason;

    /**
     * 截图名
     */
    private String imgName;

    /**
     * 截图完整URL（后端拼接后返回）
     */
    private String imgUrl;

    /**
     * 审核状态 0-未审核 1-审核通过 2-待更新
     */
    private Integer auditStatus;

    /**
     * 个税状态（是否能报个税）：null或空字符串表示能报，有值表示不能报
     */
    private String individualTax;

    /**
     * 个税密码
     */
    private String individualTaxCode;
}
