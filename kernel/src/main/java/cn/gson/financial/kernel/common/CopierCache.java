package cn.gson.financial.kernel.common;/* Copyright © 2020 Yuech and/or its affiliates. All rights reserved. */

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * @Author zhb
 * @Date 2025-05-15
 * @Description
 */
public class CopierCache<K, V> extends LinkedHashMap<K, V> {

    private int cacheSize;
    public CopierCache(int cacheSize) {
        super(16, (float) 0.75, true);
        this.cacheSize = cacheSize;
    }

    @Override
    protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
        return size() > cacheSize;
    }

    @Override
    public int hashCode() {
        return super.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        return super.equals(obj);
    }
}