package cn.gson.financial.kernel.model.vo;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.List;

/**
 * @Author zhb
 * @Date 2025-07-04
 * @Description
 */
@Data
public class DownLoadReportVo implements Serializable {
    private static final long serialVersionUID = -3031070861684510827L;

    /**
     * 账期时间 季报下载会根据此时间自动推算
     */
    private LocalDate voucherDate;
    /**
     * 利润表是都为季报
     */
    private Boolean lrIsQuarter = Boolean.TRUE;

    /**
     * 是否为年报（年报只生成资产负债表，不生成利润表）
     */
    private Boolean lrIsYear = Boolean.FALSE;

    /**
     * 账套ID
     */
    private List<Long> accountSetsIdList;
}
