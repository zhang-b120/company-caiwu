package cn.gson.financial.kernel.model.vo;

import lombok.Data;

/**
 * @Author zhb
 * @Date 2025-09-17
 * @Description
 */
@Data
public class RecognizeBankExcelVo {

    private String filePath;

    private String businessCode;

    private String businessType;
}