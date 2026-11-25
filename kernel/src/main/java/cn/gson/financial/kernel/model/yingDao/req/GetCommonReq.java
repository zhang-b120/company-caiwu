package cn.gson.financial.kernel.model.yingDao.req;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * @Author zhb
 * @Date 2025-09-01
 * @Description
 */
@Data
public class GetCommonReq implements Serializable {

    @Serial
    private static final long serialVersionUID = 5585004637306946399L;
    /**
     * 账套ID
     */
    private Long accountSetsId;

    /**
     * 统一社会信用代码（税号）。与 accountSetsId 二选一，二者都传时以 accountSetsId 为准。
     */
    private String creditCode;

    /**
     * 账期 可为空 YYYYMMDD
     */
    private String period;
}