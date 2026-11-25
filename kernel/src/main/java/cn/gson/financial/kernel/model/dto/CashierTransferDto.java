package cn.gson.financial.kernel.model.dto;

import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A DTO for the {@link cn.gson.financial.kernel.model.entity.CashierTransfer} entity
 */
@Getter
@Setter
public class CashierTransferDto implements Serializable {
    private Long id;
    private Long accountSetsId;
    private LocalDate transferDate;
    private String digest;
    private Long inAccountId;
    private String inAccount;
    private Long outAccountId;
    private String outAccount;
    private Double amount;
    private String remark;
    private Long voucherId;
    private String voucherTitle;
    private LocalDateTime createTime;
}
