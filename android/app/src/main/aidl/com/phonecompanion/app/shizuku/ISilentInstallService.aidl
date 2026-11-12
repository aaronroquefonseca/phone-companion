package com.phonecompanion.app.shizuku;

// Silent-install shell bridge (UserService side).
//
// installApk runs `pm install -r <apkPath>` as shell/root (UID 2000/0) and
// returns a packed "exit:<code>\n<output>" string the app parses with
// ShizukuSilentInstall.parseShellResult. The raw pm output rides along so
// the existing cause-aware failure mapping (AppInstallResult.failureKindOf)
// keeps working unchanged — e.g. INSTALL_FAILED_UPDATE_INCOMPATIBLE still
// offers App-info instead of Retry.
//
// destroy is the reserved Shizuku destroy method: transaction 16777115 on
// the wire (16777114 in aidl). The client calls it after every install and
// the service exits — the shell process never lingers.
interface ISilentInstallService {
    String installApk(String apkPath) = 1;

    void destroy() = 16777114;
}
