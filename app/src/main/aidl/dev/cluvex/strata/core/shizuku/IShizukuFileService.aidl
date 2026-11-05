package dev.cluvex.strata.core.shizuku;

import dev.cluvex.strata.core.shizuku.IShellOutput;

interface IShizukuFileService {
    void destroy() = 16777114;
    IBinder getFileSystemService() = 1;
    String exec(String command, long timeoutMs) = 2;
    String execStreaming(String command, long timeoutMs, IShellOutput callback) = 3;
}
