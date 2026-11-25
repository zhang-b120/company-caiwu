package cn.gson.financial.kernel.model.crm.req;

import cn.gson.financial.kernel.model.crm.dto.TaxTableDTO;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * @Author zhb
 * @Date 2025-07-06
 * @Description
 */
@EqualsAndHashCode(callSuper = true)
@Data
public class AddTaskListReq extends GetCommonReq{
    private static final long serialVersionUID = -4736063500516328962L;


    /**
     * 公司名
     */
    private String companyName;

    /**
     * 税号
     */
    private String creditCode;

    /**
     * 省份
     */
    private String province;

    /**
     * 电税密码
     */
    private String electricityTaxCode;

    /**
     * 增值税种类
     */
    private String vatType;

    /**
     * 电税手机号
     */
    private String electricityTaxPhone;

    private List<TaxTableDTO> taxTableDTOList;
}
