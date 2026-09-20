package com.infomak.moai.daddylive;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.*;

public class BatchChecker {
    public static void main(String[] args) throws Exception {
        DaddyliveResolver resolver = new DaddyliveResolver();
        ExecutorService pool = Executors.newFixedThreadPool(10);
        
        String[] samples = {
            "521", "800", "44", "1", "2", "3", "4", "5", "6", "7",
            "8", "9", "10", "11", "12", "15", "16", "20", "25", "30",
            "50", "100", "101", "110", "116", "120", "128", "130", "200", "500"
        };
        
        AtomicInteger okCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);
        List<Future<?>> futures = new ArrayList<Future<?>>();
        
        long t0 = System.currentTimeMillis();
        for (final String cid : samples) {
            futures.add(pool.submit(new Runnable() {
                public void run() {
                    try {
                        DaddyliveResolver.Result r = resolver.resolve(cid);
                        System.out.println("[OK] Canal " + cid + " -> " + r.url.substring(0, Math.min(60, r.url.length())) + "...");
                        okCount.incrementAndGet();
                    } catch (Exception e) {
                        System.out.println("[FAIL] Canal " + cid + " -> " + e.getMessage());
                        failCount.incrementAndGet();
                    }
                }
            }));
        }
        for (Future<?> f : futures) f.get();
        pool.shutdown();
        long totalMs = System.currentTimeMillis() - t0;
        System.out.println("\nResumen: " + okCount.get() + " OK, " + failCount.get() + " FAIL en " + (totalMs / 1000.0) + "s");
    }
}
