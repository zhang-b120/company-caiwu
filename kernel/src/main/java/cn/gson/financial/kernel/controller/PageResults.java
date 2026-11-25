package cn.gson.financial.kernel.controller;

import com.blazebit.persistence.PagedList;
import lombok.Getter;
import lombok.Setter;

import java.util.Collection;

/**
 * 
 * <p><b>Copyright © 2010-2019开发公司All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : com.chcm.checkout.common</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2019年12月23日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@Getter
@Setter
public class PageResults<T> extends Page {

    private int totalPage;

    private long total;

    private Collection<T> results;

    private Object data;

    public PageResults(int page, Collection<T> results, long total, int pageSize) {
        this.setPage(page);
        this.results = results;
        this.setTotal(total);
        this.setPageSize(pageSize);
        this.setTotalPage((int) Math.ceil(total / (double) pageSize));
    }

    public PageResults(Collection<T> results, Page page, long total) {
        this.setPage(page.getPage());
        this.results = results;
        this.setTotal(total);
        this.setPageSize(page.getPageSize());
        this.setTotalPage((int) Math.ceil(total / (double) page.getPageSize()));
    }

    public PageResults(PagedList<T> pagedList, Page page) {
        this.results = pagedList;
        this.setPage(page.getPage());
        this.setTotal(pagedList.getTotalSize());
        this.setTotalPage(pagedList.getTotalPages());
        this.setPageSize(page.getPageSize());
    }

    public PageResults setData(Object data) {
        this.data = data;
        return this;
    }

    public PageResults setResults(Collection<T> results) {
        this.results = results;
        return this;
    }

    public PageResults<T> setResults(Collection<T> results, long total) {
        this.results = results;
        this.setTotal(total);
        this.setTotalPage((int) Math.ceil(total / (double) this.getPageSize()));
        return this;
    }

    public boolean isFirst() {
        return this.getPage() == 0;
    }

    /**
     * @return 是否最后一页
     */
    public boolean isLast() {
        return this.getPage() >= (this.getTotalPage());
    }
}
