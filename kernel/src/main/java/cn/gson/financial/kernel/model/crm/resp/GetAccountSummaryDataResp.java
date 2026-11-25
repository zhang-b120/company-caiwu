package cn.gson.financial.kernel.model.crm.resp;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * @Author zhb
 * @Date 2025-08-05
 * @Description
 */
@Data
public class GetAccountSummaryDataResp implements Serializable {
    @Serial
    private static final long serialVersionUID = 5835165136229426956L;

    /**
     * 零申报账套数量
     */
    private Long zeroDeclareCount = 0L;

    /**
     * 小规模账套数量
     */
    private Long smallScaleCount = 0L;

    /**
     * 一般纳税人账套数量
     */
    private Long commonlyCount = 0L;

    /**
     * 账套总数
     */
    private Long accountSetsSum = 0L;

    /**
     * 已做账账套数量
     */
    private Long checkOutCount = 0L;

    /**
     * 未做账账套数量
     */
    private Long noCheckOutCount = 0L;

    /**
     * 待申报账套数量
     */
    private Long noTaxDeclareCount = 0L;
}