package cn.gson.financial.controller;

import cn.gson.financial.kernel.annotation.SessionAccountSet;
import cn.gson.financial.kernel.annotation.SessionUser;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.model.entity.AccountSets;
import cn.gson.financial.kernel.model.vo.UserVo;
import cn.gson.financial.kernel.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 *
 * <p><b>Copyright © 2010-2019开发公司All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.controller</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2019年07月30日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 *
 */
@RestController
@RequestMapping("/user")
@RequiredArgsConstructor
public class UserController {

    private final UserService service;

    @GetMapping
    public JsonResult list(@SessionAccountSet AccountSets accountSets) {
        return JsonResult.successful(service.listByAccountSetId(accountSets.getId()));
    }

    @GetMapping("ast/{accountSetsId}")
    public JsonResult astList(@PathVariable Long accountSetsId, @SessionUser UserVo userVo) {
        userVo.getAccountSetsId(accountSetsId, null);
        return JsonResult.successful(service.listByAccountSetId(accountSetsId));
    }

    @GetMapping("/ast/by/creator")
    public JsonResult astListByCreator(@RequestParam(value = "accountSetId", required = false) Long accountSetsId, Long astId, @SessionUser UserVo userVo) {
        userVo.getAccountSetsId(astId, accountSetsId);
        return JsonResult.successful(service.astListByCreator(userVo.getId()));
    }

    /**
     * 获取系统中所有用户列表（用于批量选择）
     * 返回手机号和姓名
     */
    @GetMapping("/all")
    public JsonResult getAllUsers() {
        return JsonResult.successful(service.getAllUsers());
    }
}
