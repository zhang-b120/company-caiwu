package cn.gson.financial.kernel.model.ua.request;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * @Author zhb
 * @Date 2025-06-20
 * @Description
 */
@Data
@EqualsAndHashCode(callSuper=false)
public class SetZeroDeclareReq extends GetCommonReq{

    /**
     * 是否为零申报
     */

    private Boolean zeroDeclarations;
}