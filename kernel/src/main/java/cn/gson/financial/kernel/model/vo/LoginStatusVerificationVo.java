package cn.gson.financial.kernel.model.vo;

import cn.gson.financial.kernel.model.entity.AccountSets;
import lombok.Data;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 用来向前端传送查出来的省份 组合登录状态和浏览器是否创建
 */
@Data
@Service
public class LoginStatusVerificationVo {
    List<AccountSets> accountSetsList;
    boolean isCreate;
    boolean isLogin;
}
