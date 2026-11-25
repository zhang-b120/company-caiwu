package cn.gson.financial.kernel.model.crm.req;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;

/**
 * @Author zhb
 * @Date 2025-07-05
 * @Description
 */
@Data
public class GetMyAccountSetsReq implements Serializable {

    private static final long serialVersionUID = -4785625275885181341L;

    /**
     * 手机号
     */
    private String phoneNum;

    /**
     * 公司名称
     */
    private String companyName;

    /**
     * 企业类型
     */
    private String valType;

    /**
     * 申报状态
     */
    private String taxState;
    /**
     * 结账日期
     */
    private LocalDate checkDate;
    /**
     * 结账状态 0 未结账 1已结账
     */
    private Integer status;

    /**
     * 省份
     */
    private String province;
}