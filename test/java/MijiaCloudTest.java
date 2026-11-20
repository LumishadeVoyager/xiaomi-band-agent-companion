package io.github.lumishadevoyager.bandagent;
import java.util.*;
import java.nio.charset.StandardCharsets;
import org.json.*;

/** Fixture generated with installed mijiaAPI 5.0.0, entirely synthetic keys. */
public final class MijiaCloudTest {
    public static void main(String[] args) throws Exception {
        String secret="AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",nonce="AAECAwQFBgcICQoL";
        Map<String,String> p=MijiaCloud.parameters("/miotspec/prop/get","{\"params\":[{\"did\":\"123\",\"siid\":2,\"piid\":1}],\"datasource\":1}",secret,nonce);
        if(!"ZMXpzD6PKoYGq+UAcjSzrqon3P+wqTaLN31f59lE5U/zUdPen8qwbKxTPz850b/kFPeFh0ajTc2gsCQ=".equals(p.get("data")))throw new AssertionError("encrypted data differs");
        if(!"Z6/Pgnq3MNpV8409CX+7iNBeyozuqkbWTUdZsw==".equals(p.get("rc4_hash__")))throw new AssertionError("RC4 hash differs");
        if(!"MhUhGhckCX9O2tZt1PbMSNBd70s=".equals(p.get("signature")))throw new AssertionError("signature differs");
        byte[] key=Base64.getDecoder().decode(secret),raw="synthetic Chinese 中文".getBytes(StandardCharsets.UTF_8);
        if(!Arrays.equals(raw,MijiaCloud.rc4(key,MijiaCloud.rc4(key,raw))))throw new AssertionError("roundtrip");
        JSONObject auth=new JSONObject().put("cUserId","a").put("serviceToken","b").put("ssecurity",secret).put("deviceId","d").put("userAgent","test");
        new MijiaCloud(auth,new JSONObject().put("did","123").put("siid",2).put("piid",1));
        try{new MijiaCloud(auth.put("serviceToken","bad\r\nCookie: injected"),new JSONObject().put("did","123").put("siid",2).put("piid",1));throw new AssertionError("header injection accepted");}catch(SecurityException expected){}
        System.out.println("Mijia cloud: Python/Java encryption parity, roundtrip and bounded session checks passed; no device writes.");
    }
}
