package cn.gson.financial.kernel.model.vo;

import com.alibaba.fastjson.annotation.JSONField;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 
 * <p><b>Copyright © 2010-2019开发公司All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : 凭证科目明细</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2019年08月25日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@Data
public class VoucherDetailVo {

    @JsonFormat(pattern = "yyyy-MM-dd")
    @JSONField(format = "yyyy-MM-dd")
    private LocalDate voucherDate;
    private Long voucherId;
    private Long detailsId;
    private String word;
    private Integer code;
    private String summary;
    private String subjectName;
    private Long subjectId;
    private String subjectCode;
    private BigDecimal debitAmount;
    private BigDecimal creditAmount;
    private String balanceDirection;
    private BigDecimal balance;
    private Double num;
    private Double numBalance;
    private Double price;

    @JsonIgnore
    public boolean isNull() {
        return this.debitAmount == null && this.creditAmount == null;
    }
}
