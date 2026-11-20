package io.github.lumishadevoyager.bandagent;

import android.content.*;
import android.security.keystore.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.Base64;
import javax.crypto.*;
import javax.crypto.spec.*;
import org.json.*;

/** Phone-owned power control: never depends on the PC being awake. */
public final class MijiaPower {
    private final Context context;
    private final SharedPreferences prefs;
    private volatile JSONObject config;
    private volatile Boolean actual;
    private volatile String phase="unconfigured", commandId="", commandPhase="";
    private volatile boolean commandBusy, sourceOnline;
    private volatile long sourceAt, onRequestedAt, sourceObservedAt;
    private final Object commands=new Object();
    private final java.util.concurrent.Executor reports;
    private final Runnable changed;
    private volatile long observedAt, nextRead, commandEpoch;
    private long nextConfig;
    public MijiaPower(Context context,java.util.concurrent.Executor reports,Runnable changed) {
        this.context=context;this.reports=reports;this.changed=changed;prefs=context.getSharedPreferences("mijia_power",0);
        try {
            String stored=prefs.getString("sealed","");
            if(!stored.isEmpty()){JSONObject seal=new JSONObject(stored);Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,localKey(),new GCMParameterSpec(128,Base64.getDecoder().decode(seal.getString("nonce"))));config=verify(c.doFinal(Base64.getDecoder().decode(seal.getString("ciphertext"))));}
        }catch(Exception e){phase="auth_required";}
    }
    private javax.crypto.SecretKey localKey() throws Exception {
        KeyStore keys=KeyStore.getInstance("AndroidKeyStore");keys.load(null);
        String alias="powerprofile_mijia_session_v1";
        if(!keys.containsAlias(alias)){KeyGenerator g=KeyGenerator.getInstance("AES","AndroidKeyStore");g.init(new KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());g.generateKey();}
        return (javax.crypto.SecretKey)keys.getKey(alias,null);
    }
    private JSONObject verify(byte[] envelope) throws Exception {
        PublicKey root=SignedFeed.publicKey(new String(OtaCoordinator.read(context.getAssets().open("update-public.pem"),8192),StandardCharsets.US_ASCII));
        JSONObject v=SignedFeed.verifyEnvelope(envelope,root);long now=System.currentTimeMillis()/1000,issued=v.getLong("issuedAt"),expires=v.getLong("expiresAt");
        if(!"powerprofile_mijia_pairing".equals(v.getString("purpose"))||!SignedFeed.REPOSITORY.equals(v.getString("repository"))||!context.getPackageName().equals(v.getString("appId"))||issued>now+300||expires<=now||expires<=issued||expires-issued>30*86400||v.getLong("revision")<prefs.getLong("revision",0))throw new SecurityException("invalid_mijia_pairing");
        String active=v.getString("activeTarget");if(!active.equals("computer")&&!active.equals("purifier"))throw new SecurityException("invalid_target");
        JSONObject targets=v.getJSONObject("targets");
        for(String name:new String[]{"computer","purifier"}){
            JSONObject d=targets.getJSONObject(name);
            if(!(name.equals("computer")?"cuco.plug.v3".equals(d.getString("model")):d.getString("model").contains(".airpurifier.")))throw new SecurityException("wrong_device_model");
            new MijiaCloud(v.getJSONObject("auth"),d);
        }
        return v;
    }
    private MijiaCloud client(String target) throws Exception {
        if(config==null||config.getLong("expiresAt")<=System.currentTimeMillis()/1000)throw new SecurityException("session_expired");
        return new MijiaCloud(config.getJSONObject("auth"),config.getJSONObject("targets").getJSONObject(target));
    }
    private String target(){return config==null?"computer":config.optString("activeTarget","computer");}
    public JSONObject state() throws Exception {
        boolean fresh=actual!=null&&System.currentTimeMillis()-observedAt<45000;
        long now=System.currentTimeMillis();
        String computer=now-sourceObservedAt>45000?"unknown":sourceOnline&&now-sourceAt<45000&&sourceAt>=onRequestedAt?"online":onRequestedAt>0&&now-onRequestedAt<90000?"starting":"offline";
        return new JSONObject().put("protocol",1).put("entryId","powerprofile").put("type","powerprofile_power_state").put("target",target()).put("value",fresh?actual:JSONObject.NULL).put("available",fresh&&!commandBusy&&!prefs.contains("restoreValue")).put("phase",phase).put("observedAt",observedAt).put("commandId",commandId).put("commandPhase",commandPhase).put("computerStatus",computer);
    }
    public void computerObservation(boolean online,long at){sourceOnline=online;sourceAt=at;sourceObservedAt=System.currentTimeMillis();changed.run();}
    private void report(ComputerLink link,String result,String testId,Boolean value) throws Exception {
        final JSONObject body=new JSONObject().put("protocol",1).put("entryId","powerprofile").put("target",target()).put("revision",config.getLong("revision")).put("phase",result).put("value",value==null?JSONObject.NULL:value).put("testId",testId).put("phoneVersion",ApkUpdateInstaller.current(context));
        // An audit upload must never delay a wrist receipt or invalidate a readback.
        reports.execute(()->{try{link.post("/powerprofile/mijia-report",body);}catch(Exception ignored){}});
    }
    public void tick(ComputerLink link) {
        long now=System.currentTimeMillis();if(!link.internet())return;
        if(now>=nextConfig&&!commandBusy){nextConfig=now+60000;
            try {
                JSONObject seal=link.post("/powerprofile/mijia-config",new JSONObject().put("protocol",1).put("entryId","powerprofile")).optJSONObject("config");
                if(seal!=null){Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,new SecretKeySpec(link.mijiaPairingKey(),"AES"),new GCMParameterSpec(128,Base64.getDecoder().decode(seal.getString("nonce"))));c.updateAAD("powerprofile-mijia-v1".getBytes(StandardCharsets.UTF_8));byte[] envelope=c.doFinal(Base64.getDecoder().decode(seal.getString("ciphertext")));JSONObject incoming=verify(envelope);
                    synchronized(commands){if(!commandBusy&&(config==null||incoming.getLong("revision")>config.getLong("revision"))){
                        if(prefs.contains("restoreValue"))restore(link);
                        Cipher local=Cipher.getInstance("AES/GCM/NoPadding");local.init(Cipher.ENCRYPT_MODE,localKey());JSONObject saved=new JSONObject().put("nonce",Base64.getEncoder().encodeToString(local.getIV())).put("ciphertext",Base64.getEncoder().encodeToString(local.doFinal(envelope)));
                        if(!prefs.edit().putString("sealed",saved.toString()).putLong("revision",incoming.getLong("revision")).commit())throw new java.io.IOException("pairing_not_saved");
                        config=incoming;actual=null;nextRead=0;phase="ready";prefs.edit().putString("configError","").apply();
                    }}
                }
            }catch(Exception error){String code=error.getMessage();prefs.edit().putString("configError",code!=null&&code.matches("[a-zA-Z0-9_]{1,80}")?code:error.getClass().getSimpleName()).apply();if(config==null)phase="auth_required";}
        }
        if(config==null)return;
        long readEpoch=commandEpoch;
        try {
            if(prefs.contains("restoreValue"))restore(link);
            if(prefs.getBoolean("testReportPending",false)){report(link,prefs.getString("testResultPhase","failed"),prefs.getString("testedId",""),actual);prefs.edit().putBoolean("testReportPending",false).commit();}
            JSONObject test=config.optJSONObject("acceptanceTest");
            if(test!=null&&target().equals("purifier")&&test.getLong("expiresAt")>now/1000&&!test.getString("id").equals(prefs.getString("testedId","")))testPurifier(link,test.getString("id"));
            if(now>=nextRead&&!commandBusy){nextRead=now+30000;readEpoch=commandEpoch;boolean read=client(target()).read();if(!commandBusy&&readEpoch==commandEpoch){actual=read;observedAt=System.currentTimeMillis();phase="ready";changed.run();report(link,"ready",prefs.getString("testedId",""),actual);}}
        }catch(Exception error){if(!commandBusy&&readEpoch==commandEpoch){actual=null;phase=prefs.contains("restoreValue")?"restore_pending":error instanceof SecurityException?"auth_required":"failed";changed.run();try{report(link,phase,prefs.getString("testedId",""),null);}catch(Exception unavailable){}}}
    }
    private void restore(ComputerLink link) throws Exception {
        // Recovery is pinned to the purifier even if a newer config selects PC power.
        if(!prefs.contains("restoreValue"))return;
        boolean original=prefs.getBoolean("restoreValue",false);client("purifier").set(original);
        String result=prefs.getBoolean("testTransitionVerified",false)?"restored":"failed";
        prefs.edit().remove("restoreValue").putString("testResultPhase",result).putBoolean("testReportPending",true).commit();actual=original;observedAt=System.currentTimeMillis();phase="ready";
        report(link,result,prefs.getString("testedId",""),actual);prefs.edit().putBoolean("testReportPending",false).commit();
    }
    private void testPurifier(ComputerLink link,String id) throws Exception {
        // A root-signed, short-lived acceptance request can ONLY test a purifier.
        MijiaCloud test=client("purifier");boolean original=test.read();
        if(!prefs.edit().putString("testedId",id).putBoolean("restoreValue",original).putBoolean("testTransitionVerified",false).commit())throw new java.io.IOException("test_not_saved");
        Exception failed=null;
        try{test.set(!original);prefs.edit().putBoolean("testTransitionVerified",true).commit();Thread.sleep(2000);}catch(Exception e){failed=e;}
        finally{restore(link);}
        if(failed!=null)throw failed;
        // Retained separately from routine ready reports for remote verification.
        prefs.edit().putString("lastTestReport",new JSONObject().put("id",id).put("phase","restored").put("original",original).put("restoredAt",System.currentTimeMillis()).toString()).commit();
        report(link,"restored",id,actual);
    }
    public static final class Command {
        final String id;final boolean desired;final MijiaCloud client;
        Command(String id,boolean desired,MijiaCloud client){this.id=id;this.desired=desired;this.client=client;}
    }
    public Command accept(JSONObject message) throws Exception {synchronized(commands){
        String id=message.getString("id");long age=System.currentTimeMillis()-message.getLong("issuedAt");
        if(!id.matches("[a-f0-9]{32}")||age< -5000||age>30000||!(message.get("desired") instanceof Boolean)||!target().equals(message.getString("target")))throw new SecurityException("invalid_power_request");
        java.util.Set<String> seen=new java.util.LinkedHashSet<>(prefs.getStringSet("commands",java.util.Collections.emptySet()));
        if(seen.contains(id)){changed.run();return null;}
        if(commandBusy)throw new java.io.IOException("power_command_busy");
        if(actual==null||System.currentTimeMillis()-observedAt>45000||prefs.contains("restoreValue"))throw new java.io.IOException("power_state_unknown");
        // Durable receipt before I/O prevents a retransmitted tap issuing twice.
        seen.add(id);while(seen.size()>100)seen.remove(seen.iterator().next());
        if(!prefs.edit().putStringSet("commands",seen).commit())throw new java.io.IOException("command_not_saved");
        Command command=new Command(id,message.getBoolean("desired"),client(target()));
        commandId=id;commandPhase="accepted";commandBusy=true;commandEpoch++;phase="pending";changed.run();return command;
    }}
    public void execute(ComputerLink link,Command command) {
        commandPhase="pending";changed.run();
        try{actual=command.client.set(command.desired);observedAt=System.currentTimeMillis();if(command.desired)onRequestedAt=observedAt;else onRequestedAt=0;phase="confirmed";commandPhase="confirmed";commandBusy=false;changed.run();try{report(link,"confirmed","",actual);}catch(Exception ignored){}}
        catch(Exception e){actual=null;phase="failed";commandPhase="failed";commandBusy=false;nextRead=0;changed.run();try{report(link,"failed","",null);}catch(Exception ignored){}}
    }
}
