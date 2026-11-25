package cn.gson.financial.kernel.model.crm.dto;

import cn.gson.financial.kernel.model.crm.crmEnum.CodeEnum;
import lombok.Data;

import java.io.Serializable;

/**
 * @Author zhb
 * @Date 2025-07-06
 * @Description
 */
@Data
public class TaxCommonDTO implements Serializable {
    private static final long serialVersionUID = -6106259948554867340L;

    /**
     * 返回码
     */
    private String code = CodeEnum.CODE_200.getCode();

    /**
     * 返回信息
     */
    private String msg = CodeEnum.CODE_200.getMsg();

}