package cn.gson.financial.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaIgnore;
import cn.dev33.satoken.session.SaSession;
import cn.dev33.satoken.stp.StpUtil;
import cn.gson.financial.kernel.annotation.DueCheck;
import cn.gson.financial.kernel.annotation.SessionAccountSet;
import cn.gson.financial.kernel.annotation.SessionUser;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.common.EasyExcelUtils;
import cn.gson.financial.kernel.model.dto.AccountSetsDto;
import cn.gson.financial.kernel.model.dto.AccountSetsImportDto;
import cn.gson.financial.kernel.model.dto.MaintenanceStatsDto;
import cn.gson.financial.kernel.model.dto.TaxDto;
import cn.gson.financial.kernel.model.dto.UpdateIndividualTaxDto;
import cn.gson.financial.kernel.model.entity.AccountSets;
import cn.gson.financial.kernel.model.entity.AppConfig;
import cn.gson.financial.kernel.model.entity.User;
import cn.gson.financial.kernel.model.enums.AccountStandard;
import cn.gson.financial.kernel.model.enums.VatType;
import cn.gson.financial.kernel.model.repo.UserRepository;
import cn.gson.financial.kernel.model.vo.LoginStatusVerificationVo;
import cn.gson.financial.kernel.model.vo.UserVo;
import cn.gson.financial.kernel.service.*;
import cn.gson.financial.loginStatusVerification.LoginStatusVerification;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.StrUtil;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.*;

import static cn.gson.financial.kernel.common.Constants.S_AUTHORITIES_KEY;
import static cn.gson.financial.kernel.common.Constants.S_USER_KEY;

/**
 *
 * <p><b>Copyright © 2010-2019开发公司All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.controller</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2019年07月30日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 *
 */
@RestController
@RequestMapping("/account-sets")
@RequiredArgsConstructor
@Slf4j
public class AccountSetsController {

    private final VoucherService voucherService;

    private final AppConfigService appConfigService;

    private final UserService userService;

    private final AccountSetsService accountSetsService;

    private final CheckoutService checkoutService;

    private final BalanceCalcService balanceCalcService;

    private final UserRepository userRepository;

    private final AccountSetPeriodStatusSyncService accountSetPeriodStatusSyncService;

    private final RestTemplate restTemplate = new RestTemplate();

    /**
     * 首页账套列表 + 状态
     * @param user 当前用户
     * @param period 可选账期（YYYY-MM-DD），仅用于状态表查询；为空则使用各账套当前账期
     */
    @GetMapping
    public JsonResult list(@SessionUser UserVo user, @RequestParam(required = false) LocalDate period) {
        return JsonResult.successful(this.accountSetsService.myAccountSets(user.getId(), period));
    }

    /**
     * 同步首页状态表：从任务表按同组最大 id 的 result 回填 account_set_period_status。可定时或手动触发。
     */
    @PostMapping("/sync-status")
    @SaCheckLogin
    public JsonResult syncStatus() {
        accountSetPeriodStatusSyncService.sync();
        return JsonResult.successful();
    }

    @GetMapping("/maintenance-stats")
    public JsonResult getMaintenanceStats(@SessionUser UserVo user) {
        MaintenanceStatsDto stats = accountSetsService.getMaintenanceStats(user.getId());
        return JsonResult.successful(stats);
    }

    @SaIgnore
    @GetMapping("/deactivated")
    public  JsonResult deactivated(Long  accountSetsId,Boolean deactivated){
        return JsonResult.successful(accountSetsService.deactivated(accountSetsId,deactivated));
    }

    @GetMapping("/newMyAccountSets")
    public JsonResult MyAccountSetsList(@SessionUser UserVo user ,String province,String accountPeriod,String companyName) {
        List<TaxDto> accountSetsList = accountSetsService.accountSetsOfOneKey(user.getId(),accountPeriod,province,companyName);
        return JsonResult.successful(accountSetsList);
    }

    @PostMapping
    public JsonResult save(@RequestBody AccountSets entity, @SessionUser UserVo user) {
        try {
            AppConfig config = appConfigService.load();

            entity.setCurrentAccountDate(entity.getEnableDate());
            entity.setCreatorId(user.getId());
            entity.setEffectiveDate(LocalDate.now().plusYears(1000));
            accountSetsService.save(entity, true);
            UserVo userVo = userService.getUserVo(user.getId());
            //更新 Session 中的用户信息
            StpUtil.getSession().set(S_USER_KEY, userVo)
                    .set(S_AUTHORITIES_KEY, userVo.getRoleInfo());
            return JsonResult.successful(entity);
        } catch (Exception e) {
            log.error("账套创建失败！", e);
            return JsonResult.failure(e.getMessage());
        }
    }

    /**
     * 省份中文名 → 拼音映射
     */
    private static final Map<String, String> PROVINCE_NAME_TO_PINYIN = new LinkedHashMap<>();
    static {
        PROVINCE_NAME_TO_PINYIN.put("安徽", "anhui");
        PROVINCE_NAME_TO_PINYIN.put("北京", "beijing");
        PROVINCE_NAME_TO_PINYIN.put("重庆", "chongqing");
        PROVINCE_NAME_TO_PINYIN.put("福建", "fujian");
        PROVINCE_NAME_TO_PINYIN.put("甘肃", "gansu");
        PROVINCE_NAME_TO_PINYIN.put("广东", "guangdong");
        PROVINCE_NAME_TO_PINYIN.put("广西", "guangxi");
        PROVINCE_NAME_TO_PINYIN.put("贵州", "guizhou");
        PROVINCE_NAME_TO_PINYIN.put("海南", "hainan");
        PROVINCE_NAME_TO_PINYIN.put("河北", "hebei");
        PROVINCE_NAME_TO_PINYIN.put("黑龙江", "heilongjiang");
        PROVINCE_NAME_TO_PINYIN.put("河南", "henan");
        PROVINCE_NAME_TO_PINYIN.put("湖北", "hubei");
        PROVINCE_NAME_TO_PINYIN.put("湖南", "hunan");
        PROVINCE_NAME_TO_PINYIN.put("江苏", "jiangsu");
        PROVINCE_NAME_TO_PINYIN.put("江西", "jiangxi");
        PROVINCE_NAME_TO_PINYIN.put("吉林", "jilin");
        PROVINCE_NAME_TO_PINYIN.put("辽宁", "liaoning");
        PROVINCE_NAME_TO_PINYIN.put("内蒙古", "neimenggu");
        PROVINCE_NAME_TO_PINYIN.put("宁夏", "ningxia");
        PROVINCE_NAME_TO_PINYIN.put("青海", "qinghai");
        PROVINCE_NAME_TO_PINYIN.put("山东", "shandong");
        PROVINCE_NAME_TO_PINYIN.put("上海", "shanghai");
        PROVINCE_NAME_TO_PINYIN.put("山西", "shanxi");
        PROVINCE_NAME_TO_PINYIN.put("陕西", "shaanxi");
        PROVINCE_NAME_TO_PINYIN.put("四川", "sichuan");
        PROVINCE_NAME_TO_PINYIN.put("天津", "tianjin");
        PROVINCE_NAME_TO_PINYIN.put("新疆", "xinjiang");
        PROVINCE_NAME_TO_PINYIN.put("西藏", "xizang");
        PROVINCE_NAME_TO_PINYIN.put("云南", "yunnan");
        PROVINCE_NAME_TO_PINYIN.put("浙江", "zhejiang");
        PROVINCE_NAME_TO_PINYIN.put("大连", "dalian");
        PROVINCE_NAME_TO_PINYIN.put("青岛", "qingdao");
        PROVINCE_NAME_TO_PINYIN.put("深圳", "shenzhen");
        PROVINCE_NAME_TO_PINYIN.put("厦门", "xiamen");
        PROVINCE_NAME_TO_PINYIN.put("宁波", "ningbo");
    }

    /**
     * 下载账套导入模板（含示例数据）
     */
    @GetMapping("/import/template")
    public void downloadImportTemplate(HttpServletResponse response) throws Exception {
        List<AccountSetsImportDto> sampleData = new ArrayList<>();

        AccountSetsImportDto sample1 = new AccountSetsImportDto();
        sample1.setCompanyName("示例公司（上传前请删除此行）");
        sample1.setCreditCode("91370000XXXXXXXXXX");
        sample1.setEnableDate("2025-01");
        sample1.setVatType("小规模纳税人");
        sample1.setElectricityTaxCode("abc123");
        sample1.setIndividualTaxCode("abc123");
        sample1.setProvince("山东");
        sample1.setElectricityTaxPhone("13800138000");
        sample1.setZeroDeclarations("否");
        sampleData.add(sample1);

        AccountSetsImportDto sample2 = new AccountSetsImportDto();
        sample2.setCompanyName("示例贸易有限公司（上传前请删除此行）");
        sample2.setCreditCode("91310000YYYYYYYYYY");
        sample2.setEnableDate("2025-03");
        sample2.setVatType("一般纳税人");
        sample2.setElectricityTaxCode("pwd456");
        sample2.setIndividualTaxCode("pwd456");
        sample2.setProvince("上海");
        sample2.setElectricityTaxPhone("13900139000");
        sample2.setZeroDeclarations("否");
        sampleData.add(sample2);

        EasyExcelUtils.exportFun(response, "账套导入模板", AccountSetsImportDto.class, sampleData);
    }

    /**
     * 批量导入账套（全量校验，有任何错误则全部不创建）
     */
    @PostMapping("/import")
    public JsonResult importAccountSets(@RequestParam("file") MultipartFile file,
                                        @SessionUser UserVo user) {
        try {
            List<AccountSetsImportDto> rows = EasyExcelUtils.importFun(file.getInputStream(), AccountSetsImportDto.class);
            if (CollUtil.isEmpty(rows)) {
                return JsonResult.failure("Excel文件中没有数据");
            }

            // ========== 第一阶段：全量校验，不入库 ==========
            List<String> errors = new ArrayList<>();
            List<AccountSets> entities = new ArrayList<>();

            for (int i = 0; i < rows.size(); i++) {
                int rowNum = i + 2; // Excel行号（第1行是表头）
                AccountSetsImportDto row = rows.get(i);

                // 必填校验
                if (StrUtil.isBlank(row.getCompanyName())) {
                    errors.add("第" + rowNum + "行：单位名称不能为空");
                    continue;
                }
                if (StrUtil.isBlank(row.getCreditCode())) {
                    errors.add("第" + rowNum + "行：统一社会信用代码不能为空");
                    continue;
                }

                try {
                    AccountSets entity = new AccountSets();
                    entity.setCompanyName(row.getCompanyName().trim());
                    entity.setCreditCode(row.getCreditCode().trim());

                    // 解析启用年月
                    if (StrUtil.isNotBlank(row.getEnableDate())) {
                        String dateStr = row.getEnableDate().trim();
                        LocalDate enableDate;
                        if (dateStr.contains("-")) {
                            if (dateStr.length() <= 7) {
                                enableDate = LocalDate.parse(dateStr + "-01");
                            } else {
                                enableDate = LocalDate.parse(dateStr);
                            }
                        } else if (dateStr.contains("/")) {
                            String[] parts = dateStr.split("/");
                            enableDate = LocalDate.of(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), 1);
                        } else if (dateStr.length() == 6) {
                            // YYYYMM 格式，补日为01
                            enableDate = LocalDate.parse(dateStr + "01", DateTimeFormatter.ofPattern("yyyyMMdd"));
                        } else {
                            enableDate = LocalDate.parse(dateStr, DateTimeFormatter.ofPattern("yyyyMMdd"));
                        }
                        entity.setEnableDate(enableDate);
                    } else {
                        entity.setEnableDate(LocalDate.now().withDayOfMonth(1));
                    }

                    // 启用日期校验
                    if (entity.getEnableDate() != null && !entity.getEnableDate().isAfter(LocalDate.of(2009, 12, 31))) {
                        errors.add("第" + rowNum + "行（" + row.getCompanyName() + "）：账套启用年月必须大于2009年");
                        continue;
                    }

                    entity.setCurrentAccountDate(entity.getEnableDate());
                    entity.setCreatorId(user.getId());
                    entity.setEffectiveDate(LocalDate.now().plusYears(1000));

                    // 增值税种类
                    if (StrUtil.isNotBlank(row.getVatType())) {
                        String vt = row.getVatType().trim();
                        if ("一般纳税人".equals(vt)) {
                            entity.setVatType(VatType.COMMONLY);
                        } else {
                            entity.setVatType(VatType.SMALL_SCALE);
                        }
                    }

                    // 电税密码
                    if (StrUtil.isNotBlank(row.getElectricityTaxCode())) {
                        entity.setElectricityTaxCode(row.getElectricityTaxCode().trim());
                    }
                    // 个税密码
                    if (StrUtil.isNotBlank(row.getIndividualTaxCode())) {
                        entity.setIndividualTaxCode(row.getIndividualTaxCode().trim());
                    }
                    // 税局手机号
                    if (StrUtil.isNotBlank(row.getElectricityTaxPhone())) {
                        entity.setElectricityTaxPhone(row.getElectricityTaxPhone().trim());
                    }

                    // 税务所属地（中文名 → 拼音）
                    if (StrUtil.isNotBlank(row.getProvince())) {
                        String provinceName = row.getProvince().trim();
                        String pinyin = PROVINCE_NAME_TO_PINYIN.get(provinceName);
                        if (pinyin != null) {
                            entity.setProvince(pinyin);
                        } else {
                            entity.setProvince(provinceName);
                        }
                    }

                    // 是否零申报
                    if (StrUtil.isNotBlank(row.getZeroDeclarations())) {
                        String zd = row.getZeroDeclarations().trim();
                        entity.setZeroDeclarations("是".equals(zd) || "true".equalsIgnoreCase(zd) || "1".equals(zd));
                    } else {
                        entity.setZeroDeclarations(false);
                    }

                    entity.setVoucherReviewed(false);

                    // Excel内部查重：单位名称
                    boolean duplicateName = entities.stream()
                            .anyMatch(e -> e.getCompanyName().equals(entity.getCompanyName()));
                    if (duplicateName) {
                        errors.add("第" + rowNum + "行（" + row.getCompanyName() + "）：Excel中单位名称重复");
                        continue;
                    }
                    // Excel内部查重：信用代码
                    boolean duplicateCode = entities.stream()
                            .anyMatch(e -> e.getCreditCode().equals(entity.getCreditCode()));
                    if (duplicateCode) {
                        errors.add("第" + rowNum + "行（" + row.getCompanyName() + "）：Excel中统一社会信用代码重复");
                        continue;
                    }

                    entities.add(entity);
                } catch (Exception e) {
                    errors.add("第" + rowNum + "行（" + row.getCompanyName() + "）：" + e.getMessage());
                }
            }

            // 第一阶段有格式错误直接返回，不入库
            if (!errors.isEmpty()) {
                Map<String, Object> result = new HashMap<>();
                result.put("total", rows.size());
                result.put("success", 0);
                result.put("errors", errors);
                return JsonResult.failure("校验不通过，未创建任何账套").setData(result);
            }

            // ========== 第1.5阶段：DB预校验（只读，不开事务），提前发现配额、重复、模板问题 ==========
            List<String> dbErrors = accountSetsService.preValidateForImport(entities, user.getId());
            if (!dbErrors.isEmpty()) {
                Map<String, Object> result = new HashMap<>();
                result.put("total", rows.size());
                result.put("success", 0);
                result.put("errors", dbErrors);
                return JsonResult.failure("数据校验不通过，未创建任何账套").setData(result);
            }

            // ========== 第二阶段：全部校验通过，事务性批量入库 ==========
            try {
                accountSetsService.batchSave(entities);
            } catch (Exception e) {
                log.error("账套批量入库失败，已全部回滚", e);
                Map<String, Object> failResult = new HashMap<>();
                failResult.put("total", rows.size());
                failResult.put("success", 0);
                failResult.put("errors", List.of("批量入库失败，已全部回滚：" + e.getMessage()));
                return JsonResult.failure("导入失败，已全部回滚").setData(failResult);
            }

            // 更新 Session
            UserVo userVo = userService.getUserVo(user.getId());
            StpUtil.getSession().set(S_USER_KEY, userVo)
                    .set(S_AUTHORITIES_KEY, userVo.getRoleInfo());

            Map<String, Object> result = new HashMap<>();
            result.put("total", rows.size());
            result.put("success", entities.size());
            result.put("errors", errors);
            return JsonResult.successful(result);
        } catch (Exception e) {
            log.error("账套导入失败！", e);
            return JsonResult.failure("导入失败：" + e.getMessage());
        }
    }

    /**
     * 更新账套随手记备注（同时更新首页备注）
     */
    @PostMapping("/updateNotes")
    public JsonResult updateNotes(@RequestBody Map<String, Object> body) {
        Object idObj = body.get("accountSetsId");
        Object notesObj = body.get("notes");
        Assert.notNull(idObj, "账套ID不能为空");
        Long accountSetsId = Long.valueOf(idObj.toString());
        String notes = notesObj != null ? notesObj.toString() : "";
        accountSetsService.updateNotes(accountSetsId, notes);
        return JsonResult.successful();
    }

    /**
     * 首页取票：创建/重置取票任务（只写状态表，不直接执行取票）
     */
    @PostMapping("/invoice-task")
    public JsonResult createInvoiceTask(@RequestBody Map<String, Object> body) {
        Object idObj = body.get("accountSetsId");
        Object periodObj = body.get("period");
        Assert.notNull(idObj, "账套ID不能为空");
        Assert.notNull(periodObj, "账期不能为空");
        Long accountSetsId = Long.valueOf(idObj.toString());
        LocalDate period = LocalDate.parse(periodObj.toString());
        accountSetsService.createInvoiceTask(accountSetsId, period);
        return JsonResult.successful();
    }

    /**
     * 首页取票：查看取票任务详情
     */
    @GetMapping("/invoice-task/detail")
    public JsonResult getInvoiceTaskDetail(@RequestParam Long accountSetsId, @RequestParam LocalDate period) {
        return JsonResult.successful(accountSetsService.getInvoiceStatusDetail(accountSetsId, period));
    }

    /**
     * 首页取票：单个取票条目加入/取消/重新加入任务
     */
    @PostMapping("/invoice-task/item/join")
    public JsonResult joinInvoiceItem(@RequestBody Map<String, Object> body) {
        Object idObj = body.get("itemId");
        Object joinObj = body.get("join");
        Assert.notNull(idObj, "取票条目ID不能为空");
        Long itemId = Long.valueOf(idObj.toString());
        boolean join = joinObj == null || Boolean.parseBoolean(joinObj.toString());
        accountSetsService.setInvoiceItemJoinStatus(itemId, join);
        return JsonResult.successful();
    }

    /**
     * 首页报税：全部加入任务（将该账套本期所有税种状态从 -1 置为 0）
     */
    @PostMapping("/tax-task/join-all")
    public JsonResult joinAllTaxTasks(@RequestBody Map<String, Object> body) {
        Object idObj = body.get("accountSetsId");
        Object periodObj = body.get("period");
        Assert.notNull(idObj, "账套ID不能为空");
        Assert.notNull(periodObj, "账期不能为空");
        Long accountSetsId = Long.valueOf(idObj.toString());
        LocalDate period = LocalDate.parse(periodObj.toString());
        accountSetsService.joinAllTaxItems(accountSetsId, period);
        return JsonResult.successful();
    }

    /**
     * 首页报税：单个税种加入/取消加入任务
     */
    @PostMapping("/tax-task/item/join")
    public JsonResult joinTaxItem(@RequestBody Map<String, Object> body) {
        Object idObj = body.get("itemId");
        Object joinObj = body.get("join");
        Assert.notNull(idObj, "报税条目ID不能为空");
        Long itemId = Long.valueOf(idObj.toString());
        boolean join = joinObj == null || Boolean.parseBoolean(joinObj.toString());
        accountSetsService.setTaxItemJoinStatus(itemId, join);
        return JsonResult.successful();
    }

    /**
     * 首页报税：查看报税任务详情
     */
    @GetMapping("/tax-task/detail")
    public JsonResult getTaxTaskDetail(@RequestParam Long accountSetsId, @RequestParam LocalDate period) {
        return JsonResult.successful(accountSetsService.getTaxStatusDetail(accountSetsId, period));
    }
    @SaIgnore
    @PostMapping("/saveAccountSets")
    public JsonResult saveAccountSets(@RequestBody java.util.Map<String, Object> requestData) {

        try {
            // 尝试从请求数据中获取userToken
            String userToken = (String) requestData.get("userToken");
            Long userId = null;

            if (userToken != null) {
                try {
                    // 使用token获取用户ID
                    Object loginId = StpUtil.getLoginIdByToken(userToken);
                    if (loginId != null) {
                        String[] parts = loginId.toString().split(":");
                        userId = Long.parseLong(parts[0]);
                        log.info("从token中获取到用户ID: {}", userId);
                    }
                } catch (Exception e) {
                    log.warn("解析token失败: {}", e.getMessage());
                }
            }

            // 构建AccountSets对象
            AccountSets entity = new AccountSets();
            entity.setCompanyName((String) requestData.get("companyName"));
            entity.setCreditCode((String) requestData.get("creditCode"));
            entity.setElectricityTaxCode((String) requestData.get("electricityTaxCode"));
            entity.setElectricityTaxPhone((String) requestData.get("electricityTaxPhone"));
            entity.setProvince((String) requestData.get("province"));

            // 从请求数据中获取mobile字段（必须由Session传递而来）
            String mobile = (String) requestData.get("mobile");
            if (mobile == null || mobile.isEmpty()) {
                throw new RuntimeException("用户手机号不能为空，请确保用户已正确登录");
            }

            // 处理address字段（可能是数组或字符串）
            Object address = requestData.get("address");
            if (address != null) {
                entity.setAddress(address.toString());
            }

            // 处理industry字段（Integer类型）
            Object industry = requestData.get("industry");
            if (industry != null) {
                if (industry instanceof Integer) {
                    entity.setIndustry((Integer) industry);
                } else if (industry instanceof String && !((String) industry).isEmpty()) {
                    try {
                        entity.setIndustry(Integer.parseInt((String) industry));
                    } catch (NumberFormatException e) {
                        log.warn("无法解析industry字段: {}", industry);
                    }
                }
            }

            // 处理日期
            String enableDateStr = (String) requestData.get("enableDate");
            if (enableDateStr != null) {
                entity.setEnableDate(java.time.LocalDate.parse(enableDateStr));
            }

            // 处理枚举值
            String accountStandard = (String) requestData.get("accountStandard");
            if (accountStandard != null) {
                entity.setAccountStandard(AccountStandard.valueOf(accountStandard));
            }

            String vatType = (String) requestData.get("vatType");
            if (vatType != null) {
                entity.setVatType(VatType.valueOf(vatType));
            }

            // 处理布尔值
            Object voucherReviewed = requestData.get("voucherReviewed");
            if (voucherReviewed != null) {
                entity.setVoucherReviewed(voucherReviewed.equals(1) || Boolean.TRUE.equals(voucherReviewed));
            }

            Object zeroDeclarations = requestData.get("zeroDeclarations");
            if (zeroDeclarations != null) {
                entity.setZeroDeclarations(zeroDeclarations.equals(1) || Boolean.TRUE.equals(zeroDeclarations));
            }

            // 处理astId字段（Long类型）
            Object astId = requestData.get("astId");
            if (astId != null) {
                if (astId instanceof Long) {
                    entity.setAstId((Long) astId);
                } else if (astId instanceof String && !((String) astId).isEmpty()) {
                    try {
                        entity.setAstId(Long.parseLong((String) astId));
                    } catch (NumberFormatException e) {
                        log.warn("无法解析astId字段: {}", astId);
                    }
                } else if (astId instanceof Number) {
                    entity.setAstId(((Number) astId).longValue());
                }
            }

            AppConfig config = appConfigService.load();

            // 如果有userId则使用，否则通过mobile查找用户
            User user;

                user = userRepository.getReferenceById(userId);
                log.info("使用token中的用户ID: {}", userId);


            entity.setCurrentAccountDate(entity.getEnableDate());
            entity.setCreatorId(user.getId());
            entity.setEffectiveDate(java.time.LocalDate.now().plusYears(1000));
            accountSetsService.save(entity, true);

            // 如果有token，更新对应用户的Session
            if (userToken != null && userId != null) {
                try {
                    UserVo userVo = userService.getUserVo(user.getId());
                    // 获取指定token的Session并更新
                    cn.dev33.satoken.session.SaSession session = StpUtil.getSessionByLoginId(userId + ":" + user.getMobile());
                    session.set(S_USER_KEY, userVo)
                           .set(S_AUTHORITIES_KEY, userVo.getRoleInfo());
                    log.info("已更新用户Session: {}", userId);
                } catch (Exception e) {
                    log.warn("更新Session失败: {}", e.getMessage());
                }
            }

            return JsonResult.successful(entity);
        } catch (Exception e) {
            log.error("账套创建失败！", e);
            return JsonResult.failure(e.getMessage());
        }
    }

    @DueCheck
    @PutMapping
    public JsonResult update(@RequestBody AccountSets entity, @SessionAccountSet AccountSets accountSets, @SessionUser UserVo userVo) {
        accountSetsService.update(entity);
        if (entity.getId().equals(accountSets.getId())) {
            //更新 Session 中的用户信息
            StpUtil.getSession().set(S_USER_KEY, userService.getUserVo(userVo.getId()));
        }
        return JsonResult.successful();
    }

    @DueCheck
    @GetMapping("update/currentAccountDate")
    public JsonResult updateCurrentAccountDate(@SessionAccountSet AccountSets accountSets, LocalDate currentAccountDate, @SessionUser UserVo userVo) {
        accountSets.setCurrentAccountDate(currentAccountDate);
        accountSetsService.update(accountSets);
        UserVo user = userService.getUserVo(userVo.getId());
        //更新 Session 中的用户信息
        StpUtil.getSession().set(S_USER_KEY, user)
                .set(S_AUTHORITIES_KEY, user.getRoleInfo());
        return JsonResult.successful();
    }


    @GetMapping("addUser")
    @DueCheck
    @SaCheckPermission("setting-permission-canedit")
    public JsonResult addUser(String mobile, Long roleId, @SessionAccountSet AccountSets accountSets,
                              Long astId, @SessionUser UserVo userVo) {
        this.accountSetsService.addUser(userVo.getAccountSetsId(astId, accountSets.getId()), mobile, roleId);
        return JsonResult.successful();
    }

    @GetMapping("updateUserRole")
    @DueCheck
    @SaCheckPermission("setting-permission-canedit")
    public JsonResult updateUserRole(@RequestParam Long id,
                                     @RequestParam Long roleId,
                                     @SessionAccountSet AccountSets accountSets,
                                     Long astId, @SessionUser UserVo userVo) {
        this.accountSetsService.updateUserRole(userVo.getAccountSetsId(astId, accountSets.getId()), id, roleId);
        return JsonResult.successful();
    }

    @GetMapping("addNewUser")
    @DueCheck
    @SaCheckPermission("setting-permission-canedit")
    public JsonResult addNewUser(@RequestParam String mobile,
                                 @RequestParam Long roleId,
                                 @RequestParam String code,
                                 HttpSession session,
                                 @SessionAccountSet AccountSets accountSets,
                                 Long astId, @SessionUser UserVo userVo) {
        Long accountSetsId = userVo.getAccountSetsId(astId, accountSets.getId());

        String scode = (String) session.getAttribute(mobile);
        if (scode == null || !scode.equals(code)) {
            return JsonResult.failure("验证码错误!");
        }
        session.removeAttribute(mobile);
        this.accountSetsService.addNewUser(accountSetsId, mobile, roleId);
        return JsonResult.successful();
    }

    @GetMapping("removeUser/{uid}")
    @DueCheck
    @SaCheckPermission("setting-permission-candelete")
    public JsonResult removeUser(@PathVariable Long uid, @SessionAccountSet AccountSets accountSets, Long astId, @SessionUser UserVo userVo) {
        User user = this.accountSetsService.removeUser(userVo.getAccountSetsId(astId, accountSets.getId()), uid);
        //如果已经登录则强制账号退出
        StpUtil.logout(user.getId() + ":" + user.getMobile());
        return JsonResult.successful();
    }

    @PostMapping("identification")
    public JsonResult identification(@RequestParam String code, HttpSession session, @SessionUser UserVo userVo) {
        if (code.equals(session.getAttribute(userVo.getMobile()))) {
            session.setAttribute(userVo.getMobile() + "_checked", true);
            session.removeAttribute(userVo.getMobile());
            return JsonResult.successful();
        }
        return JsonResult.failure("验证码校验失败！");
    }

    /**
     * 更新科目编码设置
     *
     * @param newEncoding
     * @return
     */
    @PostMapping("updateEncode")
    @DueCheck
    @SaCheckPermission("setting-accountsubject-cancode")
    public JsonResult updateEncode(@RequestBody List<Integer> newEncoding, @SessionAccountSet AccountSets accountSets, @SessionUser UserVo userVo) {
        this.accountSetsService.updateEncode(accountSets.getId(), userVo.getAccountSets().getEncoding(), newEncoding);
        StpUtil.getSession().set(S_USER_KEY, userService.getUserVo(userVo.getId()));
        return JsonResult.successful();
    }

    @PostMapping("handOver")
    @DueCheck
    public JsonResult handOver(@RequestParam String code, @RequestParam String mobile, @SessionAccountSet AccountSets accountSets, HttpSession session, @SessionUser UserVo userVo) {
        if (session.getAttribute(userVo.getMobile() + "_checked") == null) {
            return JsonResult.failure("亲，移交用户信息未确认！");
        }

        if (code.equals(session.getAttribute(mobile))) {
            this.accountSetsService.handOver(accountSets.getId(), userVo.getId(), mobile);
            session.removeAttribute(mobile);
            SaSession sas = StpUtil.getSession();
            UserVo user = this.userService.getUserVo(userVo.getId());
            //更新 Session 中的用户信息
            sas.set(S_USER_KEY, user)
                    .set(S_AUTHORITIES_KEY, user.getRoleInfo());
            return JsonResult.successful();
        }

        return JsonResult.failure("验证码校验失败！");
    }

    /**
     * 批量改变用户权限
     * 保留管理员权限，删除其他非管理员权限，添加新选择的用户权限
     *
     * @param body 请求体，包含 accountSetsIds 和 userMobiles
     * @param userVo 当前用户
     * @return
     */
    @PostMapping("/batchChangeUserPermission")
    @DueCheck
    public JsonResult batchChangeUserPermission(@RequestBody Map<String, Object> body, @SessionUser UserVo userVo) {
        Object idsObj = body.get("accountSetsIds");
        Object mobilesObj = body.get("userMobiles");
        if (!(idsObj instanceof List) || !(mobilesObj instanceof List)) {
            return JsonResult.failure("参数不正确");
        }

        @SuppressWarnings("unchecked")
        List<?> idList = (List<?>) idsObj;
        List<Long> accountSetsIds = idList.stream()
                .map(String::valueOf)
                .map(idStr -> {
                    try {
                        return Long.valueOf(idStr);
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException("账套ID格式不正确：" + idStr);
                    }
                })
                .toList();

        @SuppressWarnings("unchecked")
        List<?> mobileList = (List<?>) mobilesObj;
        List<String> userMobiles = mobileList.stream()
                .map(String::valueOf)
                .filter(mobile -> mobile != null && !mobile.trim().isEmpty())
                .toList();

        try {
            this.accountSetsService.batchChangeUserPermission(accountSetsIds, userMobiles);
            return JsonResult.successful();
        } catch (Exception e) {
            log.error("批量改变用户权限失败", e);
            return JsonResult.failure(e.getMessage());
        }
    }

    /**
     * 批量修改账套创建人/管理员
     *

     * @return
     */
    @PostMapping("/batchUpdateCreator")
    @DueCheck
    public JsonResult batchUpdateCreator(@RequestBody Map<String, Object> body, @SessionUser UserVo userVo) {
        Object idsObj = body.get("accountSetsIds");
        Object mobileObj = body.get("targetMobile");
        if (!(idsObj instanceof List) || mobileObj == null) {
            return JsonResult.failure("参数不正确");
        }

        // 兼容前端传递数字或字符串类型的账套ID
        List<?> idList = (List<?>) idsObj;
        List<Long> accountSetsIds = idList.stream()
                .map(String::valueOf)
                .map(idStr -> {
                    try {
                        return Long.valueOf(idStr);
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException("账套ID格式不正确：" + idStr);
                    }
                })
                .toList();

        String targetMobile = String.valueOf(mobileObj);

        try {
            this.accountSetsService.batchHandOver(accountSetsIds, userVo.getId(), targetMobile);
            // 变更后刷新当前用户的权限信息
            SaSession sas = StpUtil.getSession();
            UserVo user = this.userService.getUserVo(userVo.getId());
            sas.set(S_USER_KEY, user)
                    .set(S_AUTHORITIES_KEY, user.getRoleInfo());
            return JsonResult.successful();
        } catch (Exception e) {
            log.error("批量修改账套创建人失败", e);
            return JsonResult.failure(e.getMessage());
        }
    }

    /**
     * 检查账套是否已有凭证
     *
     * @param accountSetsId 账套 ID
     * @return
     */
    @GetMapping("checkUse")
    public JsonResult checkUse(@RequestParam Long accountSetsId) {
        return JsonResult.successful(this.voucherService.countByAccountSetsId(accountSetsId) > 0);
    }

    /**
     * 更新个税状态
     *
     * @param updateIndividualTaxDto 更新个税状态DTO，包含账套ID、个税状态（是否能报个税）和账期
     * @return
     */
    @PostMapping("/updateIndividualTax")
    public JsonResult updateIndividualTax(@RequestBody UpdateIndividualTaxDto updateIndividualTaxDto) {

        return JsonResult.successful(this.accountSetsService.updateIndividualTax(updateIndividualTaxDto));
    }

    /**
     * 更新个税密码
     *
     * @param updateIndividualTaxCodeDto
     * @return
     */
    @PostMapping("/updateIndividualTaxCode")
    public JsonResult updateIndividualTaxCode(@RequestBody cn.gson.financial.kernel.model.individualTax.dto.UpdateIndividualTaxCodeDto updateIndividualTaxCodeDto) {
        return JsonResult.successful(this.accountSetsService.updateIndividualTaxCode(updateIndividualTaxCodeDto));
    }



    /**
     * 带验证码删除
     *
     * @param id
     * @return
     */
    @DeleteMapping("/{id}")
    @DueCheck
    @SaCheckPermission("setting-accountset-candelete")
    public JsonResult delete(@PathVariable Long id, @SessionUser UserVo userVo, HttpSession session) {
        //不是管理员不允许删除，删除请联系管理员
        session.removeAttribute(userVo.getMobile());
        List<User> userList = accountSetsService.remove(id);
        for (User user : userList) {
            accountSetsService.setUserCurrentAs(user);
        }
        //更新 Session 中的用户信息
        UserVo user = this.userService.getUserVo(userVo.getId());
        StpUtil.getSession().set(S_USER_KEY, user)
                .set(S_AUTHORITIES_KEY, user.getRoleInfo());
        return JsonResult.successful();
    }

    @GetMapping("reset/{accountSetsId:\\d+}")
    @DueCheck
    @SaCheckPermission("setting-accountset-reset")
    public JsonResult reset(@PathVariable Long accountSetsId,
                            String resetType,
                            String astName,
                            LocalDate enableDate,
                            @SessionUser UserVo userVo, HttpSession session,
                            @SessionAccountSet AccountSets accountSets) {

        AccountSets ast = userService.loadByAstId(accountSetsId, userVo.getId());
        Assert.notNull(ast, "您不允许操作此账套！");
        Assert.isTrue(ast.getCreatorId().equals(userVo.getId()), "您不是账套创建人，不允许操作~");

        if (!"账套".equals(resetType) && !ast.getCompanyName().equals(astName)) {
            return JsonResult.failure("账套名称错误~");
        }

        session.removeAttribute(userVo.getMobile());
        if ("余额表".equals(resetType)) {
            voucherService.cashFlowTag(accountSetsId);
            YearMonth voucherMonth = YearMonth.from(accountSets.getEnableDate());
            balanceCalcService.calcSynchronization(accountSets.getId(), voucherMonth);
        } else {
            accountSetsService.reset(accountSetsId, enableDate, resetType, accountSets.getAstId());
            UserVo user = this.userService.getUserVo(userVo.getId());
            //更新 Session 中的用户信息
            SaSession saSession = StpUtil.getSession().set(S_USER_KEY, user);
            if (user.getRoleInfo() != null) {
                saSession.set(S_AUTHORITIES_KEY, user.getRoleInfo());
            }
        }
        return JsonResult.successful();
    }

    @GetMapping("reset/balance")
    @SaCheckLogin
    @SaCheckPermission("setting-accountset-reset")
    public JsonResult resetBalance() {
        List<AccountSets> accountSetsList = accountSetsService.listAll();
        accountSetsList.forEach(as -> {
            checkoutService.reCreateCheckout(as);
            balanceCalcService.calc(as.getId(), YearMonth.from(as.getEnableDate()));
        });
        return JsonResult.successful();
    }

        /**
     * 工作流代理接口
     */
    @Autowired
    private  LoginStatusVerification loginStatusVerification;
    @Autowired
    private LoginStatusVerificationVo loginStatusVerificationVo;
    @PostMapping("/workflow/run")
    public JsonResult workflowProxy(@RequestBody Object requestBody, @SessionUser UserVo userVo) {
        try {
            // 获取当前登录用户信息
            String mobile = userVo.getMobile();
            String userToken = StpUtil.getTokenValue(); // 获取当前用户的token
            log.info("工作流代理请求，用户手机号: {}, Token: {}", mobile, userToken);

            // 验证用户手机号不能为空
            if (mobile == null || mobile.isEmpty()) {
                return JsonResult.failure("用户手机号不能为空");
            }

            // 如果requestBody是Map类型，添加用户信息
            if (requestBody instanceof java.util.Map) {
                @SuppressWarnings("unchecked")
                java.util.Map<String, Object> requestMap = (java.util.Map<String, Object>) requestBody;

                // 获取inputs对象
                Object inputs = requestMap.get("inputs");
                if (inputs instanceof java.util.Map) {
                    @SuppressWarnings("unchecked")
                    java.util.Map<String, Object> inputsMap = (java.util.Map<String, Object>) inputs;

                    // 强制设置用户信息到inputs中（覆盖任何可能的前端传值）
                    inputsMap.put("mobile", mobile);
                    inputsMap.put("userToken", userToken); // 添加用户token
                    inputsMap.put("userId", userVo.getId()); // 添加用户ID
                    log.info("已将用户信息添加到inputs: mobile={}, userId={}", mobile, userVo.getId());
                }
            }

            // 设置请求头
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Bearer app-IVpUg7p63rdcRfeQkTMC9CmP");

            // 创建请求实体
            HttpEntity<Object> requestEntity = new HttpEntity<>(requestBody, headers);

            // 发送请求到工作流服务
            ResponseEntity<Object> response = restTemplate.exchange(
                "http://localhost/v1/workflows/run",
                HttpMethod.POST,
                requestEntity,
                Object.class
            );

            return JsonResult.successful(response.getBody());
        } catch (Exception e) {
            log.error("工作流代理请求失败", e);
            return JsonResult.failure("工作流代理请求失败: " + e.getMessage());
        }
    }
}
