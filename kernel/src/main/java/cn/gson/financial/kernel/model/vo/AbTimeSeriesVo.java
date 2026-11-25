package cn.gson.financial.kernel.model.vo;

import cn.gson.financial.kernel.config.NumBigDecimalSerializer;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 
 * <p><b>Copyright © 2010-2024 开发公司 All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.kernel.model.vo</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2024年02月27日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@Data
public class AbTimeSeriesVo {
    private String voucherDate;
    private String voucherId;
    private String word;
    private String summary;
    private String userName;
    private String auditName;
    private String subjectName;
    private String subjectCode;
    private String subjectId;
    private BigDecimal debitAmount;
    private BigDecimal debitAmountFor;
    private BigDecimal creditAmount;
    private BigDecimal creditAmountFor;
    @JsonSerialize(using = NumBigDecimalSerializer.class)
    private BigDecimal num;
    @JsonSerialize(using = NumBigDecimalSerializer.class)
    private BigDecimal debitNum;
    @JsonSerialize(using = NumBigDecimalSerializer.class)
    private BigDecimal creditNum;
    private Integer receiptNum;
    private String remark;
}
