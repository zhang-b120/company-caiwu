package cn.gson.financial.kernel.model.dto;

import lombok.Data;

/**
 * @Author zhb
 * @Date 2025-09-25
 * @Description
 */
@Data
public class BankLibDto {

    private Long id;

    /**
     * 银行编码
     */
    private String bankCode;

    /**
     * 银行名称
     */
    private String bankName;

    /**
     * 是否启用 0否 1是
     */
    private Integer enabled;

    /**
     * 是否自动识别 0否 1是
     */
    private Integer isAutoRecognize;
}