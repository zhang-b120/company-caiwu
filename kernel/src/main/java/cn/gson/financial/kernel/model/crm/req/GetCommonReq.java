package cn.gson.financial.kernel.model.crm.req;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;
import java.util.Map;

/**
 * @Author zhb
 * @Date 2025-06-30
 * @Description
 */
@Data
public class GetCommonReq implements Serializable {

    private static final long serialVersionUID = -6821780541909629863L;
    /**
     * 账套ID
     */
    private Long accountSetsId;

    /**
     * 账期 可为空
     */
    private String period;
}