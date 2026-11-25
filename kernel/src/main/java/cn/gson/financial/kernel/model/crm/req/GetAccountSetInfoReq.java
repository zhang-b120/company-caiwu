package cn.gson.financial.kernel.model.crm.req;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.apache.commons.lang3.StringUtils;

import java.util.Map;

/**
 * @Author zhb
 * @Date 2025-06-30
 * @Description
 */
@Data
@EqualsAndHashCode(callSuper=true)
public class GetAccountSetInfoReq extends GetCommonReq {

    private static final long serialVersionUID = -8223050986902371547L;

    /**
     * 账套ID
     */
    private Long accountSetsId;

    /**
     * 公司名称
     */
    private String companyName;

    /**
     * 是否零申报
     */
    private Boolean zeroDeclarations;
    /**
     * 手机号
     */
    private String mobile;

    /**
     * 纳税人类型
     */
    private String vatType;

    /**
     * 做账状态
     */
    private String checkOutStatus;

    /**
     * 省份
     */
    private String province;
}