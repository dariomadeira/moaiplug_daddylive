package com.infomak.moai.contract;

/**
 * Espejo Java del contrato Kotlin v1 de moai3 (solo para COMPILAR el plugin).
 * En runtime estas clases no viajan en el .dex: las provee la app vía
 * DexClassLoader parent-first. No modificar firmas.
 */
public interface IPlugin {
    PluginManifest manifest();

    ResolveResult resolve(ResolveRequest request);
}