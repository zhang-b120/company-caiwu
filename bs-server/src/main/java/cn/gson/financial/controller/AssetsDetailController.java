package cn.gson.financial.controller;

import cn.gson.financial.kernel.annotation.SessionAccountSet;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.model.entity.AccountSets;
import cn.gson.financial.kernel.service.AssetsDetailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;


/**
 *
 * <p><b>Copyright © 2010-2021开发公司All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : (AssetsDetail)</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2022-11-25 23:50:48</li>
 * <li>@author     : 开发者</li>
 * </ul>
 *
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/assets/detail")
public class AssetsDetailController {

    private final AssetsDetailService assetsDetailService;

    @GetMapping("/{assetsId:\\d+}")
    public JsonResult list(@SessionAccountSet AccountSets accountSets, @PathVariable Long assetsId) {
        return JsonResult.successful(assetsDetailService.list(accountSets.getId(), assetsId));
    }
}

