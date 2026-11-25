package cn.gson.financial.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * FN-08 报表异步导出线程池配置
 *
 * <p>给报表导出（资产负债表 / 利润表 / 现金流量表）走异步，避免在 1000+ 家会计同时点导出时
 * 把 Tomcat 的 web 线程池打满。
 *
 * <p>线程池策略：
 * <ul>
 *   <li>core = 10：常驻线程数（峰值少的时候保留这么多）</li>
 *   <li>max = 30：突发能扩到 30 线程（10 + 20 弹性）</li>
 *   <li>queue = 200：排队 200 后才扩 max</li>
 *   <li>拒绝策略 = CallerRunsPolicy：满了 fallback 到调用者线程（HTTP 线程）。
 *       这样不丢任务，最坏情况是 submit 退化为同步（用户多等几秒），但绝不会丢导出请求。</li>
 *   <li>keepAlive = 60s：max 弹性线程闲置 60 秒后回收</li>
 * </ul>
 *
 * <p>注意：默认 SimpleAsyncTaskExecutor 不复用线程也无 bounded queue，
 * 1000 家并发瞬间会创建 1000+ 线程把 JVM 拖垮 —— 必须用 ThreadPoolTaskExecutor。
 */
@Slf4j
@Configuration
public class ReportExportAsyncConfig {

    public static final String EXECUTOR_NAME = "reportExportExecutor";

    @Bean(name = EXECUTOR_NAME)
    public Executor reportExportExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(10);
        executor.setMaxPoolSize(30);
        executor.setQueueCapacity(200);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("report-export-");
        executor.setAllowCoreThreadTimeOut(false);
        // CallerRunsPolicy：队满 fallback 到调用者线程，不丢任务
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        log.info("[FN-08] reportExportExecutor initialized: core=10 / max=30 / queue=200 / rejection=CallerRunsPolicy");
        return executor;
    }
}
