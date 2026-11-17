package io.github.lumishadevoyager.bandagent;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;

/** Shared policy for both independent entries. Authenticates bytes before JSON use. */
public final class SignedFeed {
    public static final String REPOSITORY = "LumishadeVoyager/xiaomi-band-agent-companion";
    public final JSONObject payload;
    private SignedFeed(JSONObject payload) { this.payload = payload; }
    public static String hex(byte[] bytes) { StringBuilder out = new StringBuilder(); for (byte b : bytes) out.append(String.format(java.util.Locale.ROOT, "%02x", b & 255)); return out.toString(); }
    public static String sha256(byte[] bytes) throws Exception { return hex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    public static PublicKey publicKey(String pem) throws Exception {
        String encoded = pem.replace("-----BEGIN PUBLIC KEY-----", "").replace("-----END PUBLIC KEY-----", "").replaceAll("\\s", "");
        return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(encoded)));
    }
    public static SignedFeed verify(byte[] envelope, PublicKey root, String channel, long now) throws Exception {
        JSONObject payload = verifyEnvelope(envelope, root);
        long issued = payload.getLong("issuedAt"), expires = payload.getLong("expiresAt");
        if (payload.getInt("schema") != 1 || !REPOSITORY.equals(payload.getString("repository")) || !channel.equals(payload.getString("channel"))) throw new SecurityException("wrong_feed_identity");
        if (issued > now + 300 || expires <= now || expires <= issued || expires - issued > 1209600) throw new SecurityException("feed_expired_or_clock_invalid");
        JSONArray components = payload.getJSONArray("components");
        if (components.length() > 16) throw new SecurityException("too_many_components");
        return new SignedFeed(payload);
    }
    public static JSONObject verifyEnvelope(byte[] envelope, PublicKey root) throws Exception {
        if (envelope.length > 131072) throw new SecurityException("feed_too_large");
        JSONObject signed = new JSONObject(new String(envelope, StandardCharsets.UTF_8));
        if (signed.getInt("schema") != 1 || !"RS256".equals(signed.getString("algorithm"))) throw new SecurityException("invalid_feed_algorithm");
        byte[] bytes = Base64.getDecoder().decode(signed.getString("payload"));
        if (bytes.length > 65536) throw new SecurityException("payload_too_large");
        Signature verifier = Signature.getInstance("SHA256withRSA"); verifier.initVerify(root); verifier.update(bytes);
        if (!verifier.verify(Base64.getDecoder().decode(signed.getString("signature")))) throw new SecurityException("invalid_feed_signature");
        return new JSONObject(new String(bytes, StandardCharsets.UTF_8));
    }
    public JSONObject component(String entry, String kind, String model, String region, long currentCode, long highestSeen) throws Exception {
        if (!(entry.equals("codex") || entry.equals("harness")) || !(kind.equals("android") || kind.equals("wearable"))) throw new SecurityException("invalid_component");
        JSONObject found = null;
        JSONArray components = payload.getJSONArray("components");
        for (int i = 0; i < components.length(); i++) {
            JSONObject item = components.getJSONObject(i);
            if (!entry.equals(item.getString("entryId")) || !kind.equals(item.getString("kind"))) continue;
            if (found != null) throw new SecurityException("duplicate_component");
            found = item;
        }
        if (found == null) return null;
        String expectedPackage = "io.github.lumishadevoyager.bandagent." + entry;
        if (!expectedPackage.equals(found.getString("appId"))) throw new SecurityException("wrong_package");
        if (!contains(found.getJSONArray("models"), model) || !contains(found.getJSONArray("regions"), region)) throw new SecurityException("unsupported_device_region");
        if (found.getInt("protocolMin") > 1 || found.getInt("protocolMax") < 1 || found.getInt("protocolMin") < 1) throw new SecurityException("incompatible_protocol");
        long code = found.getLong("versionCode"), size = found.getLong("size");
        if (code < highestSeen) throw new SecurityException("release_rollback");
        if (code <= 0 || size <= 0 || size > (kind.equals("wearable") ? 2097152 : 67108864) || !found.getString("sha256").matches("[a-f0-9]{64}")) throw new SecurityException("invalid_artifact");
        artifactUrl(found.getString("url"), kind);
        return code > currentCode ? found : null;
    }
    public static void artifactUrl(String value, String kind) throws Exception {
        URI url = new URI(value);
        String prefix = "/" + REPOSITORY + "/releases/download/";
        String suffix = kind.equals("wearable") ? ".rpk" : ".apk";
        if (!"https".equals(url.getScheme()) || !"github.com".equals(url.getHost()) || url.getRawUserInfo() != null || url.getPort() != -1 || url.getRawQuery() != null || url.getFragment() != null || !url.getPath().startsWith(prefix) || !url.getPath().endsWith(suffix) || url.getPath().contains("/../")) throw new SecurityException("untrusted_artifact_url");
    }
    private static boolean contains(JSONArray values, String value) throws Exception { for (int i = 0; i < values.length(); i++) if (value.equals(values.getString(i))) return true; return false; }
}
