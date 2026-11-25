package cn.gson.financial.report.export;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * FN-08 异步报表导出 Job。
 *
 * <p>不入库，只存 Redis（key: {@code financial_v3_report:export:{jobId}}）。
 * 用 Jackson 整体序列化为 JSON 写入 Redis，便于 controller / worker / 测试共用。
 *
 * <p>字段约定：
 * <ul>
 *   <li>{@link #jobId} UUID（自带横线，方便日志读）</li>
 *   <li>{@link #status} {@code QUEUED / RUNNING / SUCCESS / FAILED}</li>
 *   <li>{@link #progress} 0-100，worker 按阶段更新</li>
 *   <li>{@link #resultPath} 完成后落地的文件绝对路径（用于 download 接口）</li>
 *   <li>{@link #errorMsg} FAILED 时填异常摘要</li>
 *   <li>{@link #chunkPaths} 预留 — 当前同步生成 &le; 50MB 不分片；未来超大文件用</li>
 * </ul>
 */
@Data
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ReportExportJob implements Serializable {

    private static final long serialVersionUID = 1L;

    /** UUID — 也是 Redis key 的尾段 */
    private String jobId;

    /** QUEUED / RUNNING / SUCCESS / FAILED */
    private String status;

    /** 0 - 100 */
    private int progress;

    /** 文件落地绝对路径（SUCCESS 时填） */
    private String resultPath;

    /** 文件大小（字节）— 便于 download 接口给 contentLength */
    private Long resultSize;

    /** FAILED 时填异常摘要 */
    private String errorMsg;

    /** 入队时间 */
    private LocalDateTime createdAt;

    /** 完成时间（SUCCESS / FAILED 都填） */
    private LocalDateTime completedAt;

    // ─── 业务上下文 ───
    private Long accountSetsId;

    /** BALANCE_SHEET / PROFIT_LOSS / CASH_FLOW */
    private String type;

    private Integer year;

    private Integer month;

    /** JSON / XLSX / PDF（默认 JSON） */
    private String format;

    /** "yyyy-MM"，方便检索/展示 */
    private String period;

    /**
     * 预留分片字段 — 当前同步生成 ≤ 50MB 不分片，default 空 list。
     * 未来超过 50MB 时由 ops 介入做 CDN 分片，每个分片绝对路径填这里。
     */
    private List<String> chunkPaths = new ArrayList<>();
}
