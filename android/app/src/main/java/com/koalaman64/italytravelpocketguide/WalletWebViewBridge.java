package com.koalaman64.italytravelpocketguide;

import android.os.Handler;
import android.os.Looper;
import android.webkit.WebView;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import static com.koalaman64.italytravelpocketguide.WalletTypes.*;

/** Activity-owned binding. Construct and call lifecycle methods on the main thread.
 * Host must gate/create UserData before loading the page and wire onBound to real
 * recovery before mounting personal UI. No native operation starts before the
 * current document echoes its freshly native-issued challenge.
 */
public final class WalletWebViewBridge implements AutoCloseable {
    public interface Owner {
        void onBound(Context context,ArchiveWebPort web);
        void onInvalidated(Context context);
        void onCommand(Context context,String request,java.util.function.Consumer<String> reply);
    }
    private final WebView view;
    private final Owner owner;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final String session=UUID.randomUUID().toString();
    private long epoch;
    private volatile Binding binding;
    private boolean closed;
    private final boolean supported;
    private static final class Binding {
        final Context context;volatile boolean ready,ended;
        WalletWebChannel channel;ArchiveWebPort web;Runnable timeout;boolean bound;String nonce;
        Binding(Context context) { this.context=context; }
    }
    public WalletWebViewBridge(WebView view,Owner owner) {
        this.view=java.util.Objects.requireNonNull(view);this.owner=java.util.Objects.requireNonNull(owner);
        supported=WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER);
        if(supported)WebViewCompat.addWebMessageListener(view,"ItalyWalletRecovery",Collections.singleton(WalletBridgeIdentity.ORIGIN),
            (source,message,origin,isMainFrame,reply)->{
                Binding b=binding;
                if(b==null||b.ended||!b.ready||source!=this.view||!WalletBridgeIdentity.trusted(isMainFrame,origin.toString(),source.getUrl()))return;
                String raw=message.getData();
                try {
                    if(!b.bound) {
                        java.util.Map<String,Object> m=WalletCodec.parse(WalletCodec.utf8(raw,2048),2048);
                        if("hello".equals(m.get("kind"))) {
                            WalletCodec.keys(m,"v","kind","nonce");if(WalletCodec.number(m.get("v"))!=1)return;
                            String nonce=WalletBridgeIdentity.nonce(m.get("nonce"));
                            String init=JsonTransferJson.encode(WalletCodec.obj("v",1,"kind","init","context",WalletWebChannel.context(b.context)));
                            evaluate(b,"accept",nonce,init);return;
                        }
                        if(!WalletBridgeIdentity.bound(raw,b.context))return;
                        b.nonce=WalletBridgeIdentity.nonce(m.get("nonce"));b.bound=true;main.removeCallbacks(b.timeout);
                        try { owner.onBound(b.context,b.web); }catch(RuntimeException failed) { invalidate(b); }
                    } else {
                        // Late old-document messages must not poison a same-URL successor.
                        if(WalletBridgeIdentity.currentFrame(raw,b.context)) {
                            java.util.Map<String,Object> m=WalletCodec.parse(WalletCodec.utf8(raw,WalletWebChannel.ENVELOPE_BYTES),WalletWebChannel.ENVELOPE_BYTES);
                            if("mutate".equals(m.get("kind"))||"wallet".equals(m.get("kind"))) {
                                try { owner.onCommand(b.context,raw,result->post(b,result)); }catch(RuntimeException failed) { invalidate(b); }
                            } else b.channel.receive(raw);
                        }
                    }
                } catch(Exception invalid) { /* Unbound or stale messages have no authority; handshake/request watchdog remains active. */ }
            });
    }
    public boolean supported() { return supported; }
    public void commandsReady(Context expected) {
        Binding b=binding;
        if(b!=null&&current(b,expected))post(b,JsonTransferJson.encode(WalletCodec.obj("v",1,"kind","wallet-ready","context",WalletWebChannel.context(expected))));
    }
    /** Called for every main-frame onPageStarted, even an untrusted URL. */
    public void newDocument() {
        Binding previous=binding;if(previous!=null)invalidate(previous);
        if(closed||!supported||epoch==Long.MAX_VALUE)return;
        Binding b=new Binding(new Context(session,UUID.randomUUID().toString(),Long.toString(++epoch),UUID.randomUUID().toString()));
        binding=b;
        b.channel=new WalletWebChannel(b.context,c->current(b,c),raw->post(b,raw),new WalletWebChannel.Timer() {
            public Runnable after(long ms,Runnable action) {
                if(!main.postDelayed(action,ms))throw new RejectedExecutionException();
                return ()->main.removeCallbacks(action);
            }
        });
        b.web=new ArchiveWebPort(b.channel,c->current(b,c));
    }
    private boolean current(Binding b,Context c) {
        return binding==b&&!b.ended&&b.ready&&b.context.sessionId.equals(c.sessionId)&&b.context.documentId.equals(c.documentId)&&b.context.epoch.equals(c.epoch);
    }
    /** Called onPageFinished; the challenge is injected into this exact document. */
    public void documentReady(String url) {
        Binding b=binding;
        if(b==null||b.ended||b.ready||!WalletBridgeIdentity.DOCUMENT.equals(url)||!WalletBridgeIdentity.DOCUMENT.equals(view.getUrl()))return;
        b.ready=true;b.timeout=()->invalidate(b);main.postDelayed(b.timeout,HOST_MS);
        evaluate(b,"hello",null,null);
    }
    private void post(Binding b,String raw) {
        if(b.nonce==null)throw new IllegalStateException("Unbound document");
        evaluate(b,"deliver",b.nonce,raw);
    }
    private void evaluate(Binding b,String method,String nonce,String raw) {
        if(!main.post(()->{
            if(binding!=b||!b.ready||b.ended||!WalletBridgeIdentity.DOCUMENT.equals(view.getUrl()))return;
            // JSON string encoding, never interpolated executable content or a page-selected function.
            String args=nonce==null?"":JsonTransferJson.encode(nonce)+","+JsonTransferJson.encode(raw);
            try { view.evaluateJavascript("window.ItalyWalletHost&&window.ItalyWalletHost."+method+"("+args+")",null); }
            catch(RuntimeException unavailable) { invalidate(b); }
        }))throw new RejectedExecutionException();
    }
    private void invalidate(Binding b) {
        if(b.ended)return;
        b.ended=true;
        // A same-URL successor must never receive its predecessor's invalidation.
        // The page checks the native-issued document challenge before gating.
        try { if(binding==b&&b.ready&&WalletBridgeIdentity.DOCUMENT.equals(view.getUrl()))
            view.evaluateJavascript("window.ItalyWalletHost&&window.ItalyWalletHost.invalidate("+JsonTransferJson.encode(WalletWebChannel.context(b.context))+")",null);
        } catch(RuntimeException unavailable) { /* Navigation may already have destroyed the document. */ }
        if(b.timeout!=null)main.removeCallbacks(b.timeout);
        if(b.web!=null)b.web.invalidate();if(b.channel!=null)b.channel.invalidate();
        owner.onInvalidated(b.context);
    }
    public void invalidateDocument() { Binding b=binding;if(b!=null)invalidate(b); }
    public void invalidateDocument(Context expected) { Binding b=binding;if(b!=null&&WalletWebChannel.context(b.context).equals(WalletWebChannel.context(expected)))invalidate(b); }
    @Override public void close() { if(closed)return;closed=true;invalidateDocument(); }
}
