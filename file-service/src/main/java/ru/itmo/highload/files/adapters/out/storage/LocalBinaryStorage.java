package ru.itmo.highload.files.adapters.out.storage;
import java.nio.file.*;
import java.io.IOException;
import java.util.UUID;
import ru.itmo.highload.files.application.ports.BinaryStorage;
import ru.itmo.highload.files.domain.*;
public final class LocalBinaryStorage implements BinaryStorage {
    private final Path root;
    private final int maxBytes;
    public LocalBinaryStorage(Path root,int maxBytes) {
        this.root=root.toAbsolutePath().normalize();this.maxBytes=maxBytes;
        try { Files.createDirectories(this.root); }
        catch(IOException error) { throw unavailable(); }
    }
    public void write(UUID key,byte[] bytes) {
        Path temp=null;
        try {
            temp=Files.createTempFile(root,"upload-",".tmp");Files.write(temp,bytes);
            Files.move(temp,root.resolve(key.toString()),StandardCopyOption.ATOMIC_MOVE);
        } catch(IOException error) { throw unavailable(); }
        finally { if(temp!=null) try { Files.deleteIfExists(temp); } catch(IOException ignored) { } }
    }
    public byte[] read(UUID key) {
        try(var input=Files.newInputStream(root.resolve(key.toString()),LinkOption.NOFOLLOW_LINKS)) {
            var bytes=input.readNBytes(maxBytes+1);
            if(bytes.length>maxBytes) throw new FileFailure(FileFailure.Kind.INTEGRITY,"FILE_INTEGRITY_ERROR","Нарушен размер сохранённого файла");
            return bytes;
        } catch(IOException error) { throw unavailable(); }
    }
    public void removeUncommitted(UUID key) {
        try { Files.deleteIfExists(root.resolve(key.toString())); }
        catch(IOException error) { throw unavailable(); }
    }
    private static FileFailure unavailable() { return new FileFailure(FileFailure.Kind.STORAGE,"FILE_STORAGE_UNAVAILABLE","Файловое хранилище временно недоступно"); }
}
