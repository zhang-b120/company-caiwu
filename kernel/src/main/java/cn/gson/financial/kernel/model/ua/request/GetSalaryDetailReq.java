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
public class GetSalaryDetailReq extends GetCommonReq{

    /**
     * 工资ID
     */
    private Long salaryId;
}
