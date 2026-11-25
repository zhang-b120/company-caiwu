package cn.gson.financial.controller;

import cn.dev33.satoken.annotation.SaIgnore;
import cn.dev33.satoken.stp.StpUtil;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.model.entity.TaskExecuteList;
import cn.gson.financial.task.service.TaskExecuteListService;
import cn.gson.financial.kernel.model.vo.UserVo;
import cn.gson.financial.kernel.annotation.SessionUser;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.poi.util.StringUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.*;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@RequiredArgsConstructor
@RestController
@RequestMapping("/taskExecuteList")
@Slf4j
public class TaskExecuteListController {
    @Autowired
    public TaskExecuteListService taskExecuteListService;

    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${static-url.base-url}")
    private String baseUrl;

    @Value("${static-url.img}")
    private String imgs;
    /**
     * 定义一个通用的获取任务执行表，根据大类获取相应的任务
     * 传给影刀所需要下载的员工信息表
     * @param type
     * @return
     */
    @SaIgnore
    @GetMapping("/select/{type}")
    public List<Map<String,String>> select(@PathVariable("type") String type) {
        List<Map<String, String>> list = new ArrayList<Map<String, String>>();
        if("individualTax".equals(type)) {
            list = taskExecuteListService.selectEmployeeInformation();
            return list;
        }
        return list;
    }

    /**
     * 更新任务执行状态
     * @param taskItem
     * @return
     */
    @SaIgnore
    @PostMapping("/update")
    public List<Map<String,String>> update(@RequestBody List<Map<String,Object>> taskItem)  {
        System.out.println(taskItem.get(0).get("taskType"));
        if ("1".equals(taskItem.get(0).get("taskType"))){
            taskExecuteListService.update(taskItem.get(0));
        }

        return null;
    }

    /**
     * 接收用户传来的任务内容并存入数据库
     * @param requestBody 请求体的Map格式，可以处理灵活的JSON结构
     * @return JsonResult 操作结果
     */
    @SaIgnore
    @PostMapping("/create")
    public JsonResult createTask(@RequestBody Map<String, Object> requestBody) {
        try {
            System.out.println("=== 新版本控制器 - 接收到的请求数据: " + requestBody);

            // 创建TaskExecuteList对象
            TaskExecuteList taskExecuteList = new TaskExecuteList();
            ObjectMapper objectMapper = new ObjectMapper();

            // 从requestBody中提取数据并设置到TaskExecuteList对象
            if (requestBody.containsKey("sponsor")) {
                String sponsor = String.valueOf(requestBody.get("sponsor"));
                taskExecuteList.setSponsor(sponsor);
                System.out.println("设置sponsor: " + sponsor);
            }

            if (requestBody.containsKey("taskType")) {
                Object taskTypeObj = requestBody.get("taskType");
                Integer taskType = null;
                if (taskTypeObj instanceof String) {
                    taskType = Integer.valueOf((String) taskTypeObj);
                } else if (taskTypeObj instanceof Integer) {
                    taskType = (Integer) taskTypeObj;
                }
                taskExecuteList.setTaskType(taskType);
                System.out.println("设置taskType: " + taskType);
            }

            if (requestBody.containsKey("taskNumber")) {
                String taskNumber = String.valueOf(requestBody.get("taskNumber"));
                taskExecuteList.setTaskNumber(taskNumber);
                System.out.println("设置taskNumber: " + taskNumber);
            }

            if (requestBody.containsKey("taskStatus")) {
                String taskStatus = String.valueOf(requestBody.get("taskStatus"));
                taskExecuteList.setTaskStatus(taskStatus);
                System.out.println("设置taskStatus: " + taskStatus);
            }

            if (requestBody.containsKey("taskResult")) {
                String taskResult = String.valueOf(requestBody.get("taskResult"));
                taskExecuteList.setTaskResult(taskResult);
                System.out.println("设置taskResult: " + taskResult);
            }

            // 处理taskData字段 - 这是关键部分
            if (requestBody.containsKey("taskData")) {
                Object taskDataObj = requestBody.get("taskData");
                System.out.println("taskData对象类型: " + taskDataObj.getClass().getName());
                System.out.println("taskData内容: " + taskDataObj);

                String taskDataString = null;
                if (taskDataObj instanceof String) {
                    taskDataString = (String) taskDataObj;
                    System.out.println("taskData是字符串，直接使用");
                } else {
                    // 将对象转换为JSON字符串
                    try {
                        taskDataString = objectMapper.writeValueAsString(taskDataObj);
                        System.out.println("taskData转换为JSON字符串: " + taskDataString);
                    } catch (Exception jsonException) {
                        System.err.println("转换taskData为JSON失败: " + jsonException.getMessage());
                        throw new RuntimeException("无法转换taskData为JSON字符串", jsonException);
                    }
                }

                taskExecuteList.setTaskData(taskDataString);
                System.out.println("最终设置的taskData: " + taskDataString);
            }

            System.out.println("完整的TaskExecuteList对象: " + taskExecuteList);

            // 解析taskData中的JSON数据，为每个元素创建单独的任务
            List<TaskExecuteList> taskList = taskExecuteListService.parseTaskDataToTaskList(taskExecuteList);

            if (taskList == null || taskList.isEmpty()) {
                return JsonResult.failure("无法解析任务数据或任务数据为空");
            }

            // 批量插入任务
            int successCount = taskExecuteListService.batchInsertTaskExecuteList(taskList);

            if (successCount > 0) {
                return JsonResult.successful("任务创建成功，共创建了 " + successCount + " 个子任务", successCount);
            } else {
                return JsonResult.failure("任务创建失败");
            }
        } catch (Exception e) {
            System.err.println("=== 控制器处理异常 ===");
            e.printStackTrace();
            return JsonResult.failure("任务创建失败: " + e.getMessage());
        }
    }


    /**
     * 批量接收任务内容并存入数据库
     * @param taskList 任务执行对象列表
     * @return JsonResult 操作结果
     */
    @SaIgnore
    @PostMapping("/batchCreate")
    public JsonResult batchCreateTask(@RequestBody List<TaskExecuteList> taskList) {
        try {
            if (taskList == null || taskList.isEmpty()) {
                return JsonResult.failure("任务列表不能为空");
            }

            // 为每个任务设置默认值
            for (TaskExecuteList task : taskList) {
                if (task.getTaskExecutionTime() == null) {
                    task.setTaskExecutionTime(LocalDateTime.now());
                }
                if (task.getTaskStatus() == null || task.getTaskStatus().isEmpty()) {
                    task.setTaskStatus("0");
                }
                if (task.getTaskResult() == null || task.getTaskResult().isEmpty()) {
                    task.setTaskResult("0");
                }
            }

            // 调用service层批量插入任务
            int successCount = taskExecuteListService.batchInsertTaskExecuteList(taskList);

            return JsonResult.successful("批量任务创建成功，共成功创建 " + successCount + " 个任务", successCount);
        } catch (Exception e) {
            return JsonResult.failure("批量任务创建失败: " + e.getMessage());
        }
    }

    /**
     * 任务执行列表工作流代理接口
     */
    @PostMapping("/workflow/run")
    public JsonResult workflowProxy(@RequestBody Object requestBody, @SessionUser UserVo userVo) {
        try {
            // 获取当前登录用户信息
            String mobile = userVo.getMobile();
            String userToken = StpUtil.getTokenValue(); // 获取当前用户的token
            log.info("任务执行列表工作流代理请求，用户手机号: {}, Token: {}", mobile, userToken);

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

                    // 处理taskData字段，确保它是字符串
                    Object taskData = inputsMap.get("taskData");
                    if (taskData != null && !(taskData instanceof String)) {
                        try {
                            ObjectMapper objectMapper = new ObjectMapper();
                            String taskDataString = objectMapper.writeValueAsString(taskData);
                            inputsMap.put("taskData", taskDataString);
                            log.info("已将taskData转换为字符串: {}", taskDataString);
                        } catch (Exception e) {
                            log.error("转换taskData为字符串失败", e);
                        }
                    }

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
            headers.set("Authorization", "Bearer app-PkHWQKizL6iJbsNUdm7RkX5y");

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
            log.error("任务执行列表工作流代理请求失败", e);
            return JsonResult.failure("任务执行列表工作流代理请求失败: " + e.getMessage());
        }
    }

    /**
     * 循环执行任务读取，每次最多处理5个不同省份的任务
     * @param taskType 任务类型
     * @param sponsor 执行人
     */
    private void executeTaskReading(String taskType, String sponsor,String provinces) {
        LocalDateTime now = LocalDateTime.now();
        String timeStr = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

        log.info("开始执行任务类型: {} - 执行人: {}, 执行时间：{}", taskType, sponsor, timeStr);

        try {
            while (true) {
                // 获取最多5个不同省份的待执行任务
                List<TaskExecuteList> tasks = taskExecuteListService.findFirstTaskByTypeAndSponsor(taskType, sponsor,provinces);

                if (tasks == null || tasks.size() == 0) {
                    log.info("没有更多待执行的任务需要处理");
                    break;
                }

                // 按省份分组执行任务
                Map<String, List<TaskExecuteList>> tasksByProvince = tasks.stream()
                        .collect(Collectors.groupingBy(TaskExecuteList::getProvince));

                // 创建固定大小的线程池，最多同时处理5个省份
                ExecutorService executorService = Executors.newFixedThreadPool(5);
                List<Future<?>> futures = new ArrayList<>();

                // 为每个省份创建一个任务
                for (Map.Entry<String, List<TaskExecuteList>> entry : tasksByProvince.entrySet()) {
                    String province = entry.getKey();
                    List<TaskExecuteList> provinceTasks = entry.getValue();

                    // 提交省份任务到线程池
                    Future<?> future = executorService.submit(() -> {
                        try {
                            for (TaskExecuteList task : provinceTasks) {
                                try {
                                    log.info("执行省份: {} - 任务ID: {}, 状态: {}",
                                        province, task.getId(), task.getTaskStatus());

                                    // 处理单个任务
                                    taskExecuteListService.processSingleTask(task,taskType);

                                    // 任务间短暂休眠
                                    Thread.sleep(100);
                                } catch (Exception e) {
                                    log.error("省份: {} - 任务ID: {} 执行失败", province, task.getId(), e);
                                }
                            }
                        } catch (Exception e) {
                            log.error("省份: {} 的任务执行过程发生错误", province, e);
                        }
                    });

                    futures.add(future);
                }

                // 等待所有省份的任务完成
                for (Future<?> future : futures) {
                    try {
                        future.get();
                    } catch (Exception e) {
                        log.error("等待任务完成时发生错误", e);
                    }
                }

                // 关闭线程池
                executorService.shutdown();
                try {
                    if (!executorService.awaitTermination(1, TimeUnit.HOURS)) {
                        executorService.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    executorService.shutdownNow();
                }

                // 短暂休眠，避免过于频繁的数据库访问
                Thread.sleep(100);
            }
        } catch (Exception e) {
            log.error("任务类型: {}执行失败", taskType, e);
        }

        log.info("任务类型: {}执行完成，结束时间：{}", taskType,
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
    }

    /**
     * 查询指定执行人和省份的所有任务
     * @param taskType 任务类型
     * @param sponsor 执行人
     * @return JsonResult 操作结果
     */
    @SaIgnore
    @GetMapping("/queryTasksByCondition")
    public JsonResult queryTasksByCondition(
            @RequestParam("taskType") Integer taskType,
            @RequestParam("sponsor") String sponsor,
            String province,
            String companyName) {
        try {
            log.info("开始查询任务 - 任务类型: {}, 省份: {}, 执行人: {}, 查询时间: {}",
                    taskType, sponsor, province,companyName,
                    LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));

            // 查询符合条件的所有任务
            List<TaskExecuteList> tasks = taskExecuteListService.findTasksByCondition(taskType, sponsor, province,companyName);

            if (tasks == null || tasks.isEmpty()) {
                log.info("未找到符合条件的任务");
                return JsonResult.successful(Collections.emptyList());
            }

            log.info("查询到{}条任务记录", tasks.size());

            // 转换任务列表为返回结果
            List<Map<String, Object>> resultList = new ArrayList<>();
            for (TaskExecuteList task : tasks) {
                Map<String, Object> result = new HashMap<>();
                result.put("taskId", task.getId());
                result.put("taskType", task.getTaskType());
                result.put("taskData", task.getTaskData());
                result.put("sponsor", task.getSponsor());
                result.put("taskNumber", task.getTaskNumber());
                result.put("province", task.getProvince());
                result.put("taskStatus", task.getTaskStatus());
                result.put("taskResult", task.getTaskResult());
                //报税任务展示
                if(taskType == 2) {
                    JSONObject taskData = JSON.parseObject(task.getTaskData());
                    if(ObjectUtils.isNotEmpty(taskData)) {
                        if(taskData.containsKey("taxType")) {
                            result.put("taxType", taskData.get("taxType"));
                        }
                        if(taskData.containsKey("companyName")){
                            result.put("companyName", taskData.get("companyName"));
                        }
                        if(taskData.containsKey("creditCode")){
                            result.put("creditCode", taskData.get("creditCode"));
                        }
                    }
                    String taskResult = task.getTaskResult().trim();
                    if (ObjectUtils.isNotEmpty(taskResult) && !"未执行".equals(taskResult)) {
                        JSONObject resultJson = JSONObject.parseObject(taskResult);
                        if(resultJson.containsKey("status")) {
                            result.put("resultStatus", resultJson.get("status"));
                        }
                        if(resultJson.containsKey("msg")) {
                            result.put("resultMsg", resultJson.get("msg"));
                        }
                        if (resultJson.containsKey("tipImg") && ObjectUtils.isNotEmpty(resultJson.get("tipImg"))) {
                            JSONArray imgJsonArray = JSONArray.parseArray(resultJson.get("tipImg").toString());
                            List<String> imgList = imgJsonArray.stream().map(item -> baseUrl + imgs + item).toList();
                            result.put("resultImgList", imgList);
                        }
                    }
                }

                resultList.add(result);
            }

            log.info("任务查询完成，结束时间：{}",
                    LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));

            return JsonResult.successful(resultList);
        } catch (Exception e) {
            log.error("查询任务失败", e);
            return JsonResult.failure("查询任务失败: " + e.getMessage());
        }
    }

    /**
     * 立即执行任务
     * @param requestBody 请求参数
     * @return JsonResult 操作结果
     */
    @PostMapping("/execute")
    @SaIgnore
    public JsonResult execute(@RequestBody Map<String, Object> requestBody) {
        try {
            log.info("开始执行任务，请求参数: {}", requestBody);

            // 获取必要参数
            String  taskType = String.valueOf(requestBody.get("taskType"));
            String province =String.valueOf( requestBody.get("province"));
            String sponsor =  String.valueOf(requestBody.get("sponsor"));
            // 执行任务
            executeTaskReading(taskType, sponsor,province);

            return JsonResult.successful("任务执行成功");
        } catch (Exception e) {
            log.error("任务执行失败", e);
            return JsonResult.failure("任务执行失败: " + e.getMessage());
        }
    }

}
