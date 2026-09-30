package com.koalaman64.italytravelpocketguide;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.Map;
import static com.koalaman64.italytravelpocketguide.WalletTypes.*;

/** Native-only tiled viewer. Root attaches/removes its view and supplies explicit retry action. */
public final class WalletViewer implements AutoCloseable {
    private final Activity activity;private final WalletRendererClient renderer;private final WalletRendererClient.Info info;
    private final Runnable dismiss,retry;private final LinearLayout root;private final TextView status;private final Surface surface;
    private final boolean wasSecure;private boolean closed,failed;private int page,epoch,rotation;private float zoom=1;
    private java.util.concurrent.CompletionStage<Result<Void>> retirement;
    public WalletViewer(Activity activity,WalletRendererClient renderer,WalletRendererClient.Info info,Runnable dismiss,Runnable retry){
        this.activity=activity;this.renderer=renderer;this.info=info;this.dismiss=dismiss;this.retry=retry;
        wasSecure=(activity.getWindow().getAttributes().flags&WindowManager.LayoutParams.FLAG_SECURE)!=0;activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        root=new LinearLayout(activity);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(Color.WHITE);status=new TextView(activity);root.addView(status);
        LinearLayout controls=new LinearLayout(activity);root.addView(controls);button(controls,"Previous",()->setPage(page-1));button(controls,"Next",()->setPage(page+1));button(controls,"Fit",()->setZoom(1));button(controls,"Close",()->{close();dismiss.run();});
        LinearLayout zoomControls=new LinearLayout(activity);root.addView(zoomControls);button(zoomControls,"Zoom out",()->setZoom(zoom/1.5f));button(zoomControls,"Zoom in",()->setZoom(zoom*1.5f));button(zoomControls,"Rotate",()->rotate());button(zoomControls,"Retry",()->{if(failed)retry.run();});
        surface=new Surface(activity);root.addView(surface,new LinearLayout.LayoutParams(-1,0,1));updateStatus();
    }
    private void button(LinearLayout row,String text,Runnable action){Button b=new Button(activity);b.setText(text);b.setContentDescription(text);b.setOnClickListener(v->action.run());row.addView(b,new LinearLayout.LayoutParams(0,-2,1));}
    public View view(){return root;}
    private void updateStatus(){status.setText(failed?"Document could not be rendered. Close or retry.":"Page "+(page+1)+" of "+info.pages+" · "+Math.round(zoom*100)+"%");}
    private void setPage(int selected){if(closed||failed||selected<0||selected>=info.pages)return;page=selected;zoom=1;epoch++;surface.needsDimensions=true;surface.reset();updateStatus();}
    private void setZoom(float selected){if(closed||failed)return;zoom=Math.max(1,Math.min(8,selected));epoch++;surface.reset();updateStatus();}
    private void rotate(){if(closed||failed)return;rotation=(rotation+1)%4;epoch++;surface.reset();}
    public void pause(){close();}
    public java.util.concurrent.CompletionStage<Result<Void>> retirement(){close();return retirement;}
    @Override public void close(){if(closed)return;closed=true;epoch++;surface.clear();retirement=renderer.closeSession();if(!wasSecure)activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);}
    private final class Surface extends View {
        final Paint paint=new Paint(Paint.FILTER_BITMAP_FLAG);final LinkedHashMap<String,Bitmap> cache=new LinkedHashMap<>(16,.75f,true);
        final ScaleGestureDetector scale;float panX,panY,lastX,lastY;boolean inFlight,needsDimensions;int pageWidth=info.width,pageHeight=info.height;
        Surface(Activity a){super(a);setContentDescription("Document page. Pinch to zoom; drag to pan.");scale=new ScaleGestureDetector(a,new ScaleGestureDetector.SimpleOnScaleGestureListener(){@Override public boolean onScale(ScaleGestureDetector d){setZoom(zoom*d.getScaleFactor());return true;}});}
        void clear(){for(Bitmap b:cache.values())b.recycle();cache.clear();invalidate();}
        void reset(){panX=panY=0;clear();}
        @Override protected void onDraw(Canvas canvas){super.onDraw(canvas);if(closed||failed||getWidth()==0||getHeight()==0)return;
            if(needsDimensions){if(!inFlight){inFlight=true;int requestedEpoch=epoch;renderer.validatePage(page).whenComplete((size,error)->post(()->{inFlight=false;if(closed)return;if(requestedEpoch!=epoch){invalidate();return;}if(error!=null||size.status!=Status.OK){failed=true;updateStatus();return;}pageWidth=size.value.width;pageHeight=size.value.height;needsDimensions=false;invalidate();}));}return;}
            // <=768 logical viewport pixels per edge => <=16 intersecting 256px tiles.
            float displayScale=Math.max(1,Math.max(getWidth(),getHeight())/768f);int vw=(int)Math.ceil((rotation%2==0?getWidth():getHeight())/displayScale),vh=(int)Math.ceil((rotation%2==0?getHeight():getWidth())/displayScale);
            int iw=info.orientation>=5?pageHeight:pageWidth,ih=info.orientation>=5?pageWidth:pageHeight;float fit=Math.min((float)vw/iw,(float)vh/ih);
            int cw=Math.max(1,Math.min(EDGE,Math.round(iw*fit*zoom))),ch=Math.max(1,Math.min(EDGE,Math.round(ih*fit*zoom)));
            panX=Math.max(0,Math.min(panX,Math.max(0,cw-vw)));panY=Math.max(0,Math.min(panY,Math.max(0,ch-vh)));
            canvas.save();canvas.translate(getWidth()/2f,getHeight()/2f);canvas.rotate(rotation*90);canvas.scale(displayScale,displayScale);canvas.translate(-vw/2f,-vh/2f);int firstX=(int)panX/256*256,firstY=(int)panY/256*256;
            for(int y=firstY;y<Math.min(ch,panY+vh);y+=256)for(int x=firstX;x<Math.min(cw,panX+vw);x+=256){String key=page+":"+cw+":"+ch+":"+x+":"+y;Bitmap b=cache.get(key);if(b!=null)canvas.drawBitmap(b,x-panX,y-panY,paint);else if(!inFlight){request(key,cw,ch,x,y,Math.min(256,cw-x),Math.min(256,ch-y));}}
            canvas.restore();
        }
        void request(String key,int cw,int ch,int x,int y,int w,int h){inFlight=true;int requestedEpoch=epoch;renderer.renderTile(page,cw,ch,x,y,w,h).whenComplete((result,error)->post(()->{
            inFlight=false;if(closed)return;if(requestedEpoch!=epoch){invalidate();return;}if(error!=null||result.status!=Status.OK){failed=true;clear();updateStatus();return;}
            WalletRendererClient.Tile t=result.value;Bitmap b=Bitmap.createBitmap(t.width,t.height,Bitmap.Config.ARGB_8888);b.copyPixelsFromBuffer(ByteBuffer.wrap(t.rgba));cache.put(key,b);while(cache.size()>16){Map.Entry<String,Bitmap> first=cache.entrySet().iterator().next();cache.remove(first.getKey());first.getValue().recycle();}invalidate();
        }));}
        @Override public boolean onTouchEvent(MotionEvent event){scale.onTouchEvent(event);if(event.getActionMasked()==MotionEvent.ACTION_DOWN){lastX=event.getX();lastY=event.getY();return true;}if(event.getActionMasked()==MotionEvent.ACTION_MOVE&&!scale.isInProgress()){float s=Math.max(1,Math.max(getWidth(),getHeight())/768f),dx=(lastX-event.getX())/s,dy=(lastY-event.getY())/s;switch(rotation){case 1:panX+=dy;panY-=dx;break;case 2:panX-=dx;panY-=dy;break;case 3:panX-=dy;panY+=dx;break;default:panX+=dx;panY+=dy;}lastX=event.getX();lastY=event.getY();invalidate();return true;}return true;}
    }
}
