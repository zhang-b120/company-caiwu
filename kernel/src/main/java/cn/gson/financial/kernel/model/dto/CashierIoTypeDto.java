package cn.gson.financial.kernel.model.dto;

import cn.gson.financial.kernel.model.entity.CashierIoType;
import com.alibaba.fastjson.JSONArray;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * DTO for {@link CashierIoType}
 */
@Data
public class CashierIoTypeDto implements Serializable {
    private Long id;
    private Long accountSetsId;
    private List<CashierIoTypeVoucherTemplateDto> templates;
    private CashierIoType.Type type;
    private JSONArray matchWords;
    private String name;
    private String code;
}
