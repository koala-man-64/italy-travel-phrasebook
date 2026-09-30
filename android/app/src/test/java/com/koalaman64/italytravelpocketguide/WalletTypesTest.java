package com.koalaman64.italytravelpocketguide;

import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.*;
import static com.koalaman64.italytravelpocketguide.WalletTypes.*;

public class WalletTypesTest {
    static Identity identity(){return new Identity("gen_"+"a".repeat(32),"0","1".repeat(64));}
    @Test public void validatesExactIdentity(){
        assertEquals(identity(),identity()); assertEquals(new Pair(identity(),null),new Pair(identity(),null));
        for(String bad:Arrays.asList("01","-1","9223372036854775808","1e3","1\n"))assertThrows(IllegalArgumentException.class,()->revision(bad));
        assertEquals("9223372036854775807",revision("9223372036854775807"));
        assertThrows(IllegalArgumentException.class,()->id("../x","doc"));
        assertThrows(IllegalArgumentException.class,()->hash("A".repeat(64)));
    }
    @Test public void rejectsControlsBidiAndLoneSurrogates(){
        assertEquals("😀".repeat(120),label("😀".repeat(120)));
        for(String bad:Arrays.asList("","x".repeat(121),"a\u202eb","a\u200fb","a\u0000b","\ud800"))assertThrows(IllegalArgumentException.class,()->label(bad));
    }
    @Test public void checksDimensionsWithLongArithmetic(){
        new PageSize(4000,10000);
        for(int[] dims:new int[][]{{0,1},{16385,1},{10000,10000},{Integer.MAX_VALUE,2}})assertThrows(IllegalArgumentException.class,()->new PageSize(dims[0],dims[1]));
    }
    @Test public void immutableCollectionsAndDocumentBounds(){
        ArrayList<PageSize> pages=new ArrayList<>(Collections.singletonList(new PageSize(10,10)));
        Document d=new Document("doc_"+"b".repeat(32),"png",1,"1".repeat(64),"test","2026-09-27T00:00:00Z",pages,1);
        pages.clear();assertEquals(1,d.pages.size());assertThrows(UnsupportedOperationException.class,()->d.pages.clear());
        assertThrows(IllegalArgumentException.class,()->new Document(d.id,"png",DOCUMENT_BYTES+1,d.sha256,"test",d.importedAtUtc,d.pages,1));
    }
    @Test public void uncertaintyIsDistinctAndCannotLeakException(){
        Result<Void> r=Result.uncertain("txn_"+"a".repeat(32)); assertEquals(Status.UNCERTAIN,r.status);assertEquals(Code.RECOVERY_REQUIRED,r.code);assertNull(r.value);
        assertEquals(Status.FAILED,Result.failed(Code.IO_FAILURE).status);assertEquals(Status.OK,Result.ok(null).status);
    }
    @Test public void preparedRequiresCompleteWebAndEscrow(){
        Change change=new Change(identity(),identity());
        assertThrows(IllegalArgumentException.class,()->new Journal("txn_"+"a".repeat(32),Operation.RESTORE,Phase.PREPARED,"0",change,change,null,null));
        new Journal("txn_"+"a".repeat(32),Operation.RESTORE,Phase.PREPARED,"0",change,change,"2".repeat(64),"3".repeat(64));
    }
}
