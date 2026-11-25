package cn.gson.financial.kernel.model.dto;

import cn.gson.financial.kernel.model.hibernate.FileInfo;
import cn.hutool.core.collection.CollUtil;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * A DTO for the {@link cn.gson.financial.kernel.model.entity.Voucher} entity
 */
@Getter
@Setter
@ToString
public class VoucherDto implements Serializable {
    private Long id;
    private String word;
    private Integer code;
    private String remark;
    private String sourceRef;
    private Integer receiptNum;
    private Long createMember;
    private String createMemberName;
    private String createMemberMobile;
    private LocalDateTime createDate;
    private BigDecimal debitAmount;
    private BigDecimal creditAmount;
    private Long accountSetsId;
    private Integer year;
    private Integer month;
    private LocalDate voucherDate;
    private Long auditMemberId;
    private Long lastUpdateMember;
    private LocalDateTime lastUpdateDate;
    private Long invoiceId;
    private List<Long> invoiceIds;
    private Long journalId;
    private List<Long> journalIds;
    private Long cashierTransferId;
    private List<Long> cashierTransferIds;
    private String auditMemberName;
    private String lastUpdateMemberName;
    private LocalDateTime auditDate;
    private Boolean carryForward;
    private List<FileInfo> billList;
    private List<VoucherDetailsDto> details = new ArrayList<>(0);
    private LocalDate yearMonth;
    private Boolean isTemplate;
    private String templateName;
    private Boolean locked;
    private Boolean insert = false;
    private Long checkTplId;
    private Long reverseVoucherId;
    private Long cashierId;
    private String cashierName;

    public List<Long> getCashierTransferIds() {
        if (CollUtil.isNotEmpty(cashierTransferIds)) {
            return cashierTransferIds;
        }
        return cashierTransferId != null ? CollUtil.newArrayList(cashierTransferId) : null;
    }

    public List<Long> getJournalIds() {
        if (CollUtil.isNotEmpty(journalIds)) {
            return journalIds;
        }
        return journalId != null ? CollUtil.newArrayList(journalId) : null;
    }
}
