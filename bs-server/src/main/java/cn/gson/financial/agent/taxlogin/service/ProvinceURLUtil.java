package cn.gson.financial.agent.taxlogin.service;

import java.util.HashMap;
import java.util.Map;

/**
 * 省份URL工具类
 * 用于管理各省份的税务网站URL
 *
 * @author RPA团队
 * @since 1.0.0
 */
public class ProvinceURLUtil {
    public static Map<String, String> proviceDict = new HashMap<String, String>();
    static {
        // 原有的省份URL（使用etax）
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
        proviceDict.put("ningbo", "https://etax.ningbo.chinatax.gov.cn:8443/");
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

        // 新增的fapiao版本URL（使用dppt）
        proviceDict.put("beijingfapiao", "https://dppt.beijing.chinatax.gov.cn:8443/");
        proviceDict.put("anhuifapiao", "https://dppt.anhui.chinatax.gov.cn:8443/");
        proviceDict.put("chongqingfapiao", "https://dppt.chongqing.chinatax.gov.cn:8443/");
        proviceDict.put("fujianfapiao", "https://dppt.fujian.chinatax.gov.cn:8443/");
        proviceDict.put("gansufapiao", "https://dppt.gansu.chinatax.gov.cn:8443/");
        proviceDict.put("guangdongfapiao", "https://dppt.guangdong.chinatax.gov.cn:8443/");
        proviceDict.put("guangxifapiao", "https://dppt.guangxi.chinatax.gov.cn:8443/");
        proviceDict.put("guizhoufapiao", "https://dppt.guizhou.chinatax.gov.cn:8443/");
        proviceDict.put("hainanfapiao", "https://dppt.hainan.chinatax.gov.cn:8443/");
        proviceDict.put("hebeifapiao", "https://dppt.hebei.chinatax.gov.cn:8443/");
        proviceDict.put("heilongjiangfapiao", "https://dppt.heilongjiang.chinatax.gov.cn:8443/");
        proviceDict.put("henanfapiao", "https://dppt.henan.chinatax.gov.cn:8443/");
        proviceDict.put("hubeifapiao", "https://dppt.hubei.chinatax.gov.cn:8443/");
        proviceDict.put("hunanfapiao", "https://dppt.hunan.chinatax.gov.cn:8443/");
        proviceDict.put("jiangsufapiao", "https://dppt.jiangsu.chinatax.gov.cn:8443/");
        proviceDict.put("jiangxifapiao", "https://dppt.jiangxi.chinatax.gov.cn:8443/");
        proviceDict.put("jilinfapiao", "https://dppt.jilin.chinatax.gov.cn:8443/");
        proviceDict.put("liaoningfapiao", "https://dppt.liaoning.chinatax.gov.cn:8443/");
        proviceDict.put("neimenggufapiao", "https://dppt.neimenggu.chinatax.gov.cn:8443/");
        proviceDict.put("ningbofapiao", "https://dppt.ningbo.chinatax.gov.cn:8443/");
        proviceDict.put("ningxiafapiao", "https://dppt.ningxia.chinatax.gov.cn:8443/");
        proviceDict.put("qinghaifapiao", "https://dppt.qinghai.chinatax.gov.cn:8443/");
        proviceDict.put("shandongfapiao", "https://dppt.shandong.chinatax.gov.cn:8443/");
        proviceDict.put("shanghaifapiao", "https://dppt.shanghai.chinatax.gov.cn:8443/");
        proviceDict.put("shanxifapiao", "https://dppt.shanxi.chinatax.gov.cn:8443/");
        proviceDict.put("shaanxifapiao", "https://dppt.shaanxi.chinatax.gov.cn:8443/");
        proviceDict.put("sichuanfapiao", "https://dppt.sichuan.chinatax.gov.cn:8443/");
        proviceDict.put("tianjinfapiao", "https://dppt.tianjin.chinatax.gov.cn:8443/");
        proviceDict.put("xinjiangfapiao", "https://dppt.xinjiang.chinatax.gov.cn:8443/");
        proviceDict.put("xizangfapiao", "https://dppt.xizang.chinatax.gov.cn:8443/");
        proviceDict.put("yunnanfapiao", "https://dppt.yunnan.chinatax.gov.cn:8443/");
        proviceDict.put("zhejiangfapiao", "https://dppt.zhejiang.chinatax.gov.cn:8443/");
        proviceDict.put("qingdaofapiao", "https://dppt.qingdao.chinatax.gov.cn:8443/");
        proviceDict.put("dalianfapiao", "https://dppt.dalian.chinatax.gov.cn:8443/");
        proviceDict.put("shenzhenfapiao", "https://dppt.shenzhen.chinatax.gov.cn:8443/");
        proviceDict.put("xiamenfapiao", "https://dppt.xiamen.chinatax.gov.cn:8443/");
    }
    public static String getUrl(String provice){
        return proviceDict.get(provice);
    }
}
