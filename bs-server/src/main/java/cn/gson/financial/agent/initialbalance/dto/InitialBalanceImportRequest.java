package cn.gson.financial.agent.initialbalance.dto;

import lombok.Data;

/**
 * 期初数据导入请求DTO
 * 
 * @author 开发者
 */
@Data
public class InitialBalanceImportRequest {
    
    /**
     * 科目余额表Excel文件的Base64编码
     */
    private String balanceFileBase64;
    
    /**
     * 科目余额表文件名（包含扩展名）
     */
    private String balanceFileName;
    
    /**
     * 系统科目Excel文件的Base64编码
     */
    private String defaultAccountsFileBase64;
    
    /**
     * 系统科目文件名（包含扩展名）
     */
    private String defaultAccountsFileName;
    
    /**
     * 会计期间（可选，默认：2025-01）
     */
    private String period;
}
