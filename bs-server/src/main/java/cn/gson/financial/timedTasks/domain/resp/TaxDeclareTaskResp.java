package cn.gson.financial.timedTasks.domain.resp;

import lombok.Data;

import java.io.Serializable;

/**
 * 应申报税费种任务：待同步的账套信息（供外部程序抓取后回传）
 */
@Data
public class TaxDeclareTaskResp implements Serializable {
    private Long accountSetsId;
    private String creditCode;
    private String province;
    private String companyName;
}
