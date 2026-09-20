package com.infomak.moai.contract;

import java.util.Objects;

public class ResolveRequest {
    private final String channelId;
    private final int fallbackIndex;

    public ResolveRequest(String channelId, int fallbackIndex) {
        this.channelId = channelId;
        this.fallbackIndex = fallbackIndex;
    }

    public String getChannelId() {
        return channelId;
    }

    public int getFallbackIndex() {
        return fallbackIndex;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ResolveRequest)) return false;
        ResolveRequest that = (ResolveRequest) o;
        return fallbackIndex == that.fallbackIndex
            && Objects.equals(channelId, that.channelId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(channelId, fallbackIndex);
    }

    @Override
    public String toString() {
        return "ResolveRequest(channelId=" + channelId + ", fallbackIndex=" + fallbackIndex + ")";
    }
}