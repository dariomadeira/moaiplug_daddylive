package com.infomak.moai.daddylive;

import com.infomak.moai.contract.ResolveRequest;
import com.infomak.moai.contract.ResolveResult;

/**
 * Clase de verificación de canales para uso con scripts externos.
 * Acepta un channel_id como argumento y devuelve el resultado en formato JSON.
 * 
 * Uso:
 *   java -cp build/plugin:build/contract com.infomak.moai.daddylive.VerifyChannel <channel_id>
 * 
 * Salida (JSON):
 *   {"id":"521","status":"online","url":"https://...","responseTimeMs":1234}
 *   {"id":"521","status":"offline","error":"No se pudo resolver"}
 */
public class VerifyChannel {
    
    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Uso: VerifyChannel <channel_id>");
            System.exit(1);
        }
        
        String channelId = args[0];
        long start = System.currentTimeMillis();
        
        try {
            DaddylivePlugin plugin = new DaddylivePlugin();
            ResolveResult result = plugin.resolve(new ResolveRequest(channelId, 0));
            long elapsed = System.currentTimeMillis() - start;
            
            // Verificar que la URL sea válida
            if (result.getUrl() != null && !result.getUrl().isEmpty()) {
                System.out.println(String.format(
                    "{\"id\":\"%s\",\"status\":\"online\",\"url\":\"%s\",\"responseTimeMs\":%d}",
                    channelId, result.getUrl(), elapsed
                ));
            } else {
                System.out.println(String.format(
                    "{\"id\":\"%s\",\"status\":\"offline\",\"error\":\"URL vacía\"}",
                    channelId
                ));
            }
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            String error = e.getMessage();
            if (error == null) error = e.getClass().getSimpleName();
            // Escapar comillas en el error
            error = error.replace("\"", "\\\"").replace("\n", " ");
            System.out.println(String.format(
                "{\"id\":\"%s\",\"status\":\"offline\",\"error\":\"%s\",\"responseTimeMs\":%d}",
                channelId, error, elapsed
            ));
        }
    }
}
