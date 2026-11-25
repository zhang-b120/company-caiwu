package cn.gson.financial.kernel.model.enums;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 
 * <p><b>Copyright © 2010-2023 开发公司 All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : 增值税种类</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2023年02月19日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
public enum VatType {
    SMALL_SCALE("小规模纳税人"), COMMONLY("一般纳税人");
    public final String display;

    VatType(String display) {
        this.display = display;
    }

    public static List<Map<String, String>> dict() {
        List<Map<String, String>> list = new ArrayList<>();
        for (VatType vatType : VatType.values()) {
            Map<String, String> map = new HashMap<>();
            map.put("title", vatType.display);
            map.put("key", vatType.name());
            list.add(map);
        }
        return list;
    }
}
