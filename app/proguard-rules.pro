# Keep JNI methods
-keepclasseswithmembernames class * {
    native <methods>;
}

-keep class io.github.romanvht.byedpi.core.ByeDpiProxy { *; }

-keep,allowoptimization class com.trilead.ssh2.** { *; }

# SOCKS5 server used by sshlib's dynamic port forwarder (the local tunnel
# endpoint that tun2socks connects to)
-keep,allowoptimization class org.connectbot.simplesocks.** { *; }

-keep,allowoptimization class io.github.romanvht.byedpi.core.TProxyService { *; }
-keep,allowoptimization class io.github.romanvht.byedpi.activities.** { *; }
-keep,allowoptimization class io.github.romanvht.byedpi.services.** { *; }
-keep,allowoptimization class io.github.romanvht.byedpi.receiver.** { *; }

-keep class io.github.romanvht.byedpi.fragments.** {
    <init>();
}

-keep,allowoptimization class io.github.romanvht.byedpi.data.** {
    <fields>;
}

-keepattributes Signature
-keepattributes *Annotation*

-repackageclasses 'ru.romanvht'
-renamesourcefileattribute ''
-keepattributes SourceFile,InnerClasses,EnclosingMethod,Signature,RuntimeVisibleAnnotations,*Annotation*,*Parcelable*
-allowaccessmodification
-overloadaggressively
-optimizationpasses 5
-verbose
-dontusemixedcaseclassnames
-adaptclassstrings
-adaptresourcefilecontents **.xml,**.json
-adaptresourcefilenames **.xml,**.json