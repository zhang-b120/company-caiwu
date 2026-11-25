package cn.gson.financial.kernel.model.crm.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * @Author zhb
 * @Date 2025-07-06
 * @Description
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class TaxDeclareItemsInfoDTO extends TaxCommonDTO {

    private static final long serialVersionUID = 3186839074571491705L;
    /**
     * 返回信息
     */
    private List<Map<String,String>> data;
    /**
     * 截图路径
     */
    private String imgUrl;
}