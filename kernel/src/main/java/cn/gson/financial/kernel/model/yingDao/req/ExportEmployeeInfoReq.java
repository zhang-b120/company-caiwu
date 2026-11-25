package cn.gson.financial.kernel.model.yingDao.req;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;

/**
 * @Author zhb
 * @Date 2025-09-02
 * @Description
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ExportEmployeeInfoReq extends GetCommonReq{

    @Serial
    private static final long serialVersionUID = 1689339462611158096L;
    /**
     * 文件名
     */
    private String fileName;
}