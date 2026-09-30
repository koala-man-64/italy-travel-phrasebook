package com.koalaman64.italytravelpocketguide;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.webkit.WebViewAssetLoader;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import java.io.ByteArrayInputStream;
import java.util.Collections;

public final class MainActivity extends Activity {
    private static final String ASSET_HOST = "appassets.androidplatform.net";
    private static final String START_URL = "https://" + ASSET_HOST + "/assets/index.html";

    private WebView webView;
    private volatile ConversationController conversation;
    private JsonTransferSaf jsonTransfer;
    private boolean jsonDocumentReady;
    private boolean messageBridgeAvailable;
    private WalletWebViewBridge walletBridge;
    private WalletAndroidRuntime walletRuntime;
    private WalletActivityHost walletActivity;
    private Integer typingOrientation;
    private int previousOrientation;

    private void faceItalianKeyboard(boolean active) {
        if (active && typingOrientation == null) {
            previousOrientation = getRequestedOrientation();
            boolean portrait = getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT;
            int rotation = getWindowManager().getDefaultDisplay().getRotation();
            typingOrientation = KeyboardOrientation.opposite(portrait, rotation);
            setRequestedOrientation(typingOrientation);
        } else if (!active && typingOrientation != null) {
            typingOrientation = null;
            setRequestedOrientation(previousOrientation);
        }
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        WebViewAssetLoader assetLoader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        webView = new WebView(this);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setSupportMultipleWindows(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setSafeBrowsingEnabled(true);
        WebView.setWebContentsDebuggingEnabled(false);

        webView.setWebViewClient(new WebViewClient() {
            @Override public void onPageStarted(WebView view, String url, Bitmap favicon) {
                faceItalianKeyboard(false);
                if(walletBridge!=null)walletBridge.newDocument();
                jsonDocumentReady = false;
                if (jsonTransfer != null) jsonTransfer.newDocument();
                if (START_URL.equals(url) && conversation != null) conversation.newDocument();
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (!isLocalAsset(uri)) return blockedResponse();
                WebResourceResponse response = assetLoader.shouldInterceptRequest(uri);
                return response != null ? response : blockedResponse();
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (isLocalAsset(uri)) return false;
                if (request.isForMainFrame() && request.hasGesture() && isMapsSearch(uri)) {
                    openMaps(uri);
                } else if (request.isForMainFrame() && request.hasGesture()
                        && "https://translate.google.com/".equals(uri.toString())) {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, uri)); }
                    catch (ActivityNotFoundException missing) {
                        Toast.makeText(MainActivity.this, "No browser is available", Toast.LENGTH_SHORT).show();
                    }
                }
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if(walletBridge!=null)walletBridge.documentReady(url);
                jsonDocumentReady = START_URL.equals(url) && START_URL.equals(view.getUrl());
                if (!messageBridgeAvailable && START_URL.equals(url)) {
                    view.evaluateJavascript("window.__offlineConversationUnsupported = true", null);
                }
            }
        });

        conversation = new ConversationController(this, () -> {
            if (webView != null)
                webView.evaluateJavascript("if (window.nativeSpeechEnded) window.nativeSpeechEnded()", null);
        });
        jsonTransfer = new JsonTransferSaf(this);
        messageBridgeAvailable = WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER);
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(webView, "ItalyKeyboardOrientation",
                    Collections.singleton("https://" + ASSET_HOST),
                    (view, message, sourceOrigin, isMainFrame, reply) -> {
                        if (!isMainFrame || !("https://" + ASSET_HOST).equals(sourceOrigin.toString())
                                || !START_URL.equals(view.getUrl())) return;
                        String command = message.getData();
                        if ("italian".equals(command)) faceItalianKeyboard(true);
                        else if ("restore".equals(command)) faceItalianKeyboard(false);
                    });
            WebViewCompat.addWebMessageListener(webView, "ItalyJsonTransfer",
                    Collections.singleton("https://" + ASSET_HOST),
                    (view, message, sourceOrigin, isMainFrame, reply) -> {
                        if (!isMainFrame || !("https://" + ASSET_HOST).equals(sourceOrigin.toString())
                                || !jsonDocumentReady || !START_URL.equals(view.getUrl()) || jsonTransfer == null) return;
                        jsonTransfer.command(message.getData(), reply::postMessage);
                    });
            WebViewCompat.addWebMessageListener(webView, "OfflineConversation",
                    Collections.singleton("https://" + ASSET_HOST),
                    (view, message, sourceOrigin, isMainFrame, reply) -> {
                        if (!isMainFrame || !("https://" + ASSET_HOST).equals(sourceOrigin.toString())
                                || !START_URL.equals(view.getUrl()) || conversation == null) return;
                        conversation.setEvents(reply::postMessage);
                        conversation.command(message.getData());
                    });
        }
        walletRuntime=WalletAndroidRuntime.get(this);
        walletActivity=new WalletActivityHost(this,walletRuntime::execute);
        walletBridge=new WalletWebViewBridge(webView,new WalletWebViewBridge.Owner() {
            public void onBound(WalletTypes.Context context,ArchiveWebPort web) {
                walletRuntime.lifecycle.bind(context,web,walletActivity).whenComplete((result,error)->{
                    if(error!=null||result==null||result.status!=WalletTypes.Status.OK)
                        runOnUiThread(()->{if(walletBridge!=null)walletBridge.invalidateDocument(context);});
                    else runOnUiThread(()->{if(walletBridge!=null)walletBridge.commandsReady(context);});
                });
            }
            public void onInvalidated(WalletTypes.Context context) { walletActivity.newDocument();walletRuntime.lifecycle.invalidate(context); }
            public void onCommand(WalletTypes.Context context,String request,java.util.function.Consumer<String> reply) {
                walletRuntime.lifecycle.command(context,request).whenComplete((result,error)->{
                    if(error==null)reply.accept(result);
                    else runOnUiThread(()->{if(walletBridge!=null)walletBridge.invalidateDocument(context);});
                });
            }
        });
        webView.addJavascriptInterface(new AndroidSpeech(), "AndroidSpeech");
        setContentView(webView);
        webView.loadUrl(START_URL);
    }

    private static boolean isLocalAsset(Uri uri) {
        return "https".equals(uri.getScheme()) && ASSET_HOST.equals(uri.getHost())
                && uri.getPath() != null && uri.getPath().startsWith("/assets/");
    }

    private static boolean isMapsSearch(Uri uri) {
        return "https".equals(uri.getScheme()) && "www.google.com".equals(uri.getHost())
                && "/maps/search/".equals(uri.getPath())
                && "1".equals(uri.getQueryParameter("api"))
                && uri.getQueryParameter("query") != null
                && !uri.getQueryParameter("query").trim().isEmpty();
    }

    private void openMaps(Uri uri) {
        Intent maps = new Intent(Intent.ACTION_VIEW, uri).setPackage("com.google.android.apps.maps");
        try {
            startActivity(maps);
        } catch (ActivityNotFoundException noMapsApp) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, uri));
            } catch (ActivityNotFoundException noBrowser) {
                Toast.makeText(this, "Google Maps is not available", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private static WebResourceResponse blockedResponse() {
        return new WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden",
                Collections.emptyMap(), new ByteArrayInputStream(new byte[0]));
    }

    private final class AndroidSpeech {
        @JavascriptInterface
        public boolean speak(String text, boolean slow) {
            ConversationController current = conversation;
            if (current == null || !current.canSpeakItalian() || text == null || text.trim().isEmpty())
                return false;
            runOnUiThread(() -> { if (conversation != null) conversation.speakPhrase(text, slow); });
            return true;
        }

        @JavascriptInterface
        public void stop() {
            runOnUiThread(() -> { if (conversation != null) conversation.stopPlayback(); });
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == ConversationController.MICROPHONE_REQUEST && conversation != null)
            conversation.permissionResult(results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if(walletActivity!=null&&walletActivity.onActivityResult(requestCode,resultCode,data))return;
        if (jsonTransfer != null && jsonTransfer.onActivityResult(requestCode, resultCode, data)) return;
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override protected void onResume() {
        super.onResume();
        if (typingOrientation != null) setRequestedOrientation(typingOrientation);
        if (webView != null) webView.onResume();
        if (conversation != null) conversation.resume();
        if (jsonTransfer != null) jsonTransfer.resume();
    }

    @Override protected void onPause() {
        if (typingOrientation != null) setRequestedOrientation(previousOrientation);
        if(walletActivity!=null)walletActivity.pauseViewer();
        if (conversation != null) conversation.pause();
        if (webView != null) webView.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if(walletBridge!=null) { walletBridge.close();walletBridge=null; }
        if(walletActivity!=null) { walletActivity.close();walletActivity=null; }
        if (jsonTransfer != null) { jsonTransfer.close(); jsonTransfer = null; }
        if (conversation != null) { conversation.close(); conversation = null; }
        if (webView != null) {
            faceItalianKeyboard(false);
            if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER))
                WebViewCompat.removeWebMessageListener(webView, "ItalyKeyboardOrientation");
            if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER))
                WebViewCompat.removeWebMessageListener(webView, "OfflineConversation");
            if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER))
                WebViewCompat.removeWebMessageListener(webView, "ItalyJsonTransfer");
            if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER))
                WebViewCompat.removeWebMessageListener(webView, "ItalyWalletRecovery");
            webView.removeJavascriptInterface("AndroidSpeech");
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
