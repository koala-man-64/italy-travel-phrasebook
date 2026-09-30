package com.koalaman64.italytravelpocketguide;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import static com.koalaman64.italytravelpocketguide.WalletTypes.*;

/** Activity-only UI/SAF owner. No provider URI or document bytes enter the WebView. */
public final class WalletActivityHost implements WalletCommands.Host,AutoCloseable {
    private static java.util.List<?> list(Object value) { if(!(value instanceof java.util.List))throw new IllegalArgumentException();return (java.util.List<?>)value; }
    private final Activity activity;
    private final ArchiveSaf documents;
    private final ArchiveSaf archives;
    private final Handler main=new Handler(Looper.getMainLooper());
    private volatile Set<String> relations=java.util.Collections.emptySet();
    private volatile boolean closed;
    private WalletViewerHost viewer;
    private WalletStore viewerStore;
    public WalletActivityHost(Activity activity,Executor worker) {
        this.activity=activity;documents=ArchiveSaf.documents(activity);archives=new ArchiveSaf(activity);
        worker.execute(()->{
            try(InputStream input=activity.getAssets().open("trip-content.json")) {
                ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] chunk=new byte[4096];int n;
                while((n=input.read(chunk))!=-1) { if(bytes.size()+n>524288)throw new IllegalArgumentException();bytes.write(chunk,0,n); }
                Map<String,Object> trip=WalletCodec.parse(bytes.toByteArray(),524288);
                if(!"itguide-trip".equals(trip.get("format"))||WalletCodec.number(trip.get("schemaVersion"))!=1)throw new IllegalArgumentException();
                String tripId=WalletCodec.text(trip.get("tripId"));Set<String> allowed=new HashSet<>();
                for(Object raw:list(trip.get("events"))) {
                    Map<String,Object> event=WalletCodec.map(raw);String eventId=WalletCodec.text(event.get("id"));
                    new Ref(tripId,eventId,"doc_00000000000000000000000000000000");
                    boolean included=false;
                    for(Object dayRaw:list(trip.get("days"))) { Map<String,Object> day=WalletCodec.map(dayRaw);
                        if(event.get("dayId").equals(day.get("id"))&&list(day.get("eventIds")).contains(eventId))included=true;
                    }
                    if(!included||!allowed.add(tripId+"/"+eventId))throw new IllegalArgumentException();
                }
                if(!closed)relations=java.util.Collections.unmodifiableSet(allowed);
            }catch(Exception invalid) { relations=java.util.Collections.emptySet(); }
        });
    }
    public boolean supported() { return !closed&&Build.VERSION.SDK_INT>=30; }
    public boolean isCurrentRelation(Ref ref) { return !closed&&relations.contains(ref.tripId+"/"+ref.eventId); }
    public CompletionStage<Result<PickerTicket>> importDocument(Context context) { return documents.requestImport(context); }
    public CompletionStage<Result<PickerTicket>> importArchive(Context context) { return archives.requestImport(context); }
    public CompletionStage<Result<OutputStream>> exportArchive(Context context) { return archives.requestExport(context); }
    public CompletionStage<Result<Void>> openDocument(Context context,WalletNativePort port,Identity expected,String id) {
        CompletableFuture<Result<Void>> answer=new CompletableFuture<>();
        main.post(()->{
            if(!supported()||!(port instanceof WalletStore)) { answer.complete(Result.failed(Code.UNSUPPORTED));return; }
            WalletStore store=(WalletStore)port;
            if(viewer==null) { viewerStore=store;viewer=new WalletViewerHost(activity,store); }
            if(viewerStore!=store) { answer.complete(Result.failed(Code.STALE_SESSION));return; }
            final WalletViewer[] shown={null};
            viewer.open(context,expected,id,v->{
                if(closed) { v.close();answer.complete(Result.failed(Code.STALE_SESSION));return; }
                shown[0]=v;activity.addContentView(v.view(),new ViewGroup.LayoutParams(-1,-1));answer.complete(Result.ok(null));
            },code->answer.complete(Result.failed(code)),()->{
                if(shown[0]!=null&&shown[0].view().getParent() instanceof ViewGroup)
                    ((ViewGroup)shown[0].view().getParent()).removeView(shown[0].view());
            });
        });return answer;
    }
    public boolean onActivityResult(int code,int result,Intent data) {
        if(code>=0xc000&&code<=0xffff) { archives.onActivityResult(code,result,data);return true; }
        if(code>=0x8000&&code<=0xbfff) { documents.onActivityResult(code,result,data);return true; }
        return false;
    }
    public void pauseViewer() { if(viewer!=null)viewer.close(); }
    public void newDocument() { documents.newDocument();archives.newDocument();pauseViewer(); }
    public void close() { closed=true;relations=java.util.Collections.emptySet();documents.close();archives.close();pauseViewer(); }
}
