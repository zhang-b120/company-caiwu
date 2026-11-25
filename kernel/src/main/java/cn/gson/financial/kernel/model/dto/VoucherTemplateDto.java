package cn.gson.financial.kernel.model.dto;

import com.alibaba.fastjson2.JSONArray;
import lombok.Data;

import java.io.Serializable;

/**
 * DTO for {@link cn.gson.financial.kernel.model.entity.VoucherTemplate}
 */
@Data
public class VoucherTemplateDto implements Serializable {
    private Long id;
    private Long accountSetsId;
    private String title;
    private JSONArray details;
}
