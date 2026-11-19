package ru.itmo.highload.files.adapters.in.rest;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.springframework.http.ContentDisposition;

final class DownloadName {
    private DownloadName() {}

    static String attachment(String original) {
        String path = original.replace('\\', '/');
        String name = path.substring(path.lastIndexOf('/') + 1);
        if (name.isBlank()) name = "document";
        boolean ascii = name.chars().allMatch(c -> c >= 32 && c < 127);
        if (ascii) return ContentDisposition.attachment().filename(name).build().toString();
        String extension =
                name.matches("(?i).*\\.[a-z0-9]{1,8}$")
                        ? name.substring(name.lastIndexOf('.'))
                        : "";
        String fallback =
                ContentDisposition.attachment().filename("document" + extension).build().toString();
        String encoded =
                URLEncoder.encode(name, StandardCharsets.UTF_8)
                        .replace("+", "%20")
                        .replace("*", "%2A");
        return fallback + "; filename*=UTF-8''" + encoded;
    }
}
