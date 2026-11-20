"""Run actual notification bridge against a fake OS and relay, without wearable SDK.

This tests ordering/ACK semantics, not Android background or physical delivery.
"""
import argparse
import os
from pathlib import Path
import subprocess
import tempfile

p = argparse.ArgumentParser()
p.add_argument('--jdk', type=Path, required=True)
p.add_argument('--json-jar', type=Path, required=True)
a = p.parse_args()
root = Path(__file__).resolve().parents[1]
sources = {
'android/content/SharedPreferences.java': '''package android.content;
import java.util.*;
public class SharedPreferences {
 Map<String,Object> values=new HashMap<>();
 public String getString(String k,String d){return (String)values.getOrDefault(k,d);}
 public Set<String> getStringSet(String k,Set<String> d){return (Set<String>)values.getOrDefault(k,d);}
 public Editor edit(){return new Editor();}
 public class Editor {public Editor putString(String k,String v){values.put(k,v);return this;}
 public Editor putLong(String k,long v){values.put(k,v);return this;}
 public Editor putStringSet(String k,Set<String> v){values.put(k,new HashSet<>(v));return this;}
 public void apply(){} public boolean commit(){return true;}}
}''',
'android/content/Intent.java': 'package android.content; public class Intent {public Intent(Object c,Class<?> t){}}',
'android/content/pm/PackageManager.java': 'package android.content.pm; public class PackageManager {public static final int PERMISSION_GRANTED=0;}',
'android/R.java': 'package android; public class R {public static class drawable {public static int stat_notify_chat=1;}}',
'android/app/Service.java': '''package android.app;
public class Service {public boolean allowed=true;
 public final NotificationManager manager=new NotificationManager();
 public final android.content.SharedPreferences prefs=new android.content.SharedPreferences();
 public android.content.SharedPreferences getSharedPreferences(String n,int m){return prefs;}
 public <T> T getSystemService(Class<T> c){return c.cast(manager);}
 public int checkSelfPermission(String p){return allowed?0:-1;}}''',
'android/app/Notification.java': '''package android.app; public class Notification {public static class Builder {
 public Builder(Object c,String channel){} public Builder setSmallIcon(int v){return this;}
 public Builder setContentTitle(String v){return this;} public Builder setContentText(String v){return this;}
 public Builder setContentIntent(PendingIntent v){return this;} public Builder setAutoCancel(boolean v){return this;}
 public Builder setOnlyAlertOnce(boolean v){return this;} public Notification build(){return new Notification();}}}''',
'android/app/PendingIntent.java': '''package android.app; public class PendingIntent {public static final int FLAG_IMMUTABLE=1;
 public static PendingIntent getActivity(Object c,int n,Object i,int f){return new PendingIntent();}}''',
'android/app/NotificationChannel.java': '''package android.app; public class NotificationChannel {
 public int importance; public NotificationChannel(String id,String title,int level){importance=level;}
 public int getImportance(){return importance;}}''',
'android/app/NotificationManager.java': '''package android.app; public class NotificationManager {
 public static final int IMPORTANCE_DEFAULT=3,IMPORTANCE_NONE=0;
 public int posted; public NotificationChannel channel;
 public void createNotificationChannel(NotificationChannel c){channel=c;}
 public NotificationChannel getNotificationChannel(String id){return channel;}
 public boolean areNotificationsEnabled(){return true;}
 public void notify(String tag,int id,Notification n){posted++;}}''',
'io/github/lumishadevoyager/bandagent/BridgeActivity.java': 'package io.github.lumishadevoyager.bandagent; public class BridgeActivity {}',
'io/github/lumishadevoyager/bandagent/ComputerLink.java': '''package io.github.lumishadevoyager.bandagent;
import org.json.*; public class ComputerLink {
 public int acknowledgements; public boolean failAck; public JSONObject response;
 public boolean paired(){return true;} public boolean internet(){return true;}
 public JSONObject post(String route,JSONObject data) throws Exception {
 if(route.endsWith("/ack")){if(failAck)throw new java.io.IOException("offline");acknowledgements++;return new JSONObject();}
 return response;}}''',
'NotificationBridgeTest.java': '''import android.app.*; import org.json.*; import io.github.lumishadevoyager.bandagent.*;
public class NotificationBridgeTest {
 static void check(boolean ok){if(!ok)throw new AssertionError();}
 public static void main(String[] args)throws Exception {
 Service os=new Service(); ComputerLink relay=new ComputerLink(); PowerProfileBridge bridge=new PowerProfileBridge(os);
 relay.response=new JSONObject().put("snapshot",new JSONObject().put("marker",17)).put("events",new JSONArray().put(new JSONObject().put("id",new String(new char[64]).replace('\\0','a'))));
 os.allowed=false;bridge.poll(relay);check(os.manager.posted==0&&relay.acknowledgements==0&&os.prefs.getString("snapshot", "").contains("17"));
 os.allowed=true;relay.failAck=true;try{bridge.poll(relay);throw new AssertionError();}catch(java.io.IOException expected){}
 check(os.manager.posted==1&&relay.acknowledgements==0);
 relay.failAck=false;bridge.poll(relay);check(os.manager.posted==1&&relay.acknowledgements==1);
 os.manager.channel.importance=0;bridge.poll(relay);check(relay.acknowledgements==1);
 System.out.println("Actual notification bridge: no SDK dependency, cache without permission, withheld ACK, ACK retry without duplicate alert, disabled channel passed");
 }}'''
}
with tempfile.TemporaryDirectory(prefix='powerprofile-notices-') as folder:
    work = Path(folder)
    files = []
    for name, source in sources.items():
        path = work / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(source, encoding='utf-8')
        files.append(str(path))
    files.append(str(root / 'android-probe/src/io/github/lumishadevoyager/bandagent/PowerProfileBridge.java'))
    suffix = '.exe' if os.name == 'nt' else ''
    subprocess.run([str(a.jdk / ('bin/javac'+suffix)), '-encoding', 'UTF-8', '-cp', str(a.json_jar), '-d', str(work), *files], check=True)
    subprocess.run([str(a.jdk / ('bin/java'+suffix)), '-cp', str(work)+os.pathsep+str(a.json_jar), 'NotificationBridgeTest'], check=True)
