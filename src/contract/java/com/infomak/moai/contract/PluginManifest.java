package com.infomak.moai.contract;

import java.util.List;
import java.util.Objects;

public class PluginManifest {
    private final String id;
    private final String nombre;
    private final String version;
    private final int minContrato;
    private final int maxContrato;
    private final List<PluginChannel> canales;
    private final String clase;
    private final String tag;

    public PluginManifest(
        String id,
        String nombre,
        String version,
        int minContrato,
        int maxContrato,
        List<PluginChannel> canales,
        String clase) {
        this(id, nombre, version, minContrato, maxContrato, canales, clase, "");
    }

    public PluginManifest(
        String id,
        String nombre,
        String version,
        int minContrato,
        int maxContrato,
        List<PluginChannel> canales,
        String clase,
        String tag) {
        this.id = id;
        this.nombre = nombre;
        this.version = version;
        this.minContrato = minContrato;
        this.maxContrato = maxContrato;
        this.canales = canales;
        this.clase = clase;
        this.tag = tag;
    }

    public String getTag() {
        return tag;
    }

    public String getId() {
        return id;
    }

    public String getNombre() {
        return nombre;
    }

    public String getVersion() {
        return version;
    }

    public int getMinContrato() {
        return minContrato;
    }

    public int getMaxContrato() {
        return maxContrato;
    }

    public List<PluginChannel> getCanales() {
        return canales;
    }

    public String getClase() {
        return clase;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PluginManifest)) return false;
        PluginManifest that = (PluginManifest) o;
        return minContrato == that.minContrato
            && maxContrato == that.maxContrato
            && Objects.equals(id, that.id)
            && Objects.equals(nombre, that.nombre)
            && Objects.equals(version, that.version)
            && Objects.equals(canales, that.canales)
            && Objects.equals(clase, that.clase);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, nombre, version, minContrato, maxContrato, canales, clase);
    }

    @Override
    public String toString() {
        return "PluginManifest(id=" + id + ", nombre=" + nombre + ", version=" + version
            + ", minContrato=" + minContrato + ", maxContrato=" + maxContrato
            + ", canales=" + canales.size() + ", clase=" + clase + ")";
    }
}