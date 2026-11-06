package com.tokenmonitor.mobile;

import android.app.*;
import android.content.*;
import android.os.Build;
import android.util.Base64;
import org.json.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;

final class CodexNotifications {
    static final String CHANNEL="codex_complete_v2", BACKGROUND="codex_receive_v1";
    static final String[] KINDS = {"complete", "input", "quota"};
    static volatile MainActivity foregroundActivity;
    static android.content.SharedPreferences prefs(Context c){return c.getSharedPreferences("codex_notifications",Context.MODE_PRIVATE);}
    static void channels(Context c){
        NotificationManager manager=c.getSystemService(NotificationManager.class);
        NotificationChannel complete=new NotificationChannel(CHANNEL,"Codex 通知",NotificationManager.IMPORTANCE_HIGH);
        complete.setDescription("Codex 任务完成、需要输入与额度不足");
        complete.enableVibration(true);complete.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
        manager.createNotificationChannel(complete);
        NotificationChannel background=new NotificationChannel(BACKGROUND,"通知接收服务",NotificationManager.IMPORTANCE_LOW);
        background.setSound(null,null);background.setShowBadge(false);
        manager.createNotificationChannel(background);
    }
    static boolean allowed(Context c){
        NotificationManager m=c.getSystemService(NotificationManager.class);
        NotificationChannel channel=m.getNotificationChannel(CHANNEL);
        return m.areNotificationsEnabled()&&(channel==null||channel.getImportance()!=NotificationManager.IMPORTANCE_NONE)
            &&(Build.VERSION.SDK_INT<33||c.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)==android.content.pm.PackageManager.PERMISSION_GRANTED);
    }
    static boolean selected(SharedPreferences p, String kind) {
        return p.getBoolean("kind_" + kind, kind.equals("complete") && p.getBoolean("enabled", false));
    }

    static boolean knownKind(String kind) {
        for (String candidate : KINDS) if (candidate.equals(kind)) return true;
        return false;
    }

    static JSONObject state(Context c) {
        SharedPreferences p = prefs(c);
        boolean allowed = allowed(c);
        JSONObject kinds = new JSONObject();
        JSONObject result = new JSONObject();
        try {
            for (String kind : KINDS) kinds.put(kind, allowed && selected(p, kind));
            result.put("kinds", kinds).put("enabled", allowed && p.getBoolean("enabled", false));
        } catch (JSONException ignored) {}
        return result;
    }

    static void setKind(Context c, String kind, boolean checked) {
        SharedPreferences p = prefs(c);
        SharedPreferences.Editor edit = p.edit();
        boolean enabled = false;
        for (String candidate : KINDS) {
            boolean on = candidate.equals(kind) ? checked : selected(p, candidate);
            edit.putBoolean("kind_" + candidate, on);
            enabled |= on;
        }
        if (!enabled || !p.getBoolean("enabled", false)) edit.remove("cursor");
        edit.putBoolean("enabled", enabled).apply();
        if (enabled) start(c);
        else c.stopService(new Intent(c, CodexNotificationService.class));
    }

    static void start(Context c) {
        c.startForegroundService(new Intent(c, CodexNotificationService.class));
    }

    static String savePairing(Context c,String code){
        try{
            if(code==null||code.length()>12000||!code.trim().startsWith("TMN1."))throw new IllegalArgumentException("请粘贴电脑通知组件生成的完整配对码");
            JSONObject data=new JSONObject(new String(Base64.decode(code.trim().substring(5),Base64.URL_SAFE|Base64.NO_WRAP),StandardCharsets.UTF_8));
            String url=data.getString("url").trim().replaceAll("/+$","");URI u=new URI(url);
            String host=u.getHost();String pin=data.optString("pin","").toLowerCase(java.util.Locale.ROOT);
            boolean local=host!=null&&host.equals("localhost");
            if(host!=null&&host.matches("[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+")){
                String[] parts=host.split("\\.");int a=Integer.parseInt(parts[0]),b=Integer.parseInt(parts[1]);
                boolean valid=true;for(String part:parts)valid&=Integer.parseInt(part)<=255;
                local=valid&&(a==10||a==127||(a==192&&b==168)||(a==172&&b>=16&&b<=31));
            }
            if(host==null||u.getUserInfo()!=null||u.getQuery()!=null||u.getFragment()!=null||(!u.getPath().isEmpty())||u.getPort()==0||u.getPort()>65535
                ||!("https".equals(u.getScheme())||("http".equals(u.getScheme())&&local)))throw new IllegalArgumentException("通知地址需使用 HTTPS；HTTP 仅支持局域网");
            if(!pin.isEmpty()&&!pin.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("证书指纹无效");
            String key=data.getString("key");if(!key.matches("[A-Za-z0-9_-]{32,128}"))throw new IllegalArgumentException("通知密钥无效");
            boolean changed=!url.equals(prefs(c).getString("url",""))||!key.equals(prefs(c).getString("key",""))||!pin.equals(prefs(c).getString("pin",""));
            var edit=prefs(c).edit().putString("url",url).putString("key",key).putString("pin",pin);
            if(changed)edit.remove("cursor");
            edit.apply();
            return "";
        }catch(Exception e){return e instanceof IllegalArgumentException?e.getMessage():"配对码格式不正确，请重新复制";}
    }
    static PendingIntent open(Context c){return PendingIntent.getActivity(c,0,new Intent(c,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP|Intent.FLAG_ACTIVITY_CLEAR_TOP),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);}
    static void show(Context c,String id,String kind,String title,String body,String sessionName,boolean silent){
        if(!allowed(c))return;
        // Android's notification framework caps individual CharSequences at 5120 chars.
        String text=body.length()>5000?body.substring(0,4998)+"…":body;
        String label = switch (kind) {
            case "input" -> "任务需要输入";
            case "quota" -> "额度不足";
            default -> "任务结束";
        };
        String context = "Codex · " + label;
        if (!sessionName.trim().isEmpty()) context += " · " + sessionName;
        Notification notification=new Notification.Builder(c,CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title).setContentText(text).setSubText(context)
            .setStyle(new Notification.BigTextStyle().bigText(text)).setContentIntent(open(c))
            .setCategory(Notification.CATEGORY_MESSAGE).setVisibility(Notification.VISIBILITY_PRIVATE)
            .setAutoCancel(true).setOnlyAlertOnce(true).setGroup("codex_tasks").setGroupAlertBehavior(silent?Notification.GROUP_ALERT_SUMMARY:Notification.GROUP_ALERT_ALL).build();
        c.getSystemService(NotificationManager.class).notify("codex:"+id,2,notification);
        MainActivity activity = foregroundActivity;
        if (!silent && activity != null) activity.showCompletionBanner(context, title, text);
    }
}
