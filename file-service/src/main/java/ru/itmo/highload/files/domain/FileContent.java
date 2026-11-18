package ru.itmo.highload.files.domain;
import java.security.MessageDigest;
import java.util.HexFormat;
import ru.itmo.highload.common.domain.BusinessFailure;
public final class FileContent {
    private FileContent() { }
    public record Info(String mimeType,long size,String checksum) { }
    public static Info inspect(byte[] bytes,String declaredMime,FileLimits limits) {
        if(bytes.length==0) throw new BusinessFailure(BusinessFailure.Kind.VALIDATION,"FILE_EMPTY","Файл пуст");
        if(bytes.length>limits.maxBytes()) throw new FileFailure(FileFailure.Kind.TOO_LARGE,"FILE_TOO_LARGE","Превышен допустимый размер файла");
        if(declaredMime==null || !limits.allowedMimeTypes().contains(declaredMime))
            throw new FileFailure(FileFailure.Kind.UNSUPPORTED_TYPE,"FILE_TYPE_NOT_ALLOWED","Недопустимый MIME-тип файла");
        String actual=starts(bytes,new byte[]{37,80,68,70,45})?"application/pdf"
                :starts(bytes,new byte[]{(byte)137,80,78,71,13,10,26,10})?"image/png"
                :starts(bytes,new byte[]{(byte)255,(byte)216,(byte)255})?"image/jpeg":null;
        if(!declaredMime.equals(actual)) throw new FileFailure(FileFailure.Kind.CONTENT_MISMATCH,"FILE_CONTENT_MISMATCH","Содержимое не соответствует MIME-типу");
        return new Info(actual,bytes.length,HexFormat.of().formatHex(hash(bytes)));
    }
    public static void verify(byte[] bytes,FileDocument file) {
        if(bytes.length!=file.size() || !MessageDigest.isEqual(hash(bytes),HexFormat.of().parseHex(file.checksum())))
            throw new FileFailure(FileFailure.Kind.INTEGRITY,"FILE_INTEGRITY_ERROR","Проверка целостности файла не пройдена");
    }
    private static boolean starts(byte[] bytes,byte[] prefix) {
        if(bytes.length<prefix.length) return false;
        for(int i=0;i<prefix.length;i++) if(bytes[i]!=prefix[i]) return false;
        return true;
    }
    private static byte[] hash(byte[] bytes) {
        try { return MessageDigest.getInstance("SHA-256").digest(bytes); }
        catch(java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
