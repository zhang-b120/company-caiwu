package cn.gson.financial.timedTasks.domain.req;

import lombok.Data;

import java.time.LocalDate;

@Data
public class ImportTaxFileReq {
    private String templateKey;
    private Long accountSetId;
    private String voucherDate;
    private Boolean reset;
    private String dimension;
    /**
     * 是否为年报（年报只生成资产负债表）
     */
    private Boolean isYear = Boolean.FALSE;
}
