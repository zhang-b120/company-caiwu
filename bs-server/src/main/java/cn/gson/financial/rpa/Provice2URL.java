package cn.gson.financial.rpa;

import java.util.HashMap;
import java.util.Map;

public class Provice2URL {
    public static Map<String, String> proviceDict = new HashMap<String, String>();
    static {
        proviceDict.put("beijing", "https://etax.beijing.chinatax.gov.cn:8443/");
        proviceDict.put("anhui", "https://etax.anhui.chinatax.gov.cn:8443/");
        proviceDict.put("chongqing", "https://etax.chongqing.chinatax.gov.cn:8443/");
        proviceDict.put("fujian", "https://etax.fujian.chinatax.gov.cn:8443/");
        proviceDict.put("gansu", "https://etax.gansu.chinatax.gov.cn:8443/");
        proviceDict.put("guangdong", "https://etax.guangdong.chinatax.gov.cn:8443/");
        proviceDict.put("guangxi", "https://etax.guangxi.chinatax.gov.cn:8443/");
        proviceDict.put("guizhou", "https://etax.guizhou.chinatax.gov.cn:8443/");
        proviceDict.put("hainan", "https://etax.hainan.chinatax.gov.cn:8443/");
        proviceDict.put("hebei", "https://etax.hebei.chinatax.gov.cn:8443/");
        proviceDict.put("heilongjiang", "https://etax.heilongjiang.chinatax.gov.cn:8443/");
        proviceDict.put("henan", "https://etax.henan.chinatax.gov.cn:8443/");
        proviceDict.put("hubei", "https://etax.hubei.chinatax.gov.cn:8443/");
        proviceDict.put("hunan", "https://etax.hunan.chinatax.gov.cn:8443/");
        proviceDict.put("jiangsu", "https://etax.jiangsu.chinatax.gov.cn:8443/");
        proviceDict.put("jiangxi", "https://etax.jiangxi.chinatax.gov.cn:8443/");
        proviceDict.put("jilin", "https://etax.jilin.chinatax.gov.cn:8443/");
        proviceDict.put("liaoning", "https://etax.liaoning.chinatax.gov.cn:8443/");
        proviceDict.put("neimenggu", "https://etax.neimenggu.chinatax.gov.cn:8443/");
        proviceDict.put("ningxia", "https://etax.ningxia.chinatax.gov.cn:8443/");
        proviceDict.put("qinghai", "https://etax.qinghai.chinatax.gov.cn:8443/");
        proviceDict.put("shandong", "https://etax.shandong.chinatax.gov.cn:8443/");
        proviceDict.put("shanghai", "https://etax.shanghai.chinatax.gov.cn:8443/");
        proviceDict.put("shanxi", "https://etax.shanxi.chinatax.gov.cn:8443/");
        proviceDict.put("shaanxi", "https://etax.shaanxi.chinatax.gov.cn/");
        proviceDict.put("sichuan", "https://etax.sichuan.chinatax.gov.cn/");
        proviceDict.put("tianjin", "https://etax.tianjin.chinatax.gov.cn:8443/");
        proviceDict.put("xinjiang", "https://etax.xinjiang.chinatax.gov.cn:8443/");
        proviceDict.put("xizang", "https://etax.xizang.chinatax.gov.cn:5100/");
        proviceDict.put("yunnan", "https://etax.yunnan.chinatax.gov.cn/");
        proviceDict.put("zhejiang", "https://etax.zhejiang.chinatax.gov.cn:8443/");
        proviceDict.put("qingdao", "https://etax.qingdao.chinatax.gov.cn:8443/");
        proviceDict.put("dalian", "https://etax.dalian.chinatax.gov.cn:8443/");
        proviceDict.put("shenzhen", "https://etax.shenzhen.chinatax.gov.cn:8443/");
        proviceDict.put("xiamen", "https://etax.xiamen.chinatax.gov.cn:8443/");
        proviceDict.put("ningbo", "https://etax.ningbo.chinatax.gov.cn:8443/");
    }
    public static String getUrl(String provice){
        return proviceDict.get(provice);
    }
}
