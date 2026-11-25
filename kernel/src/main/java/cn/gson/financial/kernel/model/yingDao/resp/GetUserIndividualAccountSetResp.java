package cn.gson.financial.kernel.model.yingDao.resp;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * @Author zhb
 * @Date 2025-09-01
 * @Description
 */
@Data
public class GetUserIndividualAccountSetResp implements Serializable {
    @Serial
    private static final long serialVersionUID = 488563402859261469L;
    /**
     * 个税任务表的id
     */
    private Long id;

    /**
     * 账套ID
     */
    private Long accountSetsId;

    /**
     * 公司名称
     */
    private String companyName;

    /**
     * 税号
     */
    private String creditCode;

    /**
     * 个税密码
     */
    private String individualTaxCode;

    /**
     * 申报数量
     */
    private Integer declareCount;

    /**
     * 账期（上个月的第一天，格式：YYYY-MM-DD）
     */
    public String period;
}
