package cn.gson.financial;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 
 * <p><b>Copyright © 2010-2019开发公司All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2019年07月19日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@EnableAspectJAutoProxy
@SpringBootApplication
@EnableAsync
@EnableScheduling
public class FinancialApplication {

    public static void main(String[] args) {
        SpringApplication.run(FinancialApplication.class, args);
    }
}
