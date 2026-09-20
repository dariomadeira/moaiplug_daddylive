package com.infomak.moai.contract;

import java.util.Map;
import java.util.Objects;

public class ResolveResult {
    private final String url;
    private final Map<String, String> headers;
    private final DrmInfo drm;
    private final String format;
    private final long ttlMs;

    public ResolveResult(
        String url,
        Map<String, String> headers,
        DrmInfo drm,
        String format,
        long ttlMs) {
        this.url = url;
        this.headers = headers;
        this.drm = drm;
        this.format = format;
        this.ttlMs = ttlMs;
    }

    public String getUrl() {
        return url;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    public DrmInfo getDrm() {
        return drm;
    }

    public String getFormat() {
        return format;
    }

    public long getTtlMs() {
        return ttlMs;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ResolveResult)) return false;
        ResolveResult that = (ResolveResult) o;
        return ttlMs == that.ttlMs
            && Objects.equals(url, that.url)
            && Objects.equals(headers, that.headers)
            && Objects.equals(drm, that.drm)
            && Objects.equals(format, that.format);
    }

    @Override
    public int hashCode() {
        return Objects.hash(url, headers, drm, format, ttlMs);
    }

    @Override
    public String toString() {
        return "ResolveResult(url=" + url + ", headers=" + headers + ", drm=" + drm
            + ", format=" + format + ", ttlMs=" + ttlMs + ")";
    }
}