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
        if (s == null || s.length() == 0) {
            return new byte[0];
        }
        int n = s.length();
        int[] indices = new int[n];
        int count = 0;
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if (c == '=') {
                break; // fin o padding
            }
            if (c <= ' ') {
                continue; // espacios / saltos
            }
            int v = val((byte) c);
            if (v >= 0) {
                indices[count++] = v;
            }
        }
        int fullBlocks = count / 4;
        int rem = count % 4;
        int outLen = fullBlocks * 3 + (rem == 2 ? 1 : (rem == 3 ? 2 : 0));
        byte[] out = new byte[outLen];
        int oi = 0;
        int ci = 0;
        for (int b = 0; b < fullBlocks; b++) {
            int b0 = indices[ci++];
            int b1 = indices[ci++];
            int b2 = indices[ci++];
            int b3 = indices[ci++];
            out[oi++] = (byte) ((b0 << 2) | (b1 >>> 4));
            out[oi++] = (byte) (((b1 & 0x0F) << 4) | (b2 >>> 2));
            out[oi++] = (byte) (((b2 & 0x03) << 6) | b3);
        }
        if (rem == 2) {
            int b0 = indices[ci++];
            int b1 = indices[ci++];
            out[oi++] = (byte) ((b0 << 2) | (b1 >>> 4));
        } else if (rem == 3) {
            int b0 = indices[ci++];
            int b1 = indices[ci++];
            int b2 = indices[ci++];
            out[oi++] = (byte) ((b0 << 2) | (b1 >>> 4));
            out[oi++] = (byte) (((b1 & 0x0F) << 4) | (b2 >>> 2));
        }
        return out;
    }

    private static int val(byte cByte) {
        int c = cByte & 0xFF;
        if (c >= REV.length) return -1;
        return REV[c];
    }
}