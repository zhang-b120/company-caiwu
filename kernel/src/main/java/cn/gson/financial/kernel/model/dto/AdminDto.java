package cn.gson.financial.kernel.model.dto;

import cn.gson.financial.kernel.model.entity.Admin;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * A DTO for the {@link Admin} entity
 */
@Getter
@Setter
public class AdminDto implements Serializable {
    private Long id;
    private String name;
    private String telephone;
    private Boolean isSuper;
    private Boolean enabled;
    private Boolean master;
    private LocalDateTime lastLoginTime;
    private String lastIp;
    private LocalDateTime createTime;
}
