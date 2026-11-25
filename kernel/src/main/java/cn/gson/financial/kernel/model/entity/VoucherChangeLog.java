package cn.gson.financial.kernel.model.entity;

import cn.gson.financial.kernel.model.dto.VoucherDto;
import cn.gson.financial.kernel.model.hibernate.AbsEntity;
import jakarta.persistence.Entity;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.DynamicInsert;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/**
 * 
 * <p><b>Copyright © 2010-2024开发公司All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : 凭证变更记录</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2024年10月20日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@Entity
@Getter
@Setter
@DynamicUpdate
@DynamicInsert
public class VoucherChangeLog extends AbsEntity {

    @Comment("凭证ID")
    private Long voucherId;

    @Comment("修改人")
    private Long updateMember;

    @Comment("修改时间")
    private LocalDateTime updateDate;

    @Comment("凭证明细")
    @JdbcTypeCode(SqlTypes.JSON)
    private VoucherDto voucher;
}
