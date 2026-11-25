package cn.gson.financial.kernel.model.crm.req;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;
import java.io.Serializable;

/**
 * @Author zhb
 * @Date 2025-07-06
 * @Description
 */
@EqualsAndHashCode(callSuper = true)
@Data
public class GetTaxDeclareItemsReq extends GetCommonReq{
    private static final long serialVersionUID = -2013071464415220138L;

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
}
