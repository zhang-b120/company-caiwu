package cn.gson.financial.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.gson.financial.kernel.annotation.SessionAccountSet;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.model.entity.AccountSets;
import cn.gson.financial.kernel.model.entity.SalarySocial;
import cn.gson.financial.kernel.service.SalarySocialService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * <p>****************************************************************************</p >
 * <p><b>Copyright  2013-2023-09-05 开发公司 All Rights Reserved<b></p >
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.controller</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2023-09-05</li>
 * <li>@author     : 超豪</li>
 * </ul>
 * <p>****************************************************************************</p >
 */
@Slf4j
@RestController
@RequestMapping("/salary/social")
@RequiredArgsConstructor
public class SalarySocialController {
    private final SalarySocialService socialService;

    @GetMapping
    @SaCheckPermission("salary-insuranceset-canview")
    private JsonResult list(@SessionAccountSet AccountSets accountSets) {
        return JsonResult.successful(socialService.list(accountSets.getId()));
    }

    @PutMapping
    @SaCheckPermission("salary-insuranceset-canedit")
    private JsonResult update(@RequestBody SalarySocial social, @SessionAccountSet AccountSets accountSets) {
        social.setAccountSetsId(accountSets.getId());
        socialService.save(social);
        return JsonResult.successful();
    }

    @PostMapping
    @SaCheckPermission("salary-insuranceset-canedit")
    private JsonResult save(@RequestBody SalarySocial social, @SessionAccountSet AccountSets accountSets) {
        social.setAccountSetsId(accountSets.getId());
        socialService.save(social);
        return JsonResult.successful();
    }

    @DeleteMapping("/delete/{id:\\d+}")
    @SaCheckPermission("salary-insuranceset-candelete")
    public JsonResult delete(@PathVariable Long id, @SessionAccountSet AccountSets accountSets) {
        socialService.delete(accountSets.getId(), id);
        return JsonResult.successful();
    }
}
