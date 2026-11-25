package cn.gson.financial.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.gson.financial.kernel.annotation.SessionAccountSet;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.model.entity.AccountSets;
import cn.gson.financial.kernel.model.entity.AssetsType;
import cn.gson.financial.kernel.service.AssetsTypeService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 *
 * <p><b>Copyright © 2010-2019开发公司All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : 固定资产类别管理</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2019年07月30日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 *
 */
@RestController
@RequestMapping("/assets/type")
@RequiredArgsConstructor
public class AssetsTypeController {

    private final AssetsTypeService assetsTypeService;

    @GetMapping
    public JsonResult list(String filterVal, @SessionAccountSet AccountSets accountSets) {

        return JsonResult.successful(assetsTypeService.list(accountSets.getId()));
    }

    @PostMapping
    @SaCheckPermission("fixedassets-fixedassettype-canedit")
    public JsonResult save(@RequestBody AssetsType entity, @SessionAccountSet AccountSets accountSets) {
        entity.setAccountSetsId(accountSets.getId());
        assetsTypeService.save(entity);
        return JsonResult.successful(entity);
    }

    @DeleteMapping("{assetsTypeId:\\d+}")
    @SaCheckPermission("fixedassets-fixedassettype-candelete")
    public JsonResult delete(@PathVariable Long assetsTypeId, @SessionAccountSet AccountSets accountSets) {
        assetsTypeService.delete(accountSets.getId(), assetsTypeId);
        return JsonResult.successful();
    }
}
