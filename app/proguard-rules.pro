-dontwarn org.apache.commons.compress.**
-dontwarn org.tukaani.xz.**
-dontwarn org.brotli.dec.**

-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class dev.cluvex.strata.** {
    *** Companion;
}
-keepclasseswithmembers class dev.cluvex.strata.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-dontwarn coil3.**

-keep class com.topjohnwu.superuser.internal.RootServerMain { *; }
-keep class com.topjohnwu.superuser.internal.RootServerMain$* { *; }

-keepclassmembers class org.apache.commons.compress.archivers.sevenz.Coders {
    private static final java.util.Map CODER_MAP;
}

-keep class com.android.apksig.** { *; }
-dontwarn com.android.apksig.**
-keep class org.bouncycastle.jce.provider.** { *; }
-keep class org.bouncycastle.jcajce.provider.** { *; }
-keep class org.bouncycastle.crypto.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn javax.naming.**

-keep class jadx.plugins.input.**.*Plugin { *; }
-keep class * implements jadx.api.plugins.JadxPlugin { *; }
-keep,allowoptimization class jadx.** { *; }
-keepclassmembers class jadx.** { *; }
-dontwarn jadx.**
-dontwarn org.slf4j.**
-keep class com.android.tools.smali.** { *; }
-dontwarn com.android.tools.smali.**
-keep class com.googlecode.d2j.** { *; }
-dontwarn com.googlecode.d2j.**
-dontwarn org.objectweb.asm.**
-keep class org.antlr.** { *; }
-dontwarn org.antlr.**
-dontwarn com.android.tools.r8.**
-dontwarn com.android.tools.r8.**
-dontwarn java.awt.**
-dontwarn javax.swing.**
-dontwarn com.google.errorprone.annotations.**

-keep class com.reandroid.** { *; }
-dontwarn com.reandroid.**
-dontwarn org.xmlpull.**
-keep class android.content.res.XmlResourceParser { *; }
-keep class android.util.AttributeSet { *; }
-keep class org.xmlpull.v1.** { *; }

-keep class org.apache.commons.net.ftp.parser.** { *; }
-dontwarn org.apache.commons.net.**

-keep class net.schmizz.sshj.** { *; }
-keep class com.hierynomus.** { *; }
-dontwarn net.schmizz.sshj.**
-dontwarn net.i2p.crypto.**
-dontwarn org.slf4j.**

-keep interface dev.cluvex.strata.core.nativebridge.RarSink { *; }
-keepclassmembers class * implements dev.cluvex.strata.core.nativebridge.RarSink {
    boolean begin(java.lang.String, long, int);
    boolean write(byte[], int);
    void end(boolean);
}

-keepclassmembers class com.hierynomus.smbj.** { @net.engio.mbassy.listener.Handler <methods>; }
-keep class net.engio.mbassy.** { *; }
-dontwarn javax.el.**
-dontwarn org.ietf.jgss.**
-dontwarn java.rmi.UnmarshalException

-keepclassmembers class * implements org.apache.mina.core.service.IoProcessor { public <init>(...); }
-dontwarn org.springframework.**
-dontwarn javax.security.sasl.**
-dontwarn java.lang.management.**

-keep class dev.cluvex.strata.core.shizuku.** { *; }
-keep class rikka.shizuku.** { *; }
-keep interface rikka.shizuku.** { *; }
-dontwarn rikka.shizuku.**

-dontoptimize
-keep class com.android.tools.r8.** { *; }
