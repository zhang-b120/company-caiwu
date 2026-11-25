package cn.gson.financial.kernel.model.dto;

import cn.gson.financial.kernel.model.entity.SalaryField;
import cn.gson.financial.kernel.model.enums.DataType;
import cn.gson.financial.kernel.model.enums.FieldType;
import lombok.Data;

import java.io.Serializable;

/**
 * DTO for {@link SalaryField}
 */
@Data
public class SalaryFieldDto implements Serializable {
    private Long id;
    private Long accountSetsId;
    private String fieldName;
    private Long socialFieldId;
    private String socialFieldType;
    private Boolean enabled;
    private String formula;
    private FieldType fieldType;
    private DataType dataType;
    private Integer weight;
    private Boolean edit;
}
