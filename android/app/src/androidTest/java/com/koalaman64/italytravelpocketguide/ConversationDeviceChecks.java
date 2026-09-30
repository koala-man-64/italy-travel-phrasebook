package com.koalaman64.italytravelpocketguide;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Rect;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.webkit.WebView;
import org.json.JSONArray;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.io.File;
import java.io.FileOutputStream;

/** Test APK only. No network setup, microphone grant, saved-data edits, or debug bridge. */
public final class ConversationDeviceChecks extends Instrumentation {
    private Activity activity;
    private WebView web;
    private int originalOrientation;
    private final StringBuilder evidence = new StringBuilder();

    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }

    @Override public void onStart() {
        Bundle result = new Bundle();
        int code = Activity.RESULT_OK;
        try {
            Intent intent = getTargetContext().getPackageManager().getLaunchIntentForPackage(getTargetContext().getPackageName());
            if (intent == null) throw new AssertionError("No launch activity");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity = startActivitySync(intent);
            originalOrientation = activity.getRequestedOrientation();
            runOnMainSync(() -> web = findWeb(activity.getWindow().getDecorView()));
            if (web == null) throw new AssertionError("No WebView");
            await("typeof FaceConversation==='object'", "Conversation startup");
            runOnMainSync(() -> activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT));
            await("innerHeight>innerWidth", "Portrait orientation");
            js("FaceConversation.open({en:'Where is the station?',it:'Dov’è la stazione?'});true");
            await("document.getElementById('conversationDialog').dataset.screen==='draft'", "Draft opens");
            checkLayout("portrait draft");
            js("document.getElementById('conversationEdit').click();true");
            await("document.getElementById('conversationDialog').dataset.screen==='editor'", "Editor opens");
            // Use a real touch gesture: programmatic HTML focus alone does not
            // reliably open every Android keyboard.
            String rawPoint = js("(()=>{const r=document.getElementById('conversationEditor').getBoundingClientRect();return JSON.stringify([r.left+r.width/2,r.top+20,innerWidth]);})()");
            JSONArray point = new JSONArray(new JSONArray("[" + rawPoint + "]").getString(0));
            int[] origin = new int[2]; runOnMainSync(() -> web.getLocationOnScreen(origin));
            float scale = (float) web.getWidth() / (float) point.getDouble(2);
            float x = origin[0] + (float) point.getDouble(0) * scale;
            float y = origin[1] + (float) point.getDouble(1) * scale;
            long downTime = SystemClock.uptimeMillis();
            sendPointerSync(MotionEvent.obtain(downTime,downTime,MotionEvent.ACTION_DOWN,x,y,0));
            sendPointerSync(MotionEvent.obtain(downTime,downTime+50,MotionEvent.ACTION_UP,x,y,0));
            runOnMainSync(() -> {
                web.requestFocus();
                ((InputMethodManager) activity.getSystemService(Activity.INPUT_METHOD_SERVICE)).showSoftInput(web, InputMethodManager.SHOW_IMPLICIT);
            });
            awaitKeyboard();
            js("(()=>{const e=document.getElementById('conversationEditor');e.value=('A long station message. ').repeat(80);e.setSelectionRange(5,15);e.dispatchEvent(new Event('input'));return true;})()");
            checkLayout("portrait keyboard and long editor");
            runOnMainSync(() -> activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE));
            Thread.sleep(500);
            if (activity.isDestroyed()) throw new AssertionError("Rotation recreated the Activity and discarded the live WebView");
            await("innerWidth>innerHeight", "Landscape orientation");
            checkLayout("landscape keyboard and long editor");
            runOnMainSync(() -> ((InputMethodManager) activity.getSystemService(Activity.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(web.getWindowToken(), 0));
            js("document.getElementById('conversationEditorDone').click();true");
            await("document.getElementById('conversationDialog').dataset.screen==='draft'", "Editor done");
            checkLayout("landscape long draft");
            js("FaceConversation.open({en:'Hello',it:'Buongiorno'});document.getElementById('conversationShow').click();true");
            await("document.getElementById('conversationDialog').dataset.screen==='italian'", "Prepared Italian opens");
            checkLayout("landscape Italian");
            checkItalianKeyboard(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, "portrait");
            checkItalianKeyboard(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, "landscape");
            js("document.getElementById('conversationMore').click();true");
            checkLayout("landscape More");
            // The paged menu can place Setup on a later page.
            js("document.getElementById('conversationOpenSetup').click();true");
            await("document.getElementById('conversationDialog').dataset.screen==='setup'", "Setup opens");
            checkLayout("landscape setup");
            evidence.append("DEVICE LAYOUT PASS\n");
        } catch (Throwable failure) {
            code = Activity.RESULT_CANCELED;
            evidence.append("DEVICE LAYOUT FAIL: ").append(failure.toString()).append('\n');
            try {
                File file = new File(getContext().getExternalFilesDir(null), "conversation-device-failure.png");
                Bitmap screenshot = getUiAutomation().takeScreenshot();
                if (screenshot != null) {
                    try (FileOutputStream out = new FileOutputStream(file)) { screenshot.compress(Bitmap.CompressFormat.PNG, 100, out); }
                    screenshot.recycle(); evidence.append("Failure screenshot: ").append(file.getAbsolutePath()).append('\n');
                }
            } catch (Throwable ignored) { evidence.append("Failure screenshot unavailable\n"); }
        } finally {
            if (web != null && activity != null && !activity.isDestroyed()) {
                try { js("if(window.FaceConversation)FaceConversation.clear();const d=document.getElementById('conversationDialog');if(d&&d.open)d.close();true"); }
                catch (Throwable ignored) { evidence.append("Cleanup: document unavailable\n"); }
            }
            if (activity != null && !activity.isDestroyed()) {
                try { runOnMainSync(() -> {
                    ((InputMethodManager) activity.getSystemService(Activity.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(activity.getWindow().getDecorView().getWindowToken(), 0);
                    activity.setRequestedOrientation(originalOrientation);
                }); } catch (Throwable failure) {
                    code = Activity.RESULT_CANCELED;
                    evidence.append("Cleanup could not restore keyboard/orientation: ").append(failure.toString()).append('\n');
                }
            }
            result.putString("stream", evidence.toString());
            finish(code, result);
        }
    }

    private void checkItalianKeyboard(int orientation, String label) throws Exception {
        runOnMainSync(() -> activity.setRequestedOrientation(orientation));
        Thread.sleep(600);
        int before = activity.getWindowManager().getDefaultDisplay().getRotation();
        js("document.getElementById('conversationTypeItalian').click();true");
        await("document.getElementById('conversationDialog').dataset.screen==='editor' && document.getElementById('conversationDialog').dataset.rotated==='false'", "Native Italian editor " + label);
        awaitRotation((before + 2) % 4);
        runOnMainSync(() -> {
            web.requestFocus();
            ((InputMethodManager) activity.getSystemService(Activity.INPUT_METHOD_SERVICE)).showSoftInput(web, InputMethodManager.SHOW_IMPLICIT);
        });
        awaitKeyboard();
        js("(()=>{const e=document.getElementById('conversationEditor');e.value='È qui vicino.';e.dispatchEvent(new Event('input'));return true;})()");
        checkLayout(label + " Italian keyboard");
        js("document.getElementById('conversationBack').click();true");
        awaitRotation(before);
        if (activity.getRequestedOrientation() != orientation) throw new AssertionError("Orientation preference not restored");
        if (activity.isDestroyed()) throw new AssertionError("Italian typing recreated Activity");
        evidence.append(label).append(" Italian keyboard and restore PASS\n");
    }

    private void awaitRotation(int expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        do {
            if (activity.getWindowManager().getDefaultDisplay().getRotation() == expected) return;
            Thread.sleep(100);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Display did not reach rotation " + expected);
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

    private void await(String expression, String label) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        do { if ("true".equals(js(expression))) { evidence.append(label).append(" PASS\n"); return; } Thread.sleep(100); }
        while (System.nanoTime() < deadline);
        throw new AssertionError(label + " timed out");
    }

    private void awaitKeyboard() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        do {
            AtomicReference<Boolean> visible = new AtomicReference<>(false);
            runOnMainSync(() -> {
                if (android.os.Build.VERSION.SDK_INT >= 30 && web.getRootWindowInsets() != null)
                    visible.set(web.getRootWindowInsets().isVisible(android.view.WindowInsets.Type.ime()));
                else {
                    Rect rect = new Rect(); web.getWindowVisibleDisplayFrame(rect);
                    visible.set(web.getRootView().getHeight() - rect.height() > 150);
                }
            });
            if (visible.get()) { evidence.append("System keyboard visible PASS\n"); return; }
            Thread.sleep(100);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("System keyboard did not appear");
    }

    private void checkLayout(String label) throws Exception {
        // Wait for the keyboard/orientation animation and requestAnimationFrame pagination.
        Thread.sleep(400);
        String raw = js("(()=>{const d=document.getElementById('conversationDialog'),r=d.getBoundingClientRect(),bad=[];for(const e of d.querySelectorAll('*')){if(!e.getClientRects().length||e.closest('[hidden]')||e.classList.contains('conversation-measure'))continue;const b=e.getBoundingClientRect();if(!b.width||!b.height)continue;if(e.scrollHeight>e.clientHeight+2||e.scrollWidth>e.clientWidth+2||b.top<r.top-1||b.bottom>r.bottom+1||b.left<r.left-1||b.right>r.right+1)bad.push((e.id||e.className)+' scroll '+e.scrollHeight+'/'+e.clientHeight+' rect '+b.top+','+b.bottom+' viewport '+r.height);}return bad.join(', ');})()");
        String failures = new JSONArray("[" + raw + "]").getString(0);
        if (!failures.isEmpty()) throw new AssertionError(label + ": " + failures);
        evidence.append(label).append(" PASS\n");
    }
}
