package com.koalaman64.italytravelpocketguide;

import android.content.Context;
import android.os.SystemClock;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** One application-owned writer and bounded I/O worker for this process. Activity
 * recreation never closes a disk/decoder still owned by unfinished work. A halted
 * instance requires process restart; it is never replaced automatically.
 */
public final class WalletAndroidRuntime {
    private static WalletAndroidRuntime instance;
    public final WalletLifecycle lifecycle;
    private final ThreadPoolExecutor serial=new ThreadPoolExecutor(1,1,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(128));
    private final ThreadPoolExecutor io=new ThreadPoolExecutor(1,1,30,TimeUnit.SECONDS,new SynchronousQueue<>());
    private WalletAndroidRuntime(Context context) {
        Context app=context.getApplicationContext();
        lifecycle=new WalletLifecycle(evidence->{
            WalletDisk disk=WalletAndroidDisk.create(app);
            try { return new WalletStore(disk,serial,io,new WalletDecoder(app),evidence,SystemClock::elapsedRealtime); }
            catch(RuntimeException failed) { disk.close();throw failed; }
        },serial,SystemClock::elapsedRealtime);
    }
    public static synchronized WalletAndroidRuntime get(Context context) {
        if(instance==null)instance=new WalletAndroidRuntime(context);return instance;
    }
    public void execute(Runnable task) { serial.execute(task); }
}
