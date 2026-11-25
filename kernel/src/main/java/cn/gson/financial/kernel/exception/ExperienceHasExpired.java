package cn.gson.financial.kernel.exception;

/**
 * 
 * <ul style="margin:15px;">
 * <li>Description : financial</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2024年08月26日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
public class ExperienceHasExpired extends RuntimeException {
    public ExperienceHasExpired(String message) {
        super(message);
    }
}
