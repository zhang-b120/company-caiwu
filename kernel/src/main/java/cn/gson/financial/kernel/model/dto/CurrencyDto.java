package cn.gson.financial.kernel.model.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.io.Serializable;

/**
 * A DTO for the {@link cn.gson.financial.kernel.model.entity.Currency} entity
 */
@Data
public class CurrencyDto implements Serializable {
    private Long id;
    private Long accountSetsId;
    private String code;
    private String name;
    @JsonSerialize(using = ToStringSerializer.class)
    private Double exchangeRate;
    private Boolean localCurrency;
    private String rate;
}
