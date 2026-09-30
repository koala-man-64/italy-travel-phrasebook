package com.koalaman64.italytravelpocketguide;

import org.junit.Test;
import static org.junit.Assert.*;
import static com.koalaman64.italytravelpocketguide.WalletTypes.*;

public class ArchiveBridgeIdentityTest {
    @Test public void onlyExactTrustedMainPageIsAdmitted() {
        assertTrue(WalletBridgeIdentity.trusted(true,WalletBridgeIdentity.ORIGIN,WalletBridgeIdentity.DOCUMENT));
        assertFalse(WalletBridgeIdentity.trusted(false,WalletBridgeIdentity.ORIGIN,WalletBridgeIdentity.DOCUMENT));
        for(String origin:new String[]{"http://appassets.androidplatform.net","https://appassets.androidplatform.net.evil","https://appassets.androidplatform.net:443","null"})
            assertFalse(WalletBridgeIdentity.trusted(true,origin,WalletBridgeIdentity.DOCUMENT));
        for(String url:new String[]{WalletBridgeIdentity.ORIGIN+"/assets/other.html",WalletBridgeIdentity.DOCUMENT+"#old",WalletBridgeIdentity.DOCUMENT+"?old",null})
            assertFalse(WalletBridgeIdentity.trusted(true,WalletBridgeIdentity.ORIGIN,url));
    }
    @Test public void oldMainFrameCannotBindNewDocumentChallenge()throws Exception {
        Context current=new Context("s","fresh","1","r"),old=new Context("s","old","0","r");
        String fresh=JsonTransferJson.encode(WalletCodec.obj("v",1,"kind","bound","context",WalletWebChannel.context(current),"nonce","a".repeat(32)));
        String stale=JsonTransferJson.encode(WalletCodec.obj("v",1,"kind","bound","context",WalletWebChannel.context(old),"nonce","a".repeat(32)));
        assertTrue(WalletBridgeIdentity.bound(fresh,current));assertFalse(WalletBridgeIdentity.bound(stale,current));
        try { WalletBridgeIdentity.bound(fresh.replace("\"v\":1","\"v\":1,\"v\":1"),current);fail(); }catch(Exception expected) {}
    }
    @Test public void oldReplyWithSameRequestIdCannotPoisonSuccessor()throws Exception {
        Context current=new Context("s","fresh","1","r"),old=new Context("s","old","0","r");
        WalletWebChannel channel=new WalletWebChannel(current,c->true,s->{},(ms,action)->()->{});
        java.util.concurrent.CompletableFuture<String> pending=channel.exchange(current,"enterRecovery","[]").toCompletableFuture();
        byte[] bytes="{\"status\":\"OK\",\"value\":null}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        java.util.Map<String,Object> frame=WalletCodec.obj("v",1,"context",WalletWebChannel.context(old),"id","1","seq",0,"count",1,"bytes",bytes.length,"sha256",WalletWebChannel.digest(bytes),"data",java.util.Base64.getEncoder().encodeToString(bytes));
        String stale=JsonTransferJson.encode(frame);
        if(WalletBridgeIdentity.currentFrame(stale,current))channel.receive(stale);
        assertFalse(pending.isDone());
        frame.put("context",WalletWebChannel.context(current));String fresh=JsonTransferJson.encode(frame);
        assertTrue(WalletBridgeIdentity.currentFrame(fresh,current));channel.receive(fresh);
        assertEquals(new String(bytes,java.nio.charset.StandardCharsets.UTF_8),pending.join());
    }
}
