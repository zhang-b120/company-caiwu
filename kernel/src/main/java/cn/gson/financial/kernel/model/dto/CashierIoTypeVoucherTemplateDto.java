package cn.gson.financial.kernel.model.dto;

import cn.gson.financial.kernel.model.entity.CashierAccount;
import cn.gson.financial.kernel.model.entity.CashierIoType;
import cn.gson.financial.kernel.model.entity.CashierIoTypeVoucherTemplate;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;

/**
 * A DTO for the {@link CashierIoTypeVoucherTemplate} entity
 */
@Getter
@Setter
public class CashierIoTypeVoucherTemplateDto implements Serializable {
    private Long id;
    private Long ioTypeId;
    private String ioTypeName;
    private Long wordId;
    private String word;
    private Long accountSetsId;
    private String subjectTitle;
    private String subjectId;
    private CashierIoType.Type type;
    private CashierAccount.CashierAccountType journalType;
    private CashierIoTypeVoucherTemplate.Direction direction;
}
