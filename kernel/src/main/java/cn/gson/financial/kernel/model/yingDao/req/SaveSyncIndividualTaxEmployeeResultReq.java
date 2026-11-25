package cn.gson.financial.kernel.model.yingDao.req;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;

/**
 * @Author zhb
 * @Date 2025-09-04
 * @Description
 */
@EqualsAndHashCode(callSuper = true)
@Data
public class SaveSyncIndividualTaxEmployeeResultReq extends GetCommonReq{
    @Serial
    private static final long serialVersionUID = 406861858674437930L;

    /**
     * 结果 0-成功 1-失败
     */
    private Integer result;

    /**
     * 类型 例如员工-ry, 工资-gz
     */
    private String type;

    /**
     * 来源 例如影刀-yingdao
     */
    private String source;

    private String reason;
}
