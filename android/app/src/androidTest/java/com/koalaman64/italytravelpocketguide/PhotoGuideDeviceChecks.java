package com.koalaman64.italytravelpocketguide;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Test APK only. Exercises the real network-blocked WebView offline; leaves documents and models alone. */
public final class PhotoGuideDeviceChecks extends Instrumentation {
    private WebView web;
    private String originalTab;
    private Boolean originalOnline;
    private Boolean originalBlockNetworkLoads;
    private final StringBuilder evidence = new StringBuilder();

    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }

    @Override public void onStart() {
        Bundle result = new Bundle();
        int code = Activity.RESULT_OK;
        try {
            Intent intent = getTargetContext().getPackageManager().getLaunchIntentForPackage(getTargetContext().getPackageName());
            if (intent == null) throw new AssertionError("No launch activity");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            Activity activity = startActivitySync(intent);
            runOnMainSync(() -> web = findWeb(activity.getWindow().getDecorView()));
            if (web == null) throw new AssertionError("No WebView");
            // The production boundary intercepts non-local requests. Add the stricter
            // WebView network switch for this offline test, then restore it afterward.
            runOnMainSync(() -> {
                originalBlockNetworkLoads = web.getSettings().getBlockNetworkLoads();
                web.getSettings().setBlockNetworkLoads(true);
                if (!web.getSettings().getBlockNetworkLoads()) throw new AssertionError("Cannot block WebView network loads");
            });
            evidence.append("Test WebView network loads blocked PASS\n");
            originalOnline = "true".equals(js("navigator.onLine"));
            runOnMainSync(() -> web.setNetworkAvailable(false));
            await("navigator.onLine===false", "Android WebView offline mode");
            await("document.querySelectorAll('.destination-guide').length===9", "Offline guide startup");
            originalTab = js("document.querySelector('[role=tab][aria-selected=true]').id");
            js("document.getElementById('tab-itinerary').click();true");
            await("document.getElementById('tab-itinerary').getAttribute('aria-selected')==='true'", "Itinerary tab");
            await("document.querySelectorAll('.itinerary-events > li').length===30 && document.querySelectorAll('#today-root .destination-event-link').length===0", "Original itinerary and independent Today");

            JSONArray ids = new JSONArray(decode(js("JSON.stringify(Array.from(document.querySelectorAll('.destination-guide'),e=>e.id))")));
            for (int i = 0; i < ids.length(); i++) {
                String id = ids.getString(i);
                String section = "document.getElementById(" + JSONObject.quote(id) + ")";
                touch(section + ".querySelector(':scope > summary')");
                await(section + ".open", id + " expands by touch");
                for (int category = 0; category < 3; category++) {
                    String group = section + ".querySelectorAll('.destination-category')[" + category + "]";
                    touch(group + ".querySelector('summary')");
                    await(group + ".open", id + " category " + category);
                }
                int images = Integer.parseInt(js(section + ".querySelectorAll('img').length"));
                for (int photo = 0; photo < images; photo++) {
                    String image = section + ".querySelectorAll('img')[" + photo + "]";
                    js(image + ".scrollIntoView({block:'center'});true");
                    await(image + ".complete && " + image + ".naturalWidth>0 && !" + image + ".hidden", id + " photo " + photo);
                }
                String credit = section + ".querySelector('.destination-credit')";
                touch(credit + ".querySelector('summary')");
                await(credit + ".open && " + credit + ".innerText.includes('https://commons.wikimedia.org/')", id + " offline attribution");
                await("document.documentElement.scrollWidth<=document.documentElement.clientWidth+1", id + " no horizontal overflow");
            }
            await("document.querySelectorAll('.destination-card img').length===57 && new Set(Array.from(document.querySelectorAll('.destination-card img'),i=>i.getAttribute('src'))).size===38", "57 cards / 38 shared real photographs");
            await("Array.from(document.querySelectorAll('.destination-map')).every(a=>{const u=new URL(a.href);return u.origin==='https://www.google.com'&&u.pathname==='/maps/search/'&&u.searchParams.get('api')==='1'&&!!u.searchParams.get('query');})", "Existing Maps URL boundary");
            await("Array.from(document.querySelectorAll('.destination-card img')).every(i=>i.alt&&i.width&&i.height&&i.loading==='lazy')", "Photo accessible names and fixed dimensions");
            evidence.append("PHOTO GUIDE OFFLINE DEVICE CHECKS PASS\n");
        } catch (Throwable failure) {
            code = Activity.RESULT_CANCELED;
            evidence.append("FAIL: ").append(failure).append('\n');
        } finally {
            if (web != null && originalBlockNetworkLoads != null) {
                try { runOnMainSync(() -> web.getSettings().setBlockNetworkLoads(originalBlockNetworkLoads)); }
                catch (RuntimeException failure) { code = Activity.RESULT_CANCELED; evidence.append("Could not restore WebView network setting: ").append(failure).append('\n'); }
            }
            if (web != null && originalOnline != null) {
                try { runOnMainSync(() -> web.setNetworkAvailable(originalOnline)); }
                catch (RuntimeException failure) { code = Activity.RESULT_CANCELED; evidence.append("Could not restore WebView network indicator: ").append(failure).append('\n'); }
            }
            if (web != null && originalTab != null) {
                try { js("document.getElementById(" + originalTab + ").click();true"); }
                catch (Exception failure) { code = Activity.RESULT_CANCELED; evidence.append("Could not restore original tab: ").append(failure).append('\n'); }
            }
            result.putString("stream", evidence.toString());
            finish(code, result);
        }
    }

    private static WebView findWeb(View view) {
        if (view instanceof WebView) return (WebView) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                WebView found = findWeb(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private String js(String expression) throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        AtomicReference<String> value = new AtomicReference<>();
        runOnMainSync(() -> web.evaluateJavascript(expression, result -> { value.set(result); ready.countDown(); }));
        if (!ready.await(5, TimeUnit.SECONDS)) throw new AssertionError("WebView evaluation timed out");
        return value.get();
    }

    private static String decode(String raw) throws Exception { return new JSONArray("[" + raw + "]").getString(0); }

    private void await(String expression, String label) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        do {
            if ("true".equals(js(expression))) { evidence.append(label).append(" PASS\n"); return; }
            Thread.sleep(100);
        } while (System.nanoTime() < deadline);
        throw new AssertionError(label + " timed out");
    }

    private void touch(String expression) throws Exception {
        js(expression + ".scrollIntoView({block:'center'});true");
        // Allow scrolling to settle before computing a native touch point.
        Thread.sleep(150);
        JSONArray point = new JSONArray(decode(js("(()=>{const r=(" + expression + ").getBoundingClientRect();return JSON.stringify([r.left+r.width/2,r.top+r.height/2,innerWidth]);})()")));
        int[] origin = new int[2]; runOnMainSync(() -> web.getLocationOnScreen(origin));
        float scale = (float) web.getWidth() / (float) point.getDouble(2);
        float x = origin[0] + (float) point.getDouble(0) * scale;
        float y = origin[1] + (float) point.getDouble(1) * scale;
        long now = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0);
        MotionEvent up = MotionEvent.obtain(now, now + 50, MotionEvent.ACTION_UP, x, y, 0);
        try { sendPointerSync(down); sendPointerSync(up); } finally { down.recycle(); up.recycle(); }
    }
}
