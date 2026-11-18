package io.github.lumishadevoyager.bandagent;

import android.content.Context;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.cert.*;
import javax.net.ssl.*;
import org.json.*;

/** One-time signed pairing invitation; TLS certificate is pinned per computer. */
public final class ComputerLink {
    private final Context context;
    private final File configFile;
    private JSONObject config;
    public ComputerLink(Context context) throws Exception {
        this.context = context; configFile = new File(context.getFilesDir(), "computer-link.json");
        if (configFile.isFile()) config = new JSONObject(new String(java.nio.file.Files.readAllBytes(configFile.toPath()), StandardCharsets.UTF_8));
    }
    public void pair(byte[] envelope) throws Exception {
        PublicKey root = SignedFeed.publicKey(new String(OtaCoordinator.read(context.getAssets().open("update-public.pem"), 8192), StandardCharsets.US_ASCII));
        JSONObject invite = SignedFeed.verifyEnvelope(envelope, root); long now = System.currentTimeMillis() / 1000;
        long issued = invite.getLong("issuedAt"), expires = invite.getLong("expiresAt");
        URI url = new URI(invite.getString("endpoint"));
        boolean internet = "powerprofile_relay_pairing".equals(invite.optString("purpose"));
        if ((!internet && !"computer_pairing".equals(invite.optString("purpose"))) || !context.getPackageName().equals(invite.getString("appId")) || !SignedFeed.REPOSITORY.equals(invite.getString("repository")) || issued > now + 300 || expires <= now || expires <= issued || expires - issued > 600 || !"https".equals(url.getScheme()) || url.getHost() == null || url.getRawUserInfo() != null || !"".equals(url.getPath()) || url.getRawQuery() != null || url.getFragment() != null || !invite.getString("token").matches("[a-f0-9]{64}")) throw new SecurityException("invalid_computer_pairing");
        if (internet && (!"io.github.lumishadevoyager.bandagent.powerprofile".equals(context.getPackageName()) || !url.getHost().matches("[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}") || url.getHost().endsWith(".local") || (url.getPort() != -1 && url.getPort() != 443))) throw new SecurityException("invalid_relay_pairing");
        if (!internet && !invite.getString("certSha256").matches("[a-f0-9]{64}")) throw new SecurityException("invalid_computer_pin");
        config = new JSONObject().put("endpoint", invite.getString("endpoint")).put("certSha256", internet ? "" : invite.getString("certSha256")).put("token", invite.getString("token")).put("transport", internet ? "internet" : "lan");
        try (FileOutputStream out = new FileOutputStream(configFile)) { out.write(config.toString().getBytes(StandardCharsets.UTF_8)); out.getFD().sync(); }
    }
    public boolean paired() { return config != null; }
    public boolean internet() { return config != null && "internet".equals(config.optString("transport")); }
    public JSONObject post(String route, JSONObject data) throws Exception { return request(route, data.toString().getBytes(StandardCharsets.UTF_8)); }
    public JSONObject request(String route, byte[] body) throws Exception {
        if (config == null) throw new IOException("computer_not_paired");
        if (!(route.equals("/powerprofile/device-report") || route.equals("/powerprofile/poll") || route.equals("/powerprofile/ack") || route.equals("/diagnostics") || route.equals("/audio") || route.equals("/poll") || route.equals("/ack") || route.matches("/recording\\?entryId=(codex|harness)&audioId=audio_[a-z0-9]+"))) throw new SecurityException("invalid_computer_route");
        if (internet() && !(route.equals("/powerprofile/poll") || route.equals("/powerprofile/ack") || route.equals("/powerprofile/device-report"))) throw new SecurityException("invalid_relay_route");
        URL url = new URL(config.getString("endpoint") + route); final String pin = config.getString("certSha256");
        HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
        if (!internet()) {
        X509TrustManager trust = new X509TrustManager() {
            public java.security.cert.X509Certificate[] getAcceptedIssuers() { return new java.security.cert.X509Certificate[0]; }
            public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType) throws CertificateException { throw new CertificateException("client_not_supported"); }
            public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType) throws CertificateException { try { if (chain.length == 0 || !pin.equals(SignedFeed.sha256(chain[0].getEncoded()))) throw new CertificateException("computer_tls_pin_mismatch"); chain[0].checkValidity(); } catch (CertificateException error) { throw error; } catch (Exception error) { throw new CertificateException(error); } }
        };
        SSLContext tls = SSLContext.getInstance("TLS"); tls.init(null, new TrustManager[] { trust }, null);
        connection.setSSLSocketFactory(tls.getSocketFactory()); connection.setHostnameVerifier((hostname, session) -> hostname.equals(url.getHost()));
        }
        // Internet pairing uses normal platform CA + hostname validation.
        // The existing pinned self-signed computer path is unchanged.
        connection.setConnectTimeout(internet() ? 8000 : 4000); connection.setReadTimeout(internet() ? 20000 : 10000); connection.setInstanceFollowRedirects(false); connection.setRequestMethod("POST"); connection.setDoOutput(true);
        connection.setRequestProperty("Authorization", "Bearer " + config.getString("token")); connection.setRequestProperty("Content-Type", "application/json"); connection.setFixedLengthStreamingMode(body.length);
        try {
            try (OutputStream out = connection.getOutputStream()) { out.write(body); }
            if (connection.getResponseCode() != 200) throw new IOException("computer_http_" + connection.getResponseCode());
            return new JSONObject(new String(OtaCoordinator.read(connection.getInputStream(), 65536), StandardCharsets.UTF_8));
        } finally { connection.disconnect(); }
    }
}
