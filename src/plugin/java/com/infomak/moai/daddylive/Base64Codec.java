package com.infomak.moai.daddylive;

/**
 * Decodificador Base64 estándar en Java 8 puro (sin java.util.Base64 ni
 * android.util.Base64, para que el dex no dependa del API level del host).
 * Tolerante a whitespace y padding opcional.
 */
final class Base64Codec {

    private static final int[] REV = new int[128];

    static {
        for (int i = 0; i < REV.length; i++) {
            REV[i] = -1;
        }
        String alpha = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
        for (int i = 0; i < alpha.length(); i++) {
            REV[alpha.charAt(i)] = i;
        }
    }

    private Base64Codec() {
    }

    /** Decodifica una cadena Base64 estándar (con/sin padding, ignorando saltos). */
    static byte[] decode(String s) {
        int n = s.length();
        byte[] clean = new byte[n];
        int m = 0;
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if (c == '\n' || c == '\r' || c == ' ' || c == '\t') {
                continue;
            }
            clean[m++] = (byte) c;
        }
        int rem = m % 4;
        int pad = 0;
        if (rem == 1) {
            throw new IllegalArgumentException("Base64 inválido (longitud 1 mod 4)");
        }
        if (rem == 2 || rem == 3) {
            pad = 4 - rem;
        }
        int outLen = ((m + pad) / 4) * 3;
        if (rem == 2) outLen -= 2;
        else if (rem == 3) outLen -= 1;
        byte[] out = new byte[outLen];
        int oi = 0;
        int b0 = 0, b1 = 0, b2 = 0, b3 = 0;
        int i = 0;
        while (i < m) {
            b0 = val(clean[i++]);
            b1 = val(clean[i++]);
            b2 = (i < m) ? val(clean[i++]) : -1;
            b3 = (i < m) ? val(clean[i++]) : -1;
            if (b0 < 0 || b1 < 0) {
                throw new IllegalArgumentException("Base64 inválido");
            }
            int c1 = (b0 << 2) | (b1 >>> 4);
            out[oi++] = (byte) c1;
            if (b2 >= 0) {
                int c2 = ((b1 & 0xF) << 4) | (b2 >>> 2);
                out[oi++] = (byte) c2;
            }
            if (b3 >= 0) {
                int c3 = ((b2 & 0x3) << 6) | b3;
                out[oi++] = (byte) c3;
            }
        }
        return out;
    }

    private static int val(byte cByte) {
        int c = cByte & 0xFF;
        if (c >= REV.length) return -1;
        return REV[c];
    }
}