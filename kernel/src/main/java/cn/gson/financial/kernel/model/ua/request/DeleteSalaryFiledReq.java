package cn.gson.financial.kernel.model.ua.request;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * @Author zhb
 * @Date 2025-05-16
 * @Description
 */
@EqualsAndHashCode(callSuper = true)
@Data
public class DeleteSalaryFiledReq extends GetCommonReq{

    /**
     * 字段ID
     */
    private Long filedId;
}
