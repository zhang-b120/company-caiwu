package cn.gson.financial.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.gson.financial.kernel.annotation.SessionAccountSet;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.controller.Page;
import cn.gson.financial.kernel.model.entity.AccountSets;
import cn.gson.financial.kernel.model.hibernate.PrimaryKey;
import cn.gson.financial.kernel.service.AttachmentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 *
 * <p><b>Copyright © 2010-2023 开发公司 All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.controller</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2023年11月30日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 *
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/att")
@SaCheckPermission("setting-backup-canview")
public class AttachmentController {

    private final AttachmentService attachmentService;

    @GetMapping
    public JsonResult list(@SessionAccountSet AccountSets accountSets, Page page) {
        return JsonResult.successful(attachmentService.list(accountSets.getId(), page));
    }


    @DeleteMapping("{backupId:\\d+}")
    public JsonResult delete(@PathVariable Long backupId, @SessionAccountSet AccountSets accountSets) {
        attachmentService.delete(new PrimaryKey(backupId, accountSets.getId()));
        return JsonResult.successful();
    }


    /**
     * 档案使用空间大小
     *
     * @return
     */
    @GetMapping("space")
    public JsonResult spaceSize(@SessionAccountSet AccountSets accountSets) {
        return JsonResult.successful(attachmentService.getAttachmentSize(accountSets.getId()));
    }
}
