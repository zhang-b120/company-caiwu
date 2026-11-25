package cn.gson.financial.agent.taxlogin.controller;

import cn.dev33.satoken.annotation.SaIgnore;
import cn.gson.financial.agent.taxlogin.service.ChaoJiYing;
import cn.gson.financial.kernel.controller.JsonResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 超级鹰验证码识别接口
 * 供外部系统通过 HTTP 调用验证码识别能力
 */
@Slf4j
@RestController
@RequestMapping("/captcha")
public class CaptchaController {

    /**
     * 点选验证码识别（两图联合）
     *
     * 请求体（JSON）：
     * {
     *   "tipImageBase64":    "...",   // 提示图 base64（不含 data:image/xxx;base64, 前缀）
     *   "selectImageBase64": "..."    // 选择图 base64（不含 data:image/xxx;base64, 前缀）
     * }
     *
     * 响应 data（JSON）：
     * {
     *   "points": [[x1,y1], [x2,y2], ...]   // 按提示顺序排列的点击坐标（相对选择图左上角，单位像素）
     * }
     */
    @PostMapping("/click/solve")
    @SaIgnore
    public JsonResult solveClickCaptcha(@RequestBody Map<String, String> body) {
        String tipImageBase64 = body.get("tipImageBase64");
        String selectImageBase64 = body.get("selectImageBase64");

        if (tipImageBase64 == null || tipImageBase64.isBlank()) {
            return JsonResult.failure("tipImageBase64 不能为空");
        }
        if (selectImageBase64 == null || selectImageBase64.isBlank()) {
            return JsonResult.failure("selectImageBase64 不能为空");
        }

        // 兼容带前缀的 base64（data:image/xxx;base64,）
        tipImageBase64 = stripBase64Prefix(tipImageBase64);
        selectImageBase64 = stripBase64Prefix(selectImageBase64);

        try {
            List<int[]> points = ChaoJiYing.solveClickCaptchaWithTwoImages(tipImageBase64, selectImageBase64);
            log.info("验证码识别成功，共 {} 个坐标", points.size());
            return JsonResult.successful().data("points", points);
        } catch (Exception e) {
            log.error("验证码识别失败", e);
            return JsonResult.failure("验证码识别失败：" + e.getMessage());
        }
    }

    /**
     * 普通字符验证码识别（数字/字母，codetype=2004）
     *
     * 请求体（JSON）：
     * {
     *   "imageBase64": "..."   // 验证码图片 base64（不含前缀）
     * }
     *
     * 响应 data（JSON）：
     * {
     *   "text": "AB12"   // 识别出的字符串
     * }
     */
    @SaIgnore
    @PostMapping("/common/solve")
    public JsonResult solveCommonCaptcha(@RequestBody Map<String, String> body) {
        String imageBase64 = body.get("imageBase64");

        if (imageBase64 == null || imageBase64.isBlank()) {
            return JsonResult.failure("imageBase64 不能为空");
        }

        imageBase64 = stripBase64Prefix(imageBase64);

        try {
            String resultJson = ChaoJiYing.solveClickCaptchaBase64Common(imageBase64);
            com.fasterxml.jackson.databind.JsonNode root =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(resultJson);

            int errNo = root.path("err_no").asInt(-1);
            if (errNo != 0) {
                String errStr = root.path("err_str").asText("未知错误");
                return JsonResult.failure("超级鹰识别失败：" + errStr);
            }

            String text = root.path("pic_str").asText();
            log.info("普通验证码识别成功：{}", text);
            return JsonResult.successful().data("text", text);
        } catch (Exception e) {
            log.error("普通验证码识别失败", e);
            return JsonResult.failure("普通验证码识别失败：" + e.getMessage());
        }
    }

    /**
     * 去掉 base64 前缀（data:image/xxx;base64,）
     */
    private String stripBase64Prefix(String base64) {
        int commaIndex = base64.indexOf(",");
        return commaIndex >= 0 ? base64.substring(commaIndex + 1) : base64;
    }
}
