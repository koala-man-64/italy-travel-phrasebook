package com.koalaman64.italytravelpocketguide;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import static com.koalaman64.italytravelpocketguide.WalletTypes.*;

/** Native viewer lease/FD lifecycle. Root owns attaching the view and origin/context admission. */
public final class WalletViewerHost implements AutoCloseable {
    private final Activity activity;private final WalletStore store;private final Handler main=new Handler(Looper.getMainLooper());
    private final WalletViewerLifecycle<Lease,Path,WalletRendererClient.Info> lifecycle=new WalletViewerLifecycle<>(main::post);
    public WalletViewerHost(Activity activity,WalletStore store){this.activity=activity;this.store=store;}
    public void open(WalletTypes.Context context,Identity expected,String documentId,Consumer<WalletViewer> show,Consumer<Code> failure,Runnable removed){
        main.post(()->lifecycle.open(new WalletViewerLifecycle.Port<Lease,Path,WalletRendererClient.Info>(){
            private Lease lease;private WalletRendererClient client;private WalletViewer viewer;private boolean hidden;
            public CompletionStage<Result<Lease>> acquire(){return store.acquireViewerLease(context,expected,documentId);}
            public CompletionStage<Result<Path>> resolve(Lease pin){lease=pin;return store.rendererInput(pin,documentId);}
            public CompletionStage<Result<WalletRendererClient.Info>> open(Path path){
                client=new WalletRendererClient(activity);
                try{return client.open(ParcelFileDescriptor.open(path.toFile(),ParcelFileDescriptor.MODE_READ_ONLY));}
                catch(Exception e){return CompletableFuture.completedFuture(Result.failed(Code.IO_FAILURE));}
            }
            public CompletionStage<Result<Void>> retire(){return client==null?CompletableFuture.completedFuture(Result.ok(null)):client.closeSession();}
            public CompletionStage<Result<Void>> release(Lease pin){return store.releaseSnapshotLease(pin);}
            public void show(WalletRendererClient.Info info){
                viewer=new WalletViewer(activity,client,info,()->{if(!hidden)lifecycle.close();},()->{if(!hidden){lifecycle.close();WalletViewerLifecycle.notifyObserver(()->failure.accept(Code.RENDERER_DIED));}});
                show.accept(viewer);renew();
            }
            private void renew(){main.postDelayed(()->{
                if(hidden||viewer==null)return;
                store.renewSnapshotLease(lease).whenComplete((r,e)->main.post(()->{
                    if(hidden)return;if(e!=null||r==null||r.status!=Status.OK)lifecycle.close();else renew();
                }));
            },30000);}
            public void hide(){if(hidden)return;hidden=true;if(viewer!=null){try{viewer.close();}finally{WalletViewerLifecycle.notifyObserver(removed);}}}
        },failure));
    }
    @Override public void close(){main.post(lifecycle::close);}
}
