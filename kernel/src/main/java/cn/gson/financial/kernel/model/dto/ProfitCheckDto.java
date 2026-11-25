package cn.gson.financial.kernel.model.dto;

import cn.gson.financial.kernel.model.entity.AcDetails;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 
 * <p><b>Copyright © 2010-2023 开发公司 All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.kernel.model.dto</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2023年05月14日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@Data
public class ProfitCheckDto {
    private BigDecimal balance;
    private BigDecimal fcAmount;
    private SubjectDto subject;
    private Long subjectId;
    private List<AcDetails> auxiliary;
    private String code;
    private String fullName;
    private String balanceDirection;
    /**
     * FN-FIX-PL-CARRY-VERIFY-ONLY：辅助核算明细 ID。
     * 主科目（无辅助）行为 0；挂辅助核算的科目会按 auxiliaryDetailId 拆多行（一行一个 aux 组合），
     * xiaowei 用此 ID 作为建 pl_carry 凭证分录的辅助维度 key（每个 aux 明细各一行结转）。
     */
    private Long auxiliaryDetailId;
}
