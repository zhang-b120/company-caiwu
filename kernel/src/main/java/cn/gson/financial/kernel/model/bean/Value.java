package cn.gson.financial.kernel.model.bean;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 
 * <p><b>Copyright © 2010-2024 开发公司 All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.kernel.model.bean</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2024年01月04日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@NoArgsConstructor
@AllArgsConstructor
@Data
public class Value implements Serializable {
    private String name;
    private Object value;
}
