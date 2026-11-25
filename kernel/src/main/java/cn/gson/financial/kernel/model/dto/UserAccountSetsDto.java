package cn.gson.financial.kernel.model.dto;

import cn.gson.financial.kernel.common.Roles;
import cn.gson.financial.kernel.model.entity.UserAccountSets;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;

/**
 * A DTO for the {@link UserAccountSets} entity
 */
@Getter
@Setter
public class UserAccountSetsDto extends AccountSetsDto implements Serializable {
    private Roles roleType;
}
