import io.github.lumishadevoyager.bandagent.SignedFeed;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.Base64;
import org.json.*;

public final class SignedFeedTest {
    static KeyPair keys; static long now = 1800000000L; static int checks;
    interface Attempt { void run() throws Exception; }
    static void rejects(Attempt action) throws Exception { try { action.run(); } catch (SecurityException expected) { checks++; return; } throw new AssertionError("unsafe feed accepted"); }
    static JSONObject item() {
        return new JSONObject().put("entryId", "codex").put("kind", "wearable").put("appId", "io.github.lumishadevoyager.bandagent.codex").put("models", new JSONArray().put("M2551B1")).put("regions", new JSONArray().put("CN")).put("versionCode", 5).put("bridgeMinCode", 4).put("protocolMin", 1).put("protocolMax", 1).put("size", 123).put("sha256", new String(new char[64]).replace('\0', 'a')).put("url", "https://github.com/" + SignedFeed.REPOSITORY + "/releases/download/v0.1.0-preview.2/codex.rpk");
    }
    static JSONObject payload(JSONObject item) { return new JSONObject().put("schema", 1).put("repository", SignedFeed.REPOSITORY).put("channel", "preview").put("issuedAt", now - 5).put("expiresAt", now + 100).put("components", new JSONArray().put(item)); }
    static byte[] envelope(JSONObject payload) throws Exception {
        byte[] bytes = payload.toString().getBytes(StandardCharsets.UTF_8); Signature s = Signature.getInstance("SHA256withRSA"); s.initSign(keys.getPrivate()); s.update(bytes);
        return new JSONObject().put("schema", 1).put("algorithm", "RS256").put("payload", Base64.getEncoder().encodeToString(bytes)).put("signature", Base64.getEncoder().encodeToString(s.sign())).toString().getBytes(StandardCharsets.UTF_8);
    }
    static SignedFeed feed(JSONObject item) throws Exception { return SignedFeed.verify(envelope(payload(item)), keys.getPublic(), "preview", now); }
    static void select(JSONObject item) throws Exception { feed(item).component("codex", "wearable", "M2551B1", "CN", 4, 4); }
    public static void main(String[] args) throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA"); g.initialize(2048); keys = g.generateKeyPair();
        if (feed(item()).component("codex", "wearable", "M2551B1", "CN", 4, 4).getInt("versionCode") != 5) throw new AssertionError(); checks++;
        if (feed(item()).component("codex", "wearable", "M2551B1", "CN", 5, 5) != null) throw new AssertionError(); checks++;
        JSONObject tampered = new JSONObject(new String(envelope(payload(item())), StandardCharsets.UTF_8)); tampered.put("payload", Base64.getEncoder().encodeToString(payload(item().put("versionCode", 6)).toString().getBytes(StandardCharsets.UTF_8)));
        rejects(() -> SignedFeed.verify(tampered.toString().getBytes(StandardCharsets.UTF_8), keys.getPublic(), "preview", now));
        rejects(() -> SignedFeed.verify(envelope(payload(item())), g.generateKeyPair().getPublic(), "preview", now));
        rejects(() -> SignedFeed.verify(envelope(payload(item()).put("expiresAt", now - 1)), keys.getPublic(), "preview", now));
        rejects(() -> SignedFeed.verify(envelope(payload(item())), keys.getPublic(), "stable", now));
        rejects(() -> select(item().put("appId", "io.github.lumishadevoyager.bandagent.harness")));
        rejects(() -> select(item().put("models", new JSONArray().put("M2401B1"))));
        rejects(() -> select(item().put("regions", new JSONArray().put("GLOBAL"))));
        rejects(() -> select(item().put("protocolMin", 2).put("protocolMax", 2)));
        rejects(() -> feed(item()).component("codex", "wearable", "M2551B1", "CN", 4, 6));
        rejects(() -> select(item().put("url", "https://github.com/other/project/releases/download/v1/codex.rpk")));
        rejects(() -> select(item().put("url", "http://github.com/" + SignedFeed.REPOSITORY + "/releases/download/v1/codex.rpk")));
        rejects(() -> SignedFeed.verify(envelope(payload(item()).put("components", new JSONArray().put(item()).put(item()))), keys.getPublic(), "preview", now).component("codex", "wearable", "M2551B1", "CN", 4, 4));
        System.out.println("Android update policy: " + checks + " real Java checks passed");
        JSONObject pp = item().put("entryId", "powerprofile").put("appId", "io.github.lumishadevoyager.bandagent.powerprofile").put("url", "https://github.com/"+SignedFeed.REPOSITORY+"/releases/download/v0.1.0-preview.9/band-agent-powerprofile-0.1.0-preview.9.rpk");
        if (feed(pp).component("powerprofile", "wearable", "M2551B1", "CN", 4, 4) == null) throw new AssertionError();
        if (!SignedFeed.gatewayArtifact(pp.getString("url"), "wearable").equals(SignedFeed.OTA_ORIGIN+"/ota/releases/download/v0.1.0-preview.9/band-agent-powerprofile-0.1.0-preview.9.rpk")) throw new AssertionError();
        rejects(() -> SignedFeed.gatewayArtifact(item().getString("url"), "wearable"));
        rejects(() -> feed(pp).component("powerprofile", "wearable", "M2551B1", "CN", 4, 6));
        System.out.println("PowerProfile component and fixed mirror policy passed");
    }
}
