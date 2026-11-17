package io.github.lumishadevoyager.bandagent;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.ECGenParameterSpec;
import java.util.Arrays;
import java.util.Base64;
import org.json.*;

/** Uses the authenticated loopback API; never extracts or logs account credentials. */
public final class AstroBoxApi {
    private final File identityFile;
    private JSONObject identity;
    private final PrivateKey signingKey;
    private String token;
    private long tokenExpires;
    public AstroBoxApi(Context context) throws Exception {
        identityFile = new File(context.getFilesDir(), "astrobox-client.json");
        String alias = "band-agent-astrobox";
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore"); keyStore.load(null);
        if (!keyStore.containsAlias(alias)) {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC", "AndroidKeyStore");
            generator.initialize(new KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY).setAlgorithmParameterSpec(new ECGenParameterSpec("ed25519")).setDigests(KeyProperties.DIGEST_NONE).build());
            generator.generateKeyPair();
        }
        signingKey = (PrivateKey) keyStore.getKey(alias, null);
        if (identityFile.isFile()) identity = new JSONObject(new String(java.nio.file.Files.readAllBytes(identityFile.toPath()), StandardCharsets.UTF_8));
        else {
            byte[] encoded = keyStore.getCertificate(alias).getPublicKey().getEncoded();
            byte[] raw = Arrays.copyOfRange(encoded, encoded.length - 32, encoded.length);
            identity = new JSONObject().put("clientName", "Band Agent " + context.getPackageName().substring(context.getPackageName().lastIndexOf('.') + 1)).put("publicKey", url64(raw)).put("fingerprint", SignedFeed.sha256(raw));
            save();
        }
    }
    private static String url64(byte[] bytes) { return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    private void save() throws Exception { try (FileOutputStream out = new FileOutputStream(identityFile)) { out.write(identity.toString().getBytes(StandardCharsets.UTF_8)); out.getFD().sync(); } }
    private JSONObject raw(String route, String method, byte[] body, String type, boolean auth) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:10721" + route).openConnection();
        c.setConnectTimeout(4000); c.setReadTimeout(20000); c.setRequestMethod(method); c.setInstanceFollowRedirects(false);
        try {
            if (auth) c.setRequestProperty("Authorization", "Bearer " + session());
            if (body != null) { c.setDoOutput(true); c.setRequestProperty("Content-Type", type); c.setFixedLengthStreamingMode(body.length); try (OutputStream out = c.getOutputStream()) { out.write(body); } }
            int status = c.getResponseCode(); byte[] bytes = read(status >= 200 && status < 300 ? c.getInputStream() : c.getErrorStream(), 1048576);
            JSONObject result = bytes.length == 0 ? new JSONObject() : new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            if (status < 200 || status >= 300) { if (status == 401) token = null; JSONObject error = result.optJSONObject("error"); throw new IOException(error == null ? "astrobox_http_" + status : error.optString("code", "astrobox_error")); }
            return result;
        } finally { c.disconnect(); }
    }
    private static byte[] read(InputStream input, int max) throws Exception {
        if (input == null) return new byte[0];
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) { byte[] buffer = new byte[8192]; int count; while ((count = in.read(buffer)) != -1) { if (out.size() + count > max) throw new IOException("astrobox_response_too_large"); out.write(buffer, 0, count); } return out.toByteArray(); }
    }
    private JSONObject json(String route, JSONObject body, boolean auth) throws Exception { return raw(route, "POST", body.toString().getBytes(StandardCharsets.UTF_8), "application/json", auth); }
    private synchronized String session() throws Exception {
        if (token != null && tokenExpires > System.currentTimeMillis() + 5000) return token;
        JSONObject challenge;
        try { challenge = json("/v2/auth/challenges", new JSONObject().put("fingerprint", identity.getString("fingerprint")), false); }
        catch (IOException error) {
            if (!"client_not_paired".equals(error.getMessage())) throw error;
            if (identity.has("pairingId")) {
                JSONObject status = raw("/v2/auth/pairing-requests/" + segment(identity.getString("pairingId")), "GET", null, null, false);
                if ("pending_user_action".equals(status.optString("state")) || "pending".equals(status.optString("state"))) throw new IOException("astrobox_pairing_required");
                if ("denied".equals(status.optString("state"))) throw new IOException("astrobox_pairing_denied");
                identity.remove("pairingId"); save();
            }
            JSONObject pairing = json("/v2/auth/pairing-requests", new JSONObject().put("clientName", identity.optString("clientName", "Band Agent Companion")).put("publicKey", identity.getString("publicKey")).put("requestedScopes", new JSONArray(new String[] { "device.read", "device.control", "resource.read", "resource.install", "queue.read", "queue.control" })).put("deviceAllowlist", new JSONArray()), false);
            identity.put("pairingId", pairing.getString("pairingId")); save(); throw new IOException("astrobox_pairing_required");
        }
        Signature signer = Signature.getInstance("Ed25519"); signer.initSign(signingKey); signer.update(Base64.getUrlDecoder().decode(challenge.getString("signingPayload")));
        JSONObject session = json("/v2/auth/sessions", new JSONObject().put("challengeId", challenge.getString("challengeId")).put("signature", url64(signer.sign())), false);
        token = session.getString("token"); tokenExpires = java.time.Instant.parse(session.getString("expiresAt")).toEpochMilli(); return token;
    }
    private static String segment(String value) throws Exception { return URLEncoder.encode(value, "UTF-8").replace("+", "%20"); }
    public JSONArray devices() throws Exception { return raw("/v2/devices", "GET", null, null, true).getJSONArray("devices"); }
    public void connectKnown(String deviceId) throws Exception {
        JSONObject device = raw("/v2/devices/" + segment(deviceId), "GET", null, null, true);
        if (!device.optBoolean("known") || !deviceId.equals(device.getString("deviceId")) || device.optString("authkey").isEmpty()) throw new IOException("astrobox_known_device_required");
        json("/v2/devices/connect", new JSONObject().put("name", device.getString("name")).put("addr", deviceId).put("authkey", device.getString("authkey")).put("kind", "xiaomi").put("connectType", device.optString("connectType", "SPP")), true);
    }
    public JSONArray apps(String device) throws Exception {
        JSONObject result = raw("/v2/devices/" + segment(device) + "/quick-apps", "GET", null, null, true);
        return result.getJSONArray("items");
    }
    public long installedVersion(String device, String appId) throws Exception {
        JSONArray apps = apps(device);
        for (int i = 0; i < apps.length(); i++) {
            JSONObject app = apps.getJSONObject(i);
            String id = app.optString("packageName", app.optString("package_name", ""));
            if (appId.equals(id)) return app.has("versionCode") ? app.getLong("versionCode") : app.getLong("version_code");
        }
        return -1;
    }
    public String install(String device, File rpk) throws Exception {
        byte[] bytes = java.nio.file.Files.readAllBytes(rpk.toPath());
        if (bytes.length > 2097152) throw new IOException("rpk_too_large");
        String boundary = "BandAgent" + java.util.UUID.randomUUID().toString().replace("-", "");
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"update.rpk\"\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.US_ASCII)); body.write(bytes); body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII));
        JSONObject upload = raw("/v2/uploads", "POST", body.toByteArray(), "multipart/form-data; boundary=" + boundary, true);
        if (upload.getLong("size") != bytes.length || !SignedFeed.sha256(bytes).equals(upload.getString("sha256"))) throw new SecurityException("astrobox_upload_integrity_failed");
        // Let AstroBox detect .rpk exactly as its official CLI's default path does.
        JSONObject result = json("/v2/queue/install", new JSONObject().put("deviceId", device).put("uploadId", upload.getString("uploadId")), true);
        return result.getString("taskId");
    }
    public JSONObject task(String id) throws Exception { return raw("/v2/queue/tasks/" + segment(id), "GET", null, null, true); }
    public void disconnect(String device) throws Exception { raw("/v2/devices/" + segment(device) + "/connection", "DELETE", null, null, true); }
    public void startQueue(String device) throws Exception { json("/v2/queue/" + segment(device) + "/start", new JSONObject(), true); }
}
