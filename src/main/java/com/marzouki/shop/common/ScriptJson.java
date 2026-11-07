package com.marzouki.shop.common;

/** Makes JSON safe to place inside a script element, where it must never be able to close the tag. */
public final class ScriptJson {

    private ScriptJson() {
    }

    public static String escape(String json) {
        return json.replace("<", "\\u003c").replace(">", "\\u003e").replace("&", "\\u0026")
                .replace("\u2028", "\\u2028").replace("\u2029", "\\u2029");
    }
}
