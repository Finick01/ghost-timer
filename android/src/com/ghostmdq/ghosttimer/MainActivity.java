package com.ghostmdq.ghosttimer;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.graphics.Insets;
import android.os.Build;
import android.view.View;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import android.database.Cursor;
import android.provider.OpenableColumns;
import android.webkit.ServiceWorkerClient;
import android.webkit.ServiceWorkerController;
import android.webkit.WebResourceResponse;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;

public class MainActivity extends Activity {
    private static final String APP_URL = "https://finick01.github.io/ghost-timer/";
    private static final int REQ_FILE = 1001;

    private WebView web;
    private ValueCallback<Uri[]> fileCallback;

    // Video received from "Compartir" / "Abrir con": served to the page at <APP_URL>__shared/<token>
    private Uri sharedUri;
    private String sharedName = "video.mp4";
    private String sharedMime = "video/mp4";
    private String sharedToken;
    private boolean sharedDelivered = true;
    private boolean pageReady = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        // Android 15+ draws apps edge to edge: keep the page clear of the status and navigation bars.
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        root.addView(web, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                Insets i = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                v.setPadding(i.left, i.top, i.right, i.bottom);
                return WindowInsets.CONSUMED;
            }
        });
        setContentView(root);

        // Predictive back (Android 13+): go back inside the app first, then close.
        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, new OnBackInvokedCallback() {
                @Override
                public void onBackInvoked() {
                    if (web != null && web.canGoBack()) web.goBack();
                    else finish();
                }
            });
        }

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowContentAccess(true);
        s.setAllowFileAccess(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setUserAgentString(s.getUserAgentString() + " GhostTimerAndroid/1.2");

        web.addJavascriptInterface(new Bridge(), "GhostAndroid");

        if (Build.VERSION.SDK_INT >= 24) {
            ServiceWorkerController.getInstance().setServiceWorkerClient(new ServiceWorkerClient() {
                @Override
                public WebResourceResponse shouldInterceptRequest(WebResourceRequest req) {
                    return serveShared(req);
                }
            });
        }

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                return serveShared(req);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                pageReady = true;
                notifyPageOfShared();
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                String url = u.toString();
                if (url.startsWith(APP_URL)) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception e) { }
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("video/*");
                try {
                    startActivityForResult(Intent.createChooser(i, "Elegí un video"), REQ_FILE);
                } catch (Exception e) {
                    fileCallback = null;
                    Toast.makeText(MainActivity.this, "No se pudo abrir la galería", Toast.LENGTH_LONG).show();
                    return false;
                }
                return true;
            }
        });

        handleIncoming(getIntent());
        if (savedInstanceState != null) web.restoreState(savedInstanceState);
        else web.loadUrl(APP_URL);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (handleIncoming(intent)) notifyPageOfShared();
    }

    /** Picks up a video shared from Google Fotos, the gallery or "Abrir con". */
    private boolean handleIncoming(Intent intent) {
        if (intent == null) return false;
        Uri u = null;
        String action = intent.getAction();
        if (Intent.ACTION_SEND.equals(action)) {
            Object extra = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (extra instanceof Uri) u = (Uri) extra;
            if (u == null && intent.getClipData() != null && intent.getClipData().getItemCount() > 0)
                u = intent.getClipData().getItemAt(0).getUri();
        } else if (Intent.ACTION_VIEW.equals(action)) {
            u = intent.getData();
        }
        if (u == null) return false;
        sharedUri = u;
        String type = intent.getType();
        if (type == null) type = getContentResolver().getType(u);
        sharedMime = (type != null && type.startsWith("video/")) ? type : "video/mp4";
        sharedName = "video.mp4";
        Cursor c = null;
        try {
            c = getContentResolver().query(u, new String[]{ OpenableColumns.DISPLAY_NAME }, null, null, null);
            if (c != null && c.moveToFirst()) {
                String n = c.getString(0);
                if (n != null && !n.isEmpty()) sharedName = n;
            }
        } catch (Exception e) {
        } finally {
            if (c != null) c.close();
        }
        sharedToken = Long.toHexString(System.nanoTime());
        sharedDelivered = false;
        // one video per launch: don't reload it again after rotation or restore
        intent.setAction(Intent.ACTION_MAIN);
        return true;
    }

    private void notifyPageOfShared() {
        if (!pageReady || sharedDelivered || web == null) return;
        web.evaluateJavascript("window.ghostCheckShared && window.ghostCheckShared()", null);
    }

    private WebResourceResponse serveShared(WebResourceRequest req) {
        try {
            Uri u = req.getUrl();
            String path = u.getPath();
            if (sharedUri == null || sharedToken == null || path == null) return null;
            if (!path.endsWith("/__shared/" + sharedToken)) return null;
            InputStream in = getContentResolver().openInputStream(sharedUri);
            if (in == null) return null;
            Map<String, String> h = new HashMap<String, String>();
            h.put("Cache-Control", "no-store");
            h.put("Access-Control-Allow-Origin", "*");
            return new WebResourceResponse(sharedMime, null, 200, "OK", h, in);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_FILE && fileCallback != null) {
            Uri[] result = null;
            if (resultCode == RESULT_OK && data != null) {
                if (data.getData() != null) result = new Uri[]{ data.getData() };
                else if (data.getClipData() != null && data.getClipData().getItemCount() > 0)
                    result = new Uri[]{ data.getClipData().getItemAt(0).getUri() };
            }
            fileCallback.onReceiveValue(result);
            fileCallback = null;
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onPause() { super.onPause(); }

    /** Receives the exported video from the page in base64 pieces and saves it to the gallery. */
    private class Bridge {
        private Uri pending;
        private OutputStream out;
        private long written;

        @JavascriptInterface
        public synchronized boolean begin(String filename, String mime) {
            closeQuietly();
            try {
                ContentResolver cr = getContentResolver();
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, filename);
                v.put(MediaStore.MediaColumns.MIME_TYPE, mime == null || mime.isEmpty() ? "video/mp4" : mime);
                v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/GhostTimer");
                v.put(MediaStore.MediaColumns.IS_PENDING, 1);
                pending = cr.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), v);
                if (pending == null) return false;
                out = cr.openOutputStream(pending);
                written = 0;
                return out != null;
            } catch (Exception e) {
                closeQuietly();
                return false;
            }
        }

        @JavascriptInterface
        public synchronized boolean append(String b64) {
            if (out == null) return false;
            try {
                byte[] bytes = Base64.decode(b64, Base64.DEFAULT);
                out.write(bytes);
                written += bytes.length;
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        @JavascriptInterface
        public synchronized String finish() {
            if (out == null || pending == null) return "";
            try {
                out.flush();
                out.close();
                out = null;
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.IS_PENDING, 0);
                getContentResolver().update(pending, v, null, null);
                final Uri saved = pending;
                pending = null;
                runOnUiThread(new Runnable() { public void run() { Toast.makeText(MainActivity.this, "Video guardado en la galería (Movies/GhostTimer)", Toast.LENGTH_LONG).show(); } });
                return saved.toString();
            } catch (Exception e) {
                closeQuietly();
                return "";
            }
        }

        /** Returns the pending shared video as "token|mime|name", or "" if there is none. */
        @JavascriptInterface
        public String takeShared() {
            if (sharedDelivered || sharedToken == null) return "";
            sharedDelivered = true;
            return sharedToken + "|" + sharedMime + "|" + sharedName;
        }

        @JavascriptInterface
        public void share(String uriString) {
            try {
                Uri u = Uri.parse(uriString);
                final Intent i = new Intent(Intent.ACTION_SEND);
                i.setType("video/mp4");
                i.putExtra(Intent.EXTRA_STREAM, u);
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                runOnUiThread(new Runnable() { public void run() { startActivity(Intent.createChooser(i, "Compartir video")); } });
            } catch (Exception e) { }
        }

        private void closeQuietly() {
            try { if (out != null) out.close(); } catch (Exception e) { }
            out = null;
            if (pending != null) {
                try { getContentResolver().delete(pending, null, null); } catch (Exception e) { }
                pending = null;
            }
        }
    }
}
