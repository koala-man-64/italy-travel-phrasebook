package com.koalaman64.italytravelpocketguide;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import java.io.InputStream;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static com.koalaman64.italytravelpocketguide.WalletTypes.*;

/** Fixed SAF selection; root routes request codes and context lifecycle. No URI crosses native seam. */
public final class WalletSaf implements AutoCloseable {
    // Disjoint from JsonTransferSaf's existing range; never wrap or recycle a code.
    private static final AtomicInteger NEXT=new AtomicInteger(0x8000);
    private final Activity activity;private final Handler main=new Handler(Looper.getMainLooper());
    private final ThreadPoolExecutor opener=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new SynchronousQueue<>());
    private CompletableFuture<Result<PickerTicket>> pending;private WalletTypes.Context context;private int code;private boolean closed;
    private CancellationSignal cancellation;private volatile Ticket ticket;private Runnable deadline;
    public WalletSaf(Activity activity){this.activity=activity;}
    public CompletionStage<Result<PickerTicket>> requestImport(WalletTypes.Context context){
        CompletableFuture<Result<PickerTicket>> result=new CompletableFuture<>();main.post(()->{
            if(closed||pending!=null||ticket!=null||opener.getActiveCount()!=0){result.complete(Result.failed(Code.BUSY));return;}
            int request=NEXT.getAndIncrement();if(request>0xbfff){result.complete(Result.failed(Code.UNSUPPORTED));return;}
            this.context=context;pending=result;code=request;cancellation=new CancellationSignal();
            deadline=()->finishFailure(Code.PICKER_TIMEOUT);main.postDelayed(deadline,PICKER_MS);
            Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                    .putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"application/pdf","image/png","image/jpeg"})
                    .putExtra(Intent.EXTRA_ALLOW_MULTIPLE,false).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            try{activity.startActivityForResult(intent,request);}catch(SecurityException e){finishFailure(Code.PERMISSION_DENIED);}catch(RuntimeException e){finishFailure(Code.PROVIDER_UNAVAILABLE);}
        });return result;
    }
    public boolean onActivityResult(int request,int result,Intent data){
        if(pending==null||request!=code)return false;main.removeCallbacks(deadline);
        if(result==Activity.RESULT_CANCELED){finishFailure(Code.CANCELLED);return true;}
        if(result!=Activity.RESULT_OK||data==null||data.getData()==null||data.getClipData()!=null){finishFailure(Code.PROVIDER_UNAVAILABLE);return true;}
        Uri uri=data.getData();if(!"content".equals(uri.getScheme())){finishFailure(Code.INVALID_REQUEST);return true;}
        CompletableFuture<Result<PickerTicket>> operation=pending;WalletTypes.Context operationContext=context;CancellationSignal signal=cancellation;
        deadline=()->finishFailure(Code.COPY_TIMEOUT);main.postDelayed(deadline,NO_PROGRESS_MS);
        try{opener.execute(()->{try{
            ParcelFileDescriptor fd=activity.getContentResolver().openFileDescriptor(uri,"r",signal);
            if(fd==null)throw new java.io.IOException();Ticket selected=new Ticket(operationContext,fd,signal);
            main.post(()->{if(closed||pending!=operation||signal.isCanceled()){selected.cancel();return;}main.removeCallbacks(deadline);ticket=selected;pending=null;selected.arm();operation.complete(Result.ok(selected));});
        }catch(Exception e){main.post(()->{if(pending==operation)finishFailure(signal.isCanceled()?Code.CANCELLED:Code.IO_FAILURE);});}});}catch(java.util.concurrent.RejectedExecutionException e){finishFailure(Code.BUSY);}return true;
    }
    private void finishFailure(Code reason){if(deadline!=null)main.removeCallbacks(deadline);if(cancellation!=null)cancellation.cancel();if(ticket!=null)ticket.cancel();if(pending!=null){pending.complete(Result.failed(reason));pending=null;}}
    public void newDocument(){main.post(()->finishFailure(Code.STALE_SESSION));}
    private final class Ticket implements PickerTicket {
        final WalletTypes.Context selectedContext;final ParcelFileDescriptor fd;final InputStream input;final CancellationSignal signal;
        volatile boolean done;volatile long progress=SystemClock.elapsedRealtime();final Runnable timer=this::checkProgress;
        Ticket(WalletTypes.Context c,ParcelFileDescriptor fd,CancellationSignal signal){selectedContext=c;this.fd=fd;this.signal=signal;input=new ParcelFileDescriptor.AutoCloseInputStream(fd);}
        void arm(){main.postDelayed(timer,NO_PROGRESS_MS);}
        void checkProgress(){if(!done){if(SystemClock.elapsedRealtime()-progress>=NO_PROGRESS_MS)cancel();else main.postDelayed(timer,NO_PROGRESS_MS);}}
        public WalletTypes.Context context(){return selectedContext;}
        public int read(byte[] bytes,int offset,int count)throws WalletFailure{if(done||signal.isCanceled())throw new WalletFailure(Code.CANCELLED);if(bytes==null||offset<0||count<1||count>CHUNK_BYTES||offset>bytes.length-count)throw new WalletFailure(Code.INVALID_REQUEST);try{int n=input.read(bytes,offset,count);if(done||signal.isCanceled())throw new WalletFailure(Code.CANCELLED);if(n>0)progress=SystemClock.elapsedRealtime();return n;}catch(java.io.IOException e){throw new WalletFailure(done?Code.CANCELLED:Code.IO_FAILURE);}}
        void cancel(){signal.cancel();try{close();}catch(WalletFailure ignored){}}
        public void close()throws WalletFailure{if(done)return;done=true;main.removeCallbacks(timer);try{input.close();fd.close();}catch(java.io.IOException e){throw new WalletFailure(Code.IO_FAILURE);}finally{main.post(()->{if(ticket==this)ticket=null;});}}
    }
    @Override public void close(){main.post(()->{closed=true;finishFailure(Code.INTERRUPTED);opener.shutdown();});}
}
