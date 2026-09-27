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
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.OutputStream;

public class MainActivity extends Activity {
    private static final String APP_URL = "https://finick01.github.io/ghost-timer/";
    private static final int REQ_FILE = 1001;

    private WebView web;
    private ValueCallback<Uri[]> fileCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowContentAccess(true);
        s.setAllowFileAccess(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setUserAgentString(s.getUserAgentString() + " GhostTimerAndroid/1.0");

        web.addJavascriptInterface(new Bridge(), "GhostAndroid");

        web.setWebViewClient(new WebViewClient() {
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

        if (savedInstanceState != null) web.restoreState(savedInstanceState);
        else web.loadUrl(APP_URL);
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
