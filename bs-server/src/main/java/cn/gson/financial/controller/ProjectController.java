package cn.gson.financial.controller;

import cn.gson.financial.kernel.annotation.SessionAccountSet;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.controller.Page;
import cn.gson.financial.kernel.model.entity.AccountSets;
import cn.gson.financial.kernel.model.entity.Project;
import cn.gson.financial.kernel.model.enums.AccountingCategoryType;
import cn.gson.financial.kernel.model.hibernate.PrimaryKey;
import cn.gson.financial.kernel.service.ProjectService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;


/**
 *
 * <p><b>Copyright © 2010-2021开发公司All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : (Project)</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2023-04-07 20:08:38</li>
 * <li>@author     : 开发者</li>
 * </ul>
 *
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/project")
public class ProjectController {

    private final ProjectService projectService;

    @GetMapping
    public JsonResult list(Page page, String filter, @SessionAccountSet AccountSets accountSets) {
        return JsonResult.successful(projectService.list(page, filter, accountSets.getId()));
    }

    @PostMapping
    public JsonResult create(@RequestBody Project project, @SessionAccountSet AccountSets accountSets) {
        project.setAccountSetsId(accountSets.getId());
        project.setCategoryId((long) AccountingCategoryType.PROJECT.ordinal());
        project.setCategoryType(AccountingCategoryType.PROJECT);
        return JsonResult.successful(projectService.save(project));
    }

    @PutMapping
    public JsonResult update(@RequestBody Project project, @SessionAccountSet AccountSets accountSets) {
        project.setAccountSetsId(accountSets.getId());
        project.setCategoryId((long) AccountingCategoryType.PROJECT.ordinal());
        project.setCategoryType(AccountingCategoryType.PROJECT);
        return JsonResult.successful(projectService.save(project));
    }

    @DeleteMapping("{projectId:\\d+}")
    public JsonResult delete(@PathVariable Long projectId, @SessionAccountSet AccountSets accountSets) {
        projectService.delete(new PrimaryKey(projectId, accountSets.getId()));
        return JsonResult.successful();
    }
}


