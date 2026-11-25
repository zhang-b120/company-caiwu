package cn.gson.financial.kernel.model.vo;

import cn.gson.financial.kernel.model.entity.AccountSets;
import cn.gson.financial.kernel.model.entity.Checkout;
import cn.gson.financial.kernel.model.entity.Role;
import cn.gson.financial.kernel.model.entity.User;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.lang.Opt;
import com.alibaba.fastjson.annotation.JSONField;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * 
 * <p><b>Copyright © 2010-2019开发公司All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.kernel.model.vo</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2019年08月27日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@Data
@EqualsAndHashCode(callSuper = false)
public class UserVo extends User {

    private AccountSets accountSets;

    @JsonIgnore
    @JSONField(serialize = false)
    private String password;

    private String role;

    private Role roleInfo;

    private List<AccountSets> accountSetsList;

    /**
     * 本位币ID
     */
    private Long localCurrencyId;

    private List<Checkout> checkoutList;

    private Long expirationDay;

    public boolean checkAstIsMine(Long astId) {
        return CollUtil.isNotEmpty(accountSetsList) && astId != null
               && this.accountSetsList.stream().filter(as -> as != null && as.getId().equals(astId))
                       .anyMatch(as -> as.getCreatorId().equals(this.getId()));
    }

    /**
     * 检查用户是否有该账套的权限（不限于创建者）
     * @param astId 账套ID
     * @return 是否有权限
     */
    public boolean checkAstHasPermission(Long astId) {
        return CollUtil.isNotEmpty(accountSetsList) && astId != null
               && this.accountSetsList.stream().filter(as -> as != null).anyMatch(as -> as.getId().equals(astId));
    }

    public Long getAccountSetsId(Long astId, Long accountSetsId) {
        return Opt.ofNullable(astId)
                .ifPresent(id -> Assert.isTrue(this.checkAstHasPermission(id), "不允许操作他人账套信息"))
                .orElse(accountSetsId);
    }
}
