package cn.gson.financial.rpa;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TaskUtil {
    // 私有静态实例
    private static TaskUtil instance;

    // 任务列表属性
    private List<Map<String, Object>> taskList;

    // 私有构造方法
    private TaskUtil() {
        taskList = new ArrayList<>();
    }

    // 公共静态方法获取实例
    public static synchronized TaskUtil getInstance() {
        if (instance == null) {
            instance = new TaskUtil();
        }
        return instance;
    }

    // 添加任务
    public void addTask(Map<String, Object> task) {
        taskList.add(task);
    }

    // 获取所有任务
    public List<Map<String, Object>> getAllTasks() {
        return new ArrayList<>(taskList);
    }

    // 移除任务
    public void removeTask(Map<String, Object> task) {
        taskList.remove(task);
    }
}
