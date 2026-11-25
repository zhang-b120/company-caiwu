package cn.gson.financial.financialProcess.controller;

import cn.dev33.satoken.annotation.SaIgnore;
import cn.dev33.satoken.stp.StpUtil;
import cn.gson.financial.financialProcess.req.ResetTicketStatusReq;
import cn.gson.financial.kernel.model.dto.FinancialProcessDTO;
import cn.gson.financial.kernel.model.entity.QIndividualTax;
import cn.gson.financial.kernel.model.entity.QSalary;
import cn.gson.financial.kernel.model.entity.TimedTaskInvoice;
import cn.gson.financial.kernel.service.FinancialProcessService;
import cn.gson.financial.kernel.service.UserService;
import cn.gson.financial.kernel.annotation.SessionUser;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.model.entity.QCheckout;
import cn.gson.financial.kernel.model.vo.UserVo;
import cn.gson.financial.kernel.service.AbsService;
import cn.gson.financial.kernel.tool.DateUtils;
import cn.gson.financial.timedTasks.service.TimedTaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static cn.gson.financial.kernel.common.Constants.S_AUTHORITIES_KEY;
import static cn.gson.financial.kernel.common.Constants.S_USER_KEY;

@RestController
@RequestMapping("/financialProcess")
@RequiredArgsConstructor
public class FinancialProcessController extends AbsService {
    private final FinancialProcessService financialProcessService;
    private final UserService userService;
    private final QCheckout qCheckout = QCheckout.checkout;
    private final QSalary qSalary = QSalary.salary;
    private final TimedTaskService timedTaskService;
    private final QIndividualTax qIndividualTax = QIndividualTax.individualTax;
    @PostMapping("/getFinancialProcess")
    public JsonResult getFinancialProcess(@SessionUser UserVo userVo, @RequestBody FinancialProcessDTO financialProcessConditional) {
        Map<String, Object> result = financialProcessService.getFinancialProcess(userVo.getAccountSetsList(), financialProcessConditional);
        return JsonResult.successful(result);
    }

    @SaIgnore
    @GetMapping("/updateFinancialProcessTools1")
    public JsonResult updateFinancialProcess1() {
        //查询该账套中每个账套的结账日期，遍历结账日期，将其中的每个账期都作为一条记录。
        List<FinancialProcessDTO> financialProcessConditionalList = jqf.selectFrom(qCheckout)
                .where(qCheckout.status.eq(2).and(
                       qCheckout.checkDate.between(LocalDate.of(2025, 7, 1),
                               LocalDate.of(2025, 11, 30))
                ))
                .fetch().stream().map(item -> {
            FinancialProcessDTO financialProcessDTO = new FinancialProcessDTO();
            financialProcessDTO.setAccountingPeriod(String.valueOf(item.getCheckDate()));
            financialProcessDTO.setAccountSetsId(item.getAccountSetsId());
            financialProcessDTO.setIsAccountingDone("是");
            return financialProcessDTO;
        }).toList();
        financialProcessConditionalList.forEach(financialProcessService::updateFinancialProcess);
        return JsonResult.successful();
    }
    @SaIgnore
    @GetMapping("/updateFinancialProcessTools2")
    public JsonResult updateFinancialProcess2() {
        //查询该账套中每个账套的结账日期，遍历结账日期，将其中的每个账期都作为一条记录。
        List<FinancialProcessDTO> financialProcessConditionalList = jqf.selectFrom(qSalary)
                .where(qSalary.state.eq(true).and(
                        qSalary.period.between(LocalDate.of(2025, 7, 1),
                                LocalDate.of(2025, 11, 30))
                ))
                .fetch().stream().map(item -> {
                    FinancialProcessDTO financialProcessDTO = new FinancialProcessDTO();
                    financialProcessDTO.setAccountingPeriod(String.valueOf(item.getPeriod()));
                    financialProcessDTO.setAccountSetsId(item.getAccountSetsId());
                    financialProcessDTO.setIsPayrollProcessed("是");
                    return financialProcessDTO;
                }).toList();
        financialProcessConditionalList.forEach(financialProcessService::updateFinancialProcess);
        return JsonResult.successful();
    }
    @SaIgnore
    @GetMapping("/updateFinancialProcessTools3")
    public JsonResult updateFinancialProcess3() {
        //查询该账套中每个账套的结账日期，遍历结账日期，将其中的每个账期都作为一条记录。
        List<FinancialProcessDTO> financialProcessConditionalList = jqf.selectFrom(qIndividualTax)
                .fetch().stream().map(item -> {
                    FinancialProcessDTO financialProcessDTO = new FinancialProcessDTO();
                    String firstDayOfLastMonthAsString = DateUtils.getFirstDayOfLastMonthAsString();
                    financialProcessDTO.setAccountingPeriod(firstDayOfLastMonthAsString);
                    financialProcessDTO.setAccountSetsId(item.getAccountSetsId());
                    financialProcessDTO.setIsIndividualTaxCompleted("是");
                    return financialProcessDTO;
                }).toList();
        financialProcessConditionalList.forEach(financialProcessService::updateFinancialProcess);
        return JsonResult.successful();
    }
    @PostMapping("/resetTicketStatus")
    public JsonResult resetTicketStatus(@RequestBody ResetTicketStatusReq resetTicketStatusReq){
        FinancialProcessDTO financialProcessDTO = new FinancialProcessDTO();
        financialProcessDTO.setAccountingPeriod(String.valueOf(resetTicketStatusReq.getAccountingPeriod()));
        financialProcessDTO.setIsTicketObtained("否");
        financialProcessDTO.setIsTaxItemCompleted("否");
        resetTicketStatusReq.getIds().forEach(item->{
            financialProcessDTO.setAccountSetsId(item);
            financialProcessService.updateFinancialProcess(financialProcessDTO);
            timedTaskService.deleteTimeTaskInvoiceByAccountSetsId(item,resetTicketStatusReq.getAccountingPeriod());
        });
        return JsonResult.successful();
    }

    /**
     * 分页查询所有账套并标记当前用户是否已分配
     */
    @PostMapping("/listAllAccountSets")
    public JsonResult listAllAccountSets(@SessionUser UserVo userVo, @RequestBody(required = false) Map<String, Object> req) {
        ensureCanAssignAccountSets(userVo);
        return JsonResult.successful(financialProcessService.listAllAccountSets(userVo.getId(), req));
    }

    /**
     * 将当前用户分配为该账套的管理员
     */
    @PostMapping("/assignSelf")
    public JsonResult assignSelf(@SessionUser UserVo userVo, @RequestBody Map<String, Object> req) {
        ensureCanAssignAccountSets(userVo);
        Long accountSetsId = parseLong(req == null ? null : req.get("accountSetsId"));
        if (accountSetsId == null) {
            return JsonResult.failure("缺少账套ID");
        }
        financialProcessService.assignSelf(userVo.getMobile(), accountSetsId);
        refreshSession(userVo.getId());
        return JsonResult.successful();
    }

    /**
     * 批量将当前用户分配为多个账套的管理员
     */
    @PostMapping("/batchAssignSelf")
    public JsonResult batchAssignSelf(@SessionUser UserVo userVo, @RequestBody Map<String, Object> req) {
        ensureCanAssignAccountSets(userVo);
        List<Long> ids = parseLongList(req == null ? null : req.get("accountSetsIds"));
        if (ids == null || ids.isEmpty()) {
            return JsonResult.failure("请至少选择一个账套");
        }
        List<Map<String, Object>> results = financialProcessService.batchAssignSelf(userVo.getMobile(), ids);
        long successCount = results.stream().filter(r -> Boolean.TRUE.equals(r.get("success"))).count();
        if (successCount > 0) {
            refreshSession(userVo.getId());
        }
        Map<String, Object> resp = new java.util.HashMap<>();
        resp.put("results", results);
        resp.put("successCount", successCount);
        resp.put("failCount", results.size() - successCount);
        return JsonResult.successful(resp);
    }

    /**
     * 将当前用户从该账套移除
     */
    @PostMapping("/unassignSelf")
    public JsonResult unassignSelf(@SessionUser UserVo userVo, @RequestBody Map<String, Object> req) {
        ensureCanAssignAccountSets(userVo);
        Long accountSetsId = parseLong(req == null ? null : req.get("accountSetsId"));
        if (accountSetsId == null) {
            return JsonResult.failure("缺少账套ID");
        }
        financialProcessService.unassignSelf(userVo.getId(), accountSetsId);
        refreshSession(userVo.getId());
        return JsonResult.successful();
    }

    private void ensureCanAssignAccountSets(UserVo userVo) {
        if (userVo == null || !Boolean.TRUE.equals(userVo.getCanAssignAccountSets())) {
            throw new cn.gson.financial.kernel.exception.ServiceException("当前账号未开放账套分配权限", 403);
        }
    }

    private void refreshSession(Long userId) {
        UserVo fresh = userService.getUserVo(userId);
        StpUtil.getSession().set(S_USER_KEY, fresh)
                .set(S_AUTHORITIES_KEY, fresh.getRoleInfo());
    }

    private static Long parseLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            return n.longValue();
        }
        String s = String.valueOf(value).trim();
        if (s.isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static List<Long> parseLongList(Object value) {
        if (!(value instanceof java.util.Collection<?> col)) {
            return java.util.Collections.emptyList();
        }
        List<Long> ids = new java.util.ArrayList<>();
        for (Object item : col) {
            Long v = parseLong(item);
            if (v != null) {
                ids.add(v);
            }
        }
        return ids;
    }
}
