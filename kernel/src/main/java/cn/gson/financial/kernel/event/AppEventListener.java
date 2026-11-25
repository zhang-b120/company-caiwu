package cn.gson.financial.kernel.event;

import cn.gson.financial.kernel.service.VoucherService;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 
 * <p><b>Copyright © 2010-2024 开发公司 All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.kernel.event</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2024年04月18日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@Log4j2
@Component
@RequiredArgsConstructor
public class AppEventListener {

    private final VoucherService voucherService;

    @EventListener
    public void subjectChangeListener(SubjectChangeEvent event) {
        voucherService.cashFlowTag(event.getSubject().getAccountSetsId());
        if (event.getCallBack() != null) {
            event.getCallBack().run();
        }
    }
}
