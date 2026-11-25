package cn.gson.financial.kernel.model.enums;

/**
 * 
 * <p><b>Copyright © 2010-2023 开发公司 All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.kernel.model.enums</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2023年11月30日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
public enum AttachmentType {
    voucher("凭证附件"), journal("日记账"), invoice("发票");

    public final String display;

    AttachmentType(String display) {
        this.display = display;
    }

    @Override
    public String toString() {
        return this.display;
    }
}
