package cn.gson.financial.kernel.model.dto;

import cn.hutool.core.collection.CollUtil;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * DTO for {@link cn.gson.financial.kernel.model.entity.Role}
 */
@Data
public class RoleDto {
    private Long id;
    private Long accountSetsId;
    private String name;
    private String describe;
    private Boolean enabled;
    private Boolean astAdmin;
    private Boolean systemDefault;
    private List<Long> authorities;

    public List<String> getAuthorities() {
        if (CollUtil.isNotEmpty(authorities)) {
            return authorities.stream().map(Object::toString).toList();
        }
        return new ArrayList<>();
    }
}
