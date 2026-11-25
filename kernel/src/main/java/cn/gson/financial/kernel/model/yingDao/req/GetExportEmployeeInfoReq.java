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
public class GetExportEmployeeInfoReq extends GetCommonReq{
    @Serial
    private static final long serialVersionUID = -2779212593290677689L;

    /**
     * 类型 例如员工-ry, 工资-gz
     */
    private String type;

    /**
     * 来源 例如影刀-yingdao
     */
    private String source;
}
