package cn.gson.financial.task.controller;

import cn.gson.financial.job.TaskSchedulerJob;
import cn.gson.financial.kernel.model.entity.TaskExecuteList;
import cn.gson.financial.task.service.TaskExecuteListService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 任务调度器控制器
 * 用于手动触发定时任务进行测试
 *
 * @author 开发者
 */
@Slf4j
@RestController
@RequestMapping("/taskScheduler")
@RequiredArgsConstructor
public class TaskSchedulerController {

    private final TaskSchedulerJob taskSchedulerJob;
    private final TaskExecuteListService taskExecuteListService;

    /**
     * 手动触发任务读取
     * 用于测试定时任务功能
     *
     * @return 执行结果
     */
    @PostMapping("/trigger")
    public Map<String, Object> triggerTaskReading() {
        Map<String, Object> result = new HashMap<>();

        try {
            log.info("手动触发任务读取");

            result.put("success", true);
            result.put("message", "任务读取已触发");
            result.put("timestamp", System.currentTimeMillis());

        } catch (Exception e) {
            log.error("手动触发任务读取失败", e);
            result.put("success", false);
            result.put("message", "任务读取触发失败: " + e.getMessage());
            result.put("timestamp", System.currentTimeMillis());
        }

        return result;
    }



    /**
     * 根据任务类型获取按省份排序的第一个待执行任务
     *
     * @param taskType 任务类型 (1-获取人员信息, 2-报个税, 3-获取发票)
     * @return 第一个指定类型的待执行任务信息
     */
    @GetMapping("/firstTask/type")
    public Map<String, Object> getFirstTaskByTypeOrderByProvince(Integer taskType) {
        Map<String, Object> result = new HashMap<>();

        try {
            log.info("查询类型为 {} 的按省份排序的第一个待执行任务", taskType);

            // 先按类型查询所有待执行任务，然后按省份排序获取第一个
            List<TaskExecuteList> typeTasks = taskExecuteListService.findByTaskType(taskType);

            // 过滤状态为0且包含省份信息的任务，按省份排序
            TaskExecuteList firstTask = typeTasks.stream()
                    .filter(task -> "0".equals(task.getTaskStatus()) &&
                                  task.getProvince() != null &&
                                  !task.getProvince().isEmpty())
                    .sorted((t1, t2) -> {
                        int provinceCompare = t1.getProvince().compareTo(t2.getProvince());
                        if (provinceCompare == 0 && t1.getTaskExecutionTime() != null && t2.getTaskExecutionTime() != null) {
                            return t1.getTaskExecutionTime().compareTo(t2.getTaskExecutionTime());
                        }
                        return provinceCompare;
                    })
                    .findFirst()
                    .orElse(null);

            if (firstTask != null) {
                result.put("success", true);
                result.put("message", "查询成功");
                result.put("task", createTaskInfo(firstTask));
            } else {
                String taskTypeName = getTaskTypeName(taskType);
                result.put("success", true);
                result.put("message", "没有找到类型为 " + taskType + "(" + taskTypeName + ") 的待执行任务");
                result.put("task", null);
            }

            result.put("timestamp", System.currentTimeMillis());

        } catch (Exception e) {
            log.error("查询类型为 {} 的第一个待执行任务失败", taskType, e);
            result.put("success", false);
            result.put("message", "查询失败: " + e.getMessage());
            result.put("timestamp", System.currentTimeMillis());
        }

        return result;
    }

    /**
     * 根据任务类型获取任务名称
     * @param taskType 任务类型
     * @return 任务名称
     */
    private String getTaskTypeName(Integer taskType) {
        switch (taskType) {
            case 1:
                return "获取人员信息";
            case 2:
                return "报个税";
            case 3:
                return "获取发票";
            default:
                return "未知类型";
        }
    }

    /**
     * 创建任务信息Map
     * @param task 任务对象
     * @return 任务信息Map
     */
    private Map<String, Object> createTaskInfo(TaskExecuteList task) {
        Map<String, Object> taskInfo = new HashMap<>();
        taskInfo.put("id", task.getId());
        taskInfo.put("sponsor", task.getSponsor());
        taskInfo.put("taskType", task.getTaskType());
        taskInfo.put("taskNumber", task.getTaskNumber());
        taskInfo.put("taskStatus", task.getTaskStatus());
        taskInfo.put("taskExecutionTime", task.getTaskExecutionTime());
        taskInfo.put("taskResult", task.getTaskResult());
        taskInfo.put("province", task.getProvince());
        // 不返回taskData，因为可能很大
        taskInfo.put("hasTaskData", task.getTaskData() != null && !task.getTaskData().isEmpty());
        return taskInfo;
    }
}
