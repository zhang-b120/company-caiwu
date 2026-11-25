package cn.gson.financial.individualTax.controller;

import cn.dev33.satoken.annotation.SaIgnore;
import cn.dev33.satoken.stp.StpUtil;
import cn.gson.financial.kernel.model.individualTax.dto.AddIndividualTaxDto;
import cn.gson.financial.kernel.model.individualTax.dto.AuditIndividualTaxDto;
import cn.gson.financial.kernel.model.individualTax.dto.DeleteIndividualTaxDto;
import cn.gson.financial.kernel.model.individualTax.dto.IndividualTaxSelectDto;
import cn.gson.financial.kernel.model.individualTax.service.IndividualTaxService;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.model.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import static cn.gson.financial.kernel.common.Constants.S_USER_KEY;

@RestController
@RequestMapping("/individualTax")
@RequiredArgsConstructor
public class IndividualTaxController {

    private final IndividualTaxService individualTaxService;

    /**
     * 查询个税情况列表
     * @param individualTaxSelectDto
     * @return
     */
    @SaIgnore
    @PostMapping("/getIndividualTaxList")
    public JsonResult getIndividualTaxList(@RequestBody IndividualTaxSelectDto individualTaxSelectDto) {
        //获取当前用户的用户id
        User user = (User)StpUtil.getSession().get(S_USER_KEY);
        individualTaxSelectDto.setUserId(user.getId());

        // 设置账期：如果DTO中没有period，则使用上个月的第一天
        if (individualTaxSelectDto.getPeriod() == null || individualTaxSelectDto.getPeriod().isEmpty()) {
            LocalDate now = LocalDate.now();
            LocalDate lastMonthFirstDay = now.minusMonths(1).withDayOfMonth(1);
            String period = lastMonthFirstDay.format(DateTimeFormatter.ISO_LOCAL_DATE);
            individualTaxSelectDto.setPeriod(period);
        }
        return JsonResult.successful(individualTaxService.getIndividualTaxList(individualTaxSelectDto));
    }
    /**
     * 添加个税任务列表
     */
    @SaIgnore
    @PostMapping("/addIndividualTaxList")
    public JsonResult addIndividualTaxList(@RequestBody AddIndividualTaxDto addIndividualTaxDto){
        return JsonResult.successful(individualTaxService.addIndividualTaxList(addIndividualTaxDto));
    }

    /**
     * 删除个税任务列表
     */
    @SaIgnore
    @PostMapping("/deleteIndividualTaxList")
    public JsonResult deleteIndividualTaxList(@RequestBody DeleteIndividualTaxDto deleteIndividualTaxDto){
         individualTaxService.deleteIndividualTaxList(deleteIndividualTaxDto);
         return JsonResult.successful();
    }

    /**
     * 审核个税任务列表
     */
    @SaIgnore
    @PostMapping("/auditIndividualTaxList")
    public JsonResult auditIndividualTaxList(@RequestBody AuditIndividualTaxDto auditIndividualTaxDto){
         individualTaxService.auditIndividualTaxList(auditIndividualTaxDto);
         return JsonResult.successful();
    }

    /**
     * 回退审核状态
     */
    @SaIgnore
    @PostMapping("/rollbackAuditIndividualTaxList")
    public JsonResult rollbackAuditIndividualTaxList(@RequestBody AuditIndividualTaxDto auditIndividualTaxDto){
         individualTaxService.rollbackAuditIndividualTaxList(auditIndividualTaxDto);
         return JsonResult.successful();
    }

    /**
     * 设置个税任务为待更新状态
     */
    @SaIgnore
    @PostMapping("/setPendingIndividualTaxList")
    public JsonResult setPendingIndividualTaxList(@RequestBody AuditIndividualTaxDto auditIndividualTaxDto){
         individualTaxService.setPendingIndividualTaxList(auditIndividualTaxDto);
         return JsonResult.successful();
    }
}
