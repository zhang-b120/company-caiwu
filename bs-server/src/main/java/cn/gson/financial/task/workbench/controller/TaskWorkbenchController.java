package cn.gson.financial.task.workbench.controller;

import cn.dev33.satoken.annotation.SaIgnore;
import cn.dev33.satoken.stp.StpUtil;
import cn.gson.financial.kernel.annotation.SessionUser;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.model.dto.VoucherDto;
import cn.gson.financial.kernel.model.entity.*;
import cn.gson.financial.kernel.model.hibernate.PrimaryKey;
import cn.gson.financial.kernel.model.repo.*;
import cn.gson.financial.kernel.model.vo.UserVo;
import cn.gson.financial.kernel.service.VoucherService;
import cn.gson.financial.task.workbench.export.IitExportRequest;
import cn.gson.financial.task.workbench.export.IitExportService;
import cn.gson.financial.task.workbench.export.IitExportTaskStatus;
import cn.gson.financial.task.workbench.service.TaskBankImportService;
import cn.gson.financial.task.workbench.service.TaskBookkeepService;
import cn.gson.financial.task.workbench.service.TaskInitImportService;
import cn.gson.financial.task.workbench.service.TaskSalarySmartImportService;
import cn.gson.financial.task.workbench.service.TaskWorkbenchService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 任务工作台控制器
 * 提供工作台页面所需的全部后端接口
 */
@Slf4j
@SaIgnore
@RestController
@RequiredArgsConstructor
@RequestMapping("/task-workbench")
public class TaskWorkbenchController {
    private final TaskWorkbenchService taskWorkbenchService;
    private final TaskBookkeepService taskBookkeepService;
    private final VoucherService voucherService;
    private final TaskInitImportService taskInitImportService;
    private final TaskBankImportService taskBankImportService;
    private final TaskSalarySmartImportService taskSalarySmartImportService;
    private final CashierAccountRepository cashierAccountRepository;
    private final CashierJournalRepository cashierJournalRepository;
    private final CashierIoTypeRepository cashierIoTypeRepository;
    private final SubjectRepository subjectRepository;
    private final BankImportPatternRepository bankImportPatternRepository;
    private final IitExportService iitExportService;

    /**
     * 获取任务列表（含统计）
     * GET /api/task-workbench/tasks
     */
    @GetMapping("/tasks")
    public JsonResult getTasks(
            @RequestParam(required = false) String period,
            @RequestParam(required = false, defaultValue = "all") String status,
            @RequestParam(required = false) String stepField,
            @RequestParam(required = false) String vat,
            @RequestParam(required = false) String accountPeriod,
            @RequestParam(required = false) String province,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false, defaultValue = "1") int page,
            @RequestParam(required = false, defaultValue = "50") int pageSize,
            @RequestParam(required = false) String filterUserIds,
            @RequestParam(required = false) String declareFilter,
            @RequestParam(required = false) String iitFilter,
            @SessionUser UserVo userVo) {
        try {
            List<Long> userIds;
            if (filterUserIds != null && !filterUserIds.isBlank()) {
                userIds = java.util.Arrays.stream(filterUserIds.split(","))
                        .map(String::trim).filter(s -> !s.isEmpty())
                        .map(Long::parseLong).collect(Collectors.toList());
            } else {
                // 未指定筛选用户时，默认只显示当前登录用户的账套
                userIds = java.util.Collections.singletonList(userVo.getId());
            }
            Map<String, Object> result = taskWorkbenchService.getTasks(
                    period, status, stepField, vat, accountPeriod, province, keyword, page, pageSize, userIds, declareFilter, iitFilter);
            return JsonResult.successful(result);
        } catch (Exception e) {
            log.error("获取任务列表失败", e);
            return JsonResult.failure("获取任务列表失败：" + e.getMessage());
        }
    }

    /**
     * 获取所有账号列表（用于任务控制台账号筛选）
     * GET /api/task-workbench/financial-users
     */
    @GetMapping("/financial-users")
    public JsonResult getFinancialUsers() {
        try {
            return JsonResult.successful(taskWorkbenchService.getFinancialUsers());
        } catch (Exception e) {
            log.error("获取账号列表失败", e);
            return JsonResult.failure("获取账号列表失败：" + e.getMessage());
        }
    }

    /**
     * 根据税号列表匹配账套（导入表格批量添加任务）
     * POST /api/task-workbench/match-credit-codes
     * 参数：{ credit_codes: ["91110105MA00ABCD12", ...], filter_user_ids: "1,2" (可选) }
     */
    @PostMapping("/match-credit-codes")
    public JsonResult matchCreditCodes(@RequestBody Map<String, Object> body,
                                       @SessionUser UserVo userVo) {
        try {
            @SuppressWarnings("unchecked")
            List<String> creditCodes = (List<String>) body.get("credit_codes");
            String filterUserIdsStr = body.get("filter_user_ids") != null
                    ? body.get("filter_user_ids").toString() : null;

            List<Long> userIds;
            if (filterUserIdsStr != null && !filterUserIdsStr.isBlank()) {
                userIds = java.util.Arrays.stream(filterUserIdsStr.split(","))
                        .map(String::trim).filter(s -> !s.isEmpty())
                        .map(Long::parseLong).collect(Collectors.toList());
            } else {
                userIds = java.util.Collections.singletonList(userVo.getId());
            }
            Map<String, Object> result = taskWorkbenchService.matchCreditCodes(creditCodes, userIds);
            return JsonResult.successful(result);
        } catch (Exception e) {
            log.error("匹配税号失败", e);
            return JsonResult.failure("匹配税号失败：" + e.getMessage());
        }
    }

    /**
     * 获取实时进度（含统计 + 事件流）
     * GET /api/task-workbench/progress
     */
    @GetMapping("/progress")
    public JsonResult getProgress(
            @RequestParam(required = false) String period,
            @SessionUser UserVo userVo) {
        try {
            Long userId = userVo.getId();
            Map<String, Object> result = taskWorkbenchService.getProgress(period, userId);
            return JsonResult.successful(result);
        } catch (Exception e) {
            log.error("获取实时进度失败", e);
            return JsonResult.failure("获取实时进度失败：" + e.getMessage());
        }
    }

    /**
     * 获取单客户详情
     * GET /api/task-workbench/tasks/{accountSetId}
     */
    @GetMapping("/tasks/{accountSetId}")
    public JsonResult getDetail(
            @PathVariable Long accountSetId,
            @RequestParam(required = false) String period) {
        try {
            Map<String, Object> detail = taskWorkbenchService.getDetail(accountSetId, period);
            return JsonResult.successful(detail);
        } catch (Exception e) {
            log.error("获取任务详情失败: accountSetId={}", accountSetId, e);
            return JsonResult.failure("获取详情失败：" + e.getMessage());
        }
    }

    /**
     * 下发单步骤任务
     * POST /api/task-workbench/dispatch
     */
    @PostMapping("/dispatch")
    public JsonResult dispatch(@RequestBody Map<String, Object> body) {
        try {
            Long accountSetId = toLong(body.get("account_set_id"));
            String period = (String) body.get("period");
            String step = (String) body.get("step");
            boolean force = Boolean.TRUE.equals(body.get("force"));
            String ocInstanceId = (String) body.get("oc_instance_id"); // 可选：指定 OC 实例编号
            String subType = (String) body.get("sub_type"); // 可选：数据采集子任务类型

            if (accountSetId == null || step == null) {
                return JsonResult.failure("account_set_id 和 step 不能为空");
            }

            Map<String, Object> result = taskWorkbenchService.dispatch(accountSetId, period, step, force, ocInstanceId, subType);
            // 做账步骤：dispatch 事务提交后，在事务外执行实际做账
            result = taskWorkbenchService.executeBookkeepAfterDispatch(result);
            boolean ok = Boolean.TRUE.equals(result.get("success"));
            return ok ? JsonResult.successful(result) : JsonResult.failure((String) result.get("msg"));
        } catch (Exception e) {
            log.error("下发任务失败", e);
            return JsonResult.failure("下发失败：" + e.getMessage());
        }
    }

    /**
     * 批量下发
     * POST /api/task-workbench/dispatch/batch
     */
    @PostMapping("/dispatch/batch")
    public JsonResult batchDispatch(@RequestBody Map<String, Object> body) {
        try {
            @SuppressWarnings("unchecked")
            List<Object> ids = (List<Object>) body.get("account_set_ids");
            String period = (String) body.get("period");
            String step = (String) body.get("step");

            if (ids == null || ids.isEmpty()) {
                return JsonResult.failure("account_set_ids 不能为空");
            }
            if (step == null) {
                return JsonResult.failure("step 不能为空");
            }

            List<Long> accountSetIds = ids.stream().map(this::toLong).toList();
            Map<String, Object> result = taskWorkbenchService.batchDispatch(accountSetIds, period, step);
            return JsonResult.successful(result);
        } catch (Exception e) {
            log.error("批量下发失败", e);
            return JsonResult.failure("批量下发失败：" + e.getMessage());
        }
    }

    /**
     * 批量下发发票取票任务（无前置限制）
     * POST /api/task-workbench/dispatch/batch-invoice
     * 参数：{ account_set_ids: [...], period: "2025-01" }
     */
    @PostMapping("/dispatch/batch-invoice")
    public JsonResult batchDispatchInvoice(@RequestBody Map<String, Object> body) {
        try {
            @SuppressWarnings("unchecked")
            List<Object> ids = (List<Object>) body.get("account_set_ids");
            String period = (String) body.get("period");
            if (ids == null || ids.isEmpty()) return JsonResult.failure("account_set_ids 不能为空");
            List<Long> accountSetIds = ids.stream().map(this::toLong).toList();
            return JsonResult.successful(taskWorkbenchService.batchDispatchInvoice(accountSetIds, period));
        } catch (Exception e) {
            log.error("批量下发取票失败", e);
            return JsonResult.failure("批量下发取票失败：" + e.getMessage());
        }
    }

    /**
     * 批量下发指定步骤（多选）
     * POST /api/task-workbench/dispatch/batch-steps
     * 参数：{ account_set_ids: [...], period: "2025-01", steps: ["verify","invoice","bank"] }
     */
    @PostMapping("/dispatch/batch-steps")
    public JsonResult batchDispatchSteps(@RequestBody Map<String, Object> body) {
        try {
            @SuppressWarnings("unchecked")
            List<Object> ids = (List<Object>) body.get("account_set_ids");
            String period = (String) body.get("period");
            @SuppressWarnings("unchecked")
            List<String> steps = (List<String>) body.get("steps");
            if (ids == null || ids.isEmpty()) return JsonResult.failure("account_set_ids 不能为空");
            if (steps == null || steps.isEmpty()) return JsonResult.failure("steps 不能为空");
            List<Long> accountSetIds = ids.stream().map(this::toLong).toList();
            return JsonResult.successful(taskWorkbenchService.batchDispatchSteps(accountSetIds, period, steps));
        } catch (Exception e) {
            log.error("批量下发步骤失败", e);
            return JsonResult.failure("批量下发失败：" + e.getMessage());
        }
    }

    /**
     * 批量全量下发（一键下发所有任务）
     * POST /api/task-workbench/dispatch/batch-all
     * 参数：{ account_set_ids: [...], period: "2025-01" }
     */
    @PostMapping("/dispatch/batch-all")
    public JsonResult batchDispatchAll(@RequestBody Map<String, Object> body) {
        try {
            @SuppressWarnings("unchecked")
            List<Object> ids = (List<Object>) body.get("account_set_ids");
            String period = (String) body.get("period");
            if (ids == null || ids.isEmpty()) return JsonResult.failure("account_set_ids 不能为空");
            List<Long> accountSetIds = ids.stream().map(this::toLong).toList();
            return JsonResult.successful(taskWorkbenchService.batchDispatchAll(accountSetIds, period));
        } catch (Exception e) {
            log.error("批量全量下发失败", e);
            return JsonResult.failure("批量全量下发失败：" + e.getMessage());
        }
    }

    /**
     * 单账套全量下发（下发该账套所有任务步骤）
     * POST /api/task-workbench/dispatch/all
     * 参数：{ account_set_id: 123, period: "2025-01" }
     */
    @PostMapping("/dispatch/all")
    public JsonResult dispatchAll(@RequestBody Map<String, Object> body) {
        try {
            Long accountSetId = toLong(body.get("account_set_id"));
            String period = (String) body.get("period");
            if (accountSetId == null) return JsonResult.failure("account_set_id 不能为空");
            return JsonResult.successful(taskWorkbenchService.dispatchAll(accountSetId, period));
        } catch (Exception e) {
            log.error("全量下发失败", e);
            return JsonResult.failure("全量下发失败：" + e.getMessage());
        }
    }

    /**
     * 批量查询指定登录渠道的办税员（批量下发前配置用）
     * POST /api/task-workbench/tax-officers/batch-query
     * 参数：{ account_set_ids: [...], login_type: "proxy" }
     */
    @PostMapping("/tax-officers/batch-query")
    public JsonResult batchQueryOfficers(@RequestBody Map<String, Object> body) {
        try {
            @SuppressWarnings("unchecked")
            List<Object> ids = (List<Object>) body.get("account_set_ids");
            String loginType = (String) body.get("login_type");
            if (ids == null || ids.isEmpty()) return JsonResult.failure("account_set_ids 不能为空");
            if (loginType == null || loginType.isBlank()) return JsonResult.failure("login_type 不能为空");
            List<Long> accountSetIds = ids.stream().map(this::toLong).toList();
            return JsonResult.successful(taskWorkbenchService.batchQueryOfficersByType(accountSetIds, loginType));
        } catch (Exception e) {
            log.error("批量查询办税员失败", e);
            return JsonResult.failure("查询失败：" + e.getMessage());
        }
    }

    /**
     * 批量设置默认办税员（用户在批量下发弹窗中选定凭证后调用）
     * POST /api/task-workbench/tax-officers/batch-set-default
     * 参数：{ account_set_ids: [...], officer_id: Y }
     * officer_id 为 batch-query 返回的凭证代表记录 ID，
     * 系统自动在已选账套中找持有相同凭证的账套并批量设为默认。
     */
    @PostMapping("/tax-officers/batch-set-default")
    public JsonResult batchSetDefaultOfficer(@RequestBody Map<String, Object> body) {
        try {
            @SuppressWarnings("unchecked")
            List<Object> ids = (List<Object>) body.get("account_set_ids");
            Long officerId = toLong(body.get("officer_id"));
            if (ids == null || ids.isEmpty()) return JsonResult.failure("account_set_ids 不能为空");
            if (officerId == null) return JsonResult.failure("officer_id 不能为空");
            List<Long> accountSetIds = ids.stream().map(this::toLong).toList();
            return JsonResult.successful(taskWorkbenchService.batchSetDefaultOfficer(accountSetIds, officerId));
        } catch (Exception e) {
            log.error("批量设置默认办税员失败", e);
            return JsonResult.failure("设置失败：" + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // 办税员管理
    // ─────────────────────────────────────────────────────────────────────

    /**
     * 获取账套下所有办税员
     * GET /api/task-workbench/tax-officers?accountSetId=xxx
     */
    @GetMapping("/tax-officers")
    public JsonResult getTaxOfficers(@RequestParam Long accountSetId) {
        try {
            return JsonResult.successful(taskWorkbenchService.getTaxOfficers(accountSetId));
        } catch (Exception e) {
            log.error("获取办税员列表失败", e);
            return JsonResult.failure("获取失败：" + e.getMessage());
        }
    }

    /**
     * 新增或更新办税员
     * POST /api/task-workbench/tax-officers
     */
    @PostMapping("/tax-officers")
    public JsonResult saveTaxOfficer(@RequestBody Map<String, Object> body) {
        try {
            Long accountSetId = toLong(body.get("account_set_id"));
            if (accountSetId == null) return JsonResult.failure("account_set_id 不能为空");
            String name = (String) body.get("name");
            if (name == null || name.isBlank()) return JsonResult.failure("办税员姓名不能为空");
            return JsonResult.successful(taskWorkbenchService.saveTaxOfficer(accountSetId, body));
        } catch (Exception e) {
            log.error("保存办税员失败", e);
            return JsonResult.failure("保存失败：" + e.getMessage());
        }
    }

    /**
     * 删除办税员
     * DELETE /api/task-workbench/tax-officers/{id}
     */
    @DeleteMapping("/tax-officers/{id}")
    public JsonResult deleteTaxOfficer(@PathVariable Long id, @RequestParam Long accountSetId) {
        try {
            return JsonResult.successful(taskWorkbenchService.deleteTaxOfficer(id, accountSetId));
        } catch (Exception e) {
            log.error("删除办税员失败", e);
            return JsonResult.failure("删除失败：" + e.getMessage());
        }
    }

    /**
     * 设置默认办税员
     * POST /api/task-workbench/tax-officers/{id}/default
     */
    @PostMapping("/tax-officers/{id}/default")
    public JsonResult setDefaultOfficer(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        try {
            Long accountSetId = toLong(body.get("account_set_id"));
            if (accountSetId == null) return JsonResult.failure("account_set_id 不能为空");
            return JsonResult.successful(taskWorkbenchService.setDefaultOfficer(id, accountSetId));
        } catch (Exception e) {
            log.error("设置默认办税员失败", e);
            return JsonResult.failure("设置失败：" + e.getMessage());
        }
    }

    /**
     * 保存账套级登录渠道
     * POST /api/task-workbench/update-login-type
     * 参数：account_set_id, login_type（officer/proxy/legal_person/ca）
     */
    @PostMapping("/update-login-type")
    public JsonResult updateLoginType(@RequestBody Map<String, Object> body) {
        try {
            Long accountSetId = toLong(body.get("account_set_id"));
            String loginType = (String) body.get("login_type");
            if (accountSetId == null) return JsonResult.failure("account_set_id 不能为空");
            return JsonResult.successful(taskWorkbenchService.saveLoginType(accountSetId, loginType));
        } catch (Exception e) {
            log.error("保存登录渠道失败", e);
            return JsonResult.failure("保存失败：" + e.getMessage());
        }
    }

    /**
     * 保存账套级 OC 实例绑定
     * POST /api/task-workbench/bind-oc
     */
    @PostMapping("/bind-oc")
    public JsonResult bindOc(@RequestBody Map<String, Object> body) {
        try {
            Long accountSetId = toLong(body.get("account_set_id"));
            String period = (String) body.get("period");
            String ocInstanceId = (String) body.get("oc_instance_id"); // 为空字符串则解绑

            if (accountSetId == null) {
                return JsonResult.failure("account_set_id 不能为空");
            }
            Map<String, Object> result = taskWorkbenchService.saveOcBinding(accountSetId, period, ocInstanceId);
            return JsonResult.successful(result);
        } catch (Exception e) {
            log.error("保存OC绑定失败", e);
            return JsonResult.failure("保存失败：" + e.getMessage());
        }
    }

    /**
     * 手动上传发票 Excel（不依赖 OC 自动下载，人工补录或提前录入）
     * POST /api/task-workbench/upload-invoice  multipart/form-data
     * 参数：file, accountSetId, period, invoiceType(可选), importMode(可选: month/season)
     */
    @PostMapping("/upload-invoice")
    public JsonResult uploadInvoice(
            @RequestParam("file") MultipartFile file,
            @RequestParam("accountSetId") Long accountSetId,
            @RequestParam(value = "period", required = false) String period,
            @RequestParam(value = "invoiceType", required = false, defaultValue = "") String invoiceType,
            @RequestParam(value = "importMode", required = false, defaultValue = "month") String importMode) {
        try {
            if (file == null || file.isEmpty()) return JsonResult.failure("请上传发票文件");
            if (accountSetId == null) return JsonResult.failure("accountSetId 不能为空");
            Map<String, Object> result = taskWorkbenchService.uploadInvoice(
                    file, accountSetId, period, invoiceType, importMode);
            boolean ok = Boolean.TRUE.equals(result.get("success"));
            return ok ? JsonResult.successful(result) : JsonResult.failure((String) result.get("msg"));
        } catch (Exception e) {
            log.error("发票上传失败: accountSetId={}", accountSetId, e);
            return JsonResult.failure("发票上传失败：" + e.getMessage());
        }
    }

    /**
     * 期初导入（一步完成：Coze 解析 + 保存 + 标记 done）
     * POST /api/task-workbench/import-init  multipart/form-data
     * 参数：file, accountType, accountSetId, period
     */
    /**
     * 期初导入实时进度订阅（SSE）
     * 前端在触发导入前先调用此接口建立连接，之后通过 EventSource 接收进度日志。
     */
    @GetMapping(value = "/import-init/progress", produces = "text/event-stream;charset=UTF-8")
    public org.springframework.web.servlet.mvc.method.annotation.SseEmitter importInitProgress(
            @RequestParam Long accountSetId) {
        return taskInitImportService.subscribeProgress(accountSetId);
    }

    @PostMapping("/import-init")
    public JsonResult importInit(
            @RequestParam("file") MultipartFile file,
            @RequestParam("accountType") String accountType,
            @RequestParam("accountSetId") Long accountSetId,
            @RequestParam(value = "period", required = false) String period) {
        try {
            if (file == null || file.isEmpty()) return JsonResult.failure("请上传文件");
            if (!"small_scale".equals(accountType) && !"general_taxpayer".equals(accountType)) {
                return JsonResult.failure("accountType 只能为 small_scale 或 general_taxpayer");
            }
            if (accountSetId == null) return JsonResult.failure("accountSetId 不能为空");
            return JsonResult.successful(taskInitImportService.importInit(file, accountType, accountSetId, period));
        } catch (Exception e) {
            log.error("期初导入失败: accountSetId={}", accountSetId, e);
            return JsonResult.failure("导入失败：" + e.getMessage());
        }
    }

    /**
     * 银行流水智能导入（支持 Excel / PDF / 图片）
     * POST /api/task-workbench/import-bank  multipart/form-data
     * 参数：file, accountSetId, period, bankName(可选), userId(可选)
     */
    @PostMapping("/import-bank")
    public JsonResult importBank(
            @RequestParam("file") MultipartFile file,
            @RequestParam("accountSetId") Long accountSetId,
            @RequestParam(value = "period", required = false) String period,
            @RequestParam(value = "bankName", required = false) String bankName,
            @RequestParam(value = "cashierAccountId", required = false) Long cashierAccountId,
            @RequestParam(value = "userId", required = false) Long userId) {
        try {
            if (file == null || file.isEmpty()) return JsonResult.failure("请上传文件");
            if (accountSetId == null) return JsonResult.failure("accountSetId 不能为空");
            Map<String, Object> result = taskBankImportService.importBankStatement(
                    file, accountSetId, period, bankName,
                    cashierAccountId, userId != null ? userId : 0L);
            return JsonResult.successful(result);
        } catch (Exception e) {
            log.error("银行流水导入失败: accountSetId={}", accountSetId, e);
            return JsonResult.failure("导入失败：" + e.getMessage());
        }
    }

    /**
     * 查询当前账期下已上传的银行流水文件列表
     * GET /api/task-workbench/bank-statement-files?accountSetId=...&period=YYYY-MM
     */
    @GetMapping("/bank-statement-files")
    public JsonResult listBankStatementFiles(
            @RequestParam("accountSetId") Long accountSetId,
            @RequestParam("period") String period) {
        try {
            if (accountSetId == null) return JsonResult.failure("accountSetId 不能为空");
            return JsonResult.successful(taskBankImportService.listBankStatementFiles(accountSetId, period));
        } catch (Exception e) {
            log.error("查询银行流水文件失败: accountSetId={}, period={}", accountSetId, period, e);
            return JsonResult.failure("查询失败：" + e.getMessage());
        }
    }

    /**
     * 删除单个银行流水文件（级联删除明细、日记账、凭证）
     * DELETE /api/task-workbench/bank-statement-files/{id}?accountSetId=...
     */
    @DeleteMapping("/bank-statement-files/{id}")
    public JsonResult deleteBankStatementFile(
            @PathVariable("id") Long id,
            @RequestParam("accountSetId") Long accountSetId) {
        try {
            if (id == null) return JsonResult.failure("id 不能为空");
            if (accountSetId == null) return JsonResult.failure("accountSetId 不能为空");
            return JsonResult.successful(taskBankImportService.deleteBankStatementFile(id, accountSetId));
        } catch (IllegalArgumentException e) {
            return JsonResult.failure(e.getMessage());
        } catch (Exception e) {
            log.error("删除银行流水文件失败: id={}, accountSetId={}", id, accountSetId, e);
            return JsonResult.failure("删除失败：" + e.getMessage());
        }
    }

    /**
     * 标记期初导入步骤完成
     * POST /api/task-workbench/complete-init
     */
    @PostMapping("/complete-init")
    public JsonResult completeInit(@RequestBody Map<String, Object> body) {
        try {
            Long accountSetId = toLong(body.get("account_set_id"));
            String period = (String) body.get("period");
            if (accountSetId == null) return JsonResult.failure("account_set_id 不能为空");
            return JsonResult.successful(taskWorkbenchService.completeInit(accountSetId, period));
        } catch (Exception e) {
            log.error("标记期初完成失败", e);
            return JsonResult.failure("操作失败：" + e.getMessage());
        }
    }

    /**
     * 重置任务
     * POST /api/task-workbench/reset
     */
    @PostMapping("/reset")
    public JsonResult reset(@RequestBody Map<String, Object> body) {
        try {
            Long accountSetId = toLong(body.get("account_set_id"));
            String period = (String) body.get("period");
            String step = (String) body.get("step");

            if (accountSetId == null) {
                return JsonResult.failure("account_set_id 不能为空");
            }

            Map<String, Object> result = taskWorkbenchService.reset(accountSetId, period, step);
            return JsonResult.successful(result);
        } catch (Exception e) {
            log.error("重置任务失败", e);
            return JsonResult.failure("重置失败：" + e.getMessage());
        }
    }

    /**
     * 批量重置指定步骤
     * POST /api/task-workbench/reset/batch-steps
     * 参数：{ account_set_ids: [...], period: "2026-03", steps: ["verify","iit","payment"] }
     */
    @PostMapping("/reset/batch-steps")
    public JsonResult batchResetSteps(@RequestBody Map<String, Object> body) {
        try {
            @SuppressWarnings("unchecked")
            List<Object> ids = (List<Object>) body.get("account_set_ids");
            String period = (String) body.get("period");
            @SuppressWarnings("unchecked")
            List<String> steps = (List<String>) body.get("steps");
            if (ids == null || ids.isEmpty()) return JsonResult.failure("account_set_ids 不能为空");
            if (steps == null || steps.isEmpty()) return JsonResult.failure("steps 不能为空");
            List<Long> accountSetIds = ids.stream().map(this::toLong).toList();
            return JsonResult.successful(taskWorkbenchService.batchResetSteps(accountSetIds, period, steps));
        } catch (Exception e) {
            log.error("批量重置步骤失败", e);
            return JsonResult.failure("批量重置失败：" + e.getMessage());
        }
    }



    /**
     * 执行结账并将做账步骤标记为完成
     * POST /api/task-workbench/complete-bookkeep
     */
    @PostMapping("/complete-bookkeep")
    public JsonResult completeBookkeep(@RequestBody Map<String, Object> body) {
        try {
            Long accountSetId = toLong(body.get("account_set_id"));
            String period = (String) body.get("period");
            if (accountSetId == null) return JsonResult.failure("account_set_id 不能为空");
            Map<String, Object> result = taskWorkbenchService.completeBookkeep(accountSetId, period);
            boolean ok = Boolean.TRUE.equals(result.get("success"));
            return ok ? JsonResult.successful(result) : JsonResult.failure((String) result.get("msg"));
        } catch (Exception e) {
            log.error("做账结账失败", e);
            return JsonResult.failure("结账失败：" + e.getMessage());
        }
    }

    /**
     * 更新客户星级
     * POST /api/task-workbench/star-rating
     * 参数：account_set_id, rating (0-5)
     */
    @PostMapping("/star-rating")
    public JsonResult updateStarRating(@RequestBody Map<String, Object> body) {
        try {
            Long accountSetId = toLong(body.get("account_set_id"));
            Integer rating = body.get("rating") != null ? Integer.parseInt(body.get("rating").toString()) : 0;
            if (accountSetId == null) return JsonResult.failure("account_set_id 不能为空");
            if (rating < 0 || rating > 5) return JsonResult.failure("星级必须在 0-5 之间");
            Map<String, Object> result = taskWorkbenchService.updateStarRating(accountSetId, rating);
            return JsonResult.successful(result);
        } catch (Exception e) {
            log.error("更新星级失败", e);
            return JsonResult.failure("更新星级失败：" + e.getMessage());
        }
    }

    /**
     * 检查账套上一年度是否全部结账（用于税务年报下发前判断）
     * GET /api/task-workbench/check-annual-checkout?accountSetId=123&period=2026-03
     * 返回 "ok" 或 "no"
     */
    @SaIgnore
    @GetMapping("/check-annual-checkout")
    public JsonResult checkAnnualCheckout(@RequestParam Long accountSetId, @RequestParam String period) {
        try {
            String result = taskWorkbenchService.checkAnnualCheckoutStatus(accountSetId, period);
            return JsonResult.successful(result);
        } catch (Exception e) {
            log.error("检查年度结账状态失败", e);
            return JsonResult.failure("查询失败：" + e.getMessage());
        }
    }

    /**
     * 切换本征期申报完成标记
     * POST /api/task-workbench/toggle-declare-completed
     * 参数：account_set_id, period(可选), completed(boolean)
     */
    @PostMapping("/toggle-declare-completed")
    public JsonResult toggleDeclareCompleted(@RequestBody Map<String, Object> body) {
        try {
            Long accountSetId = toLong(body.get("account_set_id"));
            String period = (String) body.get("period");
            boolean completed = Boolean.TRUE.equals(body.get("completed"));
            if (accountSetId == null) return JsonResult.failure("account_set_id 不能为空");
            return JsonResult.successful(taskWorkbenchService.toggleDeclareCompleted(accountSetId, period, completed));
        } catch (Exception e) {
            log.error("切换申报完成标记失败", e);
            return JsonResult.failure("操作失败：" + e.getMessage());
        }
    }

    /**
     * 保存 AI 企业简介（永久生效，用于 AI 做账时理解企业业务）
     * POST /api/task-workbench/ai-profile
     */
    @PostMapping("/ai-profile")
    public JsonResult saveAiProfile(@RequestBody Map<String, Object> body) {
        try {
            Long accountSetId = toLong(body.get("account_set_id"));
            if (accountSetId == null) return JsonResult.failure("account_set_id 不能为空");
            String aiProfile = body.get("ai_profile") != null ? body.get("ai_profile").toString() : "";
            taskWorkbenchService.saveAiProfile(accountSetId, aiProfile);
            return JsonResult.successful();
        } catch (Exception e) {
            log.error("保存AI企业简介失败", e);
            return JsonResult.failure("保存失败：" + e.getMessage());
        }
    }

    /**
     * 查询做账子任务明细（供前端弹窗展示）
     * GET /api/task-workbench/bookkeep-detail?accountSetId=xxx&period=yyyy-MM&subType=invoice
     * subType: invoice / bank / salary / asset / carry_forward
     */
    @GetMapping("/bookkeep-detail")
    public JsonResult getBookkeepDetail(@RequestParam Long accountSetId,
                                         @RequestParam String period,
                                         @RequestParam String subType) {
        try {
            java.time.YearMonth ym = java.time.YearMonth.parse(period);
            List<Map<String, Object>> rows = switch (subType) {
                case "invoice" -> taskBookkeepService.queryInvoiceDetail(accountSetId, ym);
                case "bank" -> taskBookkeepService.queryBankDetail(accountSetId, ym);
                case "salary" -> taskBookkeepService.querySalaryDetail(accountSetId, ym);
                case "asset", "asset_depreciation" -> taskBookkeepService.queryAssetDetail(accountSetId, ym);
                default -> List.of();
            };
            return JsonResult.successful(rows);
        } catch (Exception e) {
            log.error("查询做账详情失败: subType={}", subType, e);
            return JsonResult.failure("查询失败：" + e.getMessage());
        }
    }

    /**
     * 查询凭证详情（供做账详情弹窗中点击凭证号时展示）
     * GET /api/task-workbench/voucher?accountSetId=xxx&voucherId=yyy
     */
    @GetMapping("/voucher")
    public JsonResult getVoucher(@RequestParam Long accountSetId, @RequestParam Long voucherId) {
        try {
            VoucherDto voucher = voucherService.getOne(accountSetId, voucherId);
            if (voucher == null) return JsonResult.failure("凭证不存在");
            return JsonResult.successful(voucher);
        } catch (Exception e) {
            log.error("查询凭证失败: accountSetId={}, voucherId={}", accountSetId, voucherId, e);
            return JsonResult.failure("查询凭证失败：" + e.getMessage());
        }
    }

    /**
     * 快捷新增银行账户（同时在银行存款科目下建子科目）
     * POST /api/task-workbench/bank-accounts
     * 参数：account_set_id, name, card_number(可选)
     */
    @PostMapping("/bank-accounts")
    public JsonResult createBankAccount(@RequestBody Map<String, Object> body) {
        try {
            Long accountSetId = toLong(body.get("account_set_id"));
            String name = (String) body.get("name");
            String cardNumber = (String) body.get("card_number");
            if (accountSetId == null) return JsonResult.failure("account_set_id 不能为空");
            if (name == null || name.isBlank()) return JsonResult.failure("name 不能为空");
            return JsonResult.successful(taskBankImportService.createBankAccount(accountSetId, name, cardNumber));
        } catch (Exception e) {
            log.error("新增银行账户失败", e);
            return JsonResult.failure(e.getMessage());
        }
    }

    /**
     * 获取账套下的银行账户列表（用于导入银行流水时关联账户）
     * GET /api/task-workbench/bank-accounts?accountSetId=xxx
     */
    @GetMapping("/bank-accounts")
    public JsonResult getBankAccounts(@RequestParam Long accountSetId) {
        try {
            List<Map<String, Object>> list = cashierAccountRepository
                    .findByAccountSetsIdAndType(accountSetId, CashierAccount.CashierAccountType.银行存款)
                    .stream()
                    .map(a -> Map.<String, Object>of(
                            "id", a.getId(),
                            "name", a.getName() != null ? a.getName() : "",
                            "cardNumber", a.getCardNumber() != null ? a.getCardNumber() : ""
                    ))
                    .collect(Collectors.toList());
            return JsonResult.successful(list);
        } catch (Exception e) {
            log.error("获取银行账户列表失败", e);
            return JsonResult.failure("获取失败：" + e.getMessage());
        }
    }

    /**
     * 更新日记账的收支类别和对方科目，同时保存学习规律供 AI 参考
     * PATCH /api/task-workbench/journal/{id}?accountSetId=xxx
     * body: { ioTypeId, subjectId }
     */
    @PatchMapping("/journal/{id}")
    public JsonResult patchJournal(@PathVariable Long id,
                                   @RequestParam Long accountSetId,
                                   @RequestBody Map<String, Object> body) {
        try {
            CashierJournal journal = cashierJournalRepository
                    .findById(new PrimaryKey(id, accountSetId))
                    .orElseThrow(() -> new IllegalArgumentException("日记账记录不存在"));

            Long ioTypeId = body.containsKey("ioTypeId") ? toLong(body.get("ioTypeId")) : journal.getIoTypeId();
            Long subjectId = body.containsKey("subjectId") ? toLong(body.get("subjectId")) : journal.getSubjectId();

            journal.setIoTypeId(ioTypeId);
            journal.setSubjectId(subjectId);
            cashierJournalRepository.save(journal);

            // 保存学习规律：对方户名 → 收支类别 + 对方科目
            String counterparty = journal.getOppositeAccountName();
            if (counterparty != null && !counterparty.isBlank()) {
                try {
                    BankImportPattern pattern = bankImportPatternRepository
                            .findByAccountSetsIdAndCounterparty(accountSetId, counterparty)
                            .orElseGet(() -> {
                                BankImportPattern p = new BankImportPattern();
                                p.setAccountSetsId(accountSetId);
                                p.setCounterparty(counterparty);
                                p.setMatchCount(0);
                                return p;
                            });
                    // 填充收支类别信息
                    if (ioTypeId != null) {
                        cashierIoTypeRepository.findById(new PrimaryKey(ioTypeId, accountSetId)).ifPresent(t -> {
                            pattern.setIoTypeId(t.getId());
                            pattern.setIoTypeName(t.getName());
                            pattern.setIoTypeDirection(t.getType() == CashierIoType.Type.收 ? "收" : "支");
                        });
                    } else {
                        pattern.setIoTypeId(null);
                        pattern.setIoTypeName(null);
                        pattern.setIoTypeDirection(null);
                    }
                    // 填充对方科目信息
                    if (subjectId != null) {
                        subjectRepository.findById(new PrimaryKey(subjectId, accountSetId)).ifPresent(s -> {
                            pattern.setSubjectId(s.getId());
                            pattern.setSubjectCode(s.getCode());
                            pattern.setSubjectName(s.getName());
                        });
                    } else {
                        pattern.setSubjectId(null);
                        pattern.setSubjectCode(null);
                        pattern.setSubjectName(null);
                    }
                    pattern.setMatchCount(pattern.getMatchCount() + 1);
                    pattern.setLastUsedAt(java.time.LocalDateTime.now());
                    bankImportPatternRepository.save(pattern);
                    log.info("学习规律已保存: counterparty={}, ioType={}, subject={}", counterparty, ioTypeId, subjectId);
                } catch (Exception e) {
                    log.warn("保存学习规律失败（不影响主流程）: {}", e.getMessage());
                }
            }

            return JsonResult.successful();
        } catch (Exception e) {
            log.error("更新日记账失败: id={}", id, e);
            return JsonResult.failure("更新失败：" + e.getMessage());
        }
    }

    /**
     * 工资智能识别导入
     * POST /task-workbench/salary/smart-import?accountSetId=xxx&period=yyyy-MM
     * body: [{ name, phone, idCard, salary }, ...]
     */
    @PostMapping("/salary/smart-import")
    public JsonResult salarySmartImport(@RequestParam Long accountSetId,
                                        @RequestParam String period,
                                        @RequestBody List<Map<String, Object>> rows) {
        try {
            return JsonResult.successful(taskSalarySmartImportService.smartImport(accountSetId, period, rows));
        } catch (Exception e) {
            // 找到第一个本项目的堆栈帧，方便定位
            String location = java.util.Arrays.stream(e.getStackTrace())
                    .filter(f -> f.getClassName().startsWith("cn.gson.financial"))
                    .map(f -> f.getClassName().replaceAll(".*\\.", "") + "." + f.getMethodName() + ":" + f.getLineNumber())
                    .findFirst().orElse("unknown");
            log.error("工资智能导入失败: accountSetId={}, period={}, at={}", accountSetId, period, location, e);
            return JsonResult.failure("导入失败：" + e.getMessage() + " [at " + location + "]");
        }
    }

    /**
     * 保存账套级步骤执行模式配置（auto / manual）
     * POST /api/task-workbench/step-config
     * body: { account_set_id, config: { verify:"auto", init:"manual", ... } }
     */
    @PostMapping("/step-config")
    public JsonResult saveStepConfig(@RequestBody Map<String, Object> body) {
        try {
            Long accountSetId = toLong(body.get("account_set_id"));
            if (accountSetId == null) return JsonResult.failure("account_set_id 不能为空");
            @SuppressWarnings("unchecked")
            Map<String, Object> raw = (Map<String, Object>) body.get("config");
            if (raw == null) return JsonResult.failure("config 不能为空");
            Map<String, String> config = new java.util.LinkedHashMap<>();
            raw.forEach((k, v) -> config.put(k, v != null ? v.toString() : "auto"));
            taskWorkbenchService.saveStepConfig(accountSetId, config);
            return JsonResult.successful();
        } catch (Exception e) {
            log.error("保存步骤配置失败", e);
            return JsonResult.failure("保存失败：" + e.getMessage());
        }
    }

    /**
     * 批量保存步骤执行模式配置
     * POST /api/task-workbench/step-config/batch
     * body: { account_set_ids: [...], config: { verify:"auto", ... } }
     */
    /**
     * 批量保存步骤执行模式配置
     * POST /api/task-workbench/step-config/batch
     * body:
     *   指定账套模式：{ account_set_ids: [...], config: {...} }
     *   全量筛选模式：{ apply_all: true, period, status, vat, province, keyword, config: {...} }
     */
    @PostMapping("/step-config/batch")
    public JsonResult batchSaveStepConfig(@RequestBody Map<String, Object> body) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> raw = (Map<String, Object>) body.get("config");
            if (raw == null) return JsonResult.failure("config 不能为空");

            boolean applyAll = Boolean.TRUE.equals(body.get("apply_all"));
            @SuppressWarnings("unchecked")
            List<Object> ids = (List<Object>) body.get("account_set_ids");
            if (!applyAll && (ids == null || ids.isEmpty())) {
                return JsonResult.failure("请指定 account_set_ids 或设置 apply_all=true");
            }

            List<Long> accountSetIds = ids != null
                    ? ids.stream().map(this::toLong).collect(Collectors.toList())
                    : List.of();
            Map<String, String> config = new java.util.LinkedHashMap<>();
            raw.forEach((k, v) -> config.put(k, v != null ? v.toString() : "auto"));

            int count = taskWorkbenchService.batchSaveStepConfig(
                    accountSetIds, config, applyAll,
                    (String) body.get("period"),
                    (String) body.getOrDefault("status", "all"),
                    (String) body.getOrDefault("vat", ""),
                    (String) body.getOrDefault("province", ""),
                    (String) body.getOrDefault("keyword", "")
            );
            return JsonResult.successful(Map.of("count", count));
        } catch (Exception e) {
            log.error("批量保存步骤配置失败", e);
            return JsonResult.failure("保存失败：" + e.getMessage());
        }
    }

    /**
     * 人工确认后执行步骤（将 waiting → 实际下发）
     * POST /api/task-workbench/confirm-manual
     * body: { account_set_id, period, step, oc_instance_id(可选) }
     */
    @PostMapping("/confirm-manual")
    public JsonResult confirmManualStep(@RequestBody Map<String, Object> body) {
        try {
            Long accountSetId = toLong(body.get("account_set_id"));
            String period = (String) body.get("period");
            String step = (String) body.get("step");
            String ocInstanceId = (String) body.get("oc_instance_id");
            if (accountSetId == null || step == null) return JsonResult.failure("account_set_id 和 step 不能为空");
            Map<String, Object> result = taskWorkbenchService.confirmManualStep(accountSetId, period, step, ocInstanceId);
            boolean ok = Boolean.TRUE.equals(result.get("success"));
            return ok ? JsonResult.successful(result) : JsonResult.failure((String) result.get("msg"));
        } catch (Exception e) {
            log.error("确认人工步骤失败", e);
            return JsonResult.failure("确认失败：" + e.getMessage());
        }
    }

    /**
     * 异常解除：做账资产负债表不平衡时，用户修复后点击此接口重新检查。
     * POST /api/task-workbench/resolve-bookkeep-error
     * body: { account_set_id, period }
     */
    @PostMapping("/resolve-bookkeep-error")
    public JsonResult resolveBookkeepError(@RequestBody Map<String, Object> body) {
        try {
            Long accountSetId = toLong(body.get("account_set_id"));
            String period = (String) body.get("period");
            if (accountSetId == null) return JsonResult.failure("account_set_id 不能为空");
            Map<String, Object> result = taskWorkbenchService.resolveBookkeepError(accountSetId, period);
            boolean ok = Boolean.TRUE.equals(result.get("success"));
            return ok ? JsonResult.successful(result) : JsonResult.failure((String) result.get("msg"));
        } catch (Exception e) {
            log.error("异常解除失败", e);
            return JsonResult.failure("操作失败：" + e.getMessage());
        }
    }

    /**
     * 手动强制完成步骤/子任务（无论当前状态直接标记 done，解锁后续任务）
     * POST /api/task-workbench/mark-done
     * body: { account_set_id, period, step }  step 示例：verify / data / data.invoice / bookkeep.bank
     */
    @PostMapping("/mark-done")
    public JsonResult markStepDone(@RequestBody Map<String, Object> body) {
        try {
            Long accountSetId = toLong(body.get("account_set_id"));
            String period = (String) body.get("period");
            String step = (String) body.get("step");
            if (accountSetId == null || step == null) return JsonResult.failure("account_set_id 和 step 不能为空");
            Map<String, Object> result = taskWorkbenchService.markStepDone(accountSetId, period, step);
            boolean ok = Boolean.TRUE.equals(result.get("success"));
            return ok ? JsonResult.successful(result) : JsonResult.failure((String) result.get("msg"));
        } catch (Exception e) {
            log.error("手动标记完成失败", e);
            return JsonResult.failure("操作失败：" + e.getMessage());
        }
    }

    /**
     * 切换"已核查"标记（人工核查，与执行状态独立）
     * POST /api/task-workbench/mark-checked
     * body: { account_set_id, period, step, checked }
     *   step 仅支持主步骤名：verify / init / data / bookkeep / vat / iit / payment / tax_annual
     */
    @PostMapping("/mark-checked")
    public JsonResult markStepChecked(@RequestBody Map<String, Object> body) {
        try {
            Long accountSetId = toLong(body.get("account_set_id"));
            String period = (String) body.get("period");
            String step = (String) body.get("step");
            boolean checked = Boolean.TRUE.equals(body.get("checked"));
            if (accountSetId == null || step == null) return JsonResult.failure("account_set_id 和 step 不能为空");
            Map<String, Object> result = taskWorkbenchService.markStepChecked(accountSetId, period, step, checked);
            boolean ok = Boolean.TRUE.equals(result.get("success"));
            return ok ? JsonResult.successful(result) : JsonResult.failure((String) result.get("msg"));
        } catch (Exception e) {
            log.error("切换已核查标记失败", e);
            return JsonResult.failure("操作失败：" + e.getMessage());
        }
    }

    /**
     * 批量切换"已核查"标记
     * POST /api/task-workbench/mark-checked/batch
     * body: { account_set_ids: [...], period, steps: [...], checked }
     */
    @PostMapping("/mark-checked/batch")
    public JsonResult batchMarkStepChecked(@RequestBody Map<String, Object> body) {
        try {
            @SuppressWarnings("unchecked")
            List<Object> ids = (List<Object>) body.get("account_set_ids");
            String period = (String) body.get("period");
            @SuppressWarnings("unchecked")
            List<String> steps = (List<String>) body.get("steps");
            boolean checked = Boolean.TRUE.equals(body.get("checked"));
            if (ids == null || ids.isEmpty()) return JsonResult.failure("account_set_ids 不能为空");
            if (steps == null || steps.isEmpty()) return JsonResult.failure("steps 不能为空");
            List<Long> accountSetIds = ids.stream().map(this::toLong).toList();
            return JsonResult.successful(taskWorkbenchService.batchMarkStepChecked(accountSetIds, period, steps, checked));
        } catch (Exception e) {
            log.error("批量切换已核查标记失败", e);
            return JsonResult.failure("批量操作失败：" + e.getMessage());
        }
    }

    /**
     * 批量手动完成步骤
     * POST /api/task-workbench/mark-done/batch
     * body: { account_set_ids: [...], period: "2026-03", steps: ["verify","data","bookkeep.bank",...] }
     */
    @PostMapping("/mark-done/batch")
    public JsonResult batchMarkDoneSteps(@RequestBody Map<String, Object> body) {
        try {
            @SuppressWarnings("unchecked")
            List<Object> ids = (List<Object>) body.get("account_set_ids");
            String period = (String) body.get("period");
            @SuppressWarnings("unchecked")
            List<String> steps = (List<String>) body.get("steps");
            if (ids == null || ids.isEmpty()) return JsonResult.failure("account_set_ids 不能为空");
            if (steps == null || steps.isEmpty()) return JsonResult.failure("steps 不能为空");
            List<Long> accountSetIds = ids.stream().map(this::toLong).toList();
            return JsonResult.successful(taskWorkbenchService.batchMarkDoneSteps(accountSetIds, period, steps));
        } catch (Exception e) {
            log.error("批量手动完成失败", e);
            return JsonResult.failure("批量手动完成失败：" + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────
    // 个税申报情况导出（异步 + 进度查询 + 下载）
    // ─────────────────────────────────────────────────────────

    /**
     * 提交个税导出任务（异步）。
     * POST /api/task-workbench/export/iit
     * body 字段（与列表 getTasks 入参基本一致，外加 exportRange/iitTypes/onlySuccess）
     */
    @PostMapping("/export/iit")
    public JsonResult submitIitExport(@RequestBody Map<String, Object> body,
                                      @SessionUser UserVo userVo) {
        try {
            IitExportRequest req = new IitExportRequest();
            req.setPeriod((String) body.get("period"));
            req.setExportRange((String) body.getOrDefault("exportRange", "filter"));
            req.setOnlySuccess(Boolean.TRUE.equals(body.get("onlySuccess")));

            @SuppressWarnings("unchecked")
            List<String> iitTypes = (List<String>) body.get("iitTypes");
            req.setIitTypes(iitTypes);

            @SuppressWarnings("unchecked")
            List<Object> selected = (List<Object>) body.get("selectedAccountSetIds");
            if (selected != null) {
                req.setSelectedAccountSetIds(selected.stream().map(this::toLong).toList());
            }

            // 透传 getTasks 的筛选参数
            req.setStatus((String) body.get("status"));
            req.setStepField((String) body.get("stepField"));
            req.setVat((String) body.get("vat"));
            req.setAccountPeriod((String) body.get("accountPeriod"));
            req.setProvince((String) body.get("province"));
            req.setKeyword((String) body.get("keyword"));
            req.setDeclareFilter((String) body.get("declareFilter"));
            req.setIitFilter((String) body.get("iitFilter"));

            String filterUserIdsStr = body.get("filterUserIds") != null ? body.get("filterUserIds").toString() : null;
            if (filterUserIdsStr != null && !filterUserIdsStr.isBlank()) {
                req.setFilterUserIds(java.util.Arrays.stream(filterUserIdsStr.split(","))
                        .map(String::trim).filter(s -> !s.isEmpty())
                        .map(Long::parseLong).toList());
            } else {
                req.setFilterUserIds(java.util.Collections.singletonList(userVo.getId()));
            }

            if (req.getPeriod() == null || req.getPeriod().isBlank()) {
                return JsonResult.failure("period 不能为空");
            }

            String displayName = userVo.getRealName();
            if (displayName == null || displayName.isBlank()) displayName = userVo.getNickname();
            if (displayName == null || displayName.isBlank()) displayName = userVo.getMobile();
            String taskId = iitExportService.submitExport(req, userVo.getId(), displayName);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("taskId", taskId);
            return JsonResult.successful(data);
        } catch (IllegalStateException e) {
            return JsonResult.failure(e.getMessage());
        } catch (Exception e) {
            log.error("提交个税导出任务失败", e);
            return JsonResult.failure("提交导出任务失败：" + e.getMessage());
        }
    }

    /** 查询导出任务进度 */
    @GetMapping("/export/iit/{taskId}/status")
    public JsonResult getIitExportStatus(@PathVariable String taskId) {
        IitExportTaskStatus s = iitExportService.getStatus(taskId);
        if (s == null) {
            return JsonResult.failure("任务不存在或已过期");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("taskId", s.getTaskId());
        data.put("status", s.getStatus());
        data.put("stage", s.getStage());
        data.put("progress", s.getProgress());
        data.put("total", s.getTotal());
        data.put("rowsWritten", s.getRowsWritten());
        data.put("screenshotsEmbedded", s.getScreenshotsEmbedded());
        data.put("fileName", s.getFileName());
        data.put("fileSize", s.getFileSize());
        data.put("errorMsg", s.getErrorMsg());
        return JsonResult.successful(data);
    }

    /** 下载导出结果 zip */
    @GetMapping("/export/iit/{taskId}/download")
    public void downloadIitExport(@PathVariable String taskId, HttpServletResponse response) throws Exception {
        File f = iitExportService.getFile(taskId);
        if (f == null) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":404,\"msg\":\"导出文件不存在或已过期\"}");
            return;
        }
        IitExportTaskStatus s = iitExportService.getStatus(taskId);
        String fileName = s != null && s.getFileName() != null ? s.getFileName() : f.getName();
        String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");

        response.setContentType("application/zip");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"" + encoded + "\"; filename*=UTF-8''" + encoded);
        response.setContentLengthLong(f.length());

        try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(f));
             OutputStream out = response.getOutputStream()) {
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
        }
    }

    private Long toLong(Object val) {
        if (val == null) return null;
        if (val instanceof Number) return ((Number) val).longValue();
        try { return Long.parseLong(val.toString()); } catch (Exception e) { return null; }
    }
}
