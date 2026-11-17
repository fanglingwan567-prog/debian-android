package app.birch.debian;

import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.*;
import android.webkit.*;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.DynamicColors;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import java.io.*;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;

public final class MainActivity extends AppCompatActivity implements Console.Display {
    private static final String ORIGIN="https://terminal.invalid/";
    private static final int TERMINAL_BACKGROUND=Color.rgb(21,26,23);
    private enum State { PERMISSION, CHECKING, DOWNLOAD, CATALOG, TRANSFER, INSTALL, INSTALLING, TERMINAL }
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private RuntimeHost host;
    private ImageStore downloads;
    private ImageStore.Image image;
    private DebianRegistry.Channel channel;
    private List<DebianRegistry.Version> versions=Collections.emptyList();
    private DebianRegistry.Version version;
    private long received,total;

    private State state=State.PERMISSION;
    private String error,detail;
    private boolean alive=true;
    private LinearLayout page;
    private WebView web;
    private MaterialButton control,alt;
    private Console console;
    private int generation;
    @Override public void onCreate(Bundle saved){super.onCreate(saved);DynamicColors.applyToActivityIfAvailable(this);host=new RuntimeHost(this);downloads=new ImageStore(getFilesDir());showSetup();check();}
    private int dp(float n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private int color(int attr){return MaterialColors.getColor(this,attr,getString(R.string.app_name));}
    private void createPage(int background){
        page=new LinearLayout(this);page.setOrientation(LinearLayout.VERTICAL);page.setBackgroundColor(background);setContentView(page);
    }
    private WindowInsetsControllerCompat systemBars(){
        return WindowCompat.getInsetsController(getWindow(),getWindow().getDecorView());
    }
    private void showSetup(){
        WindowCompat.setDecorFitsSystemWindows(getWindow(),true);
        WindowInsetsControllerCompat bars=systemBars();
        bars.show(WindowInsetsCompat.Type.systemBars());
        boolean light=(getResources().getConfiguration().uiMode&Configuration.UI_MODE_NIGHT_MASK)!=Configuration.UI_MODE_NIGHT_YES;
        bars.setAppearanceLightStatusBars(light);bars.setAppearanceLightNavigationBars(light);
        int surface=color(com.google.android.material.R.attr.colorSurface);
        getWindow().setStatusBarColor(surface);getWindow().setNavigationBarColor(surface);
        setContentView(R.layout.activity_setup);
        View card=findViewById(R.id.setup_card);
        card.getLayoutParams().width=Math.min(dp(460),getResources().getDisplayMetrics().widthPixels-dp(48));
        card.requestLayout();
        MaterialAutoCompleteTextView branch=findViewById(R.id.setup_channel);
        branch.setSimpleItems(new String[]{getString(R.string.stable),getString(R.string.testing)});
        branch.setOnItemClickListener((parent,view,index,id)->{
            DebianRegistry.Channel selected=DebianRegistry.Channel.STABLE;
            if(index==1)selected=DebianRegistry.Channel.TESTING;
            loadCatalog(selected);
        });
        MaterialAutoCompleteTextView choice=findViewById(R.id.setup_version);
        choice.setOnItemClickListener((parent,view,index,id)->{
            if(state!=State.DOWNLOAD || index<0 || index>=versions.size())return;
            version=versions.get(index);error=null;renderSetup();
        });
    }

    private void renderSetup(){
        boolean permission=state==State.PERMISSION || state==State.CHECKING;
        boolean download=state==State.DOWNLOAD || state==State.CATALOG || state==State.TRANSFER;
        boolean busy=state==State.CHECKING || state==State.INSTALLING || state==State.CATALOG || state==State.TRANSFER;
        TextView step=findViewById(R.id.setup_step),title=findViewById(R.id.setup_title);
        TextView description=findViewById(R.id.setup_description),message=findViewById(R.id.setup_error);
        MaterialButton action=findViewById(R.id.setup_action),reselect=findViewById(R.id.setup_reselect);
        View fields=findViewById(R.id.download_fields);
        LinearProgressIndicator progress=findViewById(R.id.setup_progress);
        View panel=findViewById(R.id.download_panel);
        fields.setVisibility(View.GONE);reselect.setVisibility(View.GONE);action.setEnabled(true);
        progress.setVisibility(View.GONE);action.setVisibility(View.GONE);
        int failure=R.string.install_failure;
        if(permission){
            step.setText(R.string.permission_step);title.setText(R.string.permission_title);
            description.setText(getString(R.string.root_grant_message,getString(R.string.app_name)));
            failure=R.string.permission_failure;
            action.setText(R.string.permission_retry);action.setOnClickListener(v->check());
        }else if(download){
            step.setText(R.string.download_step);title.setText(R.string.download_title);
            description.setText(R.string.download_intro);failure=R.string.download_failure;
            fields.setVisibility(View.VISIBLE);
            findViewById(R.id.channel_box).setEnabled(!busy);
            findViewById(R.id.version_box).setEnabled(!busy && !versions.isEmpty());
            if(busy){
                TextView status=findViewById(R.id.download_status);
                if(state==State.TRANSFER && total>0){
                    status.setText(R.string.download_active);showBytes();
                    findViewById(R.id.download_percent).setVisibility(View.VISIBLE);
                    findViewById(R.id.download_size).setVisibility(View.VISIBLE);
                }else{
                    if(state==State.CATALOG)status.setText(R.string.catalog_loading);
                    else status.setText(R.string.download_connecting);
                    DownloadWaveView wave=findViewById(R.id.download_progress);wave.showWaiting();
                    findViewById(R.id.download_percent).setVisibility(View.GONE);
                    findViewById(R.id.download_size).setVisibility(View.GONE);
                }
            }
            action.setText(R.string.download_action);action.setOnClickListener(v->download());
            action.setEnabled(!busy && version!=null);action.setVisibility(View.VISIBLE);
            if(state==State.DOWNLOAD && versions.isEmpty() && channel!=null && error!=null){
                action.setText(R.string.catalog_retry);action.setEnabled(true);action.setOnClickListener(v->loadCatalog(channel));
            }else if(version!=null && error!=null)action.setText(R.string.download_retry);
        }else{
            step.setText(R.string.install_step);title.setText(R.string.install_title);
            if(state==State.INSTALLING)description.setText(detail);
            else description.setText(getString(R.string.install_description,image.tag));
            action.setText(R.string.install_action);if(error!=null)action.setText(R.string.install_retry);
            action.setOnClickListener(v->install());
            if(!busy){reselect.setVisibility(View.VISIBLE);reselect.setOnClickListener(v->{state=State.DOWNLOAD;error=null;renderSetup();});}
        }
        if(error==null){message.setVisibility(View.GONE);message.setText(null);}
        else{message.setText(getString(failure,error));message.setVisibility(View.VISIBLE);}
        if(download && busy)panel.setVisibility(View.VISIBLE);
        else panel.setVisibility(View.GONE);
        if(!download){
            if(busy)progress.setVisibility(View.VISIBLE);
            else action.setVisibility(View.VISIBLE);
        }
    }

    private void loadCatalog(DebianRegistry.Channel selected){
        if(state!=State.DOWNLOAD)return;
        MaterialAutoCompleteTextView choice=findViewById(R.id.setup_version);
        choice.setText("",false);choice.setAdapter(null);
        channel=selected;version=null;versions=Collections.emptyList();state=State.CATALOG;error=null;renderSetup();
        worker.execute(()->{
            try{List<DebianRegistry.Version> list=DebianRegistry.catalog(selected);
                runOnUiThread(()->{
                    if(!alive || state!=State.CATALOG)return;
                    versions=list;
                    String[] labels=new String[list.size()];for(int i=0;i<labels.length;i++)labels[i]=list.get(i).label;
                    choice.setSimpleItems(labels);state=State.DOWNLOAD;renderSetup();
                });
            }catch(Exception ex){reportSetupFailure(State.CATALOG,ex);}
        });
    }

    private void showBytes(){
        DownloadWaveView wave=findViewById(R.id.download_progress);
        TextView percent=findViewById(R.id.download_percent),size=findViewById(R.id.download_size);
        wave.showFraction((float)((double)received/total));
        percent.setText(getString(R.string.download_percent,received*100/total));
        size.setText(getString(R.string.download_amount,received/1048576.0,total/1048576.0));
    }

    private void download(){
        if(state!=State.DOWNLOAD || version==null)return;
        final DebianRegistry.Version selected=version;
        state=State.TRANSFER;received=0;total=0;error=null;renderSetup();
        worker.execute(()->{
            try{ImageStore.Image saved=DebianRegistry.download(selected,downloads,(done,size)->runOnUiThread(()->{
                    if(!alive || state!=State.TRANSFER)return;
                    boolean first=total==0;received=done;total=size;
                    if(first)renderSetup();else showBytes();
                }));
                runOnUiThread(()->{if(!alive || state!=State.TRANSFER)return;image=saved;state=State.INSTALL;renderSetup();});
            }catch(Exception ex){reportSetupFailure(State.TRANSFER,ex);}
        });
    }

    private void check(){
        if(state!=State.PERMISSION)return;
        state=State.CHECKING;
        error=null;
        renderSetup();
        worker.execute(()->{
            try{
                String reply=host.check();
                boolean ready=reply.endsWith("BIRCH_READY");
                if(!ready && !reply.endsWith("BIRCH_NEW"))throw new IOException("无法识别启动器响应："+reply);
                ImageStore.Image pending=null;String cacheError=null;
                try{if(ready)downloads.clear();else pending=downloads.pending();}
                catch(IOException ex){cacheError=failureMessage(ex);}
                final ImageStore.Image saved=pending;final String notice=cacheError;
                runOnUiThread(()->{
                    if(!alive || state!=State.CHECKING)return;
                    if(ready){terminal();if(notice!=null)Toast.makeText(this,"镜像暂未清理："+notice,Toast.LENGTH_LONG).show();return;}
                    image=saved;state=State.DOWNLOAD;error=notice;
                    if(saved!=null)state=State.INSTALL;
                    renderSetup();
                });
            }catch(Exception ex){reportSetupFailure(State.CHECKING,ex);}
        });
    }

    private void install(){
        if(state!=State.INSTALL)return;
        state=State.INSTALLING;
        error=null;
        detail=getString(R.string.install_reading_image);
        renderSetup();
        worker.execute(()->{
            try{
                host.install(downloads,image,msg->runOnUiThread(()->{
                    if(!alive || state!=State.INSTALLING)return;
                    detail=msg;
                    renderSetup();
                }));
                String cleanup=null;
                try{downloads.clear();}catch(IOException ex){cleanup=failureMessage(ex);}
                final String notice=cleanup;
                runOnUiThread(()->{if(alive && state==State.INSTALLING){terminal();if(notice!=null)Toast.makeText(this,"镜像暂未清理："+notice,Toast.LENGTH_LONG).show();}});
            }catch(Exception ex){reportSetupFailure(State.INSTALLING,ex);}
        });
    }

    private static String failureMessage(Exception ex){
        String message=ex.getMessage();
        if(message==null || message.trim().isEmpty())return ex.toString();
        return message;
    }

    private void reportSetupFailure(State operation,Exception ex){
        runOnUiThread(()->{
            if(!alive || state!=operation)return;
            if(operation==State.CHECKING)state=State.PERMISSION;
            else if(operation==State.INSTALLING)state=State.INSTALL;
            else state=State.DOWNLOAD;
            error=failureMessage(ex);
            renderSetup();
        });
    }

    private void reportTerminalFailure(int gen,IOException ex){
        runOnUiThread(()->{
            if(!alive || gen!=generation || state!=State.TERMINAL)return;
            stopTerminal();
            new MaterialAlertDialogBuilder(this)
                    .setMessage(getString(R.string.terminal_start_failure,failureMessage(ex)))
                    .setPositiveButton(R.string.terminal_retry,(dialog,which)->terminal())
                    .setNegativeButton("退出",(dialog,which)->finish())
                    .setOnCancelListener(dialog->finish())
                    .show();
        });
    }
    private void stopTerminal(){generation++;if(console!=null){Console previous=console;console=null;previous.close();}if(web!=null){web.removeJavascriptInterface("Birch");if(web.getParent() instanceof android.view.ViewGroup)((android.view.ViewGroup)web.getParent()).removeView(web);web.destroy();web=null;}}
    @SuppressLint("SetJavaScriptEnabled") private void terminal(){
        stopTerminal();final int gen=generation;state=State.TERMINAL;
        WindowCompat.setDecorFitsSystemWindows(getWindow(),false);
        WindowInsetsControllerCompat bars=systemBars();
        bars.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.Q)getWindow().setStatusBarContrastEnforced(false);
        bars.setAppearanceLightStatusBars(false);
        bars.show(WindowInsetsCompat.Type.statusBars());
        bars.hide(WindowInsetsCompat.Type.navigationBars());
        createPage(TERMINAL_BACKGROUND);
        web=new WebView(this);web.setBackgroundColor(TERMINAL_BACKGROUND);WebSettings settings=web.getSettings();settings.setJavaScriptEnabled(true);settings.setAllowContentAccess(false);settings.setAllowFileAccess(false);settings.setBlockNetworkLoads(true);settings.setSupportMultipleWindows(false);
        web.setFocusableInTouchMode(true);
        // The terminal owns text selection; do not start a second WebView long-click action.
        web.setOnLongClickListener(v->true);
        web.setWebViewClient(new WebViewClient(){@Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest req){return true;}
            @Override public WebResourceResponse shouldInterceptRequest(WebView v,WebResourceRequest req){String u=req.getUrl().toString();if(u.startsWith(ORIGIN)){String f=u.substring(ORIGIN.length());if(f.matches("index\\.html|screen\\.js|xterm\\.(js|css)|fit\\.js")){String mime="application/javascript";if(f.endsWith("html"))mime="text/html";if(f.endsWith("css"))mime="text/css";try{return new WebResourceResponse(mime,"UTF-8",getAssets().open("term/"+f));}catch(IOException e){reportTerminalFailure(gen,e);}}}return new WebResourceResponse("text/plain","UTF-8",new ByteArrayInputStream(new byte[0]));}});
        final Bridge bridge=new Bridge(gen);
        web.addJavascriptInterface(bridge,"Birch");page.addView(web,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout keys=new LinearLayout(this);
        keys.setOrientation(LinearLayout.VERTICAL);
        keys.setBackgroundColor(TERMINAL_BACKGROUND);
        addShortcutRow(keys,"ESC","TAB","HOME","↑","END","PGUP");
        addShortcutRow(keys,"CTRL","ALT","←","↓","→","PGDN");
        page.addView(keys,-1,-2);
        keys.setVisibility(View.GONE);
        ViewCompat.setOnApplyWindowInsetsListener(page,(v,insets)->{
            Insets safe=insets.getInsets(WindowInsetsCompat.Type.statusBars()|WindowInsetsCompat.Type.ime()|WindowInsetsCompat.Type.displayCutout()|WindowInsetsCompat.Type.captionBar());
            v.setPadding(safe.left,safe.top,safe.right,safe.bottom);
            boolean imeVisible=insets.isVisible(WindowInsetsCompat.Type.ime());
            bridge.keyboardVisible=imeVisible;
            if(imeVisible)keys.setVisibility(View.VISIBLE);
            else if(keys.getVisibility()==View.VISIBLE){
                keys.setVisibility(View.GONE);
                if(console!=null)web.evaluateJavascript("clearShortcutModifiers()",null);
            }
            return WindowInsetsCompat.CONSUMED;
        });
        ViewCompat.requestApplyInsets(page);
        web.loadUrl(ORIGIN+"index.html");
    }
    private void addShortcutRow(LinearLayout panel,String... labels){
        LinearLayout row=new LinearLayout(this);
        panel.addView(row,-1,dp(48));
        int[][] states={{android.R.attr.state_selected},{}};
        ColorStateList textColors=new ColorStateList(states,new int[]{0xffa0d8af,0xffe1e9e2});
        ColorStateList fills=new ColorStateList(states,new int[]{0xff2b4033,Color.TRANSPARENT});
        for(String label:labels){
            MaterialButton key=new MaterialButton(this,null,com.google.android.material.R.attr.borderlessButtonStyle);
            key.setText(label);key.setTextSize(13);key.setSingleLine();key.setMinWidth(0);key.setMinimumWidth(0);key.setPadding(0,0,0,0);
            key.setTextColor(textColors);key.setBackgroundTintList(fills);key.setRippleColor(ColorStateList.valueOf(0x40a0d8af));
            switch(label){
                case "CTRL": control=key;break;
                case "ALT": alt=key;break;
                case "↑": key.setContentDescription("向上");break;
                case "↓": key.setContentDescription("向下");break;
                case "←": key.setContentDescription("向左");break;
                case "→": key.setContentDescription("向右");break;
            }
            String action="pressShortcut("+org.json.JSONObject.quote(label)+")";
            key.setOnClickListener(v->{if(console!=null)web.evaluateJavascript(action,null);});
            row.addView(key,new LinearLayout.LayoutParams(0,-1,1));
        }
    }
    private final class Bridge {
        final int gen;Bridge(int gen){this.gen=gen;}
        // Written by the UI insets listener, read from WebView's JS bridge thread.
        private volatile boolean keyboardVisible;
        void ui(Runnable r){runOnUiThread(()->{if(alive && gen==generation && web!=null)r.run();});}
        @JavascriptInterface public void ready(int cols,int rows){ui(()->{if(console!=null)return;try{console=new Console(host,MainActivity.this);console.start();console.resize(cols,rows);}catch(IOException e){reportTerminalFailure(gen,e);}});}
        @JavascriptInterface public void showKeyboard(){ui(()->{
            final WebView editor=web;
            if(!editor.hasWindowFocus() || keyboardVisible)return;
            if(!editor.hasFocus() && !editor.requestFocus())return;
            // Target the editor, not the decor view. Let the tap's focus change reach
            // WebView before asking Android for IME, even when Back only hid the keyboard.
            editor.post(()->{
                if(!alive || gen!=generation || web!=editor || keyboardVisible || !editor.isAttachedToWindow()
                        || !editor.hasWindowFocus() || !editor.hasFocus())return;
                WindowCompat.getInsetsController(getWindow(),editor).show(WindowInsetsCompat.Type.ime());
            });
        });}
        @JavascriptInterface public boolean isKeyboardVisible(){return keyboardVisible;}
        @JavascriptInterface public float touchSlopDp(){
            return ViewConfiguration.get(MainActivity.this).getScaledTouchSlop()/getResources().getDisplayMetrics().density;
        }
        @JavascriptInterface public void copyText(String text){ui(()->{
            if(text==null || text.isEmpty())return;
            if(text.length()>131072){Toast.makeText(MainActivity.this,"选区过长，请分段复制",Toast.LENGTH_SHORT).show();return;}
            try{
                getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("终端文本",text));
                if(Build.VERSION.SDK_INT<33)Toast.makeText(MainActivity.this,"已复制",Toast.LENGTH_SHORT).show();
            }catch(RuntimeException ex){Toast.makeText(MainActivity.this,"未能写入系统剪贴板",Toast.LENGTH_SHORT).show();}
        });}
        @JavascriptInterface public int fontSize(){return getSharedPreferences("terminal",MODE_PRIVATE).getInt("fontSize",14);}
        @JavascriptInterface public void saveFontSize(int size){if(size>=8 && size<=36)ui(()->getSharedPreferences("terminal",MODE_PRIVATE).edit().putInt("fontSize",size).apply());}
        @JavascriptInterface public void input(String s){ui(()->{if(console!=null)console.input(s);});}
        @JavascriptInterface public void resize(int c,int r){ui(()->{if(console!=null)console.resize(c,r);});}
        @JavascriptInterface public void modifiersChanged(boolean ctrlOn,boolean altOn){ui(()->{control.setSelected(ctrlOn);alt.setSelected(altOn);});}
        @JavascriptInterface public void consumed(){ui(()->{if(console!=null)console.consumed();});}
    }
    public void output(Console source,String base64){runOnUiThread(()->{if(alive&&source==console&&web!=null)web.evaluateJavascript("deliver('"+base64+"')",null);});}
    public void ended(Console source,String reason){runOnUiThread(()->{if(alive&&source==console){console=null;if(reason==null)finish();else Toast.makeText(this,reason,Toast.LENGTH_LONG).show();}});}
    @Override public void onBackPressed(){if(state==State.INSTALLING){Toast.makeText(this,"安装进行中，请稍候",Toast.LENGTH_SHORT).show();return;}if(console!=null){new MaterialAlertDialogBuilder(this).setMessage("关闭终端会话并退出？文件会保留。").setNegativeButton("取消",null).setPositiveButton("退出",(d,w)->finish()).show();return;}super.onBackPressed();}
    @Override protected void onDestroy(){alive=false;stopTerminal();worker.shutdownNow();super.onDestroy();}
}
