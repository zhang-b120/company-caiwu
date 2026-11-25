package cn.gson.financial.kernel.model.enums;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 
 * <p><b>Copyright © 2010-2023 开发公司 All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : 会计准则</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2023年02月19日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
public enum AccountStandard {
    SMALL_BUSINESS("小企业会计准则"),
    BUSINESS("企业会计准则"),
    BUSINESS20191("企业会计准则(2019年未执行新金融准则、新收入准则和新租凭准则)"),
    BUSINESS20192("企业会计准则(2019年已执行新金融准则、新收入准则和新租赁准则)"),
    NONGOVERNMENTAL("民间非营利组织会计制度"),
    FARMER("农民专业合作社财务会计制度");

    public final String display;

    AccountStandard(String display) {
        this.display = display;
    }

    public static List<Map<String, String>> dict() {
        List<Map<String, String>> list = new ArrayList<>();
        for (AccountStandard accountStandard : AccountStandard.values()) {
            Map<String, String> map = new HashMap<>();
            map.put("title", accountStandard.display);
            map.put("key", accountStandard.name());
            list.add(map);
        }
        return list;
    }
}
