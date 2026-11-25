package cn.gson.financial.kernel.model.ua.resp;

import cn.gson.financial.kernel.model.ua.dto.TaxTableResultDTO;
import lombok.Data;

import java.io.Serializable;

/**
 * @Author zhb
 * @Date 2025-07-10
 * @Description
 */
@Data
public class GetTaxTableResultResp implements Serializable {
    private static final long serialVersionUID = 161574489325897950L;

    /**
     * 当前账期报税数据
     */
    private TaxTableResultDTO current;

    /**
     * 上账期报税数据
     */
    private TaxTableResultDTO last;
}