package com.koalaman64.italytravelpocketguide;

import android.app.Service;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.pdf.PdfRenderer;
import android.media.ExifInterface;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.system.Os;
import android.system.OsConstants;
import java.nio.ByteBuffer;
import java.io.IOException;
import static com.koalaman64.italytravelpocketguide.WalletTypes.*;

/** Must be declared exported=false isolatedProcess=true by the root-owned manifest. */
public final class WalletRendererService extends Service {
    private final Handler watchdog=new Handler(Looper.getMainLooper());
    private PdfRenderer pdf;private BitmapRegionDecoder image;private ParcelFileDescriptor input;
    private long session;private int width,height,pages,kind,orientation=1;
    private final Binder binder=new Binder(){@Override protected synchronized boolean onTransact(int code,Parcel data,Parcel reply,int flags){
        long nonce=0,request=0;Runnable kill=()->android.os.Process.killProcess(android.os.Process.myPid());
        try{
            if(data.dataSize()>4096||data.readInt()!=WalletRenderBounds.MAGIC)throw new WalletFailure(Code.INVALID_REQUEST);
            nonce=data.readLong();request=data.readLong();if(nonce==0||request<=0)throw new WalletFailure(Code.INVALID_REQUEST);
            watchdog.postDelayed(kill,SERVICE_MS);
            if(code==WalletRenderBounds.OPEN){
                closeInput();session=nonce;input=ParcelFileDescriptor.CREATOR.createFromParcel(data);if(data.dataAvail()!=0)throw new WalletFailure(Code.INVALID_REQUEST);
                // Public descriptor access-mode inspection starts at API 30.
                // Older devices retain the guide, but cannot safely decode documents.
                if(android.os.Build.VERSION.SDK_INT<30)throw new WalletFailure(Code.UNSUPPORTED);
                int access=Os.fcntlInt(input.getFileDescriptor(),OsConstants.F_GETFL,0);long size=Os.fstat(input.getFileDescriptor()).st_size;
                if((access&OsConstants.O_ACCMODE)!=OsConstants.O_RDONLY||!OsConstants.S_ISREG(Os.fstat(input.getFileDescriptor()).st_mode)||size<1||size>DOCUMENT_BYTES)throw new WalletFailure(Code.INVALID_DOCUMENT);
                byte[] prefix=new byte[8];int count=Os.pread(input.getFileDescriptor(),prefix,0,8,0);
                if(count>=5&&prefix[0]=='%'&&prefix[1]=='P'&&prefix[2]=='D'&&prefix[3]=='F'&&prefix[4]=='-'){
                    kind=1;try{pdf=new PdfRenderer(input);}catch(SecurityException e){throw new WalletFailure(Code.ENCRYPTED_OR_INVALID_PDF);}pages=pdf.getPageCount();if(pages<1||pages>PAGES)throw new WalletFailure(Code.PAGE_LIMIT);
                    try(PdfRenderer.Page page=pdf.openPage(0)){width=page.getWidth();height=page.getHeight();new PageSize(width,height);}
                }else{
                    if(count>=8&&(prefix[0]&255)==137&&prefix[1]==80&&prefix[2]==78&&prefix[3]==71&&prefix[4]==13&&prefix[5]==10&&prefix[6]==26&&prefix[7]==10)kind=2;
                    else if(count>=3&&(prefix[0]&255)==255&&(prefix[1]&255)==216&&(prefix[2]&255)==255)kind=3;
                    else throw new WalletFailure(Code.UNSUPPORTED_FORMAT);
                    BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeFileDescriptor(input.getFileDescriptor(),null,bounds);width=bounds.outWidth;height=bounds.outHeight;new PageSize(width,height);pages=1;
                    if(kind==3){ExifInterface exif=new ExifInterface(input.getFileDescriptor());orientation=exif.getAttributeInt(ExifInterface.TAG_ORIENTATION,1);if(orientation<1||orientation>8)throw new WalletFailure(Code.INVALID_DOCUMENT);}
                    image=BitmapRegionDecoder.newInstance(input.getFileDescriptor(),false);if(image==null)throw new WalletFailure(Code.INVALID_DOCUMENT);
                }
                header(reply,nonce,request,0);reply.writeInt(kind);reply.writeInt(pages);reply.writeInt(width);reply.writeInt(height);reply.writeInt(orientation);
            }else{
                if(nonce!=session||input==null)throw new WalletFailure(Code.STALE_SESSION);
                if(code==WalletRenderBounds.CLOSE){if(data.dataAvail()!=0)throw new WalletFailure(Code.INVALID_REQUEST);closeInput();header(reply,nonce,request,0);}
                else if(code==WalletRenderBounds.VALIDATE_PAGE){int page=data.readInt();if(data.dataAvail()!=0||page<0||page>=pages)throw new WalletFailure(Code.INVALID_REQUEST);
                    int w=width,h=height;if(pdf!=null){try(PdfRenderer.Page p=pdf.openPage(page)){w=p.getWidth();h=p.getHeight();new PageSize(w,h);Bitmap b=Bitmap.createBitmap(128,128,Bitmap.Config.ARGB_8888);try{Matrix m=new Matrix();m.setScale(128f/w,128f/h);p.render(b,null,m,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);}finally{b.recycle();}}}
                    else{BitmapFactory.Options options=new BitmapFactory.Options();options.inSampleSize=Math.max(1,Integer.highestOneBit(Math.max(width,height)/128));options.inPreferredConfig=Bitmap.Config.ARGB_8888;Bitmap b=image.decodeRegion(new Rect(0,0,width,height),options);if(b==null)throw new WalletFailure(Code.INVALID_DOCUMENT);b.recycle();}
                    header(reply,nonce,request,0);reply.writeInt(w);reply.writeInt(h);
                }else if(code==WalletRenderBounds.TILE){int page=data.readInt(),cw=data.readInt(),ch=data.readInt(),x=data.readInt(),y=data.readInt(),w=data.readInt(),h=data.readInt();int bytes=WalletRenderBounds.tile(page,cw,ch,x,y,w,h);if(data.dataAvail()!=0||page>=pages)throw new WalletFailure(Code.INVALID_REQUEST);
                    Bitmap tile=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);tile.eraseColor(Color.WHITE);
                    try{if(pdf!=null){try(PdfRenderer.Page p=pdf.openPage(page)){new PageSize(p.getWidth(),p.getHeight());Matrix m=new Matrix();m.setScale((float)cw/p.getWidth(),(float)ch/p.getHeight());m.postTranslate(-x,-y);p.render(tile,new Rect(0,0,w,h),m,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);}}
                        else renderImage(tile,cw,ch,x,y,w,h);
                        byte[] pixels=new byte[bytes];tile.copyPixelsToBuffer(ByteBuffer.wrap(pixels));header(reply,nonce,request,0);reply.writeInt(page);reply.writeInt(cw);reply.writeInt(ch);reply.writeInt(x);reply.writeInt(y);reply.writeInt(w);reply.writeInt(h);reply.writeInt(bytes);reply.writeByteArray(pixels);
                    }finally{tile.recycle();}
                }else throw new WalletFailure(Code.INVALID_REQUEST);
            }
        }catch(Throwable e){closeInput();reply.setDataSize(0);reply.setDataPosition(0);Code failure=e instanceof WalletFailure?((WalletFailure)e).code:Code.INVALID_DOCUMENT;header(reply,nonce,request,failure.ordinal()+1);}
        finally{watchdog.removeCallbacks(kill);}return true;
    }};
    private void renderImage(Bitmap target,int cw,int ch,int x,int y,int w,int h)throws Exception{
        // Orientation is applied to coordinates in the isolated process, never host metadata decoding.
        Matrix orientationMatrix=new Matrix();switch(orientation){case 2:orientationMatrix.setScale(-1,1);break;case 3:orientationMatrix.setRotate(180);break;case 4:orientationMatrix.setScale(1,-1);break;case 5:orientationMatrix.setRotate(90);orientationMatrix.postScale(-1,1);break;case 6:orientationMatrix.setRotate(90);break;case 7:orientationMatrix.setRotate(270);orientationMatrix.postScale(-1,1);break;case 8:orientationMatrix.setRotate(270);break;default:break;}
        android.graphics.RectF source=new android.graphics.RectF(0,0,width,height),oriented=new android.graphics.RectF();orientationMatrix.mapRect(oriented,source);orientationMatrix.postTranslate(-oriented.left,-oriented.top);orientationMatrix.postScale(cw/oriented.width(),ch/oriented.height());orientationMatrix.postTranslate(-x,-y);
        Matrix inverse=new Matrix();if(!orientationMatrix.invert(inverse))throw new WalletFailure(Code.INVALID_DOCUMENT);android.graphics.RectF region=new android.graphics.RectF(0,0,w,h);inverse.mapRect(region);
        Rect rect=new Rect(Math.max(0,(int)Math.floor(region.left)),Math.max(0,(int)Math.floor(region.top)),Math.min(width,(int)Math.ceil(region.right)),Math.min(height,(int)Math.ceil(region.bottom)));if(rect.isEmpty())throw new WalletFailure(Code.INVALID_DOCUMENT);
        BitmapFactory.Options options=new BitmapFactory.Options();options.inPreferredConfig=Bitmap.Config.ARGB_8888;int sample=1;while((rect.width()+sample-1)/sample>512||(rect.height()+sample-1)/sample>512)sample*=2;options.inSampleSize=sample;
        Bitmap decoded=image.decodeRegion(rect,options);if(decoded==null||decoded.getAllocationByteCount()>512*512*4)throw new WalletFailure(Code.DIMENSION_LIMIT);
        try{Matrix draw=new Matrix();draw.setScale((float)rect.width()/decoded.getWidth(),(float)rect.height()/decoded.getHeight());draw.postTranslate(rect.left,rect.top);draw.postConcat(orientationMatrix);new Canvas(target).drawBitmap(decoded,draw,new Paint(Paint.FILTER_BITMAP_FLAG));}finally{decoded.recycle();}
    }
    private static void header(Parcel reply,long session,long request,int status){reply.writeInt(WalletRenderBounds.MAGIC);reply.writeLong(session);reply.writeLong(request);reply.writeInt(status);}
    private void closeInput(){try{if(pdf!=null)pdf.close();}catch(Exception ignored){}pdf=null;if(image!=null)image.recycle();image=null;try{if(input!=null)input.close();}catch(IOException ignored){}input=null;session=0;orientation=1;}
    @Override public IBinder onBind(Intent intent){return binder;}
    @Override public void onDestroy(){closeInput();super.onDestroy();}
}
