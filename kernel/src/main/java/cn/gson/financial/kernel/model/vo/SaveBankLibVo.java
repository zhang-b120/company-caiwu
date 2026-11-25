package cn.gson.financial.kernel.model.vo;

import jakarta.persistence.Column;
import lombok.Data;
import org.hibernate.annotations.Comment;

/**
 * @Author zhb
 * @Date 2025-09-28
 * @Description
 */
@Data
public class SaveBankLibVo {

    /**
     * 银行编码
     */
    private String bankCode;

    /**
     * 银行名称
     */
    private String bankName;

}