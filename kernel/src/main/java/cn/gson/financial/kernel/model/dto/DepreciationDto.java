package cn.gson.financial.kernel.model.dto;

import cn.gson.financial.kernel.model.entity.AssetsDepreciation;
import com.alibaba.fastjson.annotation.JSONField;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.time.LocalDate;

/**
 * A DTO for the {@link AssetsDepreciation} entity
 */
@Getter
@Setter
public class DepreciationDto implements Serializable {
    private Long id;
    private Long accountSetsId;
    private Integer checkYear;
    private Integer checkMonth;
    private Integer status;
    private Long voucherId;
    private String voucherTitle;
    /**
     * 0，无数据,1,有数据，未生成凭证,2,有数据，已生成所有凭证
     */
    private Integer newStatus;
    /**
     * 0，无数据,1,有数据，未生成凭证,2,有数据，已生成所有凭证
     */
    private Integer updateStatus;
    private Boolean cancel;
    @JsonFormat(pattern = "yyyy年MM月")
    @JSONField(format = "yyyy年MM月")
    private LocalDate checkDate;
}
