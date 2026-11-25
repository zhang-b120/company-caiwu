package cn.gson.financial.kernel.model.dto;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * DTO for {@link cn.gson.financial.kernel.model.entity.VoucherChangeLog}
 */
@Data
public class VoucherChangeLogDto implements Serializable {
    private Long id;
    private Long accountSetsId;
    private Long voucherId;
    private Long updateMember;
    private String updateMemberName;
    private LocalDateTime updateDate;
    private VoucherDto voucher;
}