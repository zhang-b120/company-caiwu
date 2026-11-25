package cn.gson.financial.kernel.event;

import cn.gson.financial.kernel.model.entity.Subject;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

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
@Getter
public class SubjectChangeEvent extends ApplicationEvent {
    private Subject subject;
    private Runnable callBack;

    public SubjectChangeEvent(Subject subject, Runnable callBack) {
        super(subject);
        this.subject = subject;
        this.callBack = callBack;
    }

    public static SubjectChangeEvent create(Subject subject) {
        return new SubjectChangeEvent(subject, null);
    }

    public static SubjectChangeEvent create(Subject subject, Runnable callBack) {
        return new SubjectChangeEvent(subject, callBack);
    }
}
