package cn.gson.financial.crm.controller;

import cn.dev33.satoken.annotation.SaIgnore;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.model.crm.req.AddEmployeeReq;
import cn.gson.financial.kernel.model.crm.req.AddTaskListReq;
import cn.gson.financial.kernel.model.crm.req.GetCommonReq;
import cn.gson.financial.kernel.model.crm.req.GetTaxDeclareItemsReq;
import cn.gson.financial.kernel.model.crm.req.UpdateAccountSetsUserReq;
import cn.gson.financial.kernel.model.entity.AccountSets;
import cn.gson.financial.kernel.model.entity.AppConfig;
import cn.gson.financial.kernel.model.entity.Employee;
import cn.gson.financial.kernel.model.entity.GetPrivance;
import cn.gson.financial.kernel.service.AccountSetsService;
import cn.gson.financial.kernel.service.AccountSetsTemplateService;
import cn.gson.financial.kernel.service.AppConfigService;
import cn.gson.financial.crm.service.OutSystem2CRMService;
import cn.gson.financial.kernel.tool.RedisUtil;
import cn.hutool.json.JSONUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * CRM系统API
 */
@Slf4j
@RestController
@RequiredArgsConstructor
    @RequestMapping("/OutSystem2CRM")
public class OutSystem2CRMController {

    private final OutSystem2CRMService outSystem2CRMService;
    private final AppConfigService appConfigService;
    private final AccountSetsService accountSetsService;
    private final AccountSetsTemplateService templateService;
    private final RedisUtil redisUtil;
    /** 工单 121 · BENCH-BUILD：行业基准聚合 + 只读拉取 */
    private final cn.gson.financial.benchmark.service.BenchmarkBuildService benchmarkBuildService;
    private final cn.gson.financial.kernel.model.repo.IndustryBenchmarkRepository industryBenchmarkRepository;
    /** 工单 123 · FN-WXC-01：企业微信群聊采集入库 */
    private final cn.gson.financial.crm.service.WxGroupChatCollectService wxGroupChatCollectService;
    /**
     * 验证数据状态
     * @param provinceName
     * @param mobile
     * @return
     */
    @SaIgnore
    @GetMapping("/checkTaxSystemStatus")
    public JsonResult checkTaxSystemStatus(String provinceName,String mobile) {
        List<GetPrivance> list = outSystem2CRMService.getProvinceList(provinceName,"18963408530");
        return JsonResult.successful(list);
    }

    /**
     * 税局登录
     * @param map
     * @return
     */
    @SaIgnore
    @PostMapping("/loginTaxSystem")
    public JsonResult loginTaxSystem(@RequestBody HashMap<String,String> map ){
        return JsonResult.successful(outSystem2CRMService.loginTaxSystem(map.get("taxSystemKey"),map.get("province"),"18963408530","Aa123456"));
    }
    /**
     * 登录注销
     * @param map
     * @return
     */
    @SaIgnore
    @PostMapping("/logoutTaxSystem")
    public JsonResult logoutTaxSystem(@RequestBody HashMap<String,String> map ){
        return JsonResult.successful(outSystem2CRMService.logoutTaxSystem(map.get("taxSystemKey")));
    }

    /**
     * 验证码验证
     * @param map
     * @return
     */
    @SaIgnore
    @PostMapping("/checkTaxSystemCode")
    public JsonResult checkTaxSystemCode(@RequestBody HashMap<String,String> map ){
        return JsonResult.successful(outSystem2CRMService.checkTaxSystemCode(map.get("code"),map.get("taxSystemKey")));
    }

    /**
     * 对外创建账套接口（upsert 语义）
     * 若入参的税号在系统中已有账套，则改为按更新分支执行（部分覆盖非空字段），不会再因
     * "单位名称已存在/税号已存在" 报错；找不到税号对应账套时才走真正的创建流程。
     */
    @SaIgnore
    @PostMapping("/createAccountSet")
    public JsonResult createAccountSet(@RequestBody AccountSets entity) {
        try {
            // 若该税号已有账套，转为更新（避免重复创建/单位名重复校验失败）
            if (entity.getCreditCode() != null && !entity.getCreditCode().isBlank()) {
                Object updateResult = outSystem2CRMService.updateAccountSet(entity);
                if (updateResult instanceof java.util.Map<?, ?> m && Boolean.TRUE.equals(m.get("success"))) {
                    return JsonResult.successful(updateResult);
                }
                // updateAccountSet 返回 success=false 表示"未找到对应税号的账套"，继续走创建流程
            }
            AppConfig config = appConfigService.load();
            entity.setCurrentAccountDate(entity.getEnableDate());
            entity.setCreatorId(entity.getCreatorId());
            if (entity.getEffectiveDate() == null) {
                entity.setEffectiveDate(LocalDate.now().plusYears(1000));
            }
            accountSetsService.save(entity, false);
            return JsonResult.successful(entity);
        } catch (Exception e) {
            log.error("账套创建失败！", e);
            return JsonResult.failure(e.getMessage());
        }
    }

    /**
     * 对外更新账套接口
     * 按税号（creditCode）定位现有账套，传入字段非空则覆盖（部分更新）。
     * 字段范围与 createAccountSet 一致，但 id / creditCode / createDate / creatorId 受保护不会被修改。
     *
     * 请求示例：
     * POST /OutSystem2CRM/updateAccountSet
     * Body: { "creditCode": "91110000XXXXXX", "industryCode": "C13", "industryName": "农副食品加工业", "individualBusiness": true, "businessScope": "..." }
     */
    @SaIgnore
    @PostMapping("/updateAccountSet")
    public JsonResult updateAccountSet(@RequestBody AccountSets entity) {
        try {
            return JsonResult.successful(outSystem2CRMService.updateAccountSet(entity));
        } catch (Exception e) {
            log.error("账套更新失败！", e);
            return JsonResult.failure(e.getMessage());
        }
    }

    /**
     * 根据税号修改账套截止日期（effectiveDate / 有效日期）
     *
     * 请求示例：
     * POST /OutSystem2CRM/updateAccountSetEffectiveDate
     * Body: { "creditCode": "91110000XXXXXX", "effectiveDate": "2026-12-31" }
     */
    @SaIgnore
    @PostMapping("/updateAccountSetEffectiveDate")
    public JsonResult updateAccountSetEffectiveDate(@RequestBody Map<String, String> body) {
        try {
            String creditCode = body.get("creditCode");
            String effectiveDateStr = body.get("effectiveDate");
            LocalDate effectiveDate = (effectiveDateStr == null || effectiveDateStr.isBlank())
                    ? null : LocalDate.parse(effectiveDateStr);
            return JsonResult.successful(
                    outSystem2CRMService.updateAccountSetEffectiveDate(creditCode, effectiveDate));
        } catch (Exception e) {
            log.error("修改账套截止日期失败！", e);
            return JsonResult.failure(e.getMessage());
        }
    }
    @SaIgnore
    @GetMapping("/getAstList")
    public JsonResult getAstList(){
        return JsonResult.successful(templateService.list());
    }

    /**
     * 对外添加员工接口
     * 调用方传税号 + 员工信息（可选岗位工资），后端按税号反查 accountSetsId；
     * 同账套下若已存在相同身份证号的员工则跳过，不重复插入；
     * 传入 positionSalary 时把岗位工资写入当期 Salary / SalaryFieldValue（不进入复核流程）。
     *
     * 请求示例：
     * POST /OutSystem2CRM/addEmployee?creditCode=91110000XXXXXX
     * Body: {
     *   "employee":       { "name": "张三", "idCard": "11010119900101XXXX", ... },
     *   "positionSalary": 5000
     * }
     */
    @SaIgnore
    @PostMapping("/addEmployee")
    public JsonResult addEmployee(@RequestParam String creditCode, @RequestBody AddEmployeeReq req) {
        log.info("addEmployee 接收请求: creditCode={}, body={}", creditCode, JSONUtil.toJsonStr(req));
        try {
            return JsonResult.successful(outSystem2CRMService.addEmployee(creditCode, req));
        } catch (Exception e) {
            log.error("添加员工失败！creditCode={}, body={}", creditCode, JSONUtil.toJsonStr(req), e);
            return JsonResult.failure(e.getMessage());
        }
    }

    /**
     * 对外批量添加员工接口
     * 单次提交多名员工，每名员工独立事务处理，单条失败不影响其他条；
     * 返回结果按入参顺序列出每条的新增/跳过/失败状态。
     *
     * 请求示例：
     * POST /OutSystem2CRM/addEmployees?creditCode=91110000XXXXXX
     * Body: [
     *   { "employee": { "name": "张三", "idCard": "...", ... }, "positionSalary": 5000 },
     *   { "employee": { "name": "李四", "idCard": "...", ... }, "positionSalary": 6000 },
     *   { "employee": { "name": "王五", "idCard": "...", ... } }
     * ]
     */
    @SaIgnore
    @PostMapping("/addEmployees")
    public JsonResult addEmployees(@RequestParam String creditCode, @RequestBody List<AddEmployeeReq> reqList) {
        try {
            return JsonResult.successful(outSystem2CRMService.addEmployees(creditCode, reqList));
        } catch (Exception e) {
            log.error("批量添加员工失败！", e);
            return JsonResult.failure(e.getMessage());
        }
    }


    /**
     * 一键报税
     * 获取需要报税的条目
     * @param list
     * @return
     */
    @PostMapping("/getTaxDeclareItems")
    @SaIgnore
    public JsonResult getTaxDeclareItems(@RequestBody List<GetTaxDeclareItemsReq> list){
        return JsonResult.successful(outSystem2CRMService.getTaxDeclareItems(list));
    }

    /**
     * 一键报税
     *  添加报税任务
     * @param list
     * @return
     */
    @PostMapping("/addTaskList")
    @SaIgnore
    public JsonResult addTaskList(@RequestBody List<AddTaskListReq> list){
        return outSystem2CRMService.addTaskList(list);
    }


    /**
     * 短信接收测试
     * @return
     */
    @GetMapping(value = "/getCodeCaiWu", produces = "text/plain;charset=UTF-8")
    @SaIgnore
    public String getCode() {
        //查询一下数据库中的口令
        String verifyCode1 = outSystem2CRMService.getVerifyCode();
        // 获取所有CheckCode相关的key
        Set<String> keys = redisUtil.keys("*CheckCode*");
        System.out.println("找到的keys: " + keys);
        StringBuffer buffer = new StringBuffer();
        if (keys != null) {
            for (String key : keys) {
                String value = redisUtil.get(key);
                System.out.println("key: " + key + ", value: " + value);
                if (value != null) {
                    Map<String, String> checkCodeInfo = JSONUtil.toBean(value, Map.class);
                    buffer.append(checkCodeInfo.get("msg")).append("\n\r");
                }
            }
        }
        String result = buffer.toString();
        System.out.println("最终返回结果: " + result);
        return result;
    }

    @GetMapping("/setCode")
    @SaIgnore
    public void setCode(String msg){
        Map<String, String> provinces = new HashMap<>();
        {
            provinces.put("anhui", "安徽");
            provinces.put("beijing", "中国1236600");
            provinces.put("chongqing", "重庆");
            provinces.put("fujian", "福建");
            provinces.put("gansu", "甘肃");
            provinces.put("guangdong", "广东");
            provinces.put("guangxi", "广西");
            provinces.put("guizhou", "贵州");
            provinces.put("hainan", "海南");
            provinces.put("hebei", "河北");
            provinces.put("heilongjiang", "黑龙江");
            provinces.put("henan", "河南");
            provinces.put("hubei", "湖北");
            provinces.put("hunan", "湖南");
            provinces.put("jiangsu", "江苏");
            provinces.put("jiangxi", "江西");
            provinces.put("jilin", "吉林");
            provinces.put("liaoning", "辽宁");
            provinces.put("neimenggu", "内蒙古");
            provinces.put("ningxia", "宁夏");
            provinces.put("qinghai", "青海");
            provinces.put("shandong", "山东");
            provinces.put("shanghai", "上海");
            provinces.put("shanxi", "山西");
            provinces.put("shaanxi", "陕西");
            provinces.put("sichuan", "四川");
            provinces.put("tianjin", "天津");
            provinces.put("xinjiang", "新疆");
            provinces.put("xizang", "西藏");
            provinces.put("yunnan", "云南");
            provinces.put("zhejiang", "浙江");
            provinces.put("dalian", "大连");
            provinces.put("qingdao", "青岛");
            provinces.put("shenzhen", "深圳");
            provinces.put("xiamen", "厦门");
            provinces.put("ningbo","宁波");
        }
        // 提取省份名称
        String provinceName = "";
        String provinceKey = "";

        // 从短信中提取省份名称（在【】中）
        if (msg.contains("【") && msg.contains("】")) {
            String provinceText = msg.substring(msg.indexOf("【") + 1, msg.indexOf("】"));
            // 去掉"税务局"等后缀
            provinceName = provinceText.replaceAll("省|市|自治区","").replaceAll("税务局|税务|局|电子税务局", "");

            // 根据省份名称找到对应的拼音key
            for (Map.Entry<String, String> entry : provinces.entrySet()) {
                if (entry.getValue().equals(provinceName)) {
                    provinceKey = entry.getKey();
                    break;
                }
            }
        }
        // 提取验证码（6位数字）
        String code = "";
        // 使用正则表达式匹配验证码：验证码是：后面跟着6位数字
        java.util.regex.Pattern pattern = null;
        pattern = java.util.regex.Pattern.compile("验证码是：([0-9]{6})");
        if(provinceKey.equals("henan")){
            pattern = java.util.regex.Pattern.compile("验证码：([0-9]{6})");
        }

        java.util.regex.Matcher matcher = pattern.matcher(msg);
        if (matcher.find()) {
            code = matcher.group(1);
        }
        Map<String, String> checkCodeInfo = new HashMap<>();
        checkCodeInfo.put("code", code);
        checkCodeInfo.put("msg", msg);
        checkCodeInfo.put("province", provinceName);
        checkCodeInfo.put("provinceKey", provinceKey);

        // 使用唯一key存储，带时间戳
        String redisKey = provinceKey + "CheckCode";
        redisUtil.set(redisKey, JSONUtil.toJsonStr(checkCodeInfo), 300);
        System.out.println("KEY:"+redisKey+"\n\rvalue:"+JSONUtil.toJsonStr(checkCodeInfo));
    }



    /**
     * 短信接收测试
     * @return
     */
    @GetMapping(value = "/getCodeCaiWu2", produces = "text/plain;charset=UTF-8")
    @SaIgnore
    public String getCode2() {
        //查询一下数据库中的口令
        String verifyCode1 = outSystem2CRMService.getVerifyCode();

        // 获取所有CheckCode相关的key
        Set<String> keys = redisUtil.keys("*ka2*");
        System.out.println("找到的keys: " + keys);
        StringBuffer buffer = new StringBuffer();
        if (keys != null) {
            for (String key : keys) {
                String value = redisUtil.get(key);
                System.out.println("key: " + key + ", value: " + value);
                if (value != null) {
                    Map<String, String> checkCodeInfo = JSONUtil.toBean(value, Map.class);
                    buffer.append(checkCodeInfo.get("msg")).append("\n\r");
                }
            }
        }
        String result = buffer.toString();
        System.out.println("最终返回结果: " + result);
        return result;
    }


    @GetMapping("/setCode2")
    @SaIgnore
    public void setCode2(String msg){
        Map<String, String> provinces = new HashMap<>();
        {
            provinces.put("anhui", "安徽");
            provinces.put("beijing", "中国1236600");
            provinces.put("chongqing", "重庆");
            provinces.put("fujian", "福建");
            provinces.put("gansu", "甘肃");
            provinces.put("guangdong", "广东");
            provinces.put("guangxi", "广西");
            provinces.put("guizhou", "贵州");
            provinces.put("hainan", "海南");
            provinces.put("hebei", "河北");
            provinces.put("heilongjiang", "黑龙江");
            provinces.put("henan", "河南");
            provinces.put("hubei", "湖北");
            provinces.put("hunan", "湖南");
            provinces.put("jiangsu", "江苏");
            provinces.put("jiangxi", "江西");
            provinces.put("jilin", "吉林");
            provinces.put("liaoning", "辽宁");
            provinces.put("neimenggu", "内蒙古");
            provinces.put("ningxia", "宁夏");
            provinces.put("qinghai", "青海");
            provinces.put("shandong", "山东");
            provinces.put("shanghai", "上海");
            provinces.put("shanxi", "山西");
            provinces.put("shaanxi", "陕西");
            provinces.put("sichuan", "四川");
            provinces.put("tianjin", "天津");
            provinces.put("xinjiang", "新疆");
            provinces.put("xizang", "西藏");
            provinces.put("yunnan", "云南");
            provinces.put("zhejiang", "浙江");
            provinces.put("dalian", "大连");
            provinces.put("qingdao", "青岛");
            provinces.put("shenzhen", "深圳");
            provinces.put("xiamen", "厦门");
            provinces.put("ningbo","宁波");
        }
        // 提取省份名称
        String provinceName = "";
        String provinceKey = "";

        // 从短信中提取省份名称（在【】中）
        if (msg.contains("【") && msg.contains("】")) {
            String provinceText = msg.substring(msg.indexOf("【") + 1, msg.indexOf("】"));
            // 去掉"税务局"等后缀
            provinceName = provinceText.replaceAll("省|市|自治区","").replaceAll("税务局|税务|局|电子税务局", "");

            // 根据省份名称找到对应的拼音key
            for (Map.Entry<String, String> entry : provinces.entrySet()) {
                if (entry.getValue().equals(provinceName)) {
                    provinceKey = entry.getKey();
                    break;
                }
            }
        }
        // 提取验证码（6位数字）
        String code = "";
        // 使用正则表达式匹配验证码：验证码是：后面跟着6位数字
        java.util.regex.Pattern pattern = null;
        pattern = java.util.regex.Pattern.compile("验证码是：([0-9]{6})");
        if(provinceKey.equals("henan")){
            pattern = java.util.regex.Pattern.compile("验证码：([0-9]{6})");
        }

        java.util.regex.Matcher matcher = pattern.matcher(msg);
        if (matcher.find()) {
            code = matcher.group(1);
        }
        Map<String, String> checkCodeInfo = new HashMap<>();
        checkCodeInfo.put("code", code);
        checkCodeInfo.put("msg", msg);
        checkCodeInfo.put("province", provinceName);
        checkCodeInfo.put("provinceKey", provinceKey);

        // 使用唯一key存储，带时间戳
        String redisKey = provinceKey + "ka2";
        redisUtil.set(redisKey, JSONUtil.toJsonStr(checkCodeInfo), 300);
        System.out.println("KEY:"+redisKey+"\n\rvalue:"+JSONUtil.toJsonStr(checkCodeInfo));
    }

    /**
     * 验证码接收 — Box 通道（按手机号 slot 隔离）
     * GET /OutSystem2CRM/setCodeBox?msg=xxx&slot=手机号
     */
    @GetMapping("/setCodeBox")
    @SaIgnore
    public void setCodeBox(@RequestParam String msg, @RequestParam String slot) {
        Map<String, String> provinces = new HashMap<>();
        {
            provinces.put("anhui", "安徽");
            provinces.put("beijing", "中国1236600");
            provinces.put("chongqing", "重庆");
            provinces.put("fujian", "福建");
            provinces.put("gansu", "甘肃");
            provinces.put("guangdong", "广东");
            provinces.put("guangxi", "广西");
            provinces.put("guizhou", "贵州");
            provinces.put("hainan", "海南");
            provinces.put("hebei", "河北");
            provinces.put("heilongjiang", "黑龙江");
            provinces.put("henan", "河南");
            provinces.put("hubei", "湖北");
            provinces.put("hunan", "湖南");
            provinces.put("jiangsu", "江苏");
            provinces.put("jiangxi", "江西");
            provinces.put("jilin", "吉林");
            provinces.put("liaoning", "辽宁");
            provinces.put("neimenggu", "内蒙古");
            provinces.put("ningxia", "宁夏");
            provinces.put("qinghai", "青海");
            provinces.put("shandong", "山东");
            provinces.put("shanghai", "上海");
            provinces.put("shanxi", "山西");
            provinces.put("shaanxi", "陕西");
            provinces.put("sichuan", "四川");
            provinces.put("tianjin", "天津");
            provinces.put("xinjiang", "新疆");
            provinces.put("xizang", "西藏");
            provinces.put("yunnan", "云南");
            provinces.put("zhejiang", "浙江");
            provinces.put("dalian", "大连");
            provinces.put("qingdao", "青岛");
            provinces.put("shenzhen", "深圳");
            provinces.put("xiamen", "厦门");
            provinces.put("ningbo", "宁波");
        }
        String provinceName = "";
        String provinceKey = "";

        if (msg.contains("【") && msg.contains("】")) {
            String provinceText = msg.substring(msg.indexOf("【") + 1, msg.indexOf("】"));
            provinceName = provinceText.replaceAll("省|市|自治区", "").replaceAll("税务局|税务|局|电子税务局", "");
            for (Map.Entry<String, String> entry : provinces.entrySet()) {
                if (entry.getValue().equals(provinceName)) {
                    provinceKey = entry.getKey();
                    break;
                }
            }
        }

        String code = "";
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("验证码是：([0-9]{6})");
        if (provinceKey.equals("henan")) {
            pattern = java.util.regex.Pattern.compile("验证码：([0-9]{6})");
        }
        java.util.regex.Matcher matcher = pattern.matcher(msg);
        if (matcher.find()) {
            code = matcher.group(1);
        }

        // 从 slot 中提取手机号（11位，可能带 +86 前缀）
        // 例如: SIM1_18963408530 → 18963408530
        //      SIM1_中国电信_+8618963408530 → 18963408530
        String phone = slot;
        java.util.regex.Matcher phoneMatcher = java.util.regex.Pattern.compile("(?:\\+?86)?(1\\d{10})(?!\\d)").matcher(slot);
        if (phoneMatcher.find()) {
            phone = phoneMatcher.group(1);
        }

        Map<String, String> checkCodeInfo = new HashMap<>();
        checkCodeInfo.put("code", code);
        checkCodeInfo.put("msg", msg);
        checkCodeInfo.put("province", provinceName);
        checkCodeInfo.put("provinceKey", provinceKey);
        checkCodeInfo.put("slot", slot);
        checkCodeInfo.put("phone", phone);

        // Redis key: 省份拼音 + Box_ + 手机号，按手机号隔离
        String redisKey = provinceKey + "Box_" + phone;
        redisUtil.set(redisKey, JSONUtil.toJsonStr(checkCodeInfo), 300);
        System.out.println("KEY:" + redisKey + "\n\rvalue:" + JSONUtil.toJsonStr(checkCodeInfo));
    }

    /**
     * 获取迁移数据
     * @param id
     * @return
     */
    @PostMapping("/crmGetExperienceMigrationData")
    @SaIgnore
    public JsonResult getExperienceMigrationData(@RequestParam Long id){
        return JsonResult.successful(outSystem2CRMService.getExperienceMigrationData(id));
    }

    /**
     * 数据迁移
     * @param map
     * @return
     */
    @PostMapping("/crmAddAccountSetMigrationData")
    @SaIgnore
    public JsonResult addAccountSetMigrationData(@RequestBody Map<String, Object> map){
        return JsonResult.successful(outSystem2CRMService.addAccountSetMigrationData(map));
    }

    /**
     * 修改默认账套
     * @param map
     * @return
     */
    @PostMapping("/changeAccountSet4CRM")
    @SaIgnore
    public JsonResult changeAccountSet4CRM(@RequestBody  Map<String,Object> map){
        return JsonResult.successful(outSystem2CRMService.changeAccountSet4CRM(Long.valueOf(map.get("accountSetsId").toString()),map.get("mobile").toString()));
    }

    /**
     * 获取账套汇总数据
     * @return
     */
    @PostMapping("/crmGetAccountSummaryData")
    @SaIgnore
    public JsonResult crmGetAccountSummaryData(){
        return JsonResult.successful(outSystem2CRMService.crmGetAccountSummaryData());
    }

    /**
     * 获取报税信息
     * @return
     */
    @PostMapping("/crmGetTaxReportResultData")
    @SaIgnore
    public JsonResult crmGetTaxReportResultData(@RequestBody GetCommonReq req){
        return JsonResult.successful(outSystem2CRMService.crmGetTaxReportResultData(req));
    }

    /**
     * RPT-FN-01 · CRM 调 financial 取财务报表 + 科目余额（86 §D4 契约）。
     * <p>
     * 入参：{@code accountSetsId + period(YYYY-MM)}；
     * 出参 4 块：{@code zcfz / lr / xjll / subjectBalances}。
     * <p>
     * ⚠️ R6 "高新收入占比"非标准报表项，本接口不返；CRM 端 RPT-AI-01 另出来源。
     */
    @PostMapping("/crmGetReport")
    @SaIgnore
    public JsonResult crmGetReport(@RequestBody cn.gson.financial.kernel.model.crm.req.GetReportReq req){
        return JsonResult.successful(outSystem2CRMService.crmGetReport(req));
    }

    /**
     * 工单 121 · BENCH-BUILD · 只读拉取行业基准（source=SELF）。
     * <p>
     * finmaster BENCH-READ 经 {@code FinancialUtils} 拉本接口，与其本地 EXTERNAL 种子合并
     * （优先 SELF、回退 EXTERNAL）。<b>仅返聚合分位数，无任何单户数据</b>。
     */
    @PostMapping("/crmGetBenchmark")
    @SaIgnore
    public JsonResult crmGetBenchmark(){
        return JsonResult.successful(industryBenchmarkRepository.findBySource("SELF"));
    }

    /**
     * 工单 121 · BENCH-BUILD · 手动触发匿名聚合跑批（预计算，非实时）。
     * <p>
     * ⚠️ 重操作 + 当前 {@code @SaIgnore}（同其他 OutSystem2CRM 内网端点）；
     * <b>生产应加内网/IP 白名单，或改由定时任务触发，不对外暴露手动 HTTP</b>。
     * <p>
     * 前置：服务协议已覆盖"客户数据用于匿名行业对标"（121 授权门槛）。
     */
    @PostMapping("/crmBuildBenchmark")
    @SaIgnore
    public JsonResult crmBuildBenchmark(){
        return JsonResult.successful(benchmarkBuildService.buildSelfBenchmark());
    }

    /**
     * 修改账套归属人
     * @return
     */
    @PostMapping("/updateAccountSetsUser")
    @SaIgnore
    public JsonResult updateAccountSetsUser(@RequestBody UpdateAccountSetsUserReq req){
        return JsonResult.successful(outSystem2CRMService.updateAccountSetsUser(req));
    }

    /**
     * 停用账套
     * @return
     */
    @SaIgnore
    @PostMapping("/deactivated")
    public  JsonResult deactivated(@RequestParam String creditCode){
        return JsonResult.successful(outSystem2CRMService.deactivated(creditCode));
    }

    /**
     * 获取公共银行库
     * @return
     */
    @SaIgnore
    @GetMapping("/getBankLibList")
    public JsonResult uaGetBankLibList(){
        return JsonResult.successful(outSystem2CRMService.getBankLibList(null, null));
    }

    /**
     * 工单 123 · FN-WXC-01 · 企业微信群聊采集入库（工单 89 WXKW-04「入库」落地）。
     *
     * <p>qykj-finmaster 命中"发票业务"关键词后，把命中时刻前后 10 分钟的群聊内容打包推到本接口；
     * 一次命中落一行，{@code createTime=now / deleted=0}。供 CRM/财税侧后续查证、回溯。</p>
     *
     * <p>Body：{@code { "chatName": "群名", "content": "聚合后的聊天文本" }}。
     * 两者同时为空时直接 failure；否则落表返回带 id 的实体。</p>
     */
    @SaIgnore
    @PostMapping("/collectWxGroupChat")
    public JsonResult collectWxGroupChat(@RequestBody Map<String, String> body) {
        String chatName = body == null ? null : body.get("chatName");
        String content  = body == null ? null : body.get("content");
        boolean chatNameBlank = chatName == null || chatName.isBlank();
        boolean contentBlank  = content  == null || content.isBlank();
        if (chatNameBlank && contentBlank) {
            return JsonResult.failure("群聊名称与消息内容不能同时为空");
        }
        try {
            return JsonResult.successful(wxGroupChatCollectService.saveCollect(chatName, content));
        } catch (Exception e) {
            log.error("企业微信群聊采集入库失败！chatName={}, contentLen={}",
                    chatName, content == null ? 0 : content.length(), e);
            return JsonResult.failure(e.getMessage());
        }
    }

    @SaIgnore
    @PostMapping("/getAccountSetsId")
    public JsonResult getAccountSetsId(@RequestParam String creditCode, @RequestParam Long creatorId){
        AccountSets accountSets = outSystem2CRMService.getAccountSetsId(creditCode, creatorId);
        return JsonResult.successful(accountSets);
    }
}
