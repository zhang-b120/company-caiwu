package cn.gson.financial.job;

import cn.gson.financial.kernel.model.entity.AccountSets;
import cn.gson.financial.kernel.model.entity.TaskExecuteList;
import cn.gson.financial.task.service.TaskExecuteListService;
import cn.gson.financial.rpa.Provice2URL;
import cn.gson.financial.rpa.TaskUtil;
import cn.gson.financial.rpa.TaxBrowserManager;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.BoundingBox;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 任务列表定时器
 * 每晚12点读取数据库中的任务列表
 *
 * <ul style="margin:15px;">
 * <li>Description : financial</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2024年12月</li>
 * <li>@author     : 开发者</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskSchedulerJob {
    TaskUtil taskUtil = TaskUtil.getInstance();
    private final TaskExecuteListService taskExecuteListService;
    private final TaxBrowserManager taxBrowserManager;
    /**
     * 每晚12点执行任务列表读取
     * 只读取和处理状态为0（待执行）的任务
     *
     * Cron表达式格式：秒 分 时 日 月 周 [年]
     * ┌─────────────────── 秒 (0-59)
     * │ ┌───────────────── 分 (0-59)
     * │ │ ┌─────────────── 时 (0-23)
     * │ │ │ ┌───────────── 日 (1-31)
     * │ │ │ │ ┌─────────── 月 (1-12 或 JAN-DEC)
     * │ │ │ │ │ ┌───────── 周 (0-7 或 SUN-SAT，0和7都表示周日)
     * │ │ │ │ │ │
     * │ │ │ │ │ │
     * * * * * * *
     *
     * 常用时间格式示例：
     * ┌──────────────────────────────────────────────────────────────────────
     * │ 表达式                    │ 说明
     * ├──────────────────────────────────────────────────────────────────────
     * │ 0 0 0 * * *              │ 每天午夜12:00执行
     * │ 0 0 1 * * *              │ 每天凌晨1:00执行
     * │ 0 30 2 * * *             │ 每天凌晨2:30执行
     * │ 0 0 8 * * MON-FRI        │ 每个工作日上午8:00执行
     * │ 0 0 9,17 * * *           │ 每天上午9:00和下午5:00执行
     * │ 0 *\/15 * * * *           │ 每15分钟执行一次
     * │ 0 0/30 * * * *           │ 每30分钟执行一次
     * │ 0 0 *\/2 * * *            │ 每2小时执行一次
     * │ 0 0 0 1 * *              │ 每月1号午夜执行
     * │ 0 0 0 * * SUN            │ 每周日午夜执行
     * │ 0 0 0 1 1 *              │ 每年1月1日午夜执行
     * │ 0 0 0 1-7 * SUN          │ 每月第一个周日午夜执行
     * │ 0 0 2 L * *              │ 每月最后一天凌晨2:00执行
     * │ 0 0 2 * * 6L             │ 每月最后一个周五凌晨2:00执行
     * └──────────────────────────────────────────────────────────────────────
     *
     * 特殊字符说明：
     * * : 匹配任意值
     * ? : 不指定值（只能用在日期和星期字段）
     * - : 范围分隔符，如 1-5 表示1到5
     * , : 枚举分隔符，如 1,3,5 表示1、3、5
     * / : 步长分隔符，如 0/15 表示从0开始每15单位
     * L : 最后，如 L 表示月的最后一天，6L 表示最后一个周五
     * W : 工作日，如 15W 表示离15号最近的工作日
     * # : 第几个，如 6#3 表示第3个周五
     *
     * 当前设置：0 0 0 * * * = 每天午夜12:00执行
     * 任务逻辑：按省份正序排序，获取第一个状态为0（待执行）的任务进行处理
     */
//    @Async
//    @Scheduled(cron = "0 0 0 * * *")
//    public void readTaskListAtMidnight() {
//        executeTaskReading("定时任务");
//    }

    /**
     * 测试方法 - 每1分钟执行一次（仅用于测试，生产环境应注释掉）
     * 只读取和处理状态为0（待执行）的任务
     *
     * 测试用Cron表达式示例：
     * ┌──────────────────────────────────────────────────────────────────────
     * │ 表达式                    │ 说明
     * ├──────────────────────────────────────────────────────────────────────
     * │ 0 *\/1 * * * *            │ 每1分钟执行一次（快速测试）
     * │ 0 *\/5 * * * *            │ 每5分钟执行一次（常规测试）
     * │ 0 *\/10 * * * *           │ 每10分钟执行一次（较慢测试）
     * │ 0 0/30 * * * *           │ 每30分钟执行一次（慢速测试）
     * │ 30 * * * * *             │ 每分钟的第30秒执行
     * │ 0,30 * * * * *           │ 每分钟的第0秒和第30秒执行
     * └──────────────────────────────────────────────────────────────────────
     *
     * 可以通过修改cron表达式来调整测试频率
     * 当前测试设置：每1分钟执行一次，用于快速验证功能
     */ // 每5分钟执行一次，测试时可以取消注释
//    @Async
//    @Scheduled(cron = "0 */1 * * * *")
//    public void testTaskReading() {
//        System.out.println("定时任务开始执行");
//    }

    /**
     * 执行任务读取的核心逻辑
     * @param taskType 任务类型描述
     */
    private void executeTaskReading(Integer taskType ,String province,String sponsor) {
        LocalDateTime now = LocalDateTime.now();
        String timeStr = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

        log.info("开始执行{} - 按省份排序获取第一个待执行任务，执行时间：{}", taskType, timeStr);

        try {
            // 按省份正序排序获取第一个状态为0（待执行）的任务
            TaskExecuteList firstTask = taskExecuteListService.findFirstTaskByStatusOrderByProvince(province,taskType,sponsor);

            if (firstTask == null) {
                log.info("当前没有待执行状态的任务需要处理");
                return;
            }

            log.info("获取到第一个待执行任务 - ID: {}, 省份: {}, 状态: {}",
                    firstTask.getId(), firstTask.getProvince(), firstTask.getTaskStatus());

            // 处理单个任务
            processSingleTask(firstTask);

        } catch (Exception e) {
            log.error("{}执行失败", taskType, e);
        }

        log.info("{}执行完成，结束时间：{}", taskType,
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
    }

    /**
     * 处理任务列表
     * @param taskList 任务列表
     * @deprecated 已改为处理单个任务，此方法保留用于兼容性
     */
    @Deprecated
    private void processTaskList(List<TaskExecuteList> taskList) {
        log.info("开始处理任务列表...");

        for (TaskExecuteList task : taskList) {
            try {
                log.info("处理任务 - ID: {}, 任务类型: {}, 任务状态: {}, 任务条数: {}, 发起人: {}",
                        task.getId(), task.getTaskType(), task.getTaskStatus(),
                        task.getTaskNumber(), task.getSponsor());

                // 这里可以根据任务状态进行不同的处理
                // 例如：处理待执行的任务、更新任务状态等
                processIndividualTask(task);

            } catch (Exception e) {
                log.error("处理任务失败 - ID: {}", task.getId(), e);
            }
        }

        log.info("任务列表处理完成");
    }

    /**
     * 处理单个任务（新版本）
     * @param task 任务对象
     */
    private void processSingleTask(TaskExecuteList task) {
        log.info("开始处理待执行任务 - ID: {}, 省份: {}, 类型: {}",
                task.getId(), task.getProvince(), task.getTaskType());

        try {
            // 根据任务类型执行不同的处理逻辑
            Integer taskType = task.getTaskType();

            switch (taskType) {
                case 1:
                    log.info("执行获取人员信息任务 - ID: {}, 省份: {}", task.getId(), task.getProvince());
                    handleGetPersonnelInfoTask(task);
                    break;
                case 2:
                    log.info("执行报个税任务 - ID: {}, 省份: {}", task.getId(), task.getProvince());
                    handlePersonalTaxTask(task);
                    break;
                case 3:
                    log.info("执行获取发票任务 - ID: {}, 省份: {}", task.getId(), task.getProvince());
                    handleGetInvoiceTask(task);
                    break;
                default:
                    log.warn("未知任务类型: {} - 任务ID: {}, 省份: {}", taskType, task.getId(), task.getProvince());
                    handleUnknownTypeTask(task);
                    break;
            }

            log.info("任务处理完成 - ID: {}, 省份: {}, 类型: {}", task.getId(), task.getProvince(), taskType);

        } catch (Exception e) {
            log.error("处理任务失败 - ID: {}, 省份: {}, 类型: {}",
                     task.getId(), task.getProvince(), task.getTaskType(), e);
        }
    }

    /**
     * 处理获取人员信息任务（类型1）
     * @param task 任务对象
     */
    private void handleGetPersonnelInfoTask(TaskExecuteList task) {
        log.info("开始执行获取人员信息任务处理逻辑 - 任务ID: {}, 省份: {}", task.getId(), task.getProvince());

        try {
            // 获取人员信息的具体业务逻辑
            log.debug("人员信息任务数据: {}", task.getTaskData());

            log.info("正在获取人员信息...");

            // 具体实现步骤：
            // 1. 解析任务数据，获取需要查询的人员信息参数
            // 2. 调用人员信息查询接口或服务
            // 3. 处理返回的人员数据
            // 4. 将人员信息存储到数据库
            // 5. 更新任务状态为已完成

            // 示例：解析任务数据
            String taskData = task.getTaskData();
            if (taskData != null && !taskData.isEmpty()) {
                log.info("解析人员信息查询参数...");
                // TODO: 解析JSON格式的任务数据
                // TODO: 提取查询条件（如公司ID、部门、日期范围等）
            }

            // 示例：调用人员信息服务
            log.info("调用人员信息查询服务...");
            // TODO: 调用具体的人员信息服务
            // TODO: 处理查询结果

            // 示例：更新任务状态
            log.info("更新任务状态...");
            // TODO: 调用TaskExecuteListService更新任务状态为已完成

            log.info("获取人员信息任务处理完成 - 任务ID: {}", task.getId());

        } catch (Exception e) {
            log.error("获取人员信息任务处理失败 - 任务ID: {}", task.getId(), e);
            // TODO: 更新任务状态为失败
            throw e;
        }
    }

    /**
     * 处理报个税任务（类型2）
     * @param task 任务对象
     */
    private void handlePersonalTaxTask(TaskExecuteList task) {
        log.info("开始执行报个税任务处理逻辑 - 任务ID: {}, 省份: {}", task.getId(), task.getProvince());

        try {
            // 报个税的具体业务逻辑
            log.debug("报个税任务数据: {}", task.getTaskData());

            log.info("正在处理个人所得税申报...");

            // 具体实现步骤：
            // 1. 解析任务数据，获取报税相关参数
            // 2. 查询需要报税的人员信息和收入数据
            // 3. 计算个人所得税
            // 4. 生成报税文件或调用税务接口
            // 5. 记录报税结果
            // 6. 更新任务状态

            // 示例：解析报税参数
            String taskData = task.getTaskData();
            if (taskData != null && !taskData.isEmpty()) {
                log.info("解析报税参数...");
                // TODO: 解析报税相关参数（如报税期间、人员范围等）
            }

            // 示例：查询人员收入数据
            log.info("查询人员收入数据...");
            // TODO: 根据省份和任务参数查询相关人员的收入信息

            // 示例：计算个人所得税
            log.info("计算个人所得税...");
            // TODO: 根据税法规则计算每个人的应纳税额

            // 示例：提交税务申报
            log.info("提交税务申报...");
            // TODO: 调用税务系统接口或生成申报文件

            // 示例：更新任务状态
            log.info("更新任务状态...");
            // TODO: 更新任务状态和结果

            log.info("报个税任务处理完成 - 任务ID: {}", task.getId());

        } catch (Exception e) {
            log.error("报个税任务处理失败 - 任务ID: {}", task.getId(), e);
            // TODO: 更新任务状态为失败
            throw e;
        }
    }

    /**
     * 处理获取发票任务（类型3）
     *
     * TaskData JSON数据结构示例：
     * {
     *   "id": "721409575521268799",                    // 账套ID
     *   "companyName": "重庆优壹家科技有限公司",        // 公司名称
     *   "electricityTaxCode": "Aa123456",             // 电税代码
     *   "province": "chongqing",                      // 省份
     *   "zeroDeclarations": false,                    // 零申报
     *   "electricityTaxPhone": "19554509313",         // 电税手机号
     *   "enableDate": "2025-05-01",                   // 启用日期
     *   "creditCode": "91500105MA5UR2F28A",           // 统一社会信用代码(税号)
     *   "accountStandard": "SMALL_BUSINESS",          // 会计准则
     *   "cashierModule": true,                        // 出纳模块
     *   "fixedAssetModule": true,                     // 固定资产模块
     *   "vatType": "SMALL_SCALE",                     // 增值税类型 (SMALL_SCALE/COMMONLY)
     *   "voucherReviewed": false,                     // 凭证审核
     *   "createDate": "2025-06-06 17:04:05",         // 创建日期
     *   "effectiveDate": "2025-07-06",               // 生效日期
     *   "creatorId": "721059376894490497",           // 创建者ID
     *   "astId": "432597845032308737",               // 资产ID
     *   "currentAccountDate": "2025-05-01",          // 当前账期
     *   "encoding": [4,3,2,2],                       // 编码
     *   "subjectCategory": ["ASSET","LIABILITY","RIGHTS","COST","INCOME"], // 科目分类
     *   "journal": true,                             // 日记账
     *   "assets": true,                              // 资产
     *   "imported": false,                           // 是否导入
     *   "invoiceSaveType": 1,                        // 发票保存类型
     *   "startDate": "2025-04-01",                   // 开始日期
     *   "endDate": "2025-06-30",                     // 结束日期
     *   "voucherDate": null,                         // 凭证日期
     *   "completionStatus": null,                    // 完成状态
     *   "invoiceType": ["取得发票","开具发票"]        // 发票类型数组
     * }
     *
     * @param task 任务对象
     */
    private void handleGetInvoiceTask(TaskExecuteList task) {
//        Map<String,String> result =  taxBrowserManager.downInvoice(params,accountSets);


        log.info("开始执行获取发票任务处理逻辑 - 任务ID: {}, 省份: {}", task.getId(), task.getProvince());
            // 1、通过手机号判断taskUtil中是存在对应的浏览器对象
//                Map<String,Object> taskMap = checkUserExist(params.get("electricityTaxPhone"));
//                BrowserContext newContext = createBrowserContext();
//                Page page = newContext.newPage();
//                HashMap<String,Object> newMap = new HashMap<>();
//                if(doLogin(newContext,page,params)){
//                        // 账号密码登录完成，需要进行验证码确认
//                        newMap.put("code","201");//201表示需要验证码
//                        newMap.put("phoneNumber",params.get("electricityTaxPhone"));
//                        newMap.put("browser",newContext);
//                        taskUtil.addTask(newMap);
//                }
        try {
            // 获取发票的具体业务逻辑
            log.debug("获取发票任务数据: {}", task.getTaskData());

            log.info("正在获取发票信息...");

            // 解析JSON格式的任务数据
            String taskData = task.getTaskData();
            if (taskData != null && !taskData.isEmpty()) {
                log.info("解析发票查询参数...");

                // 使用ObjectMapper解析JSON
                ObjectMapper objectMapper = new ObjectMapper();
                Map<String, Object> taskDataMap = objectMapper.readValue(taskData, new TypeReference<Map<String, Object>>() {});

                // 打印解析的关键字段用于调试
                log.debug("解析到的关键字段:");
                log.debug("- 账套ID: {}", getStringValue(taskDataMap, "id"));
                log.debug("- 公司名称: {}", getStringValue(taskDataMap, "companyName"));
                log.debug("- 税号: {}", getStringValue(taskDataMap, "creditCode"));
                log.debug("- 省份: {}", getStringValue(taskDataMap, "province"));
                log.debug("- 电税手机号: {}", getStringValue(taskDataMap, "electricityTaxPhone"));
                log.debug("- VAT类型: {}", getStringValue(taskDataMap, "vatType"));
                log.debug("- 开始日期: {}", getStringValue(taskDataMap, "startDate"));
                log.debug("- 结束日期: {}", getStringValue(taskDataMap, "endDate"));
                log.debug("- 发票类型: {}", taskDataMap.get("invoiceType"));

                // 构建参数Map
                Map<String, String> params = new HashMap<>();
                params.put("electricityTaxPhone", getStringValue(taskDataMap, "electricityTaxPhone")); // 登录手机号
                params.put("creditCode", getStringValue(taskDataMap, "creditCode")); // 税号
                params.put("password", getStringValue(taskDataMap, "password")); // 电税密码(如果有)
                params.put("province", getStringValue(taskDataMap, "province")); // 省份
                params.put("accountSetsId", getStringValue(taskDataMap, "id")); // 账套ID
                params.put("startDate", getStringValue(taskDataMap, "startDate")); // 开始日期
                params.put("endDate", getStringValue(taskDataMap, "endDate")); // 结束日期

                // 构建AccountSets对象
                AccountSets accountSets = new AccountSets();
                accountSets.setId(Long.parseLong(getStringValue(taskDataMap, "id")));
                accountSets.setCreditCode(getStringValue(taskDataMap, "creditCode"));
                // 设置VAT类型
                String vatType = getStringValue(taskDataMap, "vatType");
                if ("SMALL_SCALE".equals(vatType)) {
                    // accountSets.setVatType(VatType.SMALL_SCALE); // 根据实际枚举设置
                } else if ("COMMONLY".equals(vatType)) {
                    // accountSets.setVatType(VatType.COMMONLY); // 根据实际枚举设置
                }

                // 处理发票类型 - 可能是数组
                Object invoiceTypeObj = taskDataMap.get("invoiceType");
                List<String> invoiceTypes;
                if (invoiceTypeObj instanceof List) {
                    invoiceTypes = (List<String>) invoiceTypeObj;
                } else {
                    invoiceTypes = List.of(invoiceTypeObj.toString());
                }

                String result = "";

                // 遍历处理每种发票类型
                for (String invoiceType : invoiceTypes) {
                    log.info("处理发票类型: {}", invoiceType);
                    params.put("invoiceType", invoiceType);

                    log.info("发票类型 {} 处理结果: {}", invoiceType, result);
                }

                // TODO: 更新任务状态为已完成
                log.info("更新任务状态...");
            }

            log.info("获取发票任务处理完成 - 任务ID: {}", task.getId());

        } catch (Exception e) {
            log.error("获取发票任务处理失败 - 任务ID: {}", task.getId(), e);
            // TODO: 更新任务状态为失败
        }
    }

    /**
     * 安全获取Map中的字符串值
     * @param map 数据Map
     * @param key 键名
     * @return 字符串值，如果不存在则返回空字符串
     */
    private String getStringValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value != null ? value.toString() : "";
    }

    /**
     * 处理未知类型任务
     * @param task 任务对象
     */
    private void handleUnknownTypeTask(TaskExecuteList task) {
        log.warn("处理未知类型任务 - 任务ID: {}, 省份: {}, 类型: {}",
                task.getId(), task.getProvince(), task.getTaskType());

        try {
            // 对未知类型任务的处理
            // 可以记录到错误日志或者使用默认处理逻辑

            log.debug("未知类型任务数据: {}", task.getTaskData());

            // 可以选择：
            // 1. 跳过处理
            // 2. 使用默认处理逻辑
            // 3. 发送警告通知

            log.warn("未知类型任务已跳过处理 - 任务ID: {}", task.getId());

        } catch (Exception e) {
            log.error("未知类型任务处理失败 - 任务ID: {}", task.getId(), e);
            throw e;
        }
    }

    /**
     * 处理单个任务
     * @param task 任务对象
     */
    private void processIndividualTask(TaskExecuteList task) {
        // 根据实际业务需求处理任务
        // 例如：
        // 1. 检查任务状态
        // 2. 执行特定的业务逻辑
        // 3. 更新任务状态
        // 4. 记录处理日志

        log.debug("处理任务详情 - 任务ID: {}, 类型: {}, 执行时间: {}, 省份: {}",
                task.getId(), task.getTaskType(), task.getTaskExecutionTime(), task.getProvince());

        // 根据任务状态进行不同处理
        String status = task.getTaskStatus();
        if ("0".equals(status)) {
            log.info("发现待执行任务: {}", task.getId());
            // 可以在这里调用具体的任务执行逻辑
        } else if ("1".equals(status)) {
            log.info("任务正在执行中: {}", task.getId());
        } else if ("2".equals(status)) {
            log.info("任务已完成: {}", task.getId());
        } else {
            log.warn("未知任务状态: {} - 任务ID: {}", status, task.getId());
        }
    }
        /** 一、检查登录状态
     * 1、通过手机号判断taskUtil中是存在对应的浏览器对象
     */
    public Map<String,Object>  checkUserExist(String phoneNumber){
        List<Map<String, Object>> list = taskUtil.getAllTasks();
        if (list == null) {
            return null;
        }else{
            for (Map<String, Object> map : list) {
                // 检查当前Map是否包含指定的phoneNumber键，并且值相等
                if (map.containsKey("phoneNumber") && phoneNumber.equals(map.get("phoneNumber"))) {
                    return map; // 找到匹配的电话号码
                }
            }
            return null;
        }
    }
        public  boolean doLogin(BrowserContext context,Page page,Map<String,String> map){
        try{
            String province = map.get("province");
            String webUrl = Provice2URL.getUrl(province);
//            String  webUrl= FIRST_TAX_WEB_URL + map.get("province")+ LAST_TAX_WEB_URL;
            page.navigate(webUrl);
            page.waitForSelector(".loginBtn");
            page.querySelector(".loginBtn").click();
            // 获取页面中统一信用代码输入框
            page.getByPlaceholder("统一社会信用代码/纳税人识别号").fill(map.get("creditCode"));
            page.getByPlaceholder("居民身份证号码/手机号码/用户名").fill(map.get("electricityTaxPhone"));
            page.getByPlaceholder("个人用户密码").fill(map.get("password"));
            Locator locator = page.locator(".handler");
            BoundingBox boundingBox = locator.boundingBox();
            page.mouse().move(boundingBox.x, boundingBox.y);
            page.waitForTimeout(20);
            page.mouse().down();
            Locator dragText = page.locator(".drag_text");
            BoundingBox dragTextBoundingBox = dragText.boundingBox();
            page.mouse().move(boundingBox.x + dragTextBoundingBox.width, boundingBox.y);
            page.waitForTimeout(20);
            page.mouse().up();
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("登录")).click();
            page.getByText("短信验证", new Page.GetByTextOptions().setExact(true)).click();
            page.getByText("获取验证码").click();
            return true;
        }catch (Exception e){
            e.printStackTrace();
            return false;
        }
    }
    /**
     * 创建浏览器上下文
     */
    private  BrowserContext createBrowserContext() {
        //创建浏览器上下文
        Playwright playwright = Playwright.create();
        BrowserType.LaunchOptions launchOptions = new BrowserType.LaunchOptions().setHeadless(false).setSlowMo(200)
                //跳过检查，防止被识别是自动化脚本
                .setArgs(Collections.singletonList("--disable-blink-features=AutomationControlled"));
        //设置有头浏览器方式启动
        Browser browser = playwright.chromium().launch(launchOptions);
        BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                .setIgnoreHTTPSErrors(true).setJavaScriptEnabled(true));
        return context;
    }
}
