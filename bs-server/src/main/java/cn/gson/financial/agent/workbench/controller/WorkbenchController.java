package cn.gson.financial.agent.workbench.controller;

import cn.gson.financial.agent.workbench.service.WorkbenchService;
import cn.gson.financial.kernel.annotation.SessionUser;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.model.dto.WorkbenchAccountDto;
import cn.gson.financial.kernel.model.vo.UserVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/**
 * 工作台控制器
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/workbench")
public class WorkbenchController {
    
    private final WorkbenchService workbenchService;
    
    /**
     * 获取工作台概览数据
     * @param user 当前登录用户
     * @param period 查询账期（YYYY-MM格式，可选）
     * @param statusFilter 状态筛选：all-所有, active-正常, warning-需关注, error-异常（可选）
     * @param searchKeyword 搜索关键词（可选）
     * @return 工作台账套列表
     */
    @GetMapping("/overview")
    public JsonResult getOverview(
            @SessionUser UserVo user,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM") LocalDate period,
            @RequestParam(required = false, defaultValue = "all") String statusFilter,
            @RequestParam(required = false) String searchKeyword) {
        
        try {
            List<WorkbenchAccountDto> accounts = workbenchService.getOverview(
                    user.getId(), 
                    period, 
                    statusFilter, 
                    searchKeyword
            );
            
            return JsonResult.successful(accounts);
        } catch (Exception e) {
            log.error("获取工作台概览数据失败", e);
            return JsonResult.failure("获取工作台数据失败：" + e.getMessage());
        }
    }
}
