package com.infomak.moai.daddylive;

import com.infomak.moai.contract.ResolveRequest;
import com.infomak.moai.contract.ResolveResult;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Verificador masivo de canales en un solo proceso JVM multihilo.
 * 
 * Evita el overhead de iniciar miles de procesos de Java desde Python,
 * consumiendo mínima memoria y aprovechando un pool de hilos para verificar
 * el catálogo completo en minutos sin timeouts.
 */
public class BatchVerify {

    public static class ChannelInfo {
        public final String id;
        public final String nombre;

        public ChannelInfo(String id, String nombre) {
            this.id = id;
            this.nombre = nombre;
        }
    }

    public static class ChannelResult {
        public final String id;
        public final String nombre;
        public final String status;
        public final int responseTimeMs;
        public final String url;
        public final String error;

        public ChannelResult(String id, String nombre, String status, int responseTimeMs, String url, String error) {
            this.id = id;
            this.nombre = nombre;
            this.status = status;
            this.responseTimeMs = responseTimeMs;
            this.url = url;
            this.error = error;
        }
    }

    public static void main(String[] args) {
        int workers = 3;
        int sample = 0;
        int offset = 0;
        String channelsPath = null;
        String outPath = "data/verified_java.json";
        String partialPath = "data/verified_java_partial.json";
        boolean resume = true;

        for (int i = 0; i < args.length; i++) {
            if ("--workers".equals(args[i]) && i + 1 < args.length) {
                workers = Integer.parseInt(args[++i]);
            } else if ("--sample".equals(args[i]) && i + 1 < args.length) {
                sample = Integer.parseInt(args[++i]);
            } else if ("--offset".equals(args[i]) && i + 1 < args.length) {
                offset = Integer.parseInt(args[++i]);
            } else if ("--channels".equals(args[i]) && i + 1 < args.length) {
                channelsPath = args[++i];
            } else if ("--out".equals(args[i]) && i + 1 < args.length) {
                outPath = args[++i];
            } else if ("--no-resume".equals(args[i])) {
                resume = false;
            }
        }

        File outFile = new File(outPath);
        File parentDir = outFile.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            parentDir.mkdirs();
        }

        if (sample > 0) {
            partialPath = outPath.replace(".json", "_sample" + sample + "_partial.json");
        } else {
            partialPath = outPath.replace(".json", "_partial.json");
        }

        System.err.println("[batch-verify] Iniciando verificador masivo multihilo (" + workers + " hilos)...");

        // 1. Cargar canales a verificar
        List<ChannelInfo> allChannels = loadChannels(channelsPath);
        if (allChannels.isEmpty()) {
            System.err.println("[batch-verify] ERROR: No se pudieron obtener canales.");
            System.exit(1);
        }

        // Ordenar por ID numérico
        allChannels.sort(Comparator.comparingInt(c -> {
            try {
                return Integer.parseInt(c.id);
            } catch (Exception e) {
                return 999999;
            }
        }));

        if (sample > 0 && sample < allChannels.size()) {
            Collections.shuffle(allChannels, new Random(42));
            allChannels = allChannels.subList(0, sample);
            allChannels.sort(Comparator.comparingInt(c -> Integer.parseInt(c.id)));
            System.err.println("[batch-verify] Muestra seleccionada: " + allChannels.size() + " canales.");
        }

        if (offset > 0 && offset < allChannels.size()) {
            allChannels = allChannels.subList(offset, allChannels.size());
            System.err.println("[batch-verify] Offset aplicado: " + offset + " saltados.");
        }

        // 2. Manejo de reanudación
        Map<String, ChannelResult> resultsMap = new ConcurrentHashMap<>();
        if (resume && sample == 0) {
            loadPartial(partialPath, resultsMap);
        }

        List<ChannelInfo> pending = new ArrayList<>();
        for (ChannelInfo c : allChannels) {
            if (!resultsMap.containsKey(c.id)) {
                pending.add(c);
            }
        }

        int total = allChannels.size();
        int alreadyDone = resultsMap.size();
        System.err.println("[batch-verify] Total canales: " + total + " (" + alreadyDone + " ya hechos, " + pending.size() + " pendientes)");

        if (pending.isEmpty()) {
            System.err.println("[batch-verify] Todos los canales ya están verificados.");
            saveFinalResults(outFile, outPath, resultsMap, total);
            return;
        }

        // 3. Ejecución multihilo en el mismo JVM
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        long startTime = System.currentTimeMillis();
        AtomicInteger completedCount = new AtomicInteger(alreadyDone);
        AtomicInteger onlineCount = new AtomicInteger(0);
        AtomicInteger consecutiveTimeouts = new AtomicInteger(0);

        for (ChannelResult r : resultsMap.values()) {
            if ("online".equals(r.status)) {
                onlineCount.incrementAndGet();
            }
        }

        String finalPartialPath = partialPath;
        Object saveLock = new Object();

        for (ChannelInfo ch : pending) {
            executor.submit(() -> {
                // Si hubo varios timeouts consecutivos, esperar a que se enfríe la ventana del firewall
                if (consecutiveTimeouts.get() >= 4) {
                    synchronized (saveLock) {
                        if (consecutiveTimeouts.get() >= 4) {
                            System.err.println("[batch-verify] Pausa de 15s por rate-limit del servidor...");
                            try {
                                Thread.sleep(15000);
                            } catch (InterruptedException ignored) {}
                            consecutiveTimeouts.set(0);
                        }
                    }
                }

                // Pacing suave entre peticiones para evitar bloqueos por IP
                try {
                    Thread.sleep(150);
                } catch (InterruptedException ignored) {}

                long t0 = System.currentTimeMillis();
                ChannelResult res;
                try {
                    DaddylivePlugin plugin = new DaddylivePlugin();
                    ResolveResult rr = plugin.resolve(new ResolveRequest(ch.id, 0));
                    long elapsed = System.currentTimeMillis() - t0;
                    if (rr.getUrl() != null && !rr.getUrl().isEmpty()) {
                        res = new ChannelResult(ch.id, ch.nombre, "online", (int) elapsed, rr.getUrl(), null);
                        onlineCount.incrementAndGet();
                        consecutiveTimeouts.set(0);
                    } else {
                        res = new ChannelResult(ch.id, ch.nombre, "offline", (int) elapsed, null, "URL vacía");
                    }
                } catch (Exception e) {
                    long elapsed = System.currentTimeMillis() - t0;
                    String msg = e.getMessage();
                    if (msg == null) msg = e.getClass().getSimpleName();
                    if (msg.contains("presupuesto") || msg.contains("timed out") || msg.contains("Timeout")) {
                        consecutiveTimeouts.incrementAndGet();
                    } else {
                        consecutiveTimeouts.set(0);
                    }
                    msg = msg.replace("\"", "'").replace("\n", " ");
                    res = new ChannelResult(ch.id, ch.nombre, "offline", (int) elapsed, null, msg);
                }

                resultsMap.put(ch.id, res);
                int done = completedCount.incrementAndGet();

                if (done % 10 == 0 || done == total) {
                    synchronized (saveLock) {
                        savePartial(finalPartialPath, resultsMap);
                    }
                    long elapsedSec = (System.currentTimeMillis() - startTime) / 1000;
                    double rate = done > alreadyDone && elapsedSec > 0 ? (double) (done - alreadyDone) / elapsedSec : 0;
                    long eta = rate > 0 ? (long) ((total - done) / rate) : 0;
                    System.err.println(String.format(Locale.US,
                        "[batch-verify] Progreso: %d/%d (%d%%) - %ds transcurridos - ETA %ds - Online: %d",
                        done, total, (100 * done / total), elapsedSec, eta, onlineCount.get()
                    ));
                }
            });
        }

        executor.shutdown();
        try {
            executor.awaitTermination(2, TimeUnit.HOURS);
        } catch (InterruptedException e) {
            System.err.println("[batch-verify] Interrumpido.");
        }

        synchronized (saveLock) {
            savePartial(finalPartialPath, resultsMap);
        }

        saveFinalResults(outFile, outPath, resultsMap, total);
    }

    private static void saveFinalResults(File outFile, String outPath, Map<String, ChannelResult> resultsMap, int total) {
        List<ChannelResult> sortedList = new ArrayList<>(resultsMap.values());
        sortedList.sort(Comparator.comparingInt(c -> {
            try {
                return Integer.parseInt(c.id);
            } catch (Exception e) {
                return 999999;
            }
        }));

        List<ChannelResult> onlineList = new ArrayList<>();
        List<ChannelResult> offlineList = new ArrayList<>();
        for (ChannelResult r : sortedList) {
            if ("online".equals(r.status)) {
                onlineList.add(r);
            } else {
                offlineList.add(r);
            }
        }

        System.err.println("\n[batch-verify] ==================================================");
        System.err.println("[batch-verify] RESUMEN DE VERIFICACIÓN:");
        System.err.println("[batch-verify]   ✓ Online: " + onlineList.size() + " (" + (100 * onlineList.size() / Math.max(1, sortedList.size())) + "%)");
        System.err.println("[batch-verify]   ✗ Offline: " + offlineList.size());
        System.err.println("[batch-verify]   Total analizados: " + sortedList.size());

        // Guardar archivo completo
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        String ts = sdf.format(new Date());

        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"summary\": {\n");
        sb.append("    \"total\": ").append(sortedList.size()).append(",\n");
        sb.append("    \"online\": ").append(onlineList.size()).append(",\n");
        sb.append("    \"offline\": ").append(offlineList.size()).append(",\n");
        sb.append("    \"timestamp\": \"").append(ts).append("\"\n");
        sb.append("  },\n");
        sb.append("  \"channels\": [\n");
        for (int i = 0; i < sortedList.size(); i++) {
            ChannelResult r = sortedList.get(i);
            sb.append("    {");
            sb.append("\"id\":\"").append(r.id).append("\",");
            sb.append("\"nombre\":\"").append(escapeJson(r.nombre)).append("\",");
            sb.append("\"status\":\"").append(r.status).append("\",");
            sb.append("\"responseTimeMs\":").append(r.responseTimeMs).append(",");
            if (r.url != null) {
                sb.append("\"url\":\"").append(escapeJson(r.url)).append("\",");
            } else {
                sb.append("\"url\":null,");
            }
            if (r.error != null) {
                sb.append("\"error\":\"").append(escapeJson(r.error)).append("\"");
            } else {
                sb.append("\"error\":null");
            }
            sb.append("}").append(i + 1 < sortedList.size() ? ",\n" : "\n");
        }
        sb.append("  ]\n");
        sb.append("}\n");

        writeFile(outFile, sb.toString());
        System.err.println("[batch-verify] Resultados completos guardados en: " + outPath);

        // Guardar lista online si hay canales online (NUNCA pisar con lista vacía si falló)
        if (!onlineList.isEmpty()) {
            String onlinePath = outPath.replace(".json", "_online.json");
            StringBuilder onSb = new StringBuilder();
            onSb.append("[\n");
            for (int i = 0; i < onlineList.size(); i++) {
                ChannelResult r = onlineList.get(i);
                onSb.append("  {");
                onSb.append("\"id\":\"").append(r.id).append("\",");
                onSb.append("\"nombre\":\"").append(escapeJson(r.nombre)).append("\",");
                onSb.append("\"url\":\"").append(escapeJson(r.url != null ? r.url : "")).append("\",");
                onSb.append("\"responseTimeMs\":").append(r.responseTimeMs);
                onSb.append("}").append(i + 1 < onlineList.size() ? ",\n" : "\n");
            }
            onSb.append("]\n");
            writeFile(new File(onlinePath), onSb.toString());
            System.err.println("[batch-verify] Catálogo online guardado en: " + onlinePath + " (" + onlineList.size() + " canales)");
        } else {
            System.err.println("[batch-verify] AVISO: 0 canales online detectados. No se sobreescribe el archivo online anterior.");
        }
    }

    private static void savePartial(String partialPath, Map<String, ChannelResult> resultsMap) {
        try {
            File tmp = new File(partialPath + ".tmp");
            StringBuilder sb = new StringBuilder();
            sb.append("[\n");
            List<ChannelResult> list = new ArrayList<>(resultsMap.values());
            for (int i = 0; i < list.size(); i++) {
                ChannelResult r = list.get(i);
                sb.append("  {");
                sb.append("\"id\":\"").append(r.id).append("\",");
                sb.append("\"nombre\":\"").append(escapeJson(r.nombre)).append("\",");
                sb.append("\"status\":\"").append(r.status).append("\",");
                sb.append("\"responseTimeMs\":").append(r.responseTimeMs).append(",");
                if (r.url != null) {
                    sb.append("\"url\":\"").append(escapeJson(r.url)).append("\"");
                } else {
                    sb.append("\"url\":null");
                }
                sb.append("}").append(i + 1 < list.size() ? ",\n" : "\n");
            }
            sb.append("]\n");
            writeFile(tmp, sb.toString());
            File target = new File(partialPath);
            if (target.exists()) {
                target.delete();
            }
            tmp.renameTo(target);
        } catch (Exception e) {
            System.err.println("[batch-verify] Error guardando parcial: " + e.getMessage());
        }
    }

    private static void loadPartial(String partialPath, Map<String, ChannelResult> resultsMap) {
        File f = new File(partialPath);
        if (!f.exists()) return;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            String content = sb.toString();
            Pattern p = Pattern.compile("\\{\"id\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"nombre\"\\s*:\\s*\"([^\"]*)\"\\s*,\\s*\"status\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"responseTimeMs\"\\s*:\\s*(\\d+)");
            Matcher m = p.matcher(content);
            while (m.find()) {
                String id = m.group(1);
                String nombre = m.group(2);
                String status = m.group(3);
                int ms = Integer.parseInt(m.group(4));
                resultsMap.put(id, new ChannelResult(id, nombre, status, ms, null, null));
            }
            System.err.println("[batch-verify] Cargados " + resultsMap.size() + " resultados de corrida anterior desde " + partialPath);
        } catch (Exception e) {
            System.err.println("[batch-verify] No se pudo leer parcial previo (" + e.getMessage() + "), iniciando desde cero.");
        }
    }

    private static List<ChannelInfo> loadChannels(String path) {
        List<ChannelInfo> list = new ArrayList<>();
        String json = null;

        if (path != null && new File(path).exists()) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(path), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                json = sb.toString();
            } catch (Exception e) {
                System.err.println("[batch-verify] Error leyendo " + path + ": " + e.getMessage());
            }
        }

        if (json == null) {
            // Descargar desde api/channels
            for (String domain : Config.EMBED_DOMAINS) {
                try {
                    String url = "https://" + domain + "/api/channels";
                    Map<String, String> headers = new LinkedHashMap<>();
                    headers.put("User-Agent", Config.USER_AGENT);
                    json = Http.get(url, headers);
                    if (json != null && json.startsWith("[")) {
                        break;
                    }
                } catch (Exception ignored) {
                }
            }
        }

        if (json != null) {
            Pattern p = Pattern.compile("\\{[^\\}]*\"channel_name\"\\s*:\\s*\"([^\"]+)\"[^\\}]*\"url\"\\s*:\\s*\"([^\"]+)\"[^\\}]*\\}");
            Matcher m = p.matcher(json);
            Set<String> seen = new HashSet<>();
            while (m.find()) {
                String name = m.group(1).trim();
                String url = m.group(2);
                Matcher idM = Pattern.compile("id=([0-9]+)").matcher(url);
                if (idM.find()) {
                    String id = idM.group(1);
                    if (seen.add(id)) {
                        list.add(new ChannelInfo(id, name));
                    }
                }
            }
        }
        return list;
    }

    private static void writeFile(File f, String content) {
        try (PrintWriter pw = new PrintWriter(new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8))) {
            pw.write(content);
        } catch (Exception e) {
            System.err.println("[batch-verify] Error escribiendo archivo " + f.getName() + ": " + e.getMessage());
        }
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\b", "\\b")
                .replace("\f", "\\f")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
