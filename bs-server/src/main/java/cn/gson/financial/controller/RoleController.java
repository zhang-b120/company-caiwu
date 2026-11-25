package cn.gson.financial.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.gson.financial.kernel.annotation.SessionAccountSet;
import cn.gson.financial.kernel.annotation.SessionUser;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.model.entity.AccountSets;
import cn.gson.financial.kernel.model.dto.RoleDto;
import cn.gson.financial.kernel.model.vo.UserVo;
import cn.gson.financial.kernel.service.AbsService;
import cn.gson.financial.kernel.service.PermissionsServices;
import cn.gson.financial.kernel.service.RoleService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 *
 * <p><b>Copyright © 2010-2023 开发公司 All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.controller</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2023年10月12日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 *
 */
@RestController
@RequiredArgsConstructor
@SaCheckLogin
@RequestMapping("role")
@SaCheckPermission("setting-permission-canedit")
public class RoleController extends AbsService {

    private final RoleService roleService;

    private final PermissionsServices permissionsServices;

    /**
     * 角色列表
     *
     * @param accountSetsId
     * @return
     */
    @GetMapping
    public JsonResult list(@SessionAccountSet AccountSets accountSets, Long astId, @SessionUser UserVo userVo) {
        return JsonResult.successful(roleService.list(userVo.getAccountSetsId(astId, accountSets.getId())));
    }

    /**
     * 权限列表
     *
     * @return
     */
    @GetMapping("permissions")
    public JsonResult permissions() {
        return JsonResult.successful(permissionsServices.list());
    }

    /**
     * 新建角色
     *
     * @param accountSetsId
     * @return
     */
    @PostMapping
    public JsonResult save(@SessionAccountSet AccountSets accountSets, @RequestBody RoleDto role, Long astId, @SessionUser UserVo userVo) {
        role.setAccountSetsId(userVo.getAccountSetsId(astId, accountSets.getId()));
        return JsonResult.successful(roleService.save(role));
    }

    /**
     * 更新角色
     *
     * @param accountSetsId
     * @return
     */
    @PutMapping
    public JsonResult update(@SessionAccountSet AccountSets accountSets, @RequestBody RoleDto role, Long astId, @SessionUser UserVo userVo) {
        role.setAccountSetsId(userVo.getAccountSetsId(astId, accountSets.getId()));
        return JsonResult.successful(roleService.save(role));
    }

    /**
     * 删除角色
     *
     * @param accountSetsId
     * @return
     */
    @DeleteMapping("/{roleId}")
    public JsonResult delete(@SessionAccountSet AccountSets accountSets, @PathVariable Long roleId, Long astId, @SessionUser UserVo userVo) {
        roleService.delete(userVo.getAccountSetsId(astId, accountSets.getId()), roleId);
        return JsonResult.successful();
    }
}
