package cn.gson.financial.task.workbench.export;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 个税导出任务状态（内存对象，进程级生命周期）
 */
@Data
public class IitExportTaskStatus {

    public static final String RUNNING = "running";
    public static final String DONE = "done";
    public static final String FAILED = "failed";

    private String taskId;
    private String status = RUNNING;

    /** 当前阶段文案，前端直接展示 */
    private String stage = "初始化…";
    /** 已处理账套数 */
    private int progress;
    /** 总账套数 */
    private int total;
    /** 已写入个税行数 */
    private int rowsWritten;
    /** 已嵌入截图数 */
    private int screenshotsEmbedded;

    private String fileName;
    private String filePath;
    private long fileSize;

    private String errorMsg;

    private Long userId;
    private String userName;

    private LocalDateTime createdAt = LocalDateTime.now();
    private LocalDateTime updatedAt = LocalDateTime.now();
    private LocalDateTime finishedAt;

    public void touch() {
        this.updatedAt = LocalDateTime.now();
    }
}
