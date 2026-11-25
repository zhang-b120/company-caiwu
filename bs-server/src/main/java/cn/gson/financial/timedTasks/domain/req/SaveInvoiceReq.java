package cn.gson.financial.timedTasks.domain.req;

import lombok.Data;

@Data
public class SaveInvoiceReq {
    /**
     * 信用代码
     */
    private String creditCode;
    
    /**
     * 文件字节数组
     */
    private byte[] fileInputStream;
    
    /**
     * 账套ID
     */
    private Long accountSetsId;
    
    /**
     * 发票类型
     */
    private String invoiceType;
}
