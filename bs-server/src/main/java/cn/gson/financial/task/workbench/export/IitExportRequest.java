package cn.gson.financial.task.workbench.export;

import lombok.Data;

import java.util.List;

/**
 * 个税申报导出请求参数
 *
 * <p>导出范围三选一：
 * <ul>
 *   <li>filter   - 按当前列表筛选条件导出（默认）</li>
 *   <li>selected - 仅导出 selectedAccountSetIds 中的账套</li>
 *   <li>all      - 当前账期下全部账套（忽略筛选）</li>
 * </ul>
 */
@Data
public class IitExportRequest {

    /** 账期 YYYY-MM，必填 */
    private String period;

    /** 导出范围：filter / selected / all */
    private String exportRange = "filter";

    /** 仅 selected 模式生效 */
    private List<Long> selectedAccountSetIds;

    /** 个税类型过滤（salary / business），空表示全部 */
    private List<String> iitTypes;

    /** 仅导出已申报成功（done）的记录；false 则连 pending/error/skipped 一起导出 */
    private boolean onlySuccess;

    // ── 以下字段透传给 getTasks（filter 模式生效） ──
    private String status;
    private String stepField;
    private String vat;
    private String accountPeriod;
    private String province;
    private String keyword;
    private List<Long> filterUserIds;
    private String declareFilter;
    private String iitFilter;
}
