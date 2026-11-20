package io.github.lumishadevoyager.bandagent;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.MessageDigest;
import org.json.JSONObject;

public final class OtaHttp {
    public interface Progress { void update(long bytes, long total); }
    private static HttpURLConnection open(String address, long offset) throws Exception {
        URL url = new URL(address);
        for (int redirects = 0; redirects < 6; redirects++) {
            String host = url.getHost();
            boolean gateway = host.equals(new URL(SignedFeed.OTA_ORIGIN).getHost()) && url.getPath().startsWith("/ota/") && url.getQuery() == null;
            if (!"https".equals(url.getProtocol()) || !(gateway || host.equals("github.com") || host.equals("raw.githubusercontent.com") || host.equals("release-assets.githubusercontent.com") || host.equals("objects.githubusercontent.com")) || url.getPort() != -1 || url.getUserInfo() != null) throw new SecurityException("untrusted_download_host");
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            c.setInstanceFollowRedirects(false); c.setConnectTimeout(15000); c.setReadTimeout(20000); c.setRequestProperty("Accept-Encoding", "identity");
            if (offset > 0) c.setRequestProperty("Range", "bytes=" + offset + "-");
            int code = c.getResponseCode();
            if (code >= 300 && code < 400) { String location = c.getHeaderField("Location"); c.disconnect(); if (location == null) throw new IOException("missing_redirect"); url = new URL(url, location); continue; }
            return c;
        }
        throw new IOException("too_many_redirects");
    }
    public static byte[] bytes(String address, int max) throws Exception {
        HttpURLConnection c = open(address, 0);
        try {
            if (c.getResponseCode() != 200) throw new IOException("http_" + c.getResponseCode());
            try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192]; int count;
                while ((count = in.read(buffer)) != -1) { if (out.size() + count > max) throw new IOException("response_too_large"); out.write(buffer, 0, count); }
                return out.toByteArray();
            }
        } finally { c.disconnect(); }
    }
    public static File artifact(File directory, JSONObject item, Progress progress) throws Exception {
        String hash = item.getString("sha256"), kind = item.getString("kind");
        SignedFeed.artifactUrl(item.getString("url"), kind);
        if (!hash.matches("[a-f0-9]{64}")) throw new SecurityException("invalid_hash");
        directory.mkdirs(); File file = new File(directory, hash + (kind.equals("wearable") ? ".rpk" : ".apk"));
        long total = item.getLong("size");
        if (file.isFile() && valid(file, total, hash)) return file;
        File part = new File(directory, hash + ".part"); long offset = part.isFile() ? part.length() : 0;
        if (offset >= total) { Files.deleteIfExists(part.toPath()); offset = 0; }
        String address = "powerprofile".equals(item.optString("entryId")) ? SignedFeed.gatewayArtifact(item.getString("url"), kind) : item.getString("url");
        HttpURLConnection c = open(address, offset);
        try {
            int code = c.getResponseCode();
            if (code != 200 && code != 206) throw new IOException("download_http_" + code);
            if (code == 206 && !String.valueOf(c.getHeaderField("Content-Range")).startsWith("bytes " + offset + "-")) throw new IOException("invalid_resume_range");
            if (code == 200) offset = 0;
            try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(part, offset > 0)) {
                byte[] buffer = new byte[16384]; int count; long done = offset;
                while ((count = in.read(buffer)) != -1) { done += count; if (done > total) throw new IOException("artifact_too_large"); out.write(buffer, 0, count); progress.update(done, total); }
                out.getFD().sync();
            }
        } finally { c.disconnect(); }
        if (!valid(part, total, hash)) { Files.deleteIfExists(part.toPath()); throw new SecurityException("artifact_integrity_failed"); }
        Files.move(part.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        return file;
    }
    public static boolean valid(File file, long size, String expected) throws Exception {
        if (file.length() != size) return false;
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(file)) { byte[] bytes = new byte[16384]; int count; while ((count = in.read(bytes)) != -1) hash.update(bytes, 0, count); }
        return expected.equals(SignedFeed.hex(hash.digest()));
    }
}
