package com.koalaman64.italytravelpocketguide;
import org.junit.Test;
import static org.junit.Assert.*;
import static com.koalaman64.italytravelpocketguide.WalletTypes.*;
public class WalletRenderBoundsTest {
    @Test public void boundsTileAllocationAndGeometry()throws Exception{
        assertEquals(262144,WalletRenderBounds.tile(99,16384,16384,16128,16128,256,256));
        for(int[] v:new int[][]{{100,256,256,0,0,256,256},{0,256,256,0,0,257,256},{0,Integer.MAX_VALUE,256,0,0,256,256},{0,256,256,-1,0,256,256},{0,256,256,1,0,256,256}})
            assertThrows(WalletFailure.class,()->WalletRenderBounds.tile(v[0],v[1],v[2],v[3],v[4],v[5],v[6]));
    }
    @Test public void rejectsUntrustedReplyBeforeAllocation()throws Exception{
        WalletRenderBounds.reply(24,false,1,1,2,2,0);
        assertThrows(WalletFailure.class,()->WalletRenderBounds.reply(PARCEL_BYTES+1,false,1,1,2,2,0));
        assertThrows(WalletFailure.class,()->WalletRenderBounds.reply(24,true,1,1,2,2,0));
        assertThrows(WalletFailure.class,()->WalletRenderBounds.reply(24,false,1,3,2,2,0));
        assertThrows(WalletFailure.class,()->WalletRenderBounds.reply(24,false,1,1,2,3,0));
        assertThrows(WalletFailure.class,()->WalletRenderBounds.reply(24,false,1,1,2,2,Integer.MAX_VALUE));
    }
}
