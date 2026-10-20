package com.jhz.llamachatplusplus;

import android.content.Context;
import android.content.SharedPreferences;

/** Tiny wrapper around SharedPreferences for server settings. */
final class Prefs {
    private static final String NAME = "settings";

    private Prefs() {}

    static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    static boolean expose(Context c) {
        return sp(c).getBoolean("expose", false);
    }

    static int port(Context c) {
        return sp(c).getInt("port", 8080);
    }

    static int ctx(Context c) {
        return sp(c).getInt("ctx", 4096);
    }

    static int threads(Context c) {
        int def = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
        return sp(c).getInt("threads", def);
    }

    static String apiKey(Context c) {
        return sp(c).getString("apikey", "");
    }

    static int clamp(String s, int def, int min, int max) {
        try {
            int v = Integer.parseInt(s.trim());
            return Math.max(min, Math.min(max, v));
        } catch (Exception e) {
            return def;
        }
    }
}
