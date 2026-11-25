package cn.gson.financial.kernel.common;

import cn.gson.financial.kernel.model.dto.InvoiceDto;
import cn.gson.financial.kernel.model.dto.VoucherDetailsDto;
import cn.gson.financial.kernel.model.dto.VoucherDto;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;

import java.time.LocalDate;
import java.util.*;

/**
 * @Author zhb
 * @Date 2025-09-28
 * @Description 发票转凭证工具类
 */
public class InvoiceVoucherUtils {

    /**
     * 外部接口URL
     */
    private static final String EXTERNAL_API_URL = "http://192.168.31.2:5000/generate";

    /**
     * 生成凭证
     * @param invoiceDto
     * @param companyName
     * @return 凭证数据列表
     */
    public static JSONObject generateVoucher(InvoiceDto invoiceDto, String companyName, Integer voucherCode) {
        if (ObjectUtils.isEmpty(invoiceDto) || StringUtils.isEmpty(companyName)) {
            return null;
        }
        String response = httpClient(buildRequest(invoiceDto, companyName));
        if(StringUtils.isEmpty(response)){
            return null;
        }
        return JSONObject.parseObject(response)
                .getJSONObject("data").getJSONObject("voucher").getJSONObject("raw_text");
    }

    /**
     * 构建请求参数
     * @param invoiceDto
     * @param companyName
     * @return 请求DTO
     */
    public static String buildRequest(InvoiceDto invoiceDto, String companyName) {
        Map<String, Object> map = new HashMap<>();
        map.put("发票号码", invoiceDto.getInvoiceNumber());
        map.put("发票日期", invoiceDto.getAssignmentDate());
        map.put("发票类型", invoiceDto.getNewCategory());
        map.put("公司名称", companyName);
        map.put("供应商/客户", invoiceDto.getCustomerName());
        String type = null; // 默认进项
        if ("IN".equals(invoiceDto.getType().name())) {
            type = "进项";
        } else if ("OUT".equals(invoiceDto.getType().name())) {
            type = "销项";
        }
        map.put("发票方向", type);
        List<Map<String, Object>> itemList = invoiceDto.getItems().stream().map(item -> {
            Map<String, Object> itemMap = new HashMap<>();
            itemMap.put("商品名称", item.getGoodsName());
            itemMap.put("金额", item.getAmount().subtract(item.getTax()));
            itemMap.put("税额", item.getTax());
            itemMap.put("价税合计", item.getAmount());
            return itemMap;
        }).toList();
        map.put("商品列表", itemList);
        JSONObject jsonObject = new JSONObject(map);
        return jsonObject.toString();
    }

    /**
     * 调用外部接口
     * @param request 请求参数
     * @return 响应结果
     */
    private static String httpClient(String request) {
        HttpResponse response = HttpRequest.post(EXTERNAL_API_URL)
                .header("Content-Type", "application/json")
                .body(request)
                .timeout(30000) // 30秒超时
                .execute();
        if (response.isOk()) {
            String responseBody = response.body();
            return responseBody;
        } else {
            System.out.println("接口调用失败，状态码: " + response.getStatus());
            return null;
        }
    }
}