package cn.gson.financial.kernel.model.dto;

import cn.gson.financial.kernel.model.enums.AccountingCategoryType;
import lombok.*;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Map;

/**
 * A DTO for the {@link cn.gson.financial.kernel.model.entity.CashierAccount} entity
 */
@Getter
@Setter
public class CashierAccountDto implements Serializable {
    private Long id;
    private String type;
    private String name;
    private String cardNumber;
    private String code;
    private Long accountSetsId;
    private Long subjectId;
    private String subjectCode;
    private String subjectName;
    private Long currencyId;
    private String currencyName;
    private Double initialBalance;
    private Map<Long, AcDetailsDto> categoryVal;
    private BigDecimal balance;
    /** FN-NEW-AUTOCREATE-BANK-ACCOUNT：AI 自动创建·待会计确认标记 */
    private Boolean autoCreated;

    /**
     * DTO for {@link cn.gson.financial.kernel.model.entity.AcDetails}
     */
    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class AcDetailsDto implements Serializable {
        private Long id;
        private Long accountSetsId;
        private Long categoryId;
        private AccountingCategoryType categoryType;
        private String code;
        private String name;
    }
}
