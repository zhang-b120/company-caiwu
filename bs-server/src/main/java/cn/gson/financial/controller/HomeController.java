package cn.gson.financial.controller;

import cn.gson.financial.kernel.annotation.SessionAccountSet;
import cn.gson.financial.kernel.annotation.SessionUser;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.model.entity.AccountSets;
import cn.gson.financial.kernel.model.vo.UserVo;
import cn.gson.financial.kernel.service.BalanceService;
import cn.gson.financial.kernel.service.VoucherService;
import cn.hutool.core.lang.Dict;
import cn.hutool.core.util.NumberUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 *
 * <p><b>Copyright © 2010-2019开发公司All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.controller</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2019年09月10日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 *
 */
@RestController
@RequestMapping("/home")
@RequiredArgsConstructor
public class HomeController {

    private final VoucherService voucherService;

    private final BalanceService balanceService;

    @GetMapping("/voucher/count")
    public JsonResult info(@SessionUser UserVo userVo, @SessionAccountSet AccountSets accountSets) {
        return JsonResult.successful(voucherService.count(accountSets.getId(), userVo.getAccountSets().getCurrentAccountDate().getYear(), userVo.getAccountSets().getCurrentAccountDate().getMonthValue()));
    }

    /**
     * 收入和利润图表数据
     *
     */
    @GetMapping("/chart/revenueProfit")
    public JsonResult revenueProfitChart(@SessionUser UserVo userVo, @SessionAccountSet AccountSets accountSets) {
        List<Dict> homeReport = voucherService.getHomeReport(accountSets.getId(), userVo.getAccountSets().getCurrentAccountDate().getYear());
        //根据类型和年进行分组
        try{
        Map<String, Map<Integer, BigDecimal>> collect = homeReport.stream().collect(
                Collectors.groupingBy(
                        som -> som.getStr("type"),
                        Collectors.toMap(
                                o -> o.getInt("month"),
                                o -> NumberUtil.nullToZero(o.getBigDecimal("endBalance"))
                        )
                )

        );
            return JsonResult.successful(collect);
        }catch (Exception e){
            System.out.println(e.getMessage());
        }
        return null;
    }

    /**
     * 费用
     *
     */
    @GetMapping("/chart/cost")
    public JsonResult costReport(@SessionUser UserVo userVo, @SessionAccountSet AccountSets accountSets) {
        List<Dict> homeReport = voucherService.getCostReport(accountSets.getId(), userVo.getAccountSets().getCurrentAccountDate().getYear(), userVo.getAccountSets().getCurrentAccountDate().getMonthValue());
        return JsonResult.successful(getStringListMap(homeReport));
    }


    /**
     * 货币资金
     *
     */
    @GetMapping("/chart/cash")
    public JsonResult cashReport(@SessionUser UserVo userVo, @SessionAccountSet AccountSets accountSets) {
        List<Dict> homeReport = voucherService.getCashReport(accountSets.getId(), userVo.getAccountSets().getCurrentAccountDate().getYear(), userVo.getAccountSets().getCurrentAccountDate().getMonthValue());
        //根据类型和年进行分组
        return JsonResult.successful(getStringListMap(homeReport));
    }


    /**
     * 应付/收账款
     *
     */
    @GetMapping("/chart/cope")
    public JsonResult copeTotal(@SessionUser UserVo userVo, @SessionAccountSet AccountSets accountSets) {
        LocalDate currentAccountDate = userVo.getAccountSets().getCurrentAccountDate();
        //根据类型和年进行分组
        return JsonResult.successful(balanceService.copeTotal(accountSets.getId(), currentAccountDate.getYear(), currentAccountDate.getMonthValue()));
    }


    private List<Map<String, Object>> getStringListMap(List<Dict> homeReport) {
        Map<String, List<BigDecimal>> collect = homeReport.stream().collect(Collectors.groupingBy(s -> s.getStr("type"),
                Collectors.mapping(s -> s.getBigDecimal("endBalance"), Collectors.toList())));

        List<Map<String, Object>> data = new ArrayList<>(collect.size());
        collect.forEach((key, value) -> {
            Map<String, Object> item = new HashMap<>(2);
            item.put("name", key);
            item.put("value", value.stream().mapToDouble(BigDecimal::doubleValue).sum());
            data.add(item);
        });
        return data;
    }
}
