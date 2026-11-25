package cn.gson.financial.yingDao.Cors;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.List;

/**
 * CORS 配置。
 *
 * <p><b>FN-NEW-CORS-BT (2026-05-12)</b>：为 BT 桌面端（Electron）开放跨 Origin 携带 cookie。
 * <ul>
 *   <li>{@code allowCredentials = true}：BT 用 Electron session 复用 financial 登录 cookie（fv3token），
 *       浏览器/Electron 要求 server 回 {@code Access-Control-Allow-Credentials: true}，否则 CORS 失败</li>
 *   <li>Origin pattern 从 {@code *} 收紧到明确清单，缓解 credentials=true 后扩大的 CSRF 攻击面：
 *       本地开发 (localhost / 127.0.0.1)、内网 192.168.*、Electron scheme (app://, file://)、
 *       chrome-extension、生产域名 *.qingyicloud.com</li>
 *   <li>{@code exposedHeader} 增加 {@code fv3token}：Sa-Token 在某些场景把 token 通过 header 返回，
 *       BT 端 JS 需要能读到</li>
 * </ul>
 *
 * <p>注意：task-workbench 整个 Controller 是 {@code @SaIgnore}，BT 端**不强制**带 cookie；
 * 此处 allowCredentials 只是让"带 cookie 的请求也能通过 CORS"，不带也照样能调。
 */
@Configuration
public class CorsConfig {

    /**
     * 允许的 Origin pattern 清单。
     * <p>用 pattern 而不是具体 origin —— 支持端口动态、Electron 多 scheme。
     * Spring 5+ 支持 {@code addAllowedOriginPattern} 与 {@code allowCredentials=true} 共存。
     */
    static final List<String> ALLOWED_ORIGIN_PATTERNS = List.of(
            // 本地开发（前端 yarn serve / Electron dev）
            "http://localhost:*",
            "http://127.0.0.1:*",
            "https://localhost:*",
            "https://127.0.0.1:*",
            // 内网（office / lan 部署）
            "http://192.168.*.*:*",
            "https://192.168.*.*:*",
            "http://10.*.*.*:*",
            "https://10.*.*.*:*",
            // Electron / 桌面端
            "app://*",         // Electron production scheme
            "file://*",        // file:// 加载本地 HTML
            "null",            // 某些 fetch 在 main 进程的 Origin
            "electron://*",    // 自定义 protocol
            "chrome-extension://*",
            // 生产域名
            "https://www.qingyicloud.com",
            "https://*.qingyicloud.com",
            // 自建服务器（IP 直连，反代后 Tomcat getServerPort 可能拿不到 Host 头端口，
            // 导致同源请求被 CorsFilter 误判为跨源，所以需要把对外 IP 显式列入白名单）
            "http://150.139.141.112:*",
            "https://150.139.141.112:*",
            "http://150.139.141.142:*",
            "https://150.139.141.142:*"
    );

    @Bean
    public CorsFilter corsFilter() {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", buildCorsConfiguration());
        return new CorsFilter(source);
    }

    /** 单测入口：直接拿到 /** 路径的 CorsConfiguration，断言各字段。 */
    public CorsConfiguration buildCorsConfiguration() {
        CorsConfiguration config = new CorsConfiguration();
        for (String p : ALLOWED_ORIGIN_PATTERNS) {
            config.addAllowedOriginPattern(p);
        }
        config.addAllowedMethod("*");
        config.addAllowedHeader("*");
        // 暴露 Content-Disposition（下载文件名）+ fv3token（Sa-Token 可能 header 返回）
        config.addExposedHeader("Content-Disposition");
        config.addExposedHeader("fv3token");
        config.setAllowCredentials(true);
        // preflight 缓存 1 小时，减少 OPTIONS 请求
        config.setMaxAge(3600L);
        return config;
    }
}
