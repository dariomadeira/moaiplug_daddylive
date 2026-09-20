package com.infomak.moai.contract;

import java.util.Objects;

public class DrmInfo {
    private final String tipo;
    private final String licenceUrl;

    public DrmInfo(String tipo, String licenceUrl) {
        this.tipo = tipo;
        this.licenceUrl = licenceUrl;
    }

    public String getTipo() {
        return tipo;
    }

    public String getLicenceUrl() {
        return licenceUrl;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DrmInfo)) return false;
        DrmInfo that = (DrmInfo) o;
        return Objects.equals(tipo, that.tipo) && Objects.equals(licenceUrl, that.licenceUrl);
    }

    @Override
    public int hashCode() {
        return Objects.hash(tipo, licenceUrl);
    }

    @Override
    public String toString() {
        return "DrmInfo(tipo=" + tipo + ", licenceUrl=" + licenceUrl + ")";
    }
}