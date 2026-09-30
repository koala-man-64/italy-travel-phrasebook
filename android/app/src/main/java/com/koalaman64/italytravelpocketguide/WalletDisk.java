package com.koalaman64.italytravelpocketguide;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.UUID;
import static com.koalaman64.italytravelpocketguide.WalletTypes.*;

/** Native-owned filesystem boundary. Failure hooks bracket actual durable operations. */
public final class WalletDisk implements AutoCloseable {
    public interface DirectorySync { void sync(Path directory) throws IOException; }
    public interface Faults { void hit(String boundary) throws IOException; }
    final Path root;
    private final DirectorySync sync;
    private final Faults faults;
    private final FileChannel ownerChannel;private final java.nio.channels.FileLock ownerLock;
    public WalletDisk(Path root,DirectorySync sync,Faults faults) throws IOException {
        this.root=root.toAbsolutePath().normalize();this.sync=sync;this.faults=faults;
        Files.createDirectories(this.root);
        if(Files.isSymbolicLink(this.root)||!Files.isDirectory(this.root,LinkOption.NOFOLLOW_LINKS))throw new IOException("INVALID_ROOT");
        FileChannel channel=FileChannel.open(path("owner.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);
        java.nio.channels.FileLock lock;
        try{lock=channel.tryLock();if(lock==null)throw new IOException("WALLET_ALREADY_OWNED");}catch(Exception e){channel.close();throw new IOException("WALLET_ALREADY_OWNED",e);}
        ownerChannel=channel;ownerLock=lock;
        // Parent directory is selected by the native host, never user input.
    }
    Path path(String name) throws IOException {
        if(name==null||!name.matches("[a-z0-9_.-]{1,100}"))throw new IOException("INVALID_PATH");
        Path p=root.resolve(name);
        if(Files.isSymbolicLink(p))throw new IOException("SYMLINK");
        return p;
    }
    void hit(String name) throws IOException {faults.hit(name);}
    boolean exists(String name) throws IOException {return Files.exists(path(name),LinkOption.NOFOLLOW_LINKS);}
    byte[] read(String name,int max) throws IOException {
        Path p=path(name);if(!Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS))throw new IOException("NOT_REGULAR");
        long size=Files.size(p);if(size<0||size>max)throw new WalletFailure(Code.STORAGE_LIMIT);
        byte[] bytes=new byte[(int)size];
        try(FileChannel c=FileChannel.open(p,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)){
            ByteBuffer b=ByteBuffer.wrap(bytes);while(b.hasRemaining()){int n=c.read(b);if(n<=0)throw new IOException("SHORT_READ");}
            if(c.read(ByteBuffer.allocate(1))!=-1)throw new IOException("CHANGED_FILE");
        }
        return bytes;
    }
    void write(String name,byte[] bytes,int cap,boolean immutable) throws IOException {
        if(bytes.length>cap)throw new WalletFailure(Code.STORAGE_LIMIT);
        if(immutable&&exists(name)){
            if(!Arrays.equals(read(name,cap),bytes))throw new WalletFailure(Code.TRANSACTION_CONFLICT);
            return;
        }
        reserve(bytes.length);
        Path temp=path("tmp_"+UUID.randomUUID().toString().replace("-", ""));
        hit(name+":before-write");
        try(FileChannel out=FileChannel.open(temp,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){
            ByteBuffer b=ByteBuffer.wrap(bytes);while(b.hasRemaining())out.write(b);
            hit(name+":after-write");out.force(true);hit(name+":after-sync");
        }
        Files.move(temp,path(name),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        hit(name+":after-replace");sync.sync(root);hit(name+":after-dir-sync");
        if(!Arrays.equals(read(name,cap),bytes))throw new IOException("READBACK_MISMATCH");
        hit(name+":after-readback");
    }
    void syncFile(String name) throws IOException {
        try(FileChannel c=FileChannel.open(path(name),StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){c.force(true);}
        sync.sync(root);
    }
    long used() throws IOException {
        long total=0;
        try(java.nio.file.DirectoryStream<Path> files=Files.newDirectoryStream(root)){
            for(Path p:files){if(!Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(p))throw new IOException("UNEXPECTED_FILE");total=Math.addExact(total,Files.size(p));}
        }
        return total;
    }
    void reserve(long additional) throws IOException {
        long used=used();if(additional<0||additional>STORE_BYTES-used)throw new WalletFailure(Code.STORAGE_LIMIT);
        if(Files.getFileStore(root).getUsableSpace()<additional+HEADROOM_BYTES)throw new WalletFailure(Code.NO_SPACE);
    }
    static String sha(byte[] bytes){return hex(digest().digest(bytes));}
    static MessageDigest digest(){try{return MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException e){throw new AssertionError(e);}}
    static String hex(byte[] bytes){StringBuilder b=new StringBuilder(bytes.length*2);for(byte x:bytes)b.append(String.format(java.util.Locale.ROOT,"%02x",x&255));return b.toString();}
    String hashFile(String name,long cap) throws IOException {
        MessageDigest digest=digest();long n=0;ByteBuffer b=ByteBuffer.allocate(8192);
        try(FileChannel c=FileChannel.open(path(name),StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)){
            while(true){int got=c.read(b);if(got<0)break;if(got==0)throw new IOException("NO_PROGRESS");n+=got;if(n>cap)throw new WalletFailure(Code.BYTE_LIMIT);digest.update(b.array(),0,got);b.clear();}
        }
        return hex(digest.digest());
    }
    @Override public void close()throws IOException{try{ownerLock.release();}finally{ownerChannel.close();}}
}
