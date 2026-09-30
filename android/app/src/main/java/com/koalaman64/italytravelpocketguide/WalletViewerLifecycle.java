package com.koalaman64.italytravelpocketguide;

import java.util.concurrent.CompletionStage;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import static com.koalaman64.italytravelpocketguide.WalletTypes.*;

/** Serialized attempt ownership, independent of Android so delayed completions are testable. */
final class WalletViewerLifecycle<L,F,I> {
    interface Port<L,F,I> {
        CompletionStage<Result<L>> acquire();
        CompletionStage<Result<F>> resolve(L lease);
        CompletionStage<Result<I>> open(F file);
        CompletionStage<Result<Void>> retire();
        CompletionStage<Result<Void>> release(L lease);
        void show(I info);
        void hide();
    }
    private final Executor serial;
    private Attempt current;
    private final class Attempt {
        final Port<L,F,I> port;final Consumer<Code> failure;
        L lease;boolean cancelled,pending=true,rendererStarted,retiring,reported;
        Attempt(Port<L,F,I> p,Consumer<Code> f){port=p;failure=f;}
    }
    WalletViewerLifecycle(Executor serial){this.serial=serial;}
    boolean busy(){return current!=null;}
    void open(Port<L,F,I> port,Consumer<Code> failure){
        if(current!=null){notifyObserver(()->failure.accept(Code.BUSY));return;}
        Attempt a=new Attempt(port,failure);current=a;
        invoke(port::acquire).whenComplete((r,e)->serial.execute(()->{
            a.pending=false;
            if(e!=null||r==null||r.status!=Status.OK){report(a,code(r,e));finishWithoutLease(a);return;}
            a.lease=r.value;if(a.cancelled){retire(a);return;}
            a.pending=true;
            invoke(()->port.resolve(a.lease)).whenComplete((file,error)->serial.execute(()->{
                a.pending=false;
                if(error!=null||file==null||file.status!=Status.OK){report(a,code(file,error));retire(a);return;}
                if(a.cancelled){retire(a);return;}
                a.rendererStarted=true;a.pending=true;
                invoke(()->port.open(file.value)).whenComplete((info,openError)->serial.execute(()->{
                    a.pending=false;
                    if(openError!=null||info==null||info.status!=Status.OK){report(a,code(info,openError));retire(a);return;}
                    if(a.cancelled){retire(a);return;}
                    try{port.show(info.value);}catch(RuntimeException failureToShow){report(a,Code.IO_FAILURE);retire(a);}
                }));
            }));
        }));
    }
    void close(){Attempt a=current;if(a==null)return;a.cancelled=true;if(!a.pending)retire(a);}
    private void retire(Attempt a){
        if(a.retiring)return;a.retiring=true;
        try{a.port.hide();}catch(RuntimeException uiFailure){report(a,Code.IO_FAILURE);}
        // UI removal is not retirement evidence and cannot prevent resource cleanup.
        if(!a.rendererStarted){release(a);return;}
        invoke(a.port::retire).whenComplete((r,e)->serial.execute(()->{
            if(e==null&&r!=null&&r.status==Status.OK)release(a);
            else report(a,code(r,e)); // Retain this exact attempt and its pin; no replacement.
        }));
    }
    private void release(Attempt a){
        invoke(()->a.port.release(a.lease)).whenComplete((r,e)->serial.execute(()->{
            if(e==null&&r!=null&&r.status==Status.OK){if(current==a)current=null;}
            else report(a,code(r,e));
        }));
    }
    private void finishWithoutLease(Attempt a){if(current==a)current=null;}
    private void report(Attempt a,Code code){if(!a.cancelled&&!a.reported){a.reported=true;notifyObserver(()->a.failure.accept(code));}}
    static void notifyObserver(Runnable observer){
        try{observer.run();}catch(RuntimeException observerFailure){/* Observers have no authority over cleanup or retirement evidence. */}
    }
    private Code code(Result<?> r,Throwable e){return e!=null||r==null||r.code==null?Code.RENDERER_DIED:r.code;}
    private <T> CompletionStage<Result<T>> invoke(java.util.function.Supplier<CompletionStage<Result<T>>> action){
        try{return action.get();}catch(RuntimeException failure){return CompletableFuture.completedFuture(Result.failed(Code.IO_FAILURE));}
    }
}
