package cn.gson.financial.task.service;

import cn.gson.financial.kernel.common.IndividualExcelReader;
import cn.gson.financial.kernel.model.entity.EmployeeInfo;
import cn.gson.financial.kernel.model.entity.QEmployeeInfo;
import cn.gson.financial.kernel.model.entity.QTaskExecuteList;
import cn.gson.financial.kernel.model.entity.TaskExecuteList;
import cn.gson.financial.kernel.service.AbsService;
import cn.gson.financial.crm.util.RpaUtil;
import cn.gson.financial.kernel.service.TaxService;
import cn.hutool.json.JSONUtil;
import com.alibaba.fastjson.JSON;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.jpa.impl.JPAQuery;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class TaskExecuteListService extends AbsService {

    @PersistenceContext
    private EntityManager entityManager;
    private QTaskExecuteList qTaskExecuteList = QTaskExecuteList.taskExecuteList;
    private QEmployeeInfo qEmployeeInfo = QEmployeeInfo.employeeInfo;

    private final RpaUtil rpaUtil;
    private final TaxService taxService;


    /**
     * 插入任务执行记录
     *
     * @param taskExecuteList 任务执行记录实体
     * @return 插入的记录ID
     */
    @Transactional
    public Long insertTaskExecuteList(TaskExecuteList taskExecuteList) {
        try {
            // 如果ID为空，则生成新的ID
            if (taskExecuteList.getId() == null) {
                taskExecuteList.setId(System.currentTimeMillis());
            }

            // 使用JPA的merge方法插入数据（因为手动设置了ID）
            TaskExecuteList savedEntity = entityManager.merge(taskExecuteList);
            entityManager.flush(); // 强制刷新到数据库

            System.out.println("成功插入TaskExecuteList记录，ID: " + savedEntity.getId());
            return savedEntity.getId();
        } catch (Exception e) {
            System.err.println("插入TaskExecuteList记录时发生错误: " + e.getMessage());
            throw new RuntimeException("插入TaskExecuteList记录失败: " + e.getMessage(), e);
        }
    }

    /**
     * 批量插入任务执行记录
     *
     * @param taskExecuteList 任务执行记录列表
     * @return 成功插入的记录数量
     */
    @Transactional
    public int batchInsertTaskExecuteList(List<TaskExecuteList> taskExecuteList) {
        try {
            int successCount = 0;

            for (TaskExecuteList task : taskExecuteList) {
                Long id = insertTaskExecuteList(task);
                if (id != null) {
                    successCount++;
                }
            }

            System.out.println("批量插入TaskExecuteList记录完成，成功插入 " + successCount + " 条记录");
            return successCount;
        } catch (Exception e) {
            System.err.println("批量插入TaskExecuteList记录时发生错误: " + e.getMessage());
            throw new RuntimeException("批量插入TaskExecuteList记录失败: " + e.getMessage(), e);
        }
    }

    /**
     * 根据Map参数插入任务执行记录
     *
     * @param taskParams Map格式的任务参数
     * @return 插入的记录ID
     */
    @Transactional
    public Long insertTaskExecuteListFromMap(Map<String, Object> taskParams) {
        try {
            TaskExecuteList taskExecuteList = new TaskExecuteList();

            // 从Map中获取参数并设置到实体对象
            if (taskParams.containsKey("sponsor")) {
                taskExecuteList.setSponsor(String.valueOf(taskParams.get("sponsor")));
            }
            if (taskParams.containsKey("taskType")) {
                taskExecuteList.setTaskType(Integer.valueOf(String.valueOf(taskParams.get("taskType"))));
            }
            if (taskParams.containsKey("taskNumber")) {
                taskExecuteList.setTaskNumber(String.valueOf(taskParams.get("taskNumber")));
            }
            if (taskParams.containsKey("taskStatus")) {
                taskExecuteList.setTaskStatus(String.valueOf(taskParams.get("taskStatus")));
            }

            if (taskParams.containsKey("taskResult")) {
                taskExecuteList.setTaskResult(String.valueOf(taskParams.get("taskResult")));
            }
            if (taskParams.containsKey("taskData")) {
                taskExecuteList.setTaskData(String.valueOf(taskParams.get("taskData")));
            }

            return insertTaskExecuteList(taskExecuteList);
        } catch (Exception e) {
            System.err.println("从Map参数插入TaskExecuteList记录时发生错误: " + e.getMessage());
            throw new RuntimeException("从Map参数插入TaskExecuteList记录失败: " + e.getMessage(), e);
        }
    }

    /**
     * 解析taskData中的JSON数据，为每个元素创建单独的任务
     *
     * @param originalTask 原始任务对象
     * @return 解析后的任务列表
     */
    @Transactional
    public List<TaskExecuteList> parseTaskDataToTaskList(TaskExecuteList originalTask) {
        List<TaskExecuteList> taskList = new ArrayList<>();

        try {
            String taskDataJson = originalTask.getTaskData();
            if (taskDataJson == null || taskDataJson.trim().isEmpty()) {
                System.err.println("taskData为空");
                return taskList;
            }

            ObjectMapper objectMapper = new ObjectMapper();

            // 只有当taskType为1时才进行任务拆分
            if (originalTask.getTaskType() != null && originalTask.getTaskType() == 1) {
                Map<String, Object> taskDataMap = objectMapper.readValue(taskDataJson, Map.class);

                // 获取list数组
                List<Map<String, Object>> companyList = (List<Map<String, Object>>) taskDataMap.get("list");
                if (companyList == null || companyList.isEmpty()) {
                    System.err.println("taskData中的list为空");
                    return taskList;
                }

                // 移除list字段，保留其他字段作为公共数据
                taskDataMap.remove("list");

                // 为每个公司创建一个任务
                for (Map<String, Object> companyData : companyList) {
                    TaskExecuteList subTask = new TaskExecuteList();

                    // 复制原始任务的基本信息
                    subTask.setSponsor(originalTask.getSponsor());
                    subTask.setTaskType(originalTask.getTaskType());
                    subTask.setTaskNumber(originalTask.getTaskNumber());
                    subTask.setTaskStatus(originalTask.getTaskStatus() != null ? originalTask.getTaskStatus() : "0");
                    subTask.setTaskResult(originalTask.getTaskResult() != null ? originalTask.getTaskResult() : "0");
                    subTask.setTaskExecutionTime(originalTask.getTaskExecutionTime() != null ? originalTask.getTaskExecutionTime() : LocalDateTime.now());

                    // 从companyData中提取province字段
                    if (companyData.containsKey("province")) {
                        String province = String.valueOf(companyData.get("province"));
                        subTask.setProvince(province);
                    }

                    // 合并公共数据和公司特定数据
                    Map<String, Object> mergedData = new HashMap<>(taskDataMap);
                    mergedData.putAll(companyData);

                    // 将合并后的数据转换为JSON字符串
                    String subTaskData = objectMapper.writeValueAsString(mergedData);
                    subTask.setTaskData(subTaskData);

                    taskList.add(subTask);
                    System.out.println("创建子任务，公司名称: " + companyData.get("companyName") + "，省份: " + subTask.getProvince());
                }

                System.out.println("成功解析并创建了 " + taskList.size() + " 个子任务");
            } else {
                // 对于其他类型的任务，直接创建一个任务
                TaskExecuteList task = new TaskExecuteList();

                // 复制原始任务的所有信息
                task.setSponsor(originalTask.getSponsor());
                task.setTaskType(originalTask.getTaskType());
                task.setTaskNumber(originalTask.getTaskNumber());
                task.setTaskStatus(originalTask.getTaskStatus() != null ? originalTask.getTaskStatus() : "0");
                task.setTaskResult(originalTask.getTaskResult() != null ? originalTask.getTaskResult() : "0");
                task.setTaskExecutionTime(originalTask.getTaskExecutionTime() != null ? originalTask.getTaskExecutionTime() : LocalDateTime.now());
                task.setTaskData(taskDataJson);

                taskList.add(task);
                System.out.println("创建非拆分任务，类型: " + task.getTaskType());
            }

        } catch (Exception e) {
            System.err.println("解析taskData时发生错误: " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException("解析taskData失败: " + e.getMessage(), e);
        }

        return taskList;
    }

    /**
     * 获取任务列表
     *
     * @return
     */
    @Transactional
    public List<Map<String, String>> selectEmployeeInformation() {
        List<Map<String, String>> taskExecuteList = new ArrayList<Map<String, String>>();
        bqf.selectFrom(qTaskExecuteList).select(qTaskExecuteList.id, qTaskExecuteList.sponsor, qTaskExecuteList.taskNumber, qTaskExecuteList.taskStatus, qTaskExecuteList.taskResult, qTaskExecuteList.taskData, qTaskExecuteList.taskType).where(qTaskExecuteList.taskStatus.eq("0").and(qTaskExecuteList.taskType.eq(1).or(qTaskExecuteList.taskType.eq(2)))).orderBy(qTaskExecuteList.taskType.asc()).limit(1).fetch().forEach(tuple -> {
            Map<String, String> map = new HashMap<>();
            map.put("id", String.valueOf(tuple.get(qTaskExecuteList.id)));
            map.put("sponsor", tuple.get(qTaskExecuteList.sponsor));
            map.put("taskNumber", tuple.get(qTaskExecuteList.taskNumber));
            map.put("taskStatus", tuple.get(qTaskExecuteList.taskStatus));
            map.put("taskResult", tuple.get(qTaskExecuteList.taskResult));
            map.put("taskData", tuple.get(qTaskExecuteList.taskData));
            map.put("taskType", String.valueOf(tuple.get(qTaskExecuteList.taskType)));
            taskExecuteList.add(map);
        });
        return taskExecuteList;
    }

    /*
     * 更新任务执行状态 将文件读入系统
     */
    @Transactional
    public void update(Map<String, Object> taskItem) {
        Long id = Long.valueOf(String.valueOf(taskItem.get("id")));
        jqf.update(qTaskExecuteList).where(qTaskExecuteList.id.eq(id)).set(qTaskExecuteList.taskStatus, "1").execute();

        // 从taskItem中获取文件路径
        String taskData = String.valueOf(taskItem.get("taskData"));
        String filePath = extractFilePathFromTaskData(taskData);

        // 从taskData中提取公司信息
        Map<String, String> companyInfo = extractCompanyInfoFromTaskData(taskData);
        String accountSetsId = companyInfo.get("accountSetsId");
        String companyName = companyInfo.get("companyName");

        //读取人员信息并且存入数据库
        List<EmployeeInfo> data = IndividualExcelReader.readExcel(filePath);
        //遍历
        data.forEach(item -> {
            // 设置从taskData中获取的公司信息
            item.setAccountSetsId(Long.valueOf(accountSetsId));
            item.setCompanyName(companyName);

            // 根据身份证号查询数据库中是否存在该员工信息
            EmployeeInfo existingEmployee = bqf.selectFrom(qEmployeeInfo).where(qEmployeeInfo.idNumber.eq(item.getIdNumber())).fetchOne();

            if (existingEmployee != null) {
                // 如果存在，则更新员工信息
                lazyDao.executeSql("updateEmployeeInfoItem", item.toMap());
                System.out.println("更新员工信息: " + item.getName() + " (身份证号: " + item.getIdNumber() + ")");
            } else {
                // 如果不存在，则插入新的员工信息
                lazyDao.executeSql("insertEmployeeInfoItem", item.toMap());
                System.out.println("插入新员工信息: " + item.getName() + " (身份证号: " + item.getIdNumber() + ")");
            }
        });
        System.out.println("员工信息处理完成，共处理 " + data.size() + " 条记录");
    }

    /**
     * 从taskData中提取文件路径
     *
     * @param taskData JSON数组格式的任务数据
     * @return 文件路径
     */
    private String extractFilePathFromTaskData(String taskData) {
        try {
            // 去除首尾的方括号，获取JSON对象内容
            String jsonContent = taskData.trim();
            if (jsonContent.startsWith("[") && jsonContent.endsWith("]")) {
                jsonContent = jsonContent.substring(1, jsonContent.length() - 1);
            }

            // 解析JSON对象中的字段
            String creditCode = extractJsonValue(jsonContent, "creditCode");
            String companyName = extractJsonValue(jsonContent, "companyName");
            String electricityTaxCode = extractJsonValue(jsonContent, "electricityTaxCode");

            // 根据解析出的信息构造文件路径
            String fileName = creditCode + companyName + electricityTaxCode + ".xls";
            String basePath = "C:\\Users\\23614\\Desktop\\111\\";
            String filePath = basePath + fileName;

            System.out.println("构造的文件路径: " + filePath);

            // 检查文件是否存在
            java.io.File file = new java.io.File(filePath);
            if (!file.exists()) {
                System.err.println("文件不存在: " + filePath);
                // 检查目录是否存在
                java.io.File directory = new java.io.File(basePath);
                if (!directory.exists()) {
                    System.err.println("目录不存在: " + basePath);
                } else {
                    System.err.println("目录存在，但文件不存在。目录中的文件列表:");
                    String[] files = directory.list();
                    if (files != null) {
                        for (String f : files) {
                            System.err.println("  - " + f);
                        }
                    }
                }
                throw new RuntimeException("文件不存在: " + filePath);
            }

            // 检查文件是否可读
            if (!file.canRead()) {
                System.err.println("文件无法读取: " + filePath);
                throw new RuntimeException("文件无法读取: " + filePath);
            }

            return filePath;

        } catch (Exception e) {
            System.err.println("解析文件路径失败: " + e.getMessage());
            System.err.println("taskData内容: " + taskData);
            throw new RuntimeException("无法从taskData中解析文件路径: " + taskData, e);
        }
    }

    /**
     * 从JSON字符串中提取指定字段的值
     *
     * @param jsonContent JSON内容或Map toString内容
     * @param fieldName   字段名
     * @return 字段值
     */
    private String extractJsonValue(String jsonContent, String fieldName) {
        // 先尝试JSON格式: "fieldName":"value"
        String jsonSearchPattern = "\"" + fieldName + "\":\"";
        int start = jsonContent.indexOf(jsonSearchPattern);
        if (start != -1) {
            start += jsonSearchPattern.length();
            int end = jsonContent.indexOf("\"", start);
            if (end != -1) {
                return jsonContent.substring(start, end);
            }
        }

        // 再尝试Map toString格式: fieldName=value
        String mapSearchPattern = fieldName + "=";
        start = jsonContent.indexOf(mapSearchPattern);
        if (start != -1) {
            start += mapSearchPattern.length();
            int end = jsonContent.indexOf(",", start);
            if (end == -1) {
                end = jsonContent.indexOf("}", start);
            }
            if (end != -1) {
                return jsonContent.substring(start, end).trim();
            }
        }

        return "";
    }

    /**
     * 从taskData中提取公司信息
     *
     * @param taskData JSON数组格式的任务数据
     * @return 公司信息
     */
    private Map<String, String> extractCompanyInfoFromTaskData(String taskData) {
        try {
            // 去除首尾的方括号，获取JSON对象内容
            String jsonContent = taskData.trim();
            if (jsonContent.startsWith("[") && jsonContent.endsWith("]")) {
                jsonContent = jsonContent.substring(1, jsonContent.length() - 1);
            }

            // 解析JSON对象中的字段
            String accountSetsId = extractJsonValue(jsonContent, "accountSetsId");
            String companyName = extractJsonValue(jsonContent, "companyName");

            // 构造公司信息
            Map<String, String> companyInfo = new HashMap<>();
            companyInfo.put("accountSetsId", accountSetsId);
            companyInfo.put("companyName", companyName);

            return companyInfo;

        } catch (Exception e) {
            System.err.println("解析公司信息失败: " + e.getMessage());
            System.err.println("taskData内容: " + taskData);
            throw new RuntimeException("无法从taskData中解析公司信息: " + taskData);
        }
    }

    /**
     * 查询所有任务执行记录
     *
     * @return 所有任务执行记录列表
     */
    @Transactional(readOnly = true)
    public List<TaskExecuteList> findAll() {
        try {
            List<TaskExecuteList> taskList = bqf.selectFrom(qTaskExecuteList).orderBy(qTaskExecuteList.taskExecutionTime.desc()).fetch();

            System.out.println("查询到 " + taskList.size() + " 条任务执行记录");
            return taskList;
        } catch (Exception e) {
            System.err.println("查询所有任务执行记录失败: " + e.getMessage());
            throw new RuntimeException("查询所有任务执行记录失败", e);
        }
    }

    /**
     * 根据任务状态查询任务执行记录
     *
     * @param status 任务状态
     * @return 符合条件的任务执行记录列表
     */
    @Transactional(readOnly = true)
    public List<TaskExecuteList> findByStatus(String status) {
        try {
            List<TaskExecuteList> taskList = bqf.selectFrom(qTaskExecuteList).where(qTaskExecuteList.taskStatus.eq(status)).orderBy(qTaskExecuteList.taskExecutionTime.desc()).fetch();

            System.out.println("查询到状态为 " + status + " 的任务 " + taskList.size() + " 条");
            return taskList;
        } catch (Exception e) {
            System.err.println("根据状态查询任务执行记录失败: " + e.getMessage());
            throw new RuntimeException("根据状态查询任务执行记录失败", e);
        }
    }

    /**
     * 根据任务类型查询任务执行记录
     *
     * @param taskType 任务类型
     * @return 符合条件的任务执行记录列表
     */
    @Transactional(readOnly = true)
    public List<TaskExecuteList> findByTaskType(Integer taskType) {
        try {
            List<TaskExecuteList> taskList = bqf.selectFrom(qTaskExecuteList).where(qTaskExecuteList.taskType.eq(taskType)).orderBy(qTaskExecuteList.taskExecutionTime.desc()).fetch();

            System.out.println("查询到类型为 " + taskType + " 的任务 " + taskList.size() + " 条");
            return taskList;
        } catch (Exception e) {
            System.err.println("根据类型查询任务执行记录失败: " + e.getMessage());
            throw new RuntimeException("根据类型查询任务执行记录失败", e);
        }
    }

    /**
     * 按省份正序排序获取第一个任务
     *
     * @return 第一个任务执行记录，如果没有则返回null
     */
    @Transactional(readOnly = true)
    public TaskExecuteList findFirstTaskOrderByProvince() {
        try {
            TaskExecuteList firstTask = bqf.selectFrom(qTaskExecuteList).where(qTaskExecuteList.province.isNotNull().and(qTaskExecuteList.province.isNotEmpty())).orderBy(qTaskExecuteList.province.asc(), qTaskExecuteList.taskExecutionTime.asc()).fetchFirst();

            if (firstTask != null) {
                System.out.println("获取到按省份排序的第一个任务 - ID: " + firstTask.getId() + ", 省份: " + firstTask.getProvince() + ", 状态: " + firstTask.getTaskStatus());
            } else {
                System.out.println("没有找到包含省份信息的任务");
            }

            return firstTask;
        } catch (Exception e) {
            System.err.println("按省份排序查询第一个任务失败: " + e.getMessage());
            throw new RuntimeException("按省份排序查询第一个任务失败", e);
        }
    }

    /**
     * 按省份正序排序获取第一个指定状态的任务
     *
     * @param province 任务状态
     * @return 第一个符合条件的任务执行记录，如果没有则返回null
     */
    @Transactional(readOnly = true)
    public TaskExecuteList findFirstTaskByStatusOrderByProvince(String province, Integer taskType, String sponsor) {
        try {
            TaskExecuteList firstTask = bqf.selectFrom(qTaskExecuteList).where(qTaskExecuteList.province.isNotNull().and(qTaskExecuteList.province.isNotEmpty()).and(qTaskExecuteList.taskStatus.eq("0")).and(qTaskExecuteList.province.eq(province)).and(qTaskExecuteList.taskType.eq(taskType)).and(qTaskExecuteList.sponsor.eq(sponsor))).orderBy(qTaskExecuteList.province.asc(), qTaskExecuteList.taskExecutionTime.asc(), qTaskExecuteList.taskType.asc()).fetchFirst();

            if (firstTask != null) {
                System.out.println("获取到状态为 " + 0 + " 的第一个任务 - ID: " + firstTask.getId() + ", 省份: " + firstTask.getProvince() + ", 执行时间: " + firstTask.getTaskExecutionTime());
            } else {
                System.out.println("没有找到状态为 " + 0 + " 且包含省份信息的任务");
            }

            return firstTask;
        } catch (Exception e) {
            System.err.println("按省份排序查询状态为 " + 0 + " 的第一个任务失败: " + e.getMessage());
            throw new RuntimeException("按省份排序查询第一个任务失败", e);
        }
    }

    /**
     * 根据条件查询任务列表
     *
     * @param taskType 任务类型
     * @param sponsor  执行人
     * @return 任务列表
     */
    public List<TaskExecuteList> findTasksByCondition(Integer taskType, String sponsor, String province,String companyName) {
        List<TaskExecuteList> TaskExecuteList = bqf.selectFrom(qTaskExecuteList)

                .where(qTaskExecuteList.taskType.eq(taskType)
                        .and(StringUtils.isEmpty(province) ? null : qTaskExecuteList.province.eq(province))
                        .and(StringUtils.isEmpty(sponsor) ? null :qTaskExecuteList.sponsor.eq(sponsor))
                        .and(StringUtils.isEmpty(companyName) ? null :qTaskExecuteList.taskData.like("%"+companyName+"%"))
                )
                .orderBy(qTaskExecuteList.province.asc(), qTaskExecuteList.taskExecutionTime.asc(), qTaskExecuteList.taskType.asc())
                .fetch();
        return TaskExecuteList;
    }

    /**
     * 根据任务类型和执行人查询最多5个不同省份的待执行任务
     *
     * @param taskType 任务类型
     * @param sponsor  执行人
     * @return 待执行的任务列表
     */
    public List<TaskExecuteList> findFirstTaskByTypeAndSponsor(String taskType, String sponsor,String provinceStr) {
        // 先查询出所有符合条件的不同省份
        Integer taskTypeInt = Integer.valueOf(taskType);
        List<String> provinces = bqf.selectFrom(qTaskExecuteList).select(qTaskExecuteList.province).distinct().where(qTaskExecuteList.taskStatus.eq("0").and(qTaskExecuteList.province.eq(provinceStr)).and(qTaskExecuteList.taskType.eq(taskTypeInt)).and(qTaskExecuteList.sponsor.eq(sponsor))).orderBy(qTaskExecuteList.province.asc()).limit(5)  // 最多取5个省份
                .fetch();

        if (provinces.isEmpty()) {
            return new ArrayList<>();
        }

        // 对每个省份取一条任务
        List<TaskExecuteList> result = new ArrayList<>();
        for (String province : provinces) {
            TaskExecuteList task = bqf.selectFrom(qTaskExecuteList).where(qTaskExecuteList.taskStatus.eq("0").and(qTaskExecuteList.taskType.eq(taskTypeInt)).and(qTaskExecuteList.sponsor.eq(sponsor)).and(qTaskExecuteList.province.eq(province))).orderBy(qTaskExecuteList.sort.asc()).fetchFirst();

            if (task != null) {
                result.add(task);
            }
        }

        return result;
    }

    @Transactional
    public void processSingleTask(TaskExecuteList taskExecuteList, String taskType) {
        Map<String, String> result = new HashMap<>();
        if ("1".equals(taskType)) {
            // 调用取票接口
            result = rpaUtil.downInvoice(taskExecuteList);
            jqf.update(qTaskExecuteList).set(qTaskExecuteList.taskResult, result.toString()).set(qTaskExecuteList.taskStatus, "1").where(qTaskExecuteList.id.eq(taskExecuteList.getId())).execute();
        } else if ("2".equals(taskType)) {
            // 调用报税接口 todo
            String taskData = taskExecuteList.getTaskData();
            Map<String, String> taskDataMap = JSON.parseObject(taskData, Map.class);
            taskDataMap.put("taxSystemKey",taskExecuteList.getProvince()+"*"+taskExecuteList.getSponsor());
            result = rpaUtil.oneKeyTaxButton(taskDataMap,taskExecuteList.getTaskExecutionTime().toLocalDate());
            result.put("taskListId",String.valueOf(taskExecuteList.getId()));
            result.put("taxId",taskDataMap.get("taxId") + "");
            taxService.updateTaskExecuteList(result);
        }

        /**
         *  result
         *  result.put("yigouxuan","暂无任务");
         *  result.put("weigouxuan","暂无任务");
         *  result.put("kaijufapiao","暂无任务");
         *  result.put("qudefapiao","暂无任务");
         *  result.put("yigouxuan","error");
         *  result.put("yigouxuanMsg",gouxuanMap.get("msg"));
         *  result.put("kaijufapiao","error");
         *  result.put("kaijufapiaoMsg",kaijuMap.get("msg"));
         *  result.put("qudefapiao","error");
         *  result.put("qudefapiaoMsg",qudefapiao.get("msg"));
         *  result.put("weigouxuan","error");
         *  result.put("weigouxuanMsg",weigouxuanMap.get("msg"));
         */

    }
}
