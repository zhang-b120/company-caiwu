package cn.gson.financial.controller;

import cn.gson.financial.kernel.annotation.SessionAccountSet;
import cn.gson.financial.kernel.annotation.SessionUser;
import cn.gson.financial.kernel.common.LocalDateRange;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.model.dto.BalanceDto;
import cn.gson.financial.kernel.model.entity.AccountSets;
import cn.gson.financial.kernel.model.entity.BiReport;
import cn.gson.financial.kernel.model.entity.Subject;
import cn.gson.financial.kernel.model.enums.AccessRules;
import cn.gson.financial.kernel.model.enums.BiAging;
import cn.gson.financial.kernel.model.hibernate.PrimaryKey;
import cn.gson.financial.kernel.model.vo.AccountBookQueryVO;
import cn.gson.financial.kernel.model.vo.BiVarVo;
import cn.gson.financial.kernel.model.vo.UserVo;
import cn.gson.financial.kernel.service.BalanceService;
import cn.gson.financial.kernel.service.BiReportService;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.NumberUtil;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 *
 * <p><b>Copyright © 2010-2023 开发公司 All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.controller</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2023年07月30日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 *
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/bi")
public class BiReportController {
    private final BiReportService biReportService;

    private final BalanceService balanceService;

    @GetMapping
    public JsonResult list(@SessionAccountSet AccountSets accountSets) {
        return JsonResult.successful(biReportService.list(accountSets.getId()));
    }

    @PostMapping
    public JsonResult create(@RequestBody BiReport biReport, @SessionAccountSet AccountSets accountSets) {
        biReport.setAccountSetsId(accountSets.getId());
        biReportService.save(biReport);
        return JsonResult.successful();
    }

    @PutMapping
    public JsonResult update(@RequestBody BiReport biReport, @SessionAccountSet AccountSets accountSets) {
        biReport.setAccountSetsId(accountSets.getId());
        biReportService.save(biReport);
        return JsonResult.successful();
    }

    @DeleteMapping("{biReportId:\\d+}")
    public JsonResult delete(@PathVariable Long biReportId, @SessionAccountSet AccountSets accountSets) {
        biReportService.delete(new PrimaryKey(biReportId, accountSets.getId()));
        return JsonResult.successful();
    }

    @GetMapping("{biReportId:\\d+}")
    public JsonResult load(@PathVariable Long biReportId, @SessionAccountSet AccountSets accountSets) {
        return JsonResult.successful(biReportService.load(new PrimaryKey(biReportId, accountSets.getId())));
    }

    @GetMapping("data/{biReportId:\\d+}")
    public JsonResult data(@PathVariable Long biReportId, final LocalDateRange voucherDate,
                           @SessionUser UserVo userVo,
                           @SessionAccountSet AccountSets accountSets) {
        Long accountSetsId = accountSets.getId();
        BiReport biReport = biReportService.load(new PrimaryKey(biReportId, accountSetsId));
        //获取报表变量
        JSONArray calcChain = new JSONArray();
        Map<String, Map<String, BiVarVo>> vars = biReport.getVars();
        if (vars != null) {
            List<JSONObject> config = biReport.getCells().toJavaList(JSONObject.class);
            Map<String, JSONObject> cellMap = config.stream().collect(Collectors.toMap(s -> s.getString("r") + "," + s.getString("c"), v -> v));

            final Map<String, BalanceDto> subjectBalanceMap = new HashMap<>();
            final Map<String, BalanceDto> startSubjectBalanceMap = new HashMap<>();
            final Map<String, BalanceDto> yearSubjectBalanceMap = new HashMap<>();
            final Map<String, BalanceDto> yearTotalSubjectBalanceMap = new HashMap<>();

            vars.forEach((pos, map) -> map.forEach((vName, v) -> {
                Set<Long> auxiliaryDetailIdList = new HashSet<>();
                if (v.isAC()) {
                    auxiliaryDetailIdList = getAuxiliaryDetailIdList(accountSetsId, v);
                }

                switch (BiAging.valueOf(v.getAging())) {
                    case 期初 -> {
                        if (!startSubjectBalanceMap.containsKey(v.key())) {
                            List<BalanceDto> dtoList = new ArrayList<>();
                            if (!v.isAC() || (v.isAC() && CollUtil.isNotEmpty(auxiliaryDetailIdList))) {
                                dtoList = balanceService.loadBySubjectIds(accountSetsId, v.getSubject().getId(), voucherDate.getStart(), auxiliaryDetailIdList, userVo.getLocalCurrencyId());
                            }

                            //将科目余额列表转换科目Id的map对象
                            startSubjectBalanceMap.put(v.key(), dtoList.stream().reduce(null, (a, b) -> {
                                if (a == null) {
                                    return b;
                                }
                                a.add(b);
                                return a;
                            }));
                        }
                    }
                    case 期末 -> {
                        if (!subjectBalanceMap.containsKey(v.key())) {
                            List<BalanceDto> dtoList = new ArrayList<>();
                            if (!v.isAC() || (v.isAC() && CollUtil.isNotEmpty(auxiliaryDetailIdList))) {
                                dtoList = balanceService.loadBySubjectIds(accountSetsId, v.getSubject().getId(), voucherDate.getEnd(), auxiliaryDetailIdList, userVo.getLocalCurrencyId());
                            }

                            if (!voucherDate.getStart().isEqual(voucherDate.getEnd())) {
                                List<BalanceDto> total = new ArrayList<>();
                                if (!v.isAC() || (v.isAC() && CollUtil.isNotEmpty(auxiliaryDetailIdList))) {
                                    total = balanceService.loadBySubjectIds(accountSetsId, v.getSubject().getId(), voucherDate.getStart(), voucherDate.getEnd(), auxiliaryDetailIdList, userVo.getLocalCurrencyId());
                                }

                                if (CollUtil.isNotEmpty(total)) {
                                    for (BalanceDto balanceDto : total) {
                                        //合并进来
                                        Optional<BalanceDto> first = dtoList.stream().filter(s -> s.getSubjectId().equals(balanceDto.getSubjectId()) && s.getAuxiliaryDetailId().equals(balanceDto.getAuxiliaryDetailId())).findFirst();
                                        if (first.isPresent()) {
                                            first.get().setCredit(balanceDto.getCredit());
                                            first.get().setDebit(balanceDto.getDebit());
                                        } else {
                                            dtoList.add(balanceDto);
                                        }
                                    }
                                }
                            }

                            subjectBalanceMap.put(v.key(), dtoList.stream().reduce(null, (a, b) -> {
                                if (a == null) {
                                    return b;
                                }
                                a.add(b);
                                return a;
                            }));
                        }
                    }
                    case 年初 -> {
                        if (!yearSubjectBalanceMap.containsKey(v.key())) {
                            LocalDate localDate = voucherDate.getStart().withMonth(1).withDayOfMonth(1);
                            if (localDate.isBefore(accountSets.getEnableDate())) {
                                localDate = accountSets.getEnableDate();
                            }
                            List<BalanceDto> dtoList = new ArrayList<>();
                            if (!v.isAC() || (v.isAC() && CollUtil.isNotEmpty(auxiliaryDetailIdList))) {
                                dtoList = balanceService.loadBySubjectIds(accountSetsId, v.getSubject().getId(), localDate, auxiliaryDetailIdList, userVo.getLocalCurrencyId());
                            }

                            yearSubjectBalanceMap.put(v.key(), dtoList.stream().reduce(null, (a, b) -> {
                                if (a == null) {
                                    return b;
                                }
                                a.add(b);
                                return a;
                            }));
                        }
                    }
                    case 本年 -> {
                        if (!yearTotalSubjectBalanceMap.containsKey(v.key())) {
                            List<BalanceDto> dtoList = new ArrayList<>();
                            if (!v.isAC() || (v.isAC() && CollUtil.isNotEmpty(auxiliaryDetailIdList))) {
                                dtoList = balanceService.loadBySubjectIds(accountSetsId, v.getSubject().getId(), voucherDate.getEnd(), auxiliaryDetailIdList, userVo.getLocalCurrencyId());
                            }

                            yearTotalSubjectBalanceMap.put(v.key(), dtoList.stream().reduce(null, (a, b) -> {
                                if (a == null) {
                                    return b;
                                }
                                a.add(b);
                                return a;
                            }));
                        }
                    }
                }
            }));

            if (CollUtil.isNotEmpty(vars)) {
                //循环有变量的单元格坐标，coordinate是cell的坐标[row 0,col 0]
                for (String coordinate : vars.keySet()) {
                    if (!cellMap.containsKey(coordinate)) {
                        continue;
                    }
                    Map<String, BiVarVo> var = vars.get(coordinate);
                    //行列坐标
                    AtomicReference<Object> text = new AtomicReference<>(cellMap.get(coordinate).getJSONObject("v").getString("f"));

                    var.keySet().forEach(v -> {
                        BiVarVo varConfig = var.get(v);
                        BiAging aging = BiAging.valueOf(varConfig.getAging());
                        //科目
                        Subject subject = varConfig.getSubject();

                        Map<String, BalanceDto> balanceMap = new HashMap<>();
                        switch (aging) {
                            case 期初 -> balanceMap = startSubjectBalanceMap;
                            case 期末 -> balanceMap = subjectBalanceMap;
                            case 年初 -> balanceMap = yearSubjectBalanceMap;
                            case 本年 -> balanceMap = yearTotalSubjectBalanceMap;
                        }

                        BigDecimal val = BigDecimal.ZERO;

                        if (balanceMap.containsKey(varConfig.key())) {
                            BalanceDto balance = balanceMap.get(varConfig.key());
                            if (balance == null) {
                                val = BigDecimal.ZERO;
                            } else {
                                switch (AccessRules.valueOf(varConfig.getField())) {
                                    case 发生额 -> {
                                        if ("借".equals(subject.getBalanceDirection())) {
                                            switch (aging) {
                                                case 期末 -> val = balance.getDebit();
                                                case 本年 -> val = balance.getYearTotalDebit();
                                            }
                                        } else {
                                            switch (aging) {
                                                case 期末 -> val = balance.getCredit();
                                                case 本年 -> val = balance.getYearTotalCredit();
                                            }
                                        }
                                    }
                                    case 借方发生额 -> {
                                        switch (aging) {
                                            case 期末 -> val = balance.getDebit();
                                            case 本年 -> val = balance.getYearTotalDebit();
                                        }
                                    }
                                    case 贷方发生额 -> {
                                        switch (aging) {
                                            case 期末 -> val = balance.getCredit();
                                            case 本年 -> val = balance.getYearTotalCredit();
                                        }
                                    }
                                    case 余额 -> {
                                        switch (aging) {
                                            case 期初 -> val = balance.getBeginBalance();
                                            case 期末 -> val = balance.getEndBalance();
                                            case 年初 -> val = balance.getYearTotal();
                                        }
                                    }
                                    case 借方余额 -> {
                                        switch (aging) {
                                            case 期初 -> val = balance.getBeginDebitBalance();
                                            case 期末 -> val = balance.getEndDebitBalance();
                                            case 年初 -> val = balance.getYearTotalBeginDebit();
                                        }

                                    }
                                    case 贷方余额 -> {
                                        switch (aging) {
                                            case 期初 -> val = balance.getBeginCreditBalance();
                                            case 期末 -> val = balance.getEndCreditBalance();
                                            case 年初 -> val = balance.getYearTotalBeginCredit();
                                        }
                                    }
                                }
                            }
                        }

                        if (text.get() != null) {
                            //替换变量
                            text.set(text.toString().replace(varConfig.getVarName(), "(" + NumberUtil.roundStr(val.doubleValue(), 2) + ")"));
                        }
                    });

                    //更新cell配置
                    if (text.get() != null) {
                        JSONObject object = cellMap.get(coordinate).getJSONObject("v");
                        object.put("f", text.get());
                    }
                }
            }

            cellMap.forEach((key, entry) -> {
                JSONObject v = entry.getJSONObject("v");
                if (v.containsKey("f")) {
                    JSONObject calc = new JSONObject();
                    calc.put("r", entry.getIntValue("r"));
                    calc.put("c", entry.getIntValue("c"));
                    calc.put("color", "w");
                    calc.put("times", 0);
                    calc.put("index", 0);
                    calc.put("func", Arrays.asList(true, v.getString("m"), v.getString("f")));
                    calcChain.add(calc);
                }
            });

            biReport.setCells(new JSONArray(cellMap.values()));
        }

        return JsonResult.successful()
                .data("report", biReport)
                .data("calcChain", calcChain);
    }

    private Set<Long> getAuxiliaryDetailIdList(Long accountSetsId, BiVarVo v) {
        Set<Long> auxiliaryDetailIdList = null;
        if (v.getAuxiliary() != null) {
            AccountBookQueryVO queryVO = new AccountBookQueryVO();
            queryVO.setAccountSetsId(accountSetsId);
            queryVO.setCategoryType(v.getAuxiliary().getCategoryType());
            queryVO.setCategoryId(v.getAuxiliary().getCategoryId() + "");
            queryVO.setDetailId(v.getAuxiliary().getId());
            auxiliaryDetailIdList = biReportService.auxiliaryDetailIdList(queryVO);
        }
        return auxiliaryDetailIdList;
    }
}
