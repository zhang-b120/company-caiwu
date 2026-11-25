package cn.gson.financial.kernel.model.vo;

import lombok.Data;


/**
 * @Author zhb
 * @Date 2025-09-18
 * @Description
 */
@Data
public class SaveExcelRecognizeVo {

    private Long id;

    /**
     * 业务编码
     */
    private String businessCode;

    /**
     * 业务类型
     */
    private String businessType;

    /**
     * 识别字段
     */
    private String recognizeField;

    /**
     * 识别类型 header-数据表头 skip-数据跳过 end-数据结束
     */
    private String recognizeType;

    /**
     * 是否启用 0否 1是
     */
    private Integer enabled;
}