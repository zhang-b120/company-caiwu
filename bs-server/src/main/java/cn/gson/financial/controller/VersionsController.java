package cn.gson.financial.controller;

import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.service.VersionsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * <p>****************************************************************************</p >
 * <p><b>Copyright  2013-2023-09-03 开发公司 All Rights Reserved<b></p >
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.controller</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2023-09-03</li>
 * <li>@author     : 超豪</li>
 * </ul>
 * <p>****************************************************************************</p >
 */
@Slf4j
@RestController
@RequestMapping("/versions")
@RequiredArgsConstructor
public class VersionsController {
    private final VersionsService versionsService;

    @GetMapping
    private JsonResult list() {
        return JsonResult.successful(versionsService.list());
    }

    @GetMapping("{id:\\d+}")
    private JsonResult load(@PathVariable Long id) {
        return JsonResult.successful(versionsService.load(id));
    }

}