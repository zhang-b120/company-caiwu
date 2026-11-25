package cn.gson.financial.controller;

import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.model.entity.AccountSets;
import cn.gson.financial.kernel.service.UserService;
import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * 期初数据导入控制器
 * 提供Excel文件解析和数据导入功能
 */
@Slf4j
@RestController
@RequestMapping("/initial-data-import")
@CrossOrigin(origins = "*")
public class InitialDataImportController {

    @Autowired
    private UserService userService;

    /**
     * 文件存储路径（从配置文件读取，如果没有配置则使用默认值）
     */
    @Value("${file.upload.path.oldAccount:E:\\qykj\\file\\oldAccount}")
    private String fileUploadPath;

    /**
     * 文件访问基础URL（从配置文件读取）
     */
    @Value("${file.access.baseUrl:http://localhost:8080/}")
    private String fileAccessBaseUrl;

    /**
     * 目标财务系统地址（从配置文件读取）
     */
    @Value("${target.system.baseUrl:http://localhost:8012}")
    private String targetSystemBaseUrl;

    /**
     * 解析Excel文件
     * 接收文件上传，保存到临时目录，然后调用外部财务系统接口进行解析
     *
     * @param file Excel文件
     * @param taxNumber 公司税号
     * @return 解析后的科目数据列表
     */
    @PostMapping("/parseExcelFile")
    public JsonResult parseExcelFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam("taxNumber") String taxNumber) {

        log.info("开始解析Excel文件，税号：{}，文件名：{}", taxNumber, file.getOriginalFilename());

        // 1. 文件验证
        if (file == null || file.isEmpty()) {
            return JsonResult.failure("文件不能为空");
        }

        String fileName = file.getOriginalFilename();
        if (fileName == null || (!fileName.endsWith(".xlsx") && !fileName.endsWith(".xls"))) {
            return JsonResult.failure("仅支持Excel文件格式(.xlsx, .xls)");
        }

        // 2. 保存文件到临时目录
        String tempFilePath = null;
        try {
            tempFilePath = saveTempFile(file);
            log.info("文件已保存到临时目录：{}", tempFilePath);

            // 3. 调用外部财务系统解析接口
            Map<String, Object> params = new HashMap<>();
            params.put("taxNumber", taxNumber);
            params.put("fileName", fileName);

            // 注意：根据设计文档，外部系统需要根据fileName查找文件
            // 这里我们需要将文件保存到外部系统可以访问的位置，或者直接传递文件路径
            // 如果外部系统在同一服务器上，可以直接传递文件路径
            params.put("filePath", tempFilePath);

            String url = targetSystemBaseUrl + "/account/complete";
            log.info("调用外部财务系统接口：{}", url);

            // 使用HttpRequest调用外部接口
            HttpResponse response = HttpRequest.post(url)
                    .header("Content-Type", "application/json")
                    .body(JSON.toJSONString(params))
                    .timeout(30000) // 30秒超时
                    .execute();

            if (response.isOk()) {
                String resultJson = response.body();
                JSONObject result = JSON.parseObject(resultJson);

                // 检查响应格式
                if (result.containsKey("code") && result.getInteger("code") == 200) {
                    JSONArray dataArray = result.getJSONArray("data");
                    if (dataArray != null) {
                        List<Map<String, Object>> dataList = new ArrayList<>();
                        for (int i = 0; i < dataArray.size(); i++) {
                            JSONObject item = dataArray.getJSONObject(i);
                            Map<String, Object> map = new HashMap<>();
                            map.put("code", item.getString("code"));
                            map.put("name", item.getString("name"));
                            map.put("category", item.getString("category"));
                            map.put("balanceDirection", item.getString("balanceDirection"));
                            map.put("oldCode", item.getString("oldCode"));
                            map.put("oldName", item.getString("oldName"));
                            map.put("parentCode", item.getString("parentCode"));
                            map.put("parentName", item.getString("parentName"));
                            dataList.add(map);
                        }
                        log.info("解析成功，共 {} 条数据", dataList.size());
                        return JsonResult.successful(dataList);
                    }
                } else if (result.containsKey("success") && result.getBoolean("success")) {
                    // 兼容另一种响应格式
                    JSONArray dataArray = result.getJSONArray("data");
                    if (dataArray != null) {
                        List<Map<String, Object>> dataList = new ArrayList<>();
                        for (int i = 0; i < dataArray.size(); i++) {
                            JSONObject item = dataArray.getJSONObject(i);
                            Map<String, Object> map = new HashMap<>();
                            map.put("code", item.getString("code"));
                            map.put("name", item.getString("name"));
                            map.put("category", item.getString("category"));
                            map.put("balanceDirection", item.getString("balanceDirection"));
                            map.put("oldCode", item.getString("oldCode"));
                            map.put("oldName", item.getString("oldName"));
                            map.put("parentCode", item.getString("parentCode"));
                            map.put("parentName", item.getString("parentName"));
                            dataList.add(map);
                        }
                        log.info("解析成功，共 {} 条数据", dataList.size());
                        return JsonResult.successful(dataList);
                    }
                }

                String errorMsg = result.getString("message") != null 
                    ? result.getString("message") 
                    : "解析失败";
                log.error("解析失败：{}", errorMsg);
                return JsonResult.failure(errorMsg);
            } else {
                log.error("调用外部接口失败，状态码：{}", response.getStatus());
                return JsonResult.failure("调用外部财务系统接口失败");
            }
        } catch (Exception e) {
            log.error("解析Excel文件失败", e);
            return JsonResult.failure("解析失败：" + e.getMessage());
        } finally {
            // 清理临时文件（可选，也可以保留用于后续导入）
            // deleteTempFile(tempFilePath);
        }
    }

    /**
     * 导入财务数据
     * 接收文件上传，保存到服务器，然后调用外部财务系统接口进行导入
     *
     * @param file Excel文件
     * @param taxNumber 公司税号
     * @return 导入结果
     */
    @PostMapping("/importFinancialData")
    public JsonResult importFinancialData(
            @RequestParam("file") MultipartFile file,
            @RequestParam("taxNumber") String taxNumber) {

        log.info("开始导入财务数据，税号：{}，文件名：{}", taxNumber, file.getOriginalFilename());

        // 1. 文件验证
        if (file == null || file.isEmpty()) {
            return JsonResult.failure("文件不能为空");
        }

        String originalFileName = file.getOriginalFilename();
        String saveFileName = null;
        String filePath = null;

        try {
            // 2. 保存文件到服务器
            saveFileName = System.currentTimeMillis() + "." + getFileSuffix(originalFileName);
            File uploadDir = new File(fileUploadPath);
            if (!uploadDir.exists()) {
                uploadDir.mkdirs();
            }

            File saveFile = new File(uploadDir, saveFileName);
            file.transferTo(saveFile);

            // 构建文件访问URL
            filePath = fileAccessBaseUrl + "common/file/oldAccount/" + saveFileName;
            log.info("文件已保存，访问路径：{}", filePath);

            // 3. 调用外部财务系统导入接口
            Map<String, String> params = new HashMap<>();
            params.put("taxNumber", taxNumber);
            params.put("filePath", filePath);
            params.put("oldFile", originalFileName);

            String url = targetSystemBaseUrl + "/account/importFinancialInit";
            log.info("调用外部财务系统导入接口：{}", url);

            HttpResponse response = HttpRequest.post(url)
                    .header("Content-Type", "application/json")
                    .body(JSON.toJSONString(params))
                    .timeout(60000) // 60秒超时（导入可能需要更长时间）
                    .execute();

            if (response.isOk()) {
                String resultJson = response.body();
                JSONObject result = JSON.parseObject(resultJson);

                // 检查响应格式
                if (result.containsKey("code") && result.getInteger("code") == 200) {
                    log.info("导入成功");
                    return JsonResult.successful("导入成功");
                } else if (result.containsKey("success") && result.getBoolean("success")) {
                    log.info("导入成功");
                    return JsonResult.successful("导入成功");
                }

                String errorMsg = result.getString("message") != null 
                    ? result.getString("message") 
                    : "导入失败";
                log.error("导入失败：{}", errorMsg);
                return JsonResult.failure(errorMsg);
            } else {
                log.error("调用外部接口失败，状态码：{}", response.getStatus());
                return JsonResult.failure("调用外部财务系统接口失败");
            }
        } catch (Exception e) {
            log.error("导入财务数据失败", e);
            return JsonResult.failure("导入失败：" + e.getMessage());
        }
    }

    /**
     * 保存文件到临时目录
     */
    private String saveTempFile(MultipartFile file) throws IOException {
        String tempDir = System.getProperty("java.io.tmpdir");
        String tempFileName = System.currentTimeMillis() + "_" + file.getOriginalFilename();
        String tempFilePath = tempDir + File.separator + tempFileName;

        File tempFile = new File(tempFilePath);
        file.transferTo(tempFile);

        return tempFilePath;
    }

    /**
     * 获取文件后缀
     */
    private String getFileSuffix(String fileName) {
        if (StrUtil.isBlank(fileName)) {
            return "";
        }
        int lastDotIndex = fileName.lastIndexOf(".");
        if (lastDotIndex > 0 && lastDotIndex < fileName.length() - 1) {
            return fileName.substring(lastDotIndex + 1);
        }
        return "";
    }

    /**
     * 删除临时文件
     */
    private void deleteTempFile(String filePath) {
        if (StrUtil.isNotBlank(filePath)) {
            try {
                File file = new File(filePath);
                if (file.exists()) {
                    file.delete();
                    log.info("临时文件已删除：{}", filePath);
                }
            } catch (Exception e) {
                log.warn("删除临时文件失败：{}", filePath, e);
            }
        }
    }
}

