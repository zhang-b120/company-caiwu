package cn.gson.financial.kernel.model.crm.resp;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * @Author zhb
 * @Date 2025-08-26
 * @Description
 */
@Data
public class GetTaxReportResultDataResp implements Serializable {
    @Serial
    private static final long serialVersionUID = -3705029140593492113L;

    /**
     * 报税条目总数
     */
    private Integer allCount = 0;

    /**
     * 报税完成数
     */
    private Integer successCount = 0;

    /**
     * 报税未申报数
     */
    private Integer pendingCount = 0;

    /**
     * 报税截图
     */
    private String imgPath;

    /**
     * 报税条目数据
     */
    private List<GetTaxTabResp> taxTabList;
}