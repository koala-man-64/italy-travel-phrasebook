package com.koalaman64.italytravelpocketguide;

import static com.koalaman64.italytravelpocketguide.WalletTypes.*;

/** Pure arithmetic guard shared by both sides of the untrusted Binder boundary. */
public final class WalletRenderBounds {
    private WalletRenderBounds() {}
    public static final int MAGIC=0x574c5431, OPEN=1, TILE=2, CLOSE=3, VALIDATE_PAGE=4;
    public static int tile(int page,int canvasW,int canvasH,int x,int y,int w,int h)throws WalletFailure {
        if(page<0||page>=PAGES||canvasW<1||canvasH<1||canvasW>EDGE||canvasH>EDGE||w<1||h<1||w>TILE_EDGE||h>TILE_EDGE||x<0||y<0||x>canvasW-w||y>canvasH-h)throw new WalletFailure(Code.INVALID_REQUEST);
        return w*h*4;
    }
    public static void reply(int size,boolean descriptors,long expectedSession,long session,long expectedRequest,long request,int status)throws WalletFailure {
        if(size<24||size>PARCEL_BYTES||descriptors||session!=expectedSession||request!=expectedRequest||status<0||status>Code.values().length)throw new WalletFailure(Code.INVALID_RENDER_REPLY);
    }
}
