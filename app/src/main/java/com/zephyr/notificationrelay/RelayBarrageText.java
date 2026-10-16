package com.zephyr.notificationrelay;

final class RelayBarrageText {
    static final int MAX_CODE_POINTS = 120;

    static String format(CharSequence title, CharSequence body, String appName, boolean hasIcon) {
        String heading = title == null ? "" : title.toString();
        // MiLink appends exactly one device suffix using "%s | %s" when posting the notification.
        heading = singleLine(heading).trim();
        String content = body == null ? "" : singleLine(body.toString()).trim();
        if (!hasIcon && appName != null && !appName.isBlank()
                && !heading.equals(appName) && !heading.startsWith(appName + " | ")) {
            heading = heading.isEmpty() ? appName : appName + " · " + heading;
        }
        String result = heading.isEmpty() ? content : content.isEmpty() ? heading : heading + ": " + content;
        if (result.codePointCount(0, result.length()) <= MAX_CODE_POINTS) return result;
        return result.substring(0, result.offsetByCodePoints(0, MAX_CODE_POINTS - 1)) + "…";
    }

    private static String singleLine(String value) {
        return value.replace('\r', ' ').replace('\n', ' ');
    }
}
