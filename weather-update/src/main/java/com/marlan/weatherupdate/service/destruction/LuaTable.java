package com.marlan.weatherupdate.service.destruction;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * String-aware text surgery on the DCS mission file's Lua tables.
 *
 * The mission is one giant serialized Lua table. We never parse it whole —
 * we locate the few tables we need by key, enumerate their numeric entries by
 * brace-matching (quote-aware, so braces inside strings never count), and
 * rebuild table CONTENTS with entries filtered/appended and re-indexed
 * [1..n]. Whitespace is emitted in the serializer's tab style so diffs stay
 * readable, but Lua doesn't care either way.
 */
final class LuaTable {
    private LuaTable() {
    }

    /** A located `["key"] = { ... }`: content excludes the braces. */
    record KeyTable(int keyStart, int contentStart, int contentEnd) {
    }

    /** One numeric entry `[n] = <value>`: span covers the `[` through the
     *  entry's trailing comma / `-- end of [n]` comment; value is the body
     *  text (the `{...}`, quoted string, or scalar). */
    record Entry(int index, int start, int end, String value) {
    }

    /** Index just past the closing quote of the string opening at i. */
    static int skipString(String text, int i) {
        int j = i + 1;
        while (j < text.length()) {
            char c = text.charAt(j);
            if (c == '\\') {
                j += 2;
            } else if (c == '"') {
                return j + 1;
            } else {
                j++;
            }
        }
        return text.length();
    }

    /** Index of the '}' matching the '{' at braceOpen (quote-aware); -1 if
     *  unbalanced within [braceOpen, to). */
    static int matchBrace(String text, int braceOpen, int to) {
        int depth = 0;
        int i = braceOpen;
        while (i < to) {
            char c = text.charAt(i);
            if (c == '"') {
                i = skipString(text, i);
                continue;
            }
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) return i;
            }
            i++;
        }
        return -1;
    }

    /** First `["key"] = { ... }` between from and to, or null. Quote-aware:
     *  a key mentioned inside a string value never matches. */
    static KeyTable findKeyTable(String text, String key, int from, int to) {
        String needle = "[\"" + key + "\"]";
        int i = from;
        while (i < to) {
            char c = text.charAt(i);
            if (c == '"') {
                // Could be OUR key's opening quote or a value string — peek.
                if (text.startsWith(needle, i - 1) && i >= 1 && text.charAt(i - 1) == '[') {
                    int afterKey = i - 1 + needle.length();
                    int j = afterKey;
                    while (j < to && Character.isWhitespace(text.charAt(j))) j++;
                    if (j < to && text.charAt(j) == '=') {
                        j++;
                        while (j < to && Character.isWhitespace(text.charAt(j))) j++;
                        if (j < to && text.charAt(j) == '{') {
                            int close = matchBrace(text, j, to);
                            if (close != -1) return new KeyTable(i - 1, j + 1, close);
                        }
                    }
                }
                i = skipString(text, i);
                continue;
            }
            i++;
        }
        return null;
    }

    /** The numeric entries of a table's content, in file order. */
    static List<Entry> entries(String text, int contentStart, int contentEnd) {
        List<Entry> out = new ArrayList<>();
        int i = contentStart;
        while (i < contentEnd) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c) || c == ',') {
                i++;
                continue;
            }
            if (c == '-') { // `-- end of ...` trailer line
                int nl = text.indexOf('\n', i);
                if (nl == -1 || nl >= contentEnd) break;
                i = nl + 1;
                continue;
            }
            if (c != '[') break; // string-keyed or malformed — not ours
            int entryStart = i;
            int keyClose = text.indexOf(']', i);
            if (keyClose == -1 || keyClose >= contentEnd) break;
            String rawKey = text.substring(i + 1, keyClose).trim();
            int j = keyClose + 1;
            while (j < contentEnd && Character.isWhitespace(text.charAt(j))) j++;
            if (j >= contentEnd || text.charAt(j) != '=') break;
            j++;
            while (j < contentEnd && Character.isWhitespace(text.charAt(j))) j++;
            int valueStart = j;
            int valueEnd; // exclusive
            char v = text.charAt(j);
            if (v == '{') {
                int close = matchBrace(text, j, contentEnd);
                if (close == -1) break;
                valueEnd = close + 1;
            } else if (v == '"') {
                valueEnd = skipString(text, j);
            } else {
                while (j < contentEnd && text.charAt(j) != ',' && text.charAt(j) != '\n') j++;
                valueEnd = j;
            }
            int end = valueEnd;
            if (end < contentEnd && text.charAt(end) == ',') end++;
            // consume a trailing ` -- end of [n]` comment on the same line
            int lineEnd = end;
            while (lineEnd < contentEnd && text.charAt(lineEnd) != '\n'
                    && (Character.isWhitespace(text.charAt(lineEnd)) || text.charAt(lineEnd) == '-')) {
                if (text.charAt(lineEnd) == '-') {
                    int nl = text.indexOf('\n', lineEnd);
                    lineEnd = (nl == -1 || nl > contentEnd) ? contentEnd : nl;
                    break;
                }
                lineEnd++;
            }
            end = lineEnd;
            String rawKeyDigits = rawKey.replaceAll("[^0-9-]", "");
            int index;
            try {
                index = Integer.parseInt(rawKeyDigits.isEmpty() ? rawKey : rawKeyDigits);
            } catch (NumberFormatException nfe) {
                break; // string-keyed table — not entry-shaped
            }
            out.add(new Entry(index, entryStart, end,
                    text.substring(valueStart, valueEnd).trim()));
            i = end;
        }
        return out;
    }

    /** Table content re-emitted from entry values, re-indexed [1..n], in the
     *  serializer's style. `indentTabs` is the tab depth of the entries. */
    static String emitEntries(List<String> values, int indentTabs) {
        StringBuilder sb = new StringBuilder("\n");
        String indent = "\t".repeat(indentTabs);
        for (int i = 0; i < values.size(); i++) {
            String value = values.get(i);
            sb.append(indent).append('[').append(i + 1).append("] = ");
            if (value.startsWith("{")) {
                sb.append('\n').append(indent);
            }
            sb.append(value).append(", -- end of [").append(i + 1).append("]\n");
        }
        sb.append(indent.isEmpty() ? "" : indent.substring(1));
        return sb.toString();
    }

    /** First captured group of the pattern within value, or null. */
    static String extract(String value, Pattern pattern) {
        Matcher m = pattern.matcher(value);
        return m.find() ? m.group(1) : null;
    }
}
