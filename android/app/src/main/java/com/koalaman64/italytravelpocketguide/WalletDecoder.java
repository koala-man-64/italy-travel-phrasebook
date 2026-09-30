package com.koalaman64.italytravelpocketguide;

import android.content.Context;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import static com.koalaman64.italytravelpocketguide.WalletTypes.*;

/** Serial document-validation sessions. Uncertain service retirement requires explicit host restart. */
public final class WalletDecoder implements WalletStore.Decoder,AutoCloseable {
    private final Context context;private WalletRendererClient current;private boolean quarantined,closed;
    public WalletDecoder(Context context){this.context=context.getApplicationContext();}
    @Override public synchronized CompletionStage<Result<WalletStore.Decoded>> validate(Path path,long remainingMs){
        if(current!=null||quarantined||closed)return CompletableFuture.completedFuture(Result.failed(Code.BUSY));
        WalletRendererClient client=new WalletRendererClient(context);current=client;CompletableFuture<Result<WalletStore.Decoded>> result=new CompletableFuture<>();
        client.validate(path,remainingMs).whenComplete((decoded,error)->{
            if(error!=null||decoded.status!=Status.OK){synchronized(this){quarantined=true;}client.close();result.complete(Result.failed(error==null?decoded.code:Code.RENDERER_DIED));return;}
            client.closeSession().whenComplete((retired,closeError)->{synchronized(this){if(closeError!=null||retired.status!=Status.OK){quarantined=true;result.complete(Result.failed(Code.RENDERER_DIED));}else{current=null;result.complete(decoded);}}});
        });return result;
    }
    @Override public synchronized void close(){closed=true;if(current!=null)current.close();}
}
