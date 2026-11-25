package cn.gson.financial.kernel.model.ua.request;
import lombok.Data;

import java.time.LocalDate;

import java.io.Serializable;

/**
 * @Author zhb
 * @Date 2025-05-19
 * @Description
 */
@Data
public class GetCommonReq implements Serializable {

    private static final long serialVersionUID = 2784587295256161318L;
    /**
     * 账套ID
     */
    private Long accountSetsId;

    /**
     * 账期 可为空 不为空时格式必须为"YYYY-MM-DD"
     */
    private LocalDate period;
}
