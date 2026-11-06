package com.tokenmonitor.mobile;

import android.app.*;
import android.content.*;
import android.content.res.Configuration;
import android.os.*;
import android.view.*;
import android.webkit.*;
import android.widget.*;
import org.json.*;
import java.io.ByteArrayInputStream;
import java.util.*;

/** Mobile renderer using the original Hub protocol and on-device preferences. */
public class MainActivity extends Activity implements HubClient.Listener {
    static final int BG=0xff293441;
    static final String ASSET_ROOT="https://appassets.androidplatform.net/upstream/src/electron/renderer/";
    SharedPreferences prefs;
    HubClient hub;
    ComputerConnection computer;
    OfflineCache offlineCache;
    WebView web;
    LinearLayout root,body;
    FrameLayout screen;
    View completionBanner;
    boolean pageLoaded,resumed;
    volatile boolean connected;
    volatile String reason="disconnected";
    String page="index.html";
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);
        prefs=getSharedPreferences("hub",MODE_PRIVATE);
        CodexNotifications.channels(this);
        getSharedPreferences("monitor",MODE_PRIVATE).edit().clear().apply();
        hub=new HubClient(this);offlineCache=new OfflineCache(this);
        computer=new ComputerConnection(this);
        enterFullscreen(getWindow());
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        root=column();root.setBackgroundColor(BG);
        if(Build.VERSION.SDK_INT>=30)root.setOnApplyWindowInsetsListener((view,insets)->{
            int bottom=insets.isVisible(WindowInsets.Type.ime())?insets.getInsets(WindowInsets.Type.ime()).bottom:0;
            view.setPadding(0,0,0,bottom);return insets;
        });
        body=column();root.addView(body,new LinearLayout.LayoutParams(-1,0,1));
        screen=new FrameLayout(this);
        screen.addView(root,new FrameLayout.LayoutParams(-1,-1));
        setContentView(screen);
        loadRenderer("index.html");
        if(Build.VERSION.SDK_INT>=33)getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
            android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,this::navigateBack);
    }
    @SuppressWarnings("deprecation")
    void enterFullscreen(Window window) {
        if(window==null)return;
        View decor=window.getDecorView();
        if(Build.VERSION.SDK_INT>=28){
            WindowManager.LayoutParams attrs=window.getAttributes();
            attrs.layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            window.setAttributes(attrs);
        }
        if(Build.VERSION.SDK_INT>=30){
            window.setDecorFitsSystemWindows(false);
            WindowInsetsController controller=window.getInsetsController();
            if(controller!=null){
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                controller.hide(WindowInsets.Type.systemBars());
            }
        }else decor.setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION|
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }
    @Override public void onWindowFocusChanged(boolean hasFocus){super.onWindowFocusChanged(hasFocus);if(hasFocus)enterFullscreen(getWindow());}
    @SuppressWarnings("SetJavaScriptEnabled")
    void loadRenderer(String filename) {
        page=filename;
        if(web==null){
            web=new WebView(this);web.setBackgroundColor(BG);
            WebSettings settings=web.getSettings();settings.setJavaScriptEnabled(true);settings.setDomStorageEnabled(true);
            settings.setAllowFileAccess(false);settings.setAllowContentAccess(false);
            settings.setAllowFileAccessFromFileURLs(false);settings.setAllowUniversalAccessFromFileURLs(false);
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
            settings.setSupportZoom(false);settings.setTextZoom(100);
            web.addJavascriptInterface(new MobileApi(),"Android");
            web.setWebViewClient(new WebViewClient(){
                @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request){
                    String url=request.getUrl().toString();
                    if(url.startsWith(ASSET_ROOT))return false;
                    if(request.isForMainFrame()&&(url.startsWith("https://")||url.startsWith("http://")))openExternal(url);
                    return true;
                }
                @Override public WebResourceResponse shouldInterceptRequest(WebView view,WebResourceRequest request){
                    String url=request.getUrl().toString();
                    if("appassets.androidplatform.net".equals(request.getUrl().getHost())){
                        String asset=request.getUrl().getPath().substring(1);
                        if(!asset.startsWith("upstream/")||asset.contains(".."))return new WebResourceResponse("text/plain","UTF-8",new ByteArrayInputStream(new byte[0]));
                        try{
                            String extension=android.webkit.MimeTypeMap.getFileExtensionFromUrl(url);
                            String mime=extension.equals("js")?"application/javascript":android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
                            return new WebResourceResponse(mime==null?"application/octet-stream":mime,"UTF-8",getAssets().open(asset));
                        }catch(java.io.IOException e){return new WebResourceResponse("text/plain","UTF-8",404,"Not found",new HashMap<>(),new ByteArrayInputStream(new byte[0]));}
                    }
                    if(url.startsWith("data:")||url.startsWith("blob:"))return null;
                    // Only bundled code can access the native connection bridge.
                    return new WebResourceResponse("text/plain","UTF-8",new ByteArrayInputStream(new byte[0]));
                }
                @Override public void onPageFinished(WebView view,String url){
                    pageLoaded=true;publishStatus();
                }
            });
            web.setWebChromeClient(new WebChromeClient(){
                @Override public boolean onJsAlert(WebView view,String url,String message,JsResult result){
                    new AlertDialog.Builder(MainActivity.this).setMessage(message).setPositiveButton("确定",(d,w)->result.confirm()).setOnCancelListener(d->result.cancel()).show();return true;
                }
                @Override public boolean onJsConfirm(WebView view,String url,String message,JsResult result){
                    new AlertDialog.Builder(MainActivity.this).setMessage(message).setPositiveButton("确定",(d,w)->result.confirm()).setNegativeButton("取消",(d,w)->result.cancel()).setOnCancelListener(d->result.cancel()).show();return true;
                }
                @Override public boolean onConsoleMessage(ConsoleMessage m){
                    if(m.messageLevel()==ConsoleMessage.MessageLevel.ERROR)android.util.Log.e("TokenMobileWeb",m.message().substring(0,Math.min(500,m.message().length()))+" @"+m.lineNumber());return true;
                }
            });
            if((getApplicationInfo().flags&android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)!=0)WebView.setWebContentsDebuggingEnabled(true);
        }
        body.removeAllViews();body.addView(web,new LinearLayout.LayoutParams(-1,-1));pageLoaded=false;
        web.loadUrl(ASSET_ROOT+filename);
    }
    JSONObject settings(){try{return new JSONObject(prefs.getString("settings","{}"));}catch(Exception error){return new JSONObject();}}
    void configureHub(){
        if(Looper.myLooper()!=Looper.getMainLooper()){runOnUiThread(this::configureHub);return;}
        if(computer.enabled()){hub.disconnect();if(resumed)computer.resume();else computer.pause();return;}
        computer.pause();
        JSONObject value=settings();
        String url=value.optString("hubUrl","");
        hub.configure(resumed&&"client".equals(value.optString("hubMode"))?url:"",value.optString("secret",""));
    }
    JSONObject status(){
        if(computer.enabled())return computer.state();
        JSONObject result=new JSONObject();JSONObject settings=settings();
        try{result.put("connected",connected).put("mode","client".equals(settings.optString("hubMode"))?"sync":"local")
            .put("reason",reason).put("active","client".equals(settings.optString("hubMode")));}catch(Exception ignored){}
        return result;
    }
    public final class MobileApi {
        @JavascriptInterface public String getConnectionConfig(){return computer.config().toString();}
        @JavascriptInterface public String saveConnection(String json){return computer.save(json);}
        @JavascriptInterface public void disconnectComputer(){runOnUiThread(()->{computer.disable();configureHub();sourceChanged();});}
        @JavascriptInterface public void requestComputer(String id,String channel,String args){computer.request(id,channel,args);}
        @JavascriptInterface public String getPairedComputers(){return computer.devices().toString();}
        @JavascriptInterface public void requestBluetoothPermission(){runOnUiThread(()->{if(Build.VERSION.SDK_INT>=31&&!computer.permission())requestPermissions(new String[]{android.Manifest.permission.BLUETOOTH_CONNECT},42);});}
        @JavascriptInterface public void openBluetoothSettings(){runOnUiThread(()->startActivity(new Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)));}
        @JavascriptInterface public String getNotificationState(){return CodexNotifications.state(MainActivity.this).toString();}
        @JavascriptInterface public void setCodexNotificationKind(String kind, boolean enabled) {
            if (CodexNotifications.knownKind(kind)) runOnUiThread(() -> changeCodexNotification(kind, enabled));
        }
        @JavascriptInterface public String getSettings(){return settings().toString();}
        // JavascriptInterface runs off the UI thread; acknowledge only durable saves.
        @android.annotation.SuppressLint("ApplySharedPref")
        @JavascriptInterface public String saveSettings(String json){
            JSONObject result=new JSONObject();
            try{
                if(json==null||json.length()>1024*1024)throw new IllegalArgumentException("设置过大");
                JSONObject next=new JSONObject(json);String mode=next.optString("hubMode","local");
                if(!mode.equals("local")&&!mode.equals("client"))throw new IllegalArgumentException("手机支持仅本地或连接 Hub");
                String url=next.optString("hubUrl","").trim();
                if(!url.isEmpty())url=HubClient.validateUrl(url);
                String secret=next.optString("secret","");
                if(secret.length()>4096||secret.contains("\r")||secret.contains("\n"))throw new IllegalArgumentException("共享密钥无效");
                next.put("hubUrl",url);
                if(!prefs.edit().putString("settings",next.toString()).commit())throw new IllegalStateException("无法保存设置，请检查手机存储空间");
                if(mode.equals("client"))computer.disable();
                configureHub();result.put("ok",true).put("result",next);
            }catch(Exception error){try{result.put("ok",false).put("error",error.getMessage());}catch(Exception ignored){}}
            return result.toString();
        }
        @JavascriptInterface public String getHubStatus(){return status().toString();}
        @JavascriptInterface public void requestHub(String id,String method,String path,String body){hub.request(id,method,path,body);}
        @JavascriptInterface public String getOfflineCache(){return offlineCache.read();}
        @JavascriptInterface public boolean saveOfflineCache(String json){return offlineCache.save(json);}
        @JavascriptInterface public void clearOfflineCache(){offlineCache.clear();}
        @JavascriptInterface public void openDashboard(){runOnUiThread(()->loadRenderer("dashboard.html"));}
        @JavascriptInterface public void closeDashboard(){runOnUiThread(()->loadRenderer("index.html"));}
        @JavascriptInterface public void copyText(String text){runOnUiThread(()->{
            ((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Token Monitor",text));
            Toast.makeText(MainActivity.this,"已复制",Toast.LENGTH_SHORT).show();});}
        @JavascriptInterface public void openExternal(String url){runOnUiThread(()->MainActivity.this.openExternal(url));}
    }
    void openExternal(String url){
        if(url==null||!(url.startsWith("https://")||url.startsWith("http://")))return;
        try{startActivity(new Intent(Intent.ACTION_VIEW,android.net.Uri.parse(url)));}catch(Exception ignored){}
    }
    @Override public void onHubReply(String id,JSONObject response){
        if(web!=null)web.evaluateJavascript("window.__mobileHubReply&&window.__mobileHubReply("+JSONObject.quote(id)+","+response+")",null);
    }
    @Override public void onHubEvent(JSONObject event){
        if(web!=null&&pageLoaded)web.evaluateJavascript("window.__mobileHubEvent&&window.__mobileHubEvent("+event+")",null);
    }
    @Override public void onHubStatus(boolean online,String why){connected=online;reason=why;publishStatus();}
    void sourceChanged(){if(web!=null)web.evaluateJavascript("window.__mobileSourceChanged&&window.__mobileSourceChanged()",null);publishStatus();}
    void publishStatus(){if(web!=null)web.evaluateJavascript("window.__mobileHubStatus&&window.__mobileHubStatus("+status()+")",null);}
    private String pendingNotificationKind;
    private AlertDialog notificationPairingDialog;

    private void refreshNotificationSwitches() {
        if (web != null) web.evaluateJavascript("window.__mobileNotificationStateChanged?.()", null);
    }

    private void changeCodexNotification(String kind, boolean enabled) {
        if (!enabled) {
            CodexNotifications.setKind(this, kind, false);
            refreshNotificationSwitches();
        } else if (CodexNotifications.prefs(this).getString("url", "").isEmpty()) {
            pairCodexNotifications(kind);
        } else {
            enableCodexNotification(kind);
        }
    }

    private void pairCodexNotifications(String kind) {
        if (notificationPairingDialog != null && notificationPairingDialog.isShowing()) return;
        EditText code = new EditText(this);
        code.setHint("TMN1.…");
        code.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        notificationPairingDialog = new AlertDialog.Builder(this)
            .setTitle("配对 Codex 通知")
            .setMessage("在电脑启动通知组件后，粘贴它生成的通知配对码。")
            .setView(code).setNegativeButton("取消", null).setPositiveButton("保存", null).create();
        notificationPairingDialog.setOnShowListener(dialog -> notificationPairingDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String error = CodexNotifications.savePairing(this, code.getText().toString());
            if (!error.isEmpty()) {
                code.setError(error);
                return;
            }
            notificationPairingDialog.dismiss();
            enableCodexNotification(kind);
        }));
        notificationPairingDialog.show();
    }

    private void enableCodexNotification(String kind) {
        if (CodexNotifications.allowed(this)) {
            CodexNotifications.setKind(this, kind, true);
            refreshNotificationSwitches();
        } else if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            pendingNotificationKind = kind;
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 41);
        } else {
            startActivity(new Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, getPackageName())
                .putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, CodexNotifications.CHANNEL));
        }
    }

    @Override protected void onResume(){super.onResume();resumed=true;CodexNotifications.foregroundActivity=this;enterFullscreen(getWindow());if(web!=null){web.onResume();web.resumeTimers();}configureHub();
        if (CodexNotifications.allowed(this) && CodexNotifications.prefs(this).getBoolean("enabled", false)) {
            CodexNotifications.start(this);
        }
        refreshNotificationSwitches();
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results){
        super.onRequestPermissionsResult(request,permissions,results);
        if (request == 41) {
            String kind = pendingNotificationKind;
            pendingNotificationKind = null;
            if (kind != null && results.length > 0 && CodexNotifications.allowed(this)) enableCodexNotification(kind);
            refreshNotificationSwitches();
        }
        if(request==42&&web!=null)web.evaluateJavascript("window.__mobileBluetoothPermission&&window.__mobileBluetoothPermission()",null);
    }
    @Override protected void onPause(){
        resumed=false;
        if(CodexNotifications.foregroundActivity==this)CodexNotifications.foregroundActivity=null;
        dismissCompletionBanner();
        hub.disconnect();computer.pause();
        if(web!=null){web.onPause();web.pauseTimers();}
        super.onPause();
    }
    @Override protected void onDestroy(){hub.close();computer.rpc.close();if(web!=null){web.removeJavascriptInterface("Android");web.destroy();}super.onDestroy();}
    @Override public void onConfigurationChanged(Configuration c){super.onConfigurationChanged(c);enterFullscreen(getWindow());root.requestApplyInsets();}
    void navigateBack(){
        if(Build.VERSION.SDK_INT>=30&&root.getRootWindowInsets()!=null&&root.getRootWindowInsets().isVisible(WindowInsets.Type.ime())){
            getWindow().getInsetsController().hide(WindowInsets.Type.ime());return;
        }
        web.evaluateJavascript("(()=>{if(typeof state!=='undefined'&&state.viewSwitcherOpen){setViewSwitcherOpen(false);return true;}if(document.querySelector('.shell.settings-open')){document.getElementById('settingsButton').click();return true;}if(typeof state!=='undefined'&&state.openSession){sessionDetailBack();return true;}return false;})()",handled->{
            if("true".equals(handled)||isFinishing())return;
            if(page.equals("dashboard.html"))loadRenderer("index.html");else finish();
        });
    }
    @android.annotation.SuppressLint("GestureBackNavigation")
    @Override public void onBackPressed(){navigateBack();}
    void showCompletionBanner(String context,String title,String message){
        runOnUiThread(()->{
            if(!resumed||!hasWindowFocus()||isFinishing())return;
            dismissCompletionBanner();
            LinearLayout banner=column();
            android.graphics.drawable.GradientDrawable background=new android.graphics.drawable.GradientDrawable();
            background.setColor(0xff29343f);
            background.setCornerRadius(dp(12));
            background.setStroke(dp(1),0xff54636c);
            banner.setBackground(background);
            banner.setElevation(dp(12));
            banner.setPadding(dp(14),dp(10),dp(14),dp(12));
            banner.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            banner.addView(bannerText(context,12,0xffa7d7c4,2));
            banner.addView(bannerText(title,15,0xfff2f5f6,2));
            banner.addView(bannerText(message,13,0xffd0d9df,2));
            banner.setOnClickListener(view->dismissCompletionBanner());
            int safeTop=0;
            if(Build.VERSION.SDK_INT>=28&&screen.getRootWindowInsets()!=null){
                DisplayCutout cutout=screen.getRootWindowInsets().getDisplayCutout();
                if(cutout!=null)safeTop=cutout.getSafeInsetTop();
            }
            int width=Math.max(1,Math.min(dp(420),screen.getWidth()-dp(24)));
            FrameLayout.LayoutParams layout=new FrameLayout.LayoutParams(width,-2,Gravity.TOP|Gravity.CENTER_HORIZONTAL);
            layout.topMargin=safeTop+dp(12);
            screen.addView(banner,layout);
            completionBanner=banner;
            banner.postDelayed(()->{if(completionBanner==banner)dismissCompletionBanner();},6500);
        });
    }
    private void dismissCompletionBanner(){
        if(completionBanner==null)return;
        screen.removeView(completionBanner);
        completionBanner=null;
    }
    private TextView bannerText(String text,int size,int color,int lines){
        TextView label=new TextView(this);
        label.setText(text);label.setTextSize(size);label.setTextColor(color);
        label.setMaxLines(lines);label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        return label;
    }
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    LinearLayout column(){LinearLayout value=new LinearLayout(this);value.setOrientation(LinearLayout.VERTICAL);return value;}
}
