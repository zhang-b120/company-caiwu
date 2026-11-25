package cn.gson.financial.kernel.model.crm.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;

/**
 * @Author zhb
 * @Date 2025-07-06
 * @Description
 */
@Data
public class TaxTableDTO implements Serializable {
    private static final long serialVersionUID = -2681255915124829797L;

    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long taxId;

    private String taxType;

    private String taxState;

    private LocalDate taxEndTime;
}