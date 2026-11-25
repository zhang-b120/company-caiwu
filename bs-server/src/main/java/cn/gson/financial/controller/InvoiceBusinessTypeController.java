package cn.gson.financial.controller;

import cn.dev33.satoken.annotation.SaIgnore;
import cn.gson.financial.kernel.annotation.SessionAccountSet;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.model.entity.AccountSets;
import cn.gson.financial.kernel.model.entity.InvoiceBusinessType;
import cn.gson.financial.kernel.model.enums.InvoiceType;
import cn.gson.financial.kernel.model.hibernate.PrimaryKey;
import cn.gson.financial.kernel.model.vo.InvoiceBatchUpdateVo;
import cn.gson.financial.kernel.service.InvoiceBusinessTypeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;


/**
 *
 * <p><b>Copyright © 2010-2021开发公司All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : (InvoiceBusinessType)</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2022-12-21 15:40:17</li>
 * <li>@author     : 开发者</li>
 * </ul>
 *
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/invoice/business")
public class InvoiceBusinessTypeController {

    private final InvoiceBusinessTypeService invoiceBusinessTypeService;

    @GetMapping
    public JsonResult list(@SessionAccountSet AccountSets accountSets, InvoiceType type) {
        return JsonResult.successful(invoiceBusinessTypeService.list(type, accountSets.getId()));
    }
    @GetMapping("/currentAccountSetsId")
    public JsonResult listByCurrentAccountSetsId( Long currentAccountSetsId, InvoiceType type) {
        Long accountSetId =  currentAccountSetsId;
        return JsonResult.successful(invoiceBusinessTypeService.list(type, accountSetId));
    }
    @PostMapping
    public JsonResult create(@RequestBody InvoiceBusinessType invoiceBusinessType, @SessionAccountSet AccountSets accountSets) {
        invoiceBusinessType.setAccountSetsId(accountSets.getId());
        return JsonResult.successful(invoiceBusinessTypeService.save(invoiceBusinessType));
    }

    @DeleteMapping("{invoiceBusinessTypeId:\\d+}")
    public JsonResult delete(@PathVariable Long invoiceBusinessTypeId, @SessionAccountSet AccountSets accountSets) {
        invoiceBusinessTypeService.delete(new PrimaryKey(invoiceBusinessTypeId, accountSets.getId()));
        return JsonResult.successful();
    }

    @PostMapping("/batchChangeBusinessType")
    public JsonResult batchChangeBusinessType(@SessionAccountSet AccountSets accountSets, @RequestBody InvoiceBatchUpdateVo invoiceBatchUpdate) {
        return JsonResult.successful(invoiceBusinessTypeService.batchChangeBusinessType(invoiceBatchUpdate.getInvoiceIdList(), invoiceBatchUpdate.getInvoiceType(), accountSets.getId()));
    }

}


