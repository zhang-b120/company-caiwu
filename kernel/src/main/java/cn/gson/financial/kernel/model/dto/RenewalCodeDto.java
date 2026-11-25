package cn.gson.financial.kernel.model.dto;

import com.alibaba.fastjson.annotation.JSONField;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * A DTO for the {@link cn.gson.financial.kernel.model.entity.RenewalCode} entity
 */
@Getter
@Setter
public class RenewalCodeDto implements Serializable {
    private Long id;
    private String type;
    private String code;
    private Integer month;
    private Integer userId;
    private String userName;
    private String userMobile;
    private String astName;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm")
    @JSONField(format = "yyyy-MM-dd HH:mm")
    private LocalDateTime useTime;
}
