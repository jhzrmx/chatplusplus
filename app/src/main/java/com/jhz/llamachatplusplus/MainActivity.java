package com.jhz.llamachatplusplus;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.StatFs;
import android.provider.OpenableColumns;
import android.text.Html;
import android.text.method.LinkMovementMethod;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.util.Base64;
import android.webkit.DownloadListener;
import android.webkit.JavascriptInterface;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebBackForwardList;
import android.webkit.WebChromeClient;
import android.webkit.WebHistoryItem;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.CompoundButton;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.FileOutputStream;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URL;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity implements ServerService.Listener {

    private static final int REQ_PICK_MODEL = 100;
    private static final int REQ_NOTIF = 101;
    private static final int REQ_FILE_CHOOSER = 102;
    private static final int REQ_SAVE_DOC = 103;

    /** Keeps Blob references (so blob: downloads survive revokeObjectURL) and remembers the download filename. */
    private static final String HOOK_JS = "(function(){if(window.__chatppHook)return;window.__chatppHook=1;"
            + "window.__blobs={};var oc=URL.createObjectURL;"
            + "URL.createObjectURL=function(b){var u=oc.call(URL,b);window.__blobs[u]=b;return u;};"
            + "var orv=URL.revokeObjectURL;"
            + "URL.revokeObjectURL=function(u){setTimeout(function(){delete window.__blobs[u];},30000);return orv.call(URL,u);};"
            + "var ac=HTMLAnchorElement.prototype.click;"
            + "HTMLAnchorElement.prototype.click=function(){if(this.download)window.__lastDl=this.download;"
            + "return ac.apply(this,arguments);};})();";
    private static final int COLOR_ACTIVE = 0xFF80CBC4;
    private static final int COLOR_INACTIVE = 0xFF9E9E9E;

    private final View[] pages = new View[3];
    private final TextView[] tabs = new TextView[3];
    private int currentTab = 0;

    private TextView statusText, progressText, serverPlaceholder;
    private View progressBox;
    private ProgressBar progressBar;
    private WebView webView;
    private Switch switchExpose;
    private EditText portEdit, ctxEdit, threadsEdit, keyEdit;
    private ModelAdapter adapter;
    private String webLoadedUrl;

    private ValueCallback<Uri[]> fileCallback;
    private byte[] pendingBytes;
    private String pendingUrl;

    private volatile boolean cancelCopy;
    private boolean copying;

    // ------------------------------------------------------------ lifecycle

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        pages[0] = findViewById(R.id.pageModels);
        pages[1] = findViewById(R.id.pageServer);
        pages[2] = findViewById(R.id.pageSettings);
        tabs[0] = (TextView) findViewById(R.id.tabModels);
        tabs[1] = (TextView) findViewById(R.id.tabServer);
        tabs[2] = (TextView) findViewById(R.id.tabSettings);
        for (int i = 0; i < 3; i++) bindTab(i);

        statusText = (TextView) findViewById(R.id.statusText);
        progressText = (TextView) findViewById(R.id.progressText);
        progressBox = findViewById(R.id.progressBox);
        progressBar = (ProgressBar) findViewById(R.id.progressBar);
        serverPlaceholder = (TextView) findViewById(R.id.serverPlaceholder);
        switchExpose = (Switch) findViewById(R.id.switchExpose);
        portEdit = (EditText) findViewById(R.id.portEdit);
        ctxEdit = (EditText) findViewById(R.id.ctxEdit);
        threadsEdit = (EditText) findViewById(R.id.threadsEdit);
        keyEdit = (EditText) findViewById(R.id.keyEdit);

        ListView list = (ListView) findViewById(R.id.modelList);
        adapter = new ModelAdapter();
        list.setAdapter(adapter);
        list.setEmptyView(findViewById(R.id.emptyText));

        findViewById(R.id.addButton).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pickModel(); }
        });
        findViewById(R.id.logButton).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showLog(); }
        });
        findViewById(R.id.cancelButton).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { cancelCopy = true; }
        });

        webView = (WebView) findViewById(R.id.webView);
        webView.setBackgroundColor(0xFF121212);
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        webView.addJavascriptInterface(new WebBridge(), "ChatBridge");
        webView.setWebViewClient(new WebViewClient() {
            @Override public void onPageStarted(WebView v, String url, Bitmap icon) {
                super.onPageStarted(v, url, icon);
                v.evaluateJavascript(HOOK_JS, null);
            }

            @Override public void onPageFinished(WebView v, String url) {
                super.onPageFinished(v, url);
                v.evaluateJavascript(HOOK_JS, null);
            }

            @Override public boolean shouldOverrideUrlLoading(WebView v, String url) {
                if (url.startsWith("http://127.0.0.1:") || url.startsWith("about:")
                        || url.startsWith("blob:") || url.startsWith("data:")) {
                    return false;
                }
                try { // external links open in the browser, not inside the server UI
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                } catch (ActivityNotFoundException ignored) { }
                return true;
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView w, ValueCallback<Uri[]> cb,
                                                       FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = cb;
                try {
                    startActivityForResult(params.createIntent(), REQ_FILE_CHOOSER);
                } catch (ActivityNotFoundException e) {
                    fileCallback = null;
                    toast("No file picker available");
                    return false;
                }
                return true;
            }
        });
        webView.setDownloadListener(new DownloadListener() {
            @Override public void onDownloadStart(String url, String userAgent, String contentDisposition,
                                                  String mime, long length) {
                handleDownload(url, contentDisposition, mime);
            }
        });

        loadSettingsToUi();
        switchExpose.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean checked) { saveSettings(); }
        });

        showTab(0);

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        ServerService.setListener(this);
        refreshModels();
        onStateChanged();
    }

    @Override protected void onPause() {
        saveSettings();
        ServerService.setListener(null);
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (fileCallback != null) {
            fileCallback.onReceiveValue(null);
            fileCallback = null;
        }
        webView.destroy();
        super.onDestroy();
    }

    @Override public void onBackPressed() {
        if (currentTab == 1 && canGoBackInWebView()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    /**
     * True only if there is a real page to go back to. Unloading a model loads about:blank into
     * the WebView, which leaves history entries behind; those must not swallow the back press.
     */
    private boolean canGoBackInWebView() {
        if (ServerService.getState() != ServerService.RUNNING || webLoadedUrl == null) return false;
        WebBackForwardList history = webView.copyBackForwardList();
        int cur = history.getCurrentIndex();
        if (cur <= 0) return false;
        WebHistoryItem prev = history.getItemAtIndex(cur - 1);
        return prev != null && prev.getUrl() != null && !prev.getUrl().startsWith("about:");
    }

    // ------------------------------------------------------------ menu / about

    @Override public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        MenuItem info = menu.findItem(R.id.menu_info);
        info.setOnMenuItemClickListener(new MenuItem.OnMenuItemClickListener() {
            @Override public boolean onMenuItemClick(MenuItem item) {
                showAbout();
                return true;
            }
        });
        return true;
    }

    private static final String ABOUT_BODY =
            "Turn your phone into a private, offline Llama server. Chat++ runs llama.cpp on your device "
            + "so you can chat with GGUF language models without internet access or a cloud account.<br><br>"
            + "<b>What it does</b><br>"
            + "&#8226; Import and manage .gguf models<br>"
            + "&#8226; Load / unload a model with one tap<br>"
            + "&#8226; Built-in llama.cpp web UI<br>"
            + "&#8226; Optionally share the server on your local network<br><br>"
            + "<b>Developed by</b><br>"
            + "<a href=\"https://github.com/jhzrmx\">JHZRMX</a><br><br>"
            + "<b>License</b><br>"
            + "Chat++ is open source under the "
            + "<a href=\"https://github.com/jhzrmx/chatplusplus/blob/HEAD/LICENSE\">MIT License</a>.<br>"
            + "Source code: <a href=\"https://github.com/jhzrmx/chatplusplus\">github.com/jhzrmx/chatplusplus</a><br><br>"
            + "<b>Powered by</b><br>"
            + "&#8226; <a href=\"https://github.com/ggml-org/llama.cpp\">llama.cpp</a> (server and web UI), MIT License: "
            + "<a href=\"https://github.com/ggml-org/llama.cpp/blob/master/LICENSE\">license</a><br>"
            + "&#8226; <a href=\"https://github.com/ggml-org/ggml\">ggml</a> and the GGUF format, MIT License: "
            + "<a href=\"https://github.com/ggml-org/ggml/blob/master/LICENSE\">license</a><br>"
            + "&#8226; <a href=\"https://huggingface.co/models?library=gguf\">Hugging Face</a> for finding GGUF models<br><br>"
            + "<b>Model licenses</b><br>"
            + "Every model has its own license and terms of use. Check the model page before using or sharing a model.";

    private void showAbout() {
        String version = "";
        try {
            version = "Version " + getPackageManager().getPackageInfo(getPackageName(), 0).versionName + "<br><br>";
        } catch (PackageManager.NameNotFoundException ignored) { }

        TextView tv = new TextView(this);
        tv.setText(Html.fromHtml(version + ABOUT_BODY, Html.FROM_HTML_MODE_LEGACY));
        tv.setMovementMethod(LinkMovementMethod.getInstance());
        tv.setTextColor(0xFFE0E0E0);
        tv.setLinkTextColor(COLOR_ACTIVE);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        tv.setPadding(pad, pad / 2, pad, 0);
        ScrollView sv = new ScrollView(this);
        sv.addView(tv);

        new AlertDialog.Builder(this)
                .setIcon(R.drawable.ic_launcher)
                .setTitle("About " + getString(R.string.app_name))
                .setView(sv)
                .setCancelable(true)
                .setPositiveButton("OK", null)
                .show();
    }

    // ------------------------------------------------------------ tabs

    private void bindTab(final int idx) {
        tabs[idx].setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showTab(idx); }
        });
    }

    private void showTab(int idx) {
        if (currentTab == 2 && idx != 2) saveSettings();
        currentTab = idx;
        for (int i = 0; i < 3; i++) {
            pages[i].setVisibility(i == idx ? View.VISIBLE : View.GONE);
            tabs[i].setTextColor(i == idx ? COLOR_ACTIVE : COLOR_INACTIVE);
        }
    }

    // ------------------------------------------------------------ state

    @Override public void onStateChanged() {
        int st = ServerService.getState();
        String path = ServerService.getModelPath();
        String name = path == null ? "" : new File(path).getName();
        String text;
        switch (st) {
            case ServerService.LOADING:
                text = "Loading " + name + "…";
                break;
            case ServerService.RUNNING:
                text = "Running: " + name + "\nhttp://127.0.0.1:" + ServerService.getPort();
                if (ServerService.isExposed()) {
                    String ip = lanAddress();
                    if (ip != null) text += "\nNetwork: http://" + ip + ":" + ServerService.getPort();
                }
                break;
            case ServerService.ERROR:
                text = "Error: " + ServerService.getError();
                break;
            default:
                text = "No model loaded";
        }
        statusText.setText(text);
        adapter.notifyDataSetChanged();

        if (st == ServerService.RUNNING) {
            String url = "http://127.0.0.1:" + ServerService.getPort() + "/";
            if (!url.equals(webLoadedUrl)) {
                webLoadedUrl = url;
                webView.loadUrl(url);
            }
            serverPlaceholder.setVisibility(View.GONE);
            webView.setVisibility(View.VISIBLE);
        } else {
            if (webLoadedUrl != null) {
                webView.loadUrl("about:blank");
                webLoadedUrl = null;
            }
            webView.setVisibility(View.GONE);
            serverPlaceholder.setVisibility(View.VISIBLE);
            serverPlaceholder.setText(st == ServerService.LOADING
                    ? "Loading model…" : "No model loaded.\nLoad one from the Models tab.");
        }
    }

    private static String lanAddress() {
        try {
            Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
            while (nis != null && nis.hasMoreElements()) {
                NetworkInterface ni = nis.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) return a.getHostAddress();
                }
            }
        } catch (Exception ignored) { }
        return null;
    }

    // ------------------------------------------------------------ settings

    private void loadSettingsToUi() {
        switchExpose.setChecked(Prefs.expose(this));
        portEdit.setText(String.valueOf(Prefs.port(this)));
        ctxEdit.setText(String.valueOf(Prefs.ctx(this)));
        threadsEdit.setText(String.valueOf(Prefs.threads(this)));
        keyEdit.setText(Prefs.apiKey(this));
    }

    private void saveSettings() {
        SharedPreferences.Editor e = Prefs.sp(this).edit();
        e.putBoolean("expose", switchExpose.isChecked());
        e.putInt("port", Prefs.clamp(portEdit.getText().toString(), 8080, 1024, 65535));
        e.putInt("ctx", Prefs.clamp(ctxEdit.getText().toString(), 4096, 128, 1048576));
        e.putInt("threads", Prefs.clamp(threadsEdit.getText().toString(),
                Math.max(1, Runtime.getRuntime().availableProcessors() / 2), 1, 64));
        e.putString("apikey", keyEdit.getText().toString().trim());
        e.apply();
    }

    // ------------------------------------------------------------ models

    private File modelsDir() {
        File d = getExternalFilesDir("models");
        if (d == null) d = new File(getFilesDir(), "models");
        d.mkdirs();
        return d;
    }

    private void refreshModels() {
        File dir = modelsDir();
        File[] files = dir.listFiles();
        List<File> out = new ArrayList<File>();
        if (files != null) {
            for (File f : files) {
                String n = f.getName().toLowerCase(Locale.US);
                if (n.endsWith(".gguf")) {
                    out.add(f);
                } else if (n.endsWith(".part") && !copying) {
                    f.delete(); // leftover from an interrupted import
                }
            }
        }
        Collections.sort(out, new Comparator<File>() {
            @Override public int compare(File a, File b) {
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });
        adapter.setItems(out);
    }

    private boolean isActive(File f) {
        int st = ServerService.getState();
        return (st == ServerService.LOADING || st == ServerService.RUNNING)
                && f.getAbsolutePath().equals(ServerService.getModelPath());
    }

    private void toggleModel(File f) {
        saveSettings();
        if (isActive(f)) {
            ServerService.stop(this);
        } else {
            ServerService.start(this, f.getAbsolutePath());
        }
    }

    private void confirmDelete(final File f) {
        new AlertDialog.Builder(this)
                .setTitle("Delete model")
                .setMessage("Delete " + f.getName() + "?")
                .setPositiveButton("Delete", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        if (isActive(f)) ServerService.stop(MainActivity.this);
                        if (!f.delete()) toast("Could not delete file");
                        refreshModels();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showLog() {
        TextView tv = new TextView(this);
        String log = ServerService.getLog();
        tv.setText(log.length() == 0 ? "(empty)" : log);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextSize(11);
        tv.setTextIsSelectable(true);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        tv.setPadding(pad, pad, pad, pad);
        ScrollView sv = new ScrollView(this);
        sv.addView(tv);
        new AlertDialog.Builder(this).setTitle("Server log").setView(sv)
                .setPositiveButton("Close", null).show();
    }

    // ------------------------------------------------------------ import via SAF

    private void pickModel() {
        if (copying) {
            toast("Import already in progress");
            return;
        }
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*"); // .gguf has no registered MIME type; validated after picking
        startActivityForResult(i, REQ_PICK_MODEL);
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_FILE_CHOOSER) {
            if (fileCallback != null) {
                fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res, data));
                fileCallback = null;
            }
            return;
        }
        if (req == REQ_SAVE_DOC) {
            finishSave(res, data);
            return;
        }
        if (req == REQ_PICK_MODEL && res == RESULT_OK && data != null && data.getData() != null) {
            importModel(data.getData());
        }
    }

    private void importModel(final Uri uri) {
        String name = null;
        long size = -1;
        Cursor c = null;
        try {
            c = getContentResolver().query(uri, null, null, null, null);
            if (c != null && c.moveToFirst()) {
                int ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int si = c.getColumnIndex(OpenableColumns.SIZE);
                if (ni >= 0) name = c.getString(ni);
                if (si >= 0 && !c.isNull(si)) size = c.getLong(si);
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        if (name == null) name = uri.getLastPathSegment();
        if (name == null || !name.toLowerCase(Locale.US).endsWith(".gguf")) {
            toast("Only .gguf files are supported");
            return;
        }
        name = name.replace('/', '_');

        final File dir = modelsDir();
        final File dest = new File(dir, name);
        final File part = new File(dir, name + ".part");
        if (dest.exists()) {
            toast(name + " already exists");
            return;
        }
        if (size > 0 && new StatFs(dir.getPath()).getAvailableBytes() < size + (64L << 20)) {
            toast("Not enough free storage");
            return;
        }

        final long total = size;
        final String label = name;
        copying = true;
        cancelCopy = false;
        progressBox.setVisibility(View.VISIBLE);
        progressBar.setIndeterminate(total <= 0);
        progressBar.setProgress(0);
        progressText.setText("Importing " + label + "…");

        new Thread(new Runnable() {
            @Override public void run() {
                String err = null;
                InputStream in = null;
                OutputStream out = null;
                try {
                    in = getContentResolver().openInputStream(uri);
                    if (in == null) throw new IOException("Cannot open file");
                    out = new FileOutputStream(part);
                    byte[] buf = new byte[256 * 1024];
                    long done = 0, lastUi = 0;
                    boolean first = true;
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        if (first) {
                            first = false;
                            if (n < 4 || buf[0] != 'G' || buf[1] != 'G' || buf[2] != 'U' || buf[3] != 'F') {
                                throw new IOException("Not a valid GGUF file");
                            }
                        }
                        out.write(buf, 0, n);
                        done += n;
                        if (cancelCopy) break;
                        long now = System.currentTimeMillis();
                        if (now - lastUi > 250) {
                            lastUi = now;
                            postProgress(label, done, total);
                        }
                    }
                    if (first) throw new IOException("File is empty");
                } catch (IOException e) {
                    err = e.getMessage() == null ? "Import failed" : e.getMessage();
                } finally {
                    try { if (in != null) in.close(); } catch (IOException ignored) { }
                    try { if (out != null) out.close(); } catch (IOException ignored) { }
                }
                final String msg;
                if (err != null) {
                    part.delete();
                    msg = err;
                } else if (cancelCopy) {
                    part.delete();
                    msg = "Import cancelled";
                } else if (!part.renameTo(dest)) {
                    part.delete();
                    msg = "Could not save model";
                } else {
                    msg = "Imported " + label;
                }
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        copying = false;
                        progressBox.setVisibility(View.GONE);
                        toast(msg);
                        refreshModels();
                    }
                });
            }
        }, "import").start();
    }

    private void postProgress(final String label, final long done, final long total) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                if (total > 0) {
                    progressBar.setProgress((int) (done * 1000 / total));
                    progressText.setText("Importing " + label + "  " + fmtSize(done) + " / " + fmtSize(total));
                } else {
                    progressText.setText("Importing " + label + "  " + fmtSize(done));
                }
            }
        });
    }

    // ------------------------------------------------------------ web downloads (SAF, no permissions)

    private void handleDownload(String url, String contentDisposition, String mime) {
        if (mime == null || mime.length() == 0) mime = "application/octet-stream";
        if (url.startsWith("blob:")) {
            // Blobs only exist inside the page: read it there and hand the bytes to ChatBridge.
            webView.evaluateJavascript("(function(){var u=" + org.json.JSONObject.quote(url)
                    + ";var b=window.__blobs&&window.__blobs[u];"
                    + "function send(b){var r=new FileReader();r.onload=function(){var s=r.result;"
                    + "ChatBridge.save(window.__lastDl||'',b.type||'',s.substring(s.indexOf(',')+1));"
                    + "window.__lastDl='';};r.readAsDataURL(b);}"
                    + "if(b){send(b);}else{fetch(u).then(function(x){return x.blob();}).then(send);}})()", null);
        } else if (url.startsWith("data:")) {
            try {
                int comma = url.indexOf(',');
                String header = url.substring(5, comma);
                String payload = url.substring(comma + 1);
                byte[] bytes = header.endsWith(";base64")
                        ? Base64.decode(payload, Base64.DEFAULT)
                        : URLDecoder.decode(payload, "UTF-8").getBytes("UTF-8");
                int semi = header.indexOf(';');
                String m = semi > 0 ? header.substring(0, semi) : header;
                startSave(URLUtil.guessFileName(url, contentDisposition, m), m.length() > 0 ? m : mime, bytes, null);
            } catch (Exception e) {
                toast("Download failed");
            }
        } else if (url.startsWith("http://") || url.startsWith("https://")) {
            startSave(URLUtil.guessFileName(url, contentDisposition, mime), mime, null, url);
        }
    }

    /** Asks the user where to save (SAF create-document), then writes in finishSave(). */
    private void startSave(String name, String mime, byte[] bytes, String url) {
        pendingBytes = bytes;
        pendingUrl = url;
        if (name == null || name.length() == 0) name = "download";
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType(mime == null || mime.length() == 0 ? "application/octet-stream" : mime);
        i.putExtra(Intent.EXTRA_TITLE, name.replace('/', '_'));
        try {
            startActivityForResult(i, REQ_SAVE_DOC);
        } catch (ActivityNotFoundException e) {
            pendingBytes = null;
            pendingUrl = null;
            toast("No file picker available");
        }
    }

    private void finishSave(int res, Intent data) {
        final byte[] bytes = pendingBytes;
        final String url = pendingUrl;
        pendingBytes = null;
        pendingUrl = null;
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        final Uri dest = data.getData();
        final String key = Prefs.apiKey(this);
        toast("Saving…");
        new Thread(new Runnable() {
            @Override public void run() {
                String msg = "Saved";
                OutputStream out = null;
                InputStream in = null;
                HttpURLConnection c = null;
                try {
                    out = getContentResolver().openOutputStream(dest, "w");
                    if (out == null) throw new IOException("Cannot write file");
                    if (bytes != null) {
                        out.write(bytes);
                    } else if (url != null) {
                        c = (HttpURLConnection) new URL(url).openConnection();
                        if (key.length() > 0) c.setRequestProperty("Authorization", "Bearer " + key);
                        in = c.getInputStream();
                        byte[] buf = new byte[64 * 1024];
                        int n;
                        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    }
                } catch (IOException e) {
                    msg = "Save failed: " + e.getMessage();
                } finally {
                    try { if (in != null) in.close(); } catch (IOException ignored) { }
                    try { if (out != null) out.close(); } catch (IOException ignored) { }
                    if (c != null) c.disconnect();
                }
                final String m = msg;
                runOnUiThread(new Runnable() {
                    @Override public void run() { toast(m); }
                });
            }
        }, "save").start();
    }

    /** Called from the page's JavaScript (see handleDownload). */
    private class WebBridge {
        @JavascriptInterface
        public void save(final String name, final String mime, String base64) {
            final byte[] bytes;
            try {
                bytes = Base64.decode(base64, Base64.DEFAULT);
            } catch (IllegalArgumentException e) {
                return;
            }
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    String n = name;
                    if (n == null || n.length() == 0) n = URLUtil.guessFileName("blob", null, mime);
                    startSave(n, mime, bytes, null);
                }
            });
        }
    }

    // ------------------------------------------------------------ helpers

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    static String fmtSize(long b) {
        if (b >= (1L << 30)) return String.format(Locale.US, "%.2f GB", b / 1073741824.0);
        if (b >= (1L << 20)) return String.format(Locale.US, "%.1f MB", b / 1048576.0);
        return String.format(Locale.US, "%d KB", b / 1024);
    }

    // ------------------------------------------------------------ adapter

    private class ModelAdapter extends BaseAdapter {
        private List<File> items = new ArrayList<File>();

        void setItems(List<File> l) {
            items = l;
            notifyDataSetChanged();
        }

        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int i) { return items.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override public View getView(int pos, View convert, ViewGroup parent) {
            View v = convert;
            if (v == null) {
                v = LayoutInflater.from(MainActivity.this).inflate(R.layout.item_model, parent, false);
            }
            final File f = items.get(pos);
            ((TextView) v.findViewById(R.id.modelName)).setText(f.getName());

            boolean active = isActive(f);
            String status = "";
            if (active) {
                status = ServerService.getState() == ServerService.LOADING ? "  •  loading…" : "  •  loaded";
            }
            ((TextView) v.findViewById(R.id.modelInfo)).setText(fmtSize(f.length()) + status);

            Button load = (Button) v.findViewById(R.id.loadButton);
            load.setText(active ? "Unload" : "Load");
            load.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View b) { toggleModel(f); }
            });
            v.findViewById(R.id.deleteButton).setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View b) { confirmDelete(f); }
            });
            return v;
        }
    }
}
