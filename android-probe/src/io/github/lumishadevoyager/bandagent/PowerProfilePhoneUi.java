package io.github.lumishadevoyager.bandagent;

import android.app.Activity;
import android.content.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.*;
import android.widget.*;

/** Lightweight native status page. Leaving the page does not own service life. */
public final class PowerProfilePhoneUi {
    private final Activity activity;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final int ink=Color.rgb(236,242,249),muted=Color.rgb(145,157,173),mint=Color.rgb(160,230,182);
    private TextView service,wear,sync,power,battery,receipt,detail;
    private String message="";
    private final Runnable refresh=new Runnable(){public void run(){render();handler.postDelayed(this,2000);}};
    public PowerProfilePhoneUi(Activity activity){this.activity=activity;}
    private int dp(float value){return (int)(value*activity.getResources().getDisplayMetrics().density+.5f);}
    private GradientDrawable background(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private TextView text(String value,int size,int color){TextView v=new TextView(activity);v.setText(value);v.setTextColor(color);v.setTextSize(size);return v;}
    private LinearLayout column(){LinearLayout v=new LinearLayout(activity);v.setOrientation(LinearLayout.VERTICAL);return v;}
    private LinearLayout card(LinearLayout root,String title){LinearLayout v=column();v.setPadding(dp(20),dp(18),dp(20),dp(18));v.setBackground(background(Color.rgb(24,30,39),22));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.bottomMargin=dp(12);root.addView(v,p);TextView heading=text(title,17,ink);heading.setTypeface(null,Typeface.BOLD);v.addView(heading);return v;}
    private TextView row(LinearLayout parent,String title){LinearLayout line=new LinearLayout(activity);line.setGravity(Gravity.CENTER_VERTICAL);line.setPadding(0,dp(10),0,dp(4));line.addView(text(title,14,muted),new LinearLayout.LayoutParams(0,-2,1));TextView value=text("—",14,ink);value.setGravity(Gravity.END);line.addView(value);parent.addView(line);return value;}
    private void button(LinearLayout parent,String label,Runnable action,boolean accent){Button b=new Button(activity);b.setText(label);b.setAllCaps(false);b.setTextSize(14);b.setTextColor(accent?Color.rgb(17,33,24):ink);b.setBackground(background(accent?mint:Color.rgb(38,47,60),16));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(48));p.topMargin=dp(10);parent.addView(b,p);b.setOnClickListener(v->action.run());}
    private void action(String action){try{activity.startForegroundService(new Intent(activity,BridgeService.class).setAction(action));message="已请求，正在处理";render();}catch(RuntimeException e){message="请检查后台权限后重试";render();}}
    private void open(Intent intent){try{activity.startActivity(intent);}catch(RuntimeException e){message="此系统未提供该入口，请在应用设置中检查";render();}}
    public View create(Runnable connect,Runnable openWatch,Runnable installWatch){
        ScrollView scroll=new ScrollView(activity);scroll.setFillViewport(true);scroll.setBackgroundColor(Color.rgb(10,14,20));
        LinearLayout root=column();root.setPadding(dp(20),dp(20),dp(20),dp(24));scroll.addView(root);
        root.setOnApplyWindowInsetsListener((view,insets)->{android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars());root.setPadding(dp(20)+bars.left,dp(20)+bars.top,dp(20)+bars.right,dp(24)+bars.bottom);return insets;});
        TextView title=text("PowerProfile",30,ink);title.setTypeface(null,Typeface.BOLD);root.addView(title);
        TextView subtitle=text("手环伴侣   ·   v"+version(),14,muted);LinearLayout.LayoutParams sub=new LinearLayout.LayoutParams(-1,-2);sub.topMargin=dp(5);sub.bottomMargin=dp(24);root.addView(subtitle,sub);
        LinearLayout connection=card(root,"连接状态");service=row(connection,"后台服务");wear=row(connection,"手环");sync=row(connection,"最近同步");power=row(connection,"米家云端");receipt=row(connection,"手环回执");
        button(connection,"打开手环应用",openWatch,true);
        LinearLayout keep=card(root,"后台运行");battery=text("读取后台权限…",14,muted);battery.setPadding(0,dp(10),0,0);keep.addView(battery);
        button(keep,"允许后台运行",()->{
            if(PhoneStatus.unrestricted(activity))open(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+activity.getPackageName())));
            else open(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,Uri.parse("package:"+activity.getPackageName())));
        },false);
        button(keep,"通知权限",()->{
            if(activity.checkSelfPermission("android.permission.POST_NOTIFICATIONS")!=android.content.pm.PackageManager.PERMISSION_GRANTED)activity.requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"},10);
            else open(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,activity.getPackageName()));
        },false);
        TextView tip=text("在系统应用设置中允许后台活动和自启动，并将本应用锁定在最近任务。完成后可关闭此页面。持续同步会增加耗电；系统强制停止或超级省电仍可能中断连接。",12,muted);tip.setPadding(0,dp(12),0,0);keep.addView(tip);
        LinearLayout updates=card(root,"更新");
        Switch automatic=new Switch(activity);automatic.setText("自动更新预览版");automatic.setTextColor(ink);automatic.setTextSize(14);automatic.setPadding(0,dp(12),0,0);automatic.setChecked(activity.getSharedPreferences("ota",0).getBoolean("autoUpdates",true));automatic.setOnCheckedChangeListener((v,checked)->activity.getSharedPreferences("ota",0).edit().putBoolean("autoUpdates",checked).apply());updates.addView(automatic);
        button(updates,"检查更新",()->action("check"),false);
        LinearLayout tools=card(root,"连接与维护");LinearLayout extra=column();extra.setVisibility(View.GONE);
        button(tools,"展开维护选项",()->extra.setVisibility(extra.getVisibility()==View.VISIBLE?View.GONE:View.VISIBLE),false);tools.addView(extra);
        button(extra,"重新连接手环",()->{action("reconnect");connect.run();},false);
        button(extra,"配置手环更新通路",()->action("installer_setup"),false);
        button(extra,"打开 AstroBox",()->{Intent i=activity.getPackageManager().getLaunchIntentForPackage("moe.astralsight.astrobox");if(i!=null)open(i);else{message="请先安装 AstroBox";render();}},false);
        button(extra,"允许安装手机更新",()->open(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+activity.getPackageName()))),false);
        button(extra,"手动安装手环包",installWatch,false);
        button(extra,"10 秒后发送测试提醒",()->action("powerprofile_test_notice"),false);
        detail=text("",12,muted);detail.setPadding(0,dp(16),0,0);extra.addView(detail);
        return scroll;
    }
    private String age(long at){if(at<=0)return "等待首次同步";long seconds=Math.max(0,(System.currentTimeMillis()-at)/1000);return seconds<60?seconds+" 秒前":seconds<3600?seconds/60+" 分钟前":seconds/3600+" 小时前";}
    private String version(){try{return String.valueOf(ApkUpdateInstaller.current(activity));}catch(Exception ignored){return "—";}}
    public void note(String value){message=value==null?"":value;render();}
    public void start(){handler.removeCallbacks(refresh);refresh.run();}
    public void stop(){handler.removeCallbacks(refresh);}
    private void render(){
        if(service==null)return;android.content.SharedPreferences s=activity.getSharedPreferences("runtime",0);
        service.setText(BridgeService.running?"● 运行中":"待启动");service.setTextColor(BridgeService.running?mint:muted);
        wear.setText(BridgeService.running&&s.getBoolean("wearConnected",false)?"已连接":"等待连接");sync.setText(age(s.getLong("lastSyncAt",0)));receipt.setText(age(s.getLong("lastWearAt",0)));
        power.setText((s.getString("powerTarget","").equals("purifier")?"净化器测试 · ":"")+(s.getBoolean("powerAvailable",false)&&BridgeService.running?"可用":"等待连接"));
        boolean exempt=PhoneStatus.unrestricted(activity),notices=PhoneStatus.notifications(activity);
        battery.setText((exempt?"✓ 电池限制已解除":"待设置：允许忽略电池优化")+"\n"+(notices?"✓ 通知已开启":"待设置：允许通知"));battery.setTextColor(exempt&&notices?mint:muted);
        android.os.PowerManager pm=activity.getSystemService(android.os.PowerManager.class);
        battery.append("\n"+(s.getBoolean("cpuLease",false)&&BridgeService.running?"✓ 息屏持续同步已启用":"息屏同步等待启动")+(pm.isPowerSaveMode()?" · 省电模式":"")+"\n执行心跳："+age(s.getLong("healthAt",0))+"\n同步错误："+s.getString("syncError", "") );
        detail.setText(message+"\n更新："+activity.getSharedPreferences("ota",0).getString("lastUpdateStatus","等待自动检查"));
    }
}
