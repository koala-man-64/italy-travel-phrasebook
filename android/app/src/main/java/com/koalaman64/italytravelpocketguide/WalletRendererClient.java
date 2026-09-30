package com.koalaman64.italytravelpocketguide;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import static com.koalaman64.italytravelpocketguide.WalletTypes.*;

/** One worker/one request, independent host deadline, no automatic rebind or worker replacement. */
public final class WalletRendererClient implements WalletStore.Decoder,AutoCloseable {
    public static final class Info {public final String kind;public final int pages,width,height,orientation;Info(String k,int p,int w,int h,int o){kind=k;pages=p;width=w;height=h;orientation=o;}}
    public static final class Tile {public final int page,canvasW,canvasH,x,y,width,height;public final byte[] rgba;
        Tile(int p,int cw,int ch,int x,int y,int w,int h,byte[] rgba){page=p;canvasW=cw;canvasH=ch;this.x=x;this.y=y;width=w;height=h;this.rgba=rgba;}}
    private interface Write {void write(Parcel data)throws Exception;}
    private interface Read<T> {T read(Parcel reply)throws Exception;}
    private final Context context;private final Handler main=new Handler(Looper.getMainLooper());
    private final ThreadPoolExecutor worker=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new java.util.concurrent.ArrayBlockingQueue<>(1));
    private final AtomicBoolean busy=new AtomicBoolean();private final long nonce=new SecureRandom().nextLong()|1L;
    private volatile IBinder binder;private volatile boolean quarantined,closed,retiring;private long request;private boolean bound;
    private final WalletRendererRetirement retirementEvidence=new WalletRendererRetirement();
    private ParcelFileDescriptor inputDescriptor;
    private CompletableFuture<Result<Void>> retirement;
    private CompletableFuture<Result<Info>> opening;private java.util.function.Consumer<Code> failPending;private volatile CompletionStage<?> activeCall;
    private final ServiceConnection connection=new ServiceConnection(){
        public void onServiceConnected(ComponentName name,IBinder service){if(closed||quarantined||retiring)return;binder=service;try{service.linkToDeath(()->main.post(()->retire(Code.RENDERER_DIED)),0);}catch(Exception e){retire(Code.RENDERER_DIED);}if(bindReady!=null){Runnable ready=bindReady;bindReady=null;ready.run();}}
        public void onServiceDisconnected(ComponentName name){retire(Code.RENDERER_DIED);}
        public void onBindingDied(ComponentName name){retire(Code.RENDERER_DIED);}
        public void onNullBinding(ComponentName name){retire(Code.RENDERER_DIED);}
    };
    private Runnable bindReady;
    public WalletRendererClient(Context context){this.context=context.getApplicationContext();}
    private void retire(Code reason){quarantined=true;binder=null;bindReady=null;closeInput();if(opening!=null)opening.complete(Result.failed(reason));if(failPending!=null)failPending.accept(reason);if(bound){try{context.unbindService(connection);}catch(IllegalArgumentException ignored){}bound=false;}}
    private synchronized void closeInput(){if(inputDescriptor!=null){try{inputDescriptor.close();inputDescriptor=null;retirementEvidence.descriptorClosed();}catch(Exception ignored){/* No retirement proof. */}}}
    public CompletionStage<Result<Info>> open(ParcelFileDescriptor descriptor){
        CompletableFuture<Result<Info>> result=new CompletableFuture<>();main.post(()->{
            if(closed||quarantined||retiring||opening!=null){closeFd(descriptor);result.complete(Result.failed(Code.BUSY));return;}opening=result;inputDescriptor=descriptor;retirementEvidence.descriptorOwned();
            Runnable timeout=()->{retire(Code.RENDERER_TIMEOUT);closeFd(descriptor);result.complete(Result.failed(Code.RENDERER_TIMEOUT));};main.postDelayed(timeout,HOST_MS);
            bindReady=()->{retirementEvidence.submitted();call(WalletRenderBounds.OPEN,data->descriptor.writeToParcel(data,0),reply->{
                int kind=reply.readInt(),pages=reply.readInt(),width=reply.readInt(),height=reply.readInt(),orientation=reply.readInt();new PageSize(width,height);
                if(kind<1||kind>3||pages<1||pages>PAGES||(kind!=1&&pages!=1)||orientation<1||orientation>8)throw new WalletFailure(Code.INVALID_RENDER_REPLY);
                return new Info(kind==1?"pdf":kind==2?"png":"jpeg",pages,width,height,orientation);
            }).whenComplete((r,e)->{main.removeCallbacks(timeout);closeInput();if(e!=null){retire(Code.INVALID_RENDER_REPLY);result.complete(Result.failed(Code.INVALID_RENDER_REPLY));}else result.complete(r);});};
            try{bound=context.bindService(new Intent(context,WalletRendererService.class),connection,Context.BIND_AUTO_CREATE);if(!bound){retire(Code.UNSUPPORTED);closeFd(descriptor);result.complete(Result.failed(Code.UNSUPPORTED));}}catch(RuntimeException e){retire(Code.UNSUPPORTED);closeFd(descriptor);result.complete(Result.failed(Code.UNSUPPORTED));}
        });return result;
    }
    private <T> CompletionStage<Result<T>> call(int operation,Write write,Read<T> read){
        CompletableFuture<Result<T>> result=new CompletableFuture<>();IBinder target=binder;
        if(closed||quarantined||(retiring&&operation!=WalletRenderBounds.CLOSE)||target==null||!busy.compareAndSet(false,true)){result.complete(Result.failed(Code.BUSY));return result;}
        long id=++request;activeCall=result;failPending=code->result.complete(Result.failed(code));Runnable timeout=()->{retire(Code.RENDERER_TIMEOUT);result.complete(Result.failed(Code.RENDERER_TIMEOUT));};main.postDelayed(timeout,HOST_MS);
        try{worker.execute(()->{Parcel input=Parcel.obtain(),output=Parcel.obtain();Result<T> completion;try{
            input.writeInt(WalletRenderBounds.MAGIC);input.writeLong(nonce);input.writeLong(id);write.write(input);
            if(!target.transact(operation,input,output,0))throw new WalletFailure(Code.INVALID_RENDER_REPLY);
            if(output.dataSize()<24||output.dataSize()>PARCEL_BYTES||output.hasFileDescriptors())throw new WalletFailure(Code.INVALID_RENDER_REPLY);
            if(output.readInt()!=WalletRenderBounds.MAGIC)throw new WalletFailure(Code.INVALID_RENDER_REPLY);long actualNonce=output.readLong(),actualId=output.readLong();int status=output.readInt();WalletRenderBounds.reply(output.dataSize(),output.hasFileDescriptors(),nonce,actualNonce,id,actualId,status);
            if(status!=0){if(output.dataAvail()!=0)throw new WalletFailure(Code.INVALID_RENDER_REPLY);throw new WalletFailure(Code.values()[status-1]);}
            T value=read.read(output);if(output.dataAvail()!=0)throw new WalletFailure(Code.INVALID_RENDER_REPLY);
            completion=!quarantined&&!closed?Result.ok(value):Result.failed(Code.INTERRUPTED);
        }catch(Exception error){Code code=error instanceof WalletFailure?((WalletFailure)error).code:Code.RENDERER_DIED;main.post(()->retire(code));completion=Result.failed(code);}
        finally{input.recycle();output.recycle();main.removeCallbacks(timeout);busy.set(false);}
        result.complete(completion);
        });}catch(java.util.concurrent.RejectedExecutionException e){busy.set(false);main.removeCallbacks(timeout);result.complete(Result.failed(Code.BUSY));}
        return result;
    }
    public CompletionStage<Result<PageSize>> validatePage(int page){return call(WalletRenderBounds.VALIDATE_PAGE,p->p.writeInt(page),r->new PageSize(r.readInt(),r.readInt()));}
    public CompletionStage<Result<Tile>> renderTile(int page,int cw,int ch,int x,int y,int width,int height){
        try{WalletRenderBounds.tile(page,cw,ch,x,y,width,height);}catch(WalletFailure e){return CompletableFuture.completedFuture(Result.failed(e.code));}
        return call(WalletRenderBounds.TILE,p->{p.writeInt(page);p.writeInt(cw);p.writeInt(ch);p.writeInt(x);p.writeInt(y);p.writeInt(width);p.writeInt(height);},r->{
            int rp=r.readInt(),rcw=r.readInt(),rch=r.readInt(),rx=r.readInt(),ry=r.readInt(),rw=r.readInt(),rh=r.readInt(),bytes=r.readInt();int expected=WalletRenderBounds.tile(rp,rcw,rch,rx,ry,rw,rh);
            if(rp!=page||rcw!=cw||rch!=ch||rx!=x||ry!=y||rw!=width||rh!=height||bytes!=expected||r.dataAvail()!=bytes+4)throw new WalletFailure(Code.INVALID_RENDER_REPLY);
            byte[] pixels=new byte[expected];r.readByteArray(pixels);return new Tile(page,cw,ch,x,y,width,height,pixels);
        });
    }
    @Override public CompletionStage<Result<WalletStore.Decoded>> validate(Path path,long remainingMs){
        CompletableFuture<Result<WalletStore.Decoded>> result=new CompletableFuture<>();long deadline=SystemClock.elapsedRealtime()+Math.min(remainingMs,VALIDATION_MS);
        try{open(ParcelFileDescriptor.open(path.toFile(),ParcelFileDescriptor.MODE_READ_ONLY)).whenComplete((info,error)->{
            if(error!=null||info.status!=Status.OK){result.complete(Result.failed(error==null?info.code:Code.RENDERER_DIED));return;}
            validateNext(info.value,0,new ArrayList<>(),deadline,result);
        });}catch(Exception e){result.complete(Result.failed(Code.IO_FAILURE));}return result;
    }
    private void validateNext(Info info,int page,List<PageSize> pages,long deadline,CompletableFuture<Result<WalletStore.Decoded>> result){
        if(SystemClock.elapsedRealtime()>=deadline){result.complete(Result.failed(Code.VALIDATION_TIMEOUT));close();return;}
        if(page==info.pages){result.complete(Result.ok(new WalletStore.Decoded(info.kind,pages,info.orientation)));return;}
        // Queue on main after previous worker has released busy, avoiding recursive completion races.
        main.post(()->validatePage(page).whenComplete((size,error)->{if(error!=null||size.status!=Status.OK){result.complete(Result.failed(error==null?size.code:Code.RENDERER_DIED));close();return;}pages.add(size.value);main.post(()->validateNext(info,page+1,pages,deadline,result));}));
    }
    public boolean isQuarantined(){return quarantined;}
    public boolean isWorkerIdle(){return !busy.get();}
    public synchronized CompletionStage<Result<Void>> closeSession(){
        if(retirement!=null)return retirement;
        retirement=new CompletableFuture<>();main.post(()->{retiring=true;bindReady=null;closeInput();finishRetirement();});return retirement;
    }
    private void finishRetirement(){
        if(retirementEvidence.proven(!busy.get())){completeRetirement(Result.ok(null));return;}
        if(busy.get()){
            CompletionStage<?> pending=activeCall;
            if(pending==null){completeRetirement(Result.failed(Code.BUSY));return;}
            pending.whenComplete((v,e)->main.post(()->{if(busy.get())completeRetirement(Result.failed(Code.RENDERER_TIMEOUT));else finishRetirement();}));return;
        }
        if(closed||quarantined||binder==null){completeRetirement(Result.failed(Code.RENDERER_DIED));return;}
        call(WalletRenderBounds.CLOSE,p->{},p->(Void)null).whenComplete((r,e)->main.post(()->{
            if(e==null&&r!=null&&r.status==Status.OK)retirementEvidence.closeAcknowledged();
            completeRetirement(retirementEvidence.proven(!busy.get())?Result.ok(null):Result.failed(Code.RENDERER_DIED));
        }));
    }
    private void completeRetirement(Result<Void> result){closed=true;retire(Code.INTERRUPTED);worker.shutdown();retirement.complete(result);}
    @Override public void close(){closeSession();}
    private static void closeFd(ParcelFileDescriptor fd){try{fd.close();}catch(Exception ignored){}}
}
