package cn.gson.financial.kernel.config;

import cn.dev33.satoken.stp.StpInterface;
import cn.dev33.satoken.stp.StpUtil;
import cn.gson.financial.kernel.model.entity.Role;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

import static cn.gson.financial.kernel.common.Constants.S_AUTHORITIES_KEY;

/**
 * 
 * <p><b>Copyright © 2010-2023 开发公司 All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : SaToken 权限资源获取</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2023年10月16日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@Component
public class StpInterfaceImpl implements StpInterface {
    /**
     * 返回指定账号id所拥有的权限码集合
     *
     * @param loginId   账号id
     * @param loginType 账号类型
     * @return 该账号id具有的权限码集合
     */
    @Override
    public List<String> getPermissionList(Object loginId, String loginType) {
        List<String> list = new ArrayList<>();
        if (StpUtil.isLogin()) {
            Role role = StpUtil.getSession().getModel(S_AUTHORITIES_KEY, Role.class);
            //账套管理员所有权限
            if (role == null || role.getAstAdmin()) {
                list.add("*");
            } else {
                return role.getAuthorities();
            }
        }
        return list;
    }

    /**
     * 返回指定账号id所拥有的角色标识集合
     *
     * @param loginId   账号id
     * @param loginType 账号类型
     * @return 该账号id具有的角色标识集合
     */
    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
        return null;
    }
}
