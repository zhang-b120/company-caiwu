package cn.gson.financial.kernel.model.dto;

import cn.gson.financial.kernel.model.enums.AssetsChangeType;
import com.alibaba.fastjson.annotation.JSONField;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A DTO for the {@link cn.gson.financial.kernel.model.entity.AssetsChange} entity
 */
@Getter
@Setter
public class AssetsChangeDto implements Serializable {
    private Long id;
    private AssetsChangeType changeType;
    private Long assetsId;
    private Integer checkYear;
    private Integer checkMonth;
    @JsonFormat(pattern = "yyyy年MM月")
    @JSONField(format = "yyyy年MM月")
    private LocalDate checkDate;
    private String beforeChange;
    private String afterChange;
    private Long voucherId;
    private Long accountSetsId;
    private LocalDate createDate;
    private String assetsName;

    private String voucherSummary;
    private String assetsSubjectCode;

    private String code;

    private String voucher;

    private BigDecimal originalValue;

    private BigDecimal depreciationInit;
}
