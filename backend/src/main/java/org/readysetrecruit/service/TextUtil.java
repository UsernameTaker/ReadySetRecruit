package org.readysetrecruit.service;

public final class TextUtil {
    private TextUtil() {}

    // word count for the word limits. KEEP THIS THE SAME as countWords() in the frontend!!
    public static int wordCount(String s) {
        if (s == null) return 0;
        String t = s.trim();
        return t.isEmpty() ? 0 : t.split("\\s+").length;
    }

    public static boolean isBlank(String s) { return s == null || s.isBlank(); }

    public static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    // team123.org -> https://team123.org (null if empty)
    public static String normalizeUrl(String url) {
        String u = trimToNull(url);
        if (u == null) return null;
        if (!u.matches("(?i)^https?://.*")) u = "https://" + u;
        return u;
    }

    public static String snippet(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max - 1).trim() + "…";
    }
}
