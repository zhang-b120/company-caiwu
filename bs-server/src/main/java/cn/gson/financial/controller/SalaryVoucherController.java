package cn.gson.financial.controller;

import cn.gson.financial.kernel.annotation.SessionAccountSet;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.model.dto.VoucherDto;
import cn.gson.financial.kernel.model.entity.AccountSets;
import cn.gson.financial.kernel.model.entity.SalaryVoucher;
import cn.gson.financial.kernel.model.vo.SalaryVoucherVo;
import cn.gson.financial.kernel.service.BalanceCalcService;
import cn.gson.financial.kernel.service.SalaryVoucherService;
import cn.gson.financial.kernel.service.VoucherService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.YearMonth;

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
@RequestMapping("/salary/voucher")
public class SalaryVoucherController {

    private final SalaryVoucherService salaryVoucherService;

    private final VoucherService voucherService;

    private final BalanceCalcService balanceCalcService;

    @GetMapping("show/{templateId}")
    public JsonResult show(@SessionAccountSet AccountSets accountSets, LocalDate period, @PathVariable Long templateId) {
        return JsonResult.successful();
    }

    /**
     * 凭证生产
     *
     * @param accountSets
     * @param salaryVoucherVo
     * @return
     */
    @PostMapping("generate")
    public JsonResult generate(@SessionAccountSet AccountSets accountSets, @RequestBody SalaryVoucherVo salaryVoucherVo) {
        VoucherDto voucherDto = voucherService.save(salaryVoucherVo.getVoucher(), accountSets, false);
        SalaryVoucher salaryVoucher = new SalaryVoucher();
        salaryVoucher.setVoucherId(voucherDto.getId());
        salaryVoucher.setPeriod(voucherDto.getVoucherDate());
        salaryVoucher.setTemplateId(salaryVoucherVo.getTemplateId());
        salaryVoucher.setYear(voucherDto.getYear());
        salaryVoucher.setMonth(voucherDto.getMonth());
        salaryVoucher.setAmount(voucherDto.getDebitAmount());
        salaryVoucher.setAccountSetsId(accountSets.getId());
        salaryVoucher.setSalaryId(salaryVoucherVo.getSalaryId());
        salaryVoucherService.save(salaryVoucher);
        //计算余额表
        balanceCalcService.calc(accountSets.getId(), YearMonth.from(salaryVoucherVo.getVoucher().getVoucherDate()));
        return JsonResult.successful();
    }

}
