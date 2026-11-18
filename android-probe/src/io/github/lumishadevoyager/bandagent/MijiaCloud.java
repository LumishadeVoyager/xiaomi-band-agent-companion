package io.github.lumishadevoyager.bandagent;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.zip.GZIPInputStream;
import javax.net.ssl.HttpsURLConnection;
import org.json.*;

/** Narrow MIoT on/off client. HTTPS goes from the phone directly to Xiaomi.
 * Wire protocol follows the installed mijiaAPI (MIT) client used by PowerProfile;
 * see docs/POWERPROFILE_MIJIA.md for provenance and session lifetime limitations.
 */
public final class MijiaCloud {
    private final JSONObject auth, device;
    private static final java.nio.charset.Charset UTF8 = StandardCharsets.UTF_8;
    public MijiaCloud(JSONObject auth, JSONObject device) throws Exception {
        this.auth = auth; this.device = device;
        for (String key : new String[]{"cUserId", "serviceToken", "ssecurity", "userAgent", "deviceId"}) {
            String value = auth.getString(key);
            if (value.isEmpty() || value.length() > 4096 || value.contains("\r") || value.contains("\n") || value.contains(";")) throw new SecurityException("invalid_session");
        }
        if (!device.getString("did").matches("[0-9]{1,24}") || device.getInt("siid") <= 0 || device.getInt("piid") <= 0) throw new SecurityException("invalid_device");
    }
    static String b64(byte[] value) { return Base64.getEncoder().encodeToString(value); }
    static byte[] rc4(byte[] key, byte[] input) {
        int[] s = new int[256]; for (int i=0;i<256;i++) s[i]=i;
        int j=0; for(int i=0;i<256;i++){j=(j+s[i]+(key[i%key.length]&255))&255;int t=s[i];s[i]=s[j];s[j]=t;}
        byte[] out=new byte[input.length];int i=0;j=0;
        for(int p=-1024;p<input.length;p++){i=(i+1)&255;j=(j+s[i])&255;int t=s[i];s[i]=s[j];s[j]=t;int k=s[(s[i]+s[j])&255];if(p>=0)out[p]=(byte)(input[p]^k);}
        return out;
    }
    static String signature(String route, String nonce, Map<String,String> params) throws Exception {
        StringBuilder value=new StringBuilder("POST&").append(route);
        for(Map.Entry<String,String> p:params.entrySet())value.append('&').append(p.getKey()).append('=').append(p.getValue());
        return b64(MessageDigest.getInstance("SHA-1").digest(value.append('&').append(nonce).toString().getBytes(UTF8)));
    }
    static Map<String,String> parameters(String route, String data, String secret, String nonce) throws Exception {
        byte[] rawSecret=Base64.getDecoder().decode(secret),rawNonce=Base64.getDecoder().decode(nonce);
        MessageDigest hash=MessageDigest.getInstance("SHA-256");hash.update(rawSecret);byte[] key=hash.digest(rawNonce);String signed=b64(key);
        Map<String,String> params=new LinkedHashMap<>();params.put("data",data);params.put("rc4_hash__",signature(route,signed,params));
        for(Map.Entry<String,String> p:params.entrySet())p.setValue(b64(rc4(key,p.getValue().getBytes(UTF8))));
        params.put("signature",signature(route,signed,params));params.put("ssecurity",secret);params.put("_nonce",nonce);return params;
    }
    private JSONArray request(String route, JSONObject data) throws Exception {
        byte[] nonce=new byte[12];new SecureRandom().nextBytes(nonce);ByteBuffer.wrap(nonce,8,4).putInt((int)(System.currentTimeMillis()/60000));
        String nonceText=b64(nonce),secret=auth.getString("ssecurity");
        Map<String,String> params=parameters(route,data.toString(),secret,nonceText);StringBuilder form=new StringBuilder();
        for(Map.Entry<String,String> p:params.entrySet()){if(form.length()>0)form.append('&');form.append(URLEncoder.encode(p.getKey(),"UTF-8")).append('=').append(URLEncoder.encode(p.getValue(),"UTF-8"));}
        HttpsURLConnection c=(HttpsURLConnection)new URL("https://api.io.mi.com/app"+route).openConnection();
        c.setRequestMethod("POST");c.setInstanceFollowRedirects(false);c.setConnectTimeout(5000);c.setReadTimeout(7000);c.setDoOutput(true);
        c.setRequestProperty("User-Agent",auth.getString("userAgent"));c.setRequestProperty("Accept-Encoding","identity");
        c.setRequestProperty("Content-Type","application/x-www-form-urlencoded");c.setRequestProperty("miot-accept-encoding","GZIP");c.setRequestProperty("miot-encrypt-algorithm","ENCRYPT-RC4");c.setRequestProperty("x-xiaomi-protocal-flag-cli","PROTOCAL-HTTP2");
        String token=auth.getString("serviceToken");c.setRequestProperty("Cookie","cUserId="+auth.getString("cUserId")+";yetAnotherServiceToken="+token+";serviceToken="+token+";timezone_id=Asia/Shanghai;timezone=GMT+08:00;is_daylight=0;dst_offset=0;channel=MI_APP_STORE;countryCode=CN;PassportDeviceId="+auth.getString("deviceId")+";locale=zh_CN");
        byte[] body=form.toString().getBytes(UTF8);c.setFixedLengthStreamingMode(body.length);
        try {
            try(OutputStream out=c.getOutputStream()){out.write(body);}
            if(c.getResponseCode()!=200)throw new IOException("mijia_http_"+c.getResponseCode());
            byte[] raw=read(c.getInputStream());JSONObject result;
            String text=new String(raw,UTF8).trim();
            if(text.startsWith("{"))result=new JSONObject(text);
            else {
                MessageDigest hash=MessageDigest.getInstance("SHA-256");hash.update(Base64.getDecoder().decode(secret));byte[] key=hash.digest(nonce);
                byte[] decoded=rc4(key,Base64.getDecoder().decode(text));
                if(decoded.length>1&&(decoded[0]&255)==31&&(decoded[1]&255)==139)decoded=read(new GZIPInputStream(new ByteArrayInputStream(decoded)));
                result=new JSONObject(new String(decoded,UTF8));
            }
            if(result.optInt("code",0)!=0||!result.has("result"))throw new IOException("mijia_session_or_api_error");
            return result.getJSONArray("result");
        } finally {c.disconnect();}
    }
    private static byte[] read(InputStream stream) throws Exception {
        try(InputStream in=stream;ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] chunk=new byte[4096];int n;while((n=in.read(chunk))!=-1){if(out.size()+n>65536)throw new IOException("response_too_large");out.write(chunk,0,n);}return out.toByteArray();}
    }
    private JSONObject property() throws Exception {return new JSONObject().put("did",device.getString("did")).put("siid",device.getInt("siid")).put("piid",device.getInt("piid"));}
    private JSONObject matching(JSONArray rows) throws Exception {
        for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);if(device.getString("did").equals(row.optString("did"))&&device.getInt("siid")==row.optInt("siid")&&device.getInt("piid")==row.optInt("piid"))return row;}
        throw new IOException("mijia_device_not_returned");
    }
    public boolean read() throws Exception {
        JSONObject row=matching(request("/miotspec/prop/get",new JSONObject().put("params",new JSONArray().put(property())).put("datasource",1)));
        if(row.getInt("code")!=0||!(row.get("value") instanceof Boolean))throw new IOException("mijia_state_unknown");return row.getBoolean("value");
    }
    public boolean set(boolean desired) throws Exception {
        JSONObject row=matching(request("/miotspec/prop/set",new JSONObject().put("params",new JSONArray().put(property().put("value",desired)))));
        int code=row.getInt("code");if(code!=0&&code!=1)throw new IOException("mijia_command_rejected");
        for(int attempt=0;attempt<4;attempt++){if(attempt>0)Thread.sleep(1000);if(read()==desired)return desired;}
        throw new IOException("mijia_readback_mismatch");
    }
}
