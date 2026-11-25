package cn.gson.financial.kernel.model.dto;

import cn.gson.financial.kernel.model.bean.FieldValue;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * DTO for {@link cn.gson.financial.kernel.model.entity.Salary}
 */
@Data
public class SalaryDto implements Serializable {
    private Long id;
    private Long accountSetsId;
    @JsonFormat(pattern = "yyyy年MM月")
    private LocalDate period;
    private Integer year;
    private Integer month;
    private Boolean state;
    private Boolean hasVoucher;
    private LocalDateTime createDate;
    private LocalDateTime updateDate;
    private FieldValue fieldValue;
    private List<SalaryFieldDto> salaryFields;
}
