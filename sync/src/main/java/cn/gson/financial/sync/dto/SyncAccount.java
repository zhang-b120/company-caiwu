package cn.gson.financial.sync.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

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
@Getter
@Setter
@NoArgsConstructor
public class SyncAccount {
    private String username;
    private String password;
    private String code;
    private String cookie;
}
