package cn.gson.financial.controller;

import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.model.dto.VoucherTemplateDto;
import cn.gson.financial.kernel.service.VoucherTemplateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 
 * <p><b>Copyright © 2010-2023 开发公司 All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.controller</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2023年09月15日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@Slf4j
@RestController
@RequestMapping("/voucher/template")
@RequiredArgsConstructor
public class VoucherTemplateController {

    private final VoucherTemplateService templateService;

    @PostMapping
    public JsonResult save(@RequestBody VoucherTemplateDto voucherTemplateDto) {
        templateService.save(voucherTemplateDto);
        return JsonResult.successful();
    }
}
