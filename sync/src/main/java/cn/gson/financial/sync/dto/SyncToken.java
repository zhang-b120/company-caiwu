package cn.gson.financial.sync.dto;

import com.alibaba.fastjson2.JSONObject;
import lombok.Builder;
import lombok.Getter;

/**
 * 
 * <p><b>Copyright © 2010-2023 开发公司 All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.kernel.sync</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2023年08月01日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@Builder
@Getter
public class SyncToken {
    private String cookie;
    private String token;
    private String asToken;
    private JSONObject result;

    public void setAsToken(String asToken) {
        this.asToken = asToken;
    }

    public void setResult(JSONObject result) {
        this.result = result;
    }

    public void setCookie(String cookie) {
        this.cookie = cookie;
    }
}
