# Termux の端末ライブラリは JNI から呼ばれるクラス・メソッドを持つ
-keep class com.termux.terminal.JNI { *; }
# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-keepclassmembers @kotlinx.serialization.Serializable class ** { *** Companion; *; }
# commons-compress が参照する任意の依存（zstd・brotli など）は使わない
-dontwarn org.apache.commons.compress.**
-dontwarn com.github.luben.zstd.**
-dontwarn org.brotli.dec.**
-dontwarn org.tukaani.xz.**
-dontwarn org.objectweb.asm.**
