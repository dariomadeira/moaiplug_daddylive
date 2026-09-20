package com.infomak.moai.contract;

import java.util.Objects;

public class PluginChannel {
    private final String id;
    private final String nombre;
    private final String logo;
    private final String categoria;
    private final String pais;

    public PluginChannel(String id, String nombre, String logo, String categoria, String pais) {
        this.id = id;
        this.nombre = nombre;
        this.logo = logo;
        this.categoria = categoria;
        this.pais = pais;
    }

    public String getId() {
        return id;
    }

    public String getNombre() {
        return nombre;
    }

    public String getLogo() {
        return logo;
    }

    public String getCategoria() {
        return categoria;
    }

    public String getPais() {
        return pais;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PluginChannel)) return false;
        PluginChannel that = (PluginChannel) o;
        return Objects.equals(id, that.id)
            && Objects.equals(nombre, that.nombre)
            && Objects.equals(logo, that.logo)
            && Objects.equals(categoria, that.categoria)
            && Objects.equals(pais, that.pais);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, nombre, logo, categoria, pais);
    }

    @Override
    public String toString() {
        return "PluginChannel(id=" + id + ", nombre=" + nombre + ", logo=" + logo
            + ", categoria=" + categoria + ", pais=" + pais + ")";
    }
}