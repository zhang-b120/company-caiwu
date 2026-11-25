package cn.gson.financial.kernel.model.dto;

import cn.hutool.core.annotation.Alias;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * DTO for {@link cn.gson.financial.kernel.model.entity.RenewalCode}
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class RenewalCodeDownloadDto implements Serializable {
    @Alias("码类型")
    private String type;
    @Alias("订购码")
    private String code;
    @Alias("订购数")
    private Integer month;
    @Alias("投放渠道")
    private String channel;

    public void setType(String type) {
        if (type.equals("astMonth")) {
            this.type = "账套期限";
        } else {
            this.type = "账套数";
        }
    }

    public String getMonth() {
        if (type.equals("astMonth")) {
            return this.month + "个月";
        } else {
            return this.month + "套";
        }
    }
}
