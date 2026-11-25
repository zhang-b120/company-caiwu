package cn.gson.financial.kernel.model.crm.req;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDate;

/**
 * 一键报税 - 报税项请求实体
 */
@Data
public class OneKeyTaxItemReq implements Serializable {
    private static final long serialVersionUID = 1L;

    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long taxId;

    private String taxType;

    private String taxState;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate taxEndTime;

    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long id;

    private Boolean checked;

    private Boolean isTaxItem;

    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long accountSetsId;
}

