package com.koalaman64.italytravelpocketguide;

import android.content.Context;
import android.system.Os;
import android.system.OsConstants;
import java.io.IOException;
import java.io.FileDescriptor;

/** Real Android directory sync; unsupported durability is propagated, never declared successful. */
public final class WalletAndroidDisk {
    private WalletAndroidDisk() {}
    public static WalletDisk create(Context context)throws IOException{
        return new WalletDisk(context.getNoBackupFilesDir().toPath().resolve("wallet-v1"),directory->{
            FileDescriptor fd=null;try{fd=Os.open(directory.toString(),OsConstants.O_RDONLY,0);if(!OsConstants.S_ISDIR(Os.fstat(fd).st_mode))throw new IOException("NOT_DIRECTORY");Os.fsync(fd);}catch(Exception e){throw new IOException("DIRECTORY_SYNC_FAILED",e);}finally{if(fd!=null)try{Os.close(fd);}catch(Exception e){throw new IOException("DIRECTORY_CLOSE_FAILED",e);}}
        },boundary->{});
    }
}
