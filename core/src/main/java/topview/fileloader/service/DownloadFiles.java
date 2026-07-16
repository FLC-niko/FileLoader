package topview.fileloader.service;

import okhttp3.Response;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

final class DownloadFiles {
    private DownloadFiles() {
    }

    static File save(Response response, File directory, String fallbackName) throws IOException {
        if (response.body() == null) throw new IOException("服务器未返回文件内容");
        Path parent = directory.toPath().toAbsolutePath().normalize();
        Files.createDirectories(parent);
        String name = sanitize(resolveName(response.header("Content-Disposition"), fallbackName), fallbackName);
        Path target = parent.resolve(name).normalize();
        if (!target.getParent().equals(parent)) throw new IOException("服务器返回了不安全的文件名");

        Path temporary = Files.createTempFile(parent, ".fileloader-", ".part");
        boolean completed = false;
        try {
            try (InputStream input = response.body().byteStream(); OutputStream output = Files.newOutputStream(temporary)) {
                input.transferTo(output);
            }
            try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            completed = true;
            return target.toFile();
        } finally {
            if (!completed) Files.deleteIfExists(temporary);
        }
    }

    private static String resolveName(String disposition, String fallback) {
        if (disposition == null || disposition.isBlank()) return fallback;
        int extended = disposition.indexOf("filename*=");
        if (extended >= 0) {
            String value = disposition.substring(extended + 10).trim();
            int separator = value.indexOf("''");
            if (separator >= 0) {
                int end = value.indexOf(';', separator + 2);
                String encoded = end >= 0 ? value.substring(separator + 2, end) : value.substring(separator + 2);
                try { return URLDecoder.decode(encoded.trim(), StandardCharsets.UTF_8); }
                catch (Exception ignored) { return encoded.trim(); }
            }
        }
        int plain = disposition.indexOf("filename=");
        if (plain < 0) return fallback;
        String value = disposition.substring(plain + 9).trim();
        int end = value.indexOf(';');
        if (end >= 0) value = value.substring(0, end);
        return value.replace("\"", "").trim();
    }

    private static String sanitize(String candidate, String fallback) {
        try {
            String name = Path.of(candidate).getFileName().toString()
                    .replaceAll("[\\p{Cntrl}\\\\/:*?\"<>|]", "_");
            return name.isBlank() ? fallback : name;
        } catch (Exception e) {
            return fallback;
        }
    }
}
