package cn.gson.financial.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaIgnore;
import cn.gson.financial.kernel.annotation.SessionAccountSet;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.model.dto.SalaryVoucherTemplateDto;
import cn.gson.financial.kernel.model.entity.AccountSets;
import cn.gson.financial.kernel.model.entity.SalaryVoucher;
import cn.gson.financial.kernel.model.entity.SalaryVoucherTemplate;
import cn.gson.financial.kernel.model.hibernate.PrimaryKey;
import cn.gson.financial.kernel.service.SalaryVoucherService;
import cn.gson.financial.kernel.service.SalaryVoucherTemplateService;
import cn.hutool.core.collection.CollUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * <p>****************************************************************************</p >
 * <p><b>Copyright  2013-2023-09-11 开发公司 All Rights Reserved<b></p >
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.controller</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2023-09-11</li>
 * <li>@author     : 超豪</li>
 * </ul>
 * <p>****************************************************************************</p >
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/salary/voucher/template")
public class SalaryVoucherTemplateController {

    private final SalaryVoucherTemplateService svts;

    private final SalaryVoucherService salaryVoucherService;

    @GetMapping
    public JsonResult list(@SessionAccountSet AccountSets accountSets, LocalDate period, Long salaryId) {
        Long accountSetId = accountSets.getId();
        List<SalaryVoucherTemplateDto> voucherTemplateDtos = svts.list(accountSetId);
        Map<Long, SalaryVoucher> voucherMap = new java.util.HashMap<>();
        // 仅当 period 与 salaryId 均非空时查询已生成凭证（避免 eq(null) 报错）；做账弹窗「凭证模板」不传期间时只返回模板列表、不展示金额
        if (period != null && salaryId != null) {
            List<Long> templateIds = voucherTemplateDtos.stream()
                    .filter(SalaryVoucherTemplateDto::getIsAccrual)
                    .map(SalaryVoucherTemplateDto::getId).toList();
            List<Long> isAccrualTemplateIds = voucherTemplateDtos.stream()
                    .filter(s -> !s.getIsAccrual())
                    .map(SalaryVoucherTemplateDto::getId).toList();
            voucherMap = salaryVoucherService.loadList(accountSetId, templateIds, period, salaryId);
            if (CollUtil.isNotEmpty(isAccrualTemplateIds)) {
                Map<Long, SalaryVoucher> map = salaryVoucherService.loadList(accountSetId, isAccrualTemplateIds, period.plusMonths(1), salaryId);
                voucherMap.putAll(map);
            }
        }
        return JsonResult.successful()
                .data("voucherMap", voucherMap)
                .data("templateList", voucherTemplateDtos);
    }

    /**
     * 生成凭证时的模板预览数据
     *
     * @param accountSetId
     * @param period
     * @param templateId
     * @return
     */
    @GetMapping("data/{templateId:\\d+}")
    public JsonResult data(@SessionAccountSet AccountSets accountSets, LocalDate period, Long salaryId, @PathVariable Long templateId) {
        return JsonResult.successful(svts.data(accountSets.getId(), templateId, period, salaryId));
    }

    @PostMapping
    @SaCheckPermission("salary-salarymanage-setting-canedit")
    public JsonResult create(@RequestBody SalaryVoucherTemplate salaryVoucherTemplate, @SessionAccountSet AccountSets accountSets) {
        salaryVoucherTemplate.setAccountSetsId(accountSets.getId());
        return JsonResult.successful(svts.save(salaryVoucherTemplate));
    }

    @DeleteMapping("{templateId:\\d+}")
    @SaCheckPermission("salary-salarymanage-setting-candelete")
    public JsonResult delete(@PathVariable Long templateId, @SessionAccountSet AccountSets accountSets) {
        svts.delete(new PrimaryKey(templateId, accountSets.getId()));
        return JsonResult.successful();
    }
    @SaIgnore
    @GetMapping("/testAddVoucherTemplate")
    public void testAddVoucherTemplate(){
       svts.testAddVoucherTemplate();
    }
}
