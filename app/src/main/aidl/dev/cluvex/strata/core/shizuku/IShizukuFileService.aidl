package dev.cluvex.strata.core.shizuku;

interface IShizukuFileService {
    void destroy() = 16777114;
    IBinder getFileSystemService() = 1;
    String exec(String command, long timeoutMs) = 2;
}
