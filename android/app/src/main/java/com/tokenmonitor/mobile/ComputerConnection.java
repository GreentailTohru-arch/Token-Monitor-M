package com.tokenmonitor.mobile;

import android.bluetooth.*;
import android.content.*;
import android.os.Build;
import org.json.*;
import java.net.URI;

/** Read-only connection to usage statistics and local session details. Hub and computer transports are mutually exclusive. */
final class ComputerConnection implements RpcConnection.Listener {
    final MainActivity activity;
    final SharedPreferences prefs;
    final RpcConnection rpc;
    volatile boolean connected,hasData;
    volatile String reason="未连接电脑";
    String configured="";
    ComputerConnection(MainActivity activity){this.activity=activity;prefs=activity.getSharedPreferences("computer",Context.MODE_PRIVATE);rpc=new RpcConnection(activity,this);}
    boolean enabled(){return prefs.getBoolean("enabled",false);}
    JSONObject config(){try{return new JSONObject(prefs.getString("config","{}"));}catch(Exception e){return new JSONObject();}}
    JSONObject state(){
        JSONObject s=new JSONObject();try{s.put("connected",connected).put("active",enabled()).put("source","computer").put("mode",config().optString("mode","wifi")).put("reason",reason);}catch(Exception ignored){}return s;
    }
    String save(String text){
        try{
            if(text==null||text.length()>16000)throw new IllegalArgumentException("连接信息无效");
            JSONObject c=new JSONObject(text);String mode=c.optString("mode");
            if(!java.util.Set.of("wifi","bluetooth","usb","remote").contains(mode))throw new IllegalArgumentException("请选择连接方式");
            String key=c.optString("key","").trim();if(key.length()<16||key.length()>256||key.contains("\n")||key.contains("\r"))throw new IllegalArgumentException("请填写电脑配对密钥");
            if(mode.equals("bluetooth")){
                if(!BluetoothAdapter.checkBluetoothAddress(c.optString("mac")))throw new IllegalArgumentException("请选择已配对的电脑");
                if(!permission())throw new IllegalArgumentException("请先允许附近设备权限");
            }else{
                String host=mode.equals("usb")?"127.0.0.1":c.optString("host","").trim();
                if(mode.equals("remote")&&!host.startsWith("https://"))throw new IllegalArgumentException("内网穿透请填写 HTTPS 地址");
                String bare=host.replaceFirst("^https?://","");int port=c.optInt("port",47823);
                URI uri=new URI((host.startsWith("https://")?"https":"http")+"://"+bare);
                if(uri.getHost()==null||uri.getPort()!=-1||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null||!uri.getPath().isEmpty()||port<1||port>65535)throw new IllegalArgumentException("地址与端口请分别填写，地址不要包含路径");
                c.put("host",host).put("port",port);
            }
            c.put("key",key);prefs.edit().putString("config",c.toString()).putBoolean("enabled",true).apply();
            JSONObject settings=activity.settings();settings.put("hubMode","local");activity.prefs.edit().putString("settings",settings.toString()).apply();
            activity.runOnUiThread(()->{pause();activity.configureHub();activity.sourceChanged();});return "";
        }catch(Exception e){return e instanceof IllegalArgumentException?e.getMessage():"连接信息格式不正确";}
    }
    void resume(){
        if(!enabled())return;JSONObject c=config();String identity=c.toString();if(identity.equals(configured))return;
        configured=identity;hasData=false;connected=false;reason="正在连接电脑";
        if(c.optString("mode").equals("bluetooth"))rpc.configureBluetooth(c.optString("mac"),c.optString("key"));
        else rpc.configureHttp(c.optString("host"),c.optInt("port",47823),c.optString("key"));
        activity.publishStatus();
    }
    void pause(){configured="";connected=false;hasData=false;rpc.disconnect();}
    void disable(){prefs.edit().putBoolean("enabled",false).apply();pause();}
    void request(String id,String channel,String args){
        if(!java.util.Set.of("stats:get","dashboard:getHistory","serviceStatus:get","codexResetForecast:get","session:getDetail").contains(channel)){
            JSONObject error=new JSONObject();try{error.put("ok",false).put("error","手机电脑连接不支持此请求");}catch(Exception ignored){}onReply(id,error);return;
        }
        rpc.call(id,channel,"session:getDetail".equals(channel)?args:"[]");
    }
    @Override public void onReply(String id,JSONObject response){
        if(android.os.Looper.myLooper()!=android.os.Looper.getMainLooper()){activity.runOnUiThread(()->onReply(id,response));return;}
        if(response.optBoolean("ok")&&response.optJSONObject("result")!=null&&response.optJSONObject("result").has("periods"))hasData=true;
        if(!response.optBoolean("ok")){connected=false;hasData=false;reason=response.optString("error","电脑服务未就绪");activity.publishStatus();}
        if(activity.web!=null)activity.web.evaluateJavascript("window.__mobileRpcReply&&window.__mobileRpcReply("+JSONObject.quote(id)+","+response+")",null);
    }
    @Override public void onStatus(String text,boolean online){
        if(!enabled())return;connected=online&&hasData;
        if(!online||hasData)reason=text;activity.publishStatus();
    }
    boolean permission(){return Build.VERSION.SDK_INT<31||activity.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT)==android.content.pm.PackageManager.PERMISSION_GRANTED;}
    @android.annotation.SuppressLint("MissingPermission")
    JSONObject devices(){
        JSONObject result=new JSONObject();JSONArray devices=new JSONArray();
        try{
            result.put("needsPermission",!permission());
            if(permission()){
                BluetoothManager m=activity.getSystemService(BluetoothManager.class);BluetoothAdapter a=m==null?null:m.getAdapter();
                if(a==null)result.put("error","此设备不支持蓝牙");else if(!a.isEnabled())result.put("error","请先打开系统蓝牙");
                else for(BluetoothDevice d:a.getBondedDevices())devices.put(new JSONObject().put("name",d.getName()).put("address",d.getAddress()));
            }
            result.put("devices",devices);
        }catch(Exception e){try{result.put("error","无法读取设备，请检查蓝牙权限");}catch(Exception ignored){}}return result;
    }
}
