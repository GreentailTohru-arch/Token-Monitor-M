package com.tokenmonitor.mobile;

import android.app.*;
import android.content.*;
import android.os.*;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.cert.X509Certificate;
import javax.net.ssl.*;

/** User-enabled foreground receiver, independent of Hub/WebView lifecycles. */
public class CodexNotificationService extends Service {
    static volatile String status="等待连接";
    private volatile boolean active;
    private volatile HttpURLConnection connection;
    private Thread worker;
    @Override public IBinder onBind(Intent intent){return null;}
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        CodexNotifications.channels(this);
        if(!CodexNotifications.prefs(this).getBoolean("enabled",false)||!CodexNotifications.allowed(this)){
            stopSelf();return START_NOT_STICKY;
        }
        if(!active){
            active=true;update("正在连接通知组件");
            worker=new Thread(this::receive,"CodexNotifications");worker.start();
        }
        return START_STICKY;
    }
    void update(String value){
        status=value;
        if(!active)return;
        Notification notification=new Notification.Builder(this,CodexNotifications.BACKGROUND)
            .setSmallIcon(R.drawable.ic_notification).setContentTitle("Codex 通知接收中").setContentText(value)
            .setContentIntent(CodexNotifications.open(this)).setOngoing(true).setOnlyAlertOnce(true)
            .setShowWhen(false).setCategory(Notification.CATEGORY_SERVICE).build();
        startForeground(1,notification);
    }
    void receive(){
        var prefs=CodexNotifications.prefs(this);
        final String url=prefs.getString("url",""),key=prefs.getString("key",""),pin=prefs.getString("pin","");
        int failures=0;boolean first=true;
        while(active&&prefs.getBoolean("enabled",false)){
            HttpURLConnection socket=null;
            try{
                if(!CodexNotifications.allowed(this))break;
                String cursor=prefs.getString("cursor","");
                socket=(HttpURLConnection)new URL(url+"/v1/events?kinds=1&cursor="+URLEncoder.encode(cursor,"UTF-8")+(first?"&wait=0":"")).openConnection();connection=socket;
                socket.setConnectTimeout(8000);socket.setReadTimeout(30000);socket.setInstanceFollowRedirects(false);socket.setUseCaches(false);
                socket.setRequestProperty("Authorization","Bearer "+key);
                if(socket instanceof HttpsURLConnection&&!pin.isEmpty())((HttpsURLConnection)socket).setSSLSocketFactory(pinned(pin));
                int code=socket.getResponseCode();
                if(code!=200)throw new IOException(code==401||code==403?"配对码已失效，请重新配对":"通知组件返回 HTTP "+code);
                ByteArrayOutputStream bytes=new ByteArrayOutputStream();
                try(InputStream input=socket.getInputStream()){
                    byte[] buffer=new byte[8192];int n;
                    while((n=input.read(buffer))!=-1){if(bytes.size()+n>16*1024*1024)throw new IOException("通知响应过大");bytes.write(buffer,0,n);}
                }
                JSONObject result=new JSONObject(bytes.toString(StandardCharsets.UTF_8.name()));JSONArray events=result.getJSONArray("events");
                String next=result.getString("cursor");if(!next.matches("[0-9a-f]{16}:[0-9]+"))throw new IOException("通知组件协议不匹配");
                if(!active||!prefs.getBoolean("enabled",false)||!url.equals(prefs.getString("url",""))||!key.equals(prefs.getString("key","")))break;
                // Keep the latest five enabled events, with one sound for the batch.
                java.util.ArrayDeque<JSONObject> enabledEvents = new java.util.ArrayDeque<>();
                for (int i = 0; i < events.length(); i++) {
                    JSONObject event = events.getJSONObject(i);
                    String kind = event.optString("kind", "complete");
                    if (!event.optString("id").matches("[0-9a-f]{64}") || !CodexNotifications.knownKind(kind)
                        || !CodexNotifications.selected(prefs, kind)) continue;
                    if (enabledEvents.size() == 5) enabledEvents.removeFirst();
                    enabledEvents.addLast(event);
                }
                while (!enabledEvents.isEmpty()) {
                    JSONObject event = enabledEvents.removeFirst();
                    CodexNotifications.show(this, event.getString("id"), event.optString("kind", "complete"),
                        event.getString("title"), event.getString("body"), event.optString("sessionName"), !enabledEvents.isEmpty());
                }
                prefs.edit().putString("cursor",next).apply();failures=0;first=false;update("已连接 · 等待 Codex 消息");
            }catch(Exception error){
                if(active)update(error instanceof SSLException?"证书验证失败，请重新复制配对码":error.getMessage()!=null&&error.getMessage().startsWith("配对码")?error.getMessage():"连接中断 · 正在自动重连");
                failures=Math.min(failures+1,5);
            }finally{if(socket!=null)socket.disconnect();connection=null;}
            if(active&&failures>0)try{Thread.sleep(Math.min(30000,1000L<<failures));}catch(InterruptedException e){break;}
        }
        stopSelf();
    }
    static SSLSocketFactory pinned(String expected)throws Exception{
        SSLContext context=SSLContext.getInstance("TLS");
        context.init(null,new TrustManager[]{new X509TrustManager(){
            public X509Certificate[] getAcceptedIssuers(){return new X509Certificate[0];}
            public void checkClientTrusted(X509Certificate[] chain,String auth)throws java.security.cert.CertificateException{throw new java.security.cert.CertificateException("Client certificate not supported");}
            public void checkServerTrusted(X509Certificate[] chain,String auth)throws java.security.cert.CertificateException{
                try{
                    if(chain.length==0)throw new GeneralSecurityException();chain[0].checkValidity();
                    byte[] digest=MessageDigest.getInstance("SHA-256").digest(chain[0].getEncoded());StringBuilder hex=new StringBuilder();
                    for(byte b:digest)hex.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
                    if(!MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),hex.toString().getBytes(StandardCharsets.US_ASCII)))throw new GeneralSecurityException();
                }catch(Exception e){throw new java.security.cert.CertificateException("Pinned certificate mismatch",e);}
            }
        }},null);
        // Default HTTPS hostname verification is retained.
        return context.getSocketFactory();
    }
    @Override public void onDestroy(){active=false;status="已关闭";if(connection!=null)connection.disconnect();if(worker!=null)worker.interrupt();stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy();}
}
