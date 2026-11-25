package cn.gson.financial.kernel.controller;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Getter;
import lombok.Setter;

/**
 * 
 * <p><b>Copyright © 2010-2022开发公司All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : com.flyemu.share.controller</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2022年03月08日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@Getter
@Setter
public class Page {

    private int pageSize = 20;

    @JsonIgnore
    private int offset;

    private int size;

    @JsonIgnore
    private int offsetEnd;

    private int page = 1;

    private String property, order;

    public int getOffset() {
        return (page - 1) * pageSize;
    }

    public int getOffsetEnd() {
        return pageSize;
    }

    public Page setSize(int size) {
        this.size = size;
        this.pageSize = size;
        return this;
    }

    public Page setPageSize(int pageSize) {
        this.pageSize = pageSize;
        this.size = pageSize;
        return this;
    }

    public Page setPage(int page) {
        this.page = page;
        return this;
    }
}
