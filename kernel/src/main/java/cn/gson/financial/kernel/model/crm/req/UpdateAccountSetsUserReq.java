package cn.gson.financial.kernel.model.crm.req;

import lombok.Data;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * @Author zhb
 * @Date 2025-11-03
 * @Description
 */
@Data
public class UpdateAccountSetsUserReq {

    /**
     * 现用户手机号
     */
    private String mobile;

    /**
     * 前用户手机号
     */
    private String preMobile;

    /**
     * 税号
     */
    private String creditCode;
}