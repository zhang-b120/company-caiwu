package cn.gson.financial.kernel.model.crm.resp;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;

/**
 * @Author zhb
 * @Date 2025-07-05
 * @Description
 */
@Data
public class GetTaxTabResp implements Serializable {
    private static final long serialVersionUID = -687305296084107285L;

    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long taxId;

    private String taxType;

    private String taxState;

    private LocalDate taxEndTime;
}