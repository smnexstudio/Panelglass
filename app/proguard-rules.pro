-keepattributes *Annotation*, InnerClasses, Signature
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class com.smnexstudio.panelglass.** { kotlinx.serialization.KSerializer serializer(...); }

# Native runtimes: their C++ side finds these classes, fields and methods by name over JNI, and neither AAR ships
# consumer rules. Without these, a minified build crashes when the detector (ONNX Runtime) or an on-device
# translator (LiteRT-LM) loads.
-keep class ai.onnxruntime.** { *; }
-keep class com.google.ai.edge.litertlm.** { *; }
-keepclasseswithmembernames,includedescriptorclasses class * { native <methods>; }

# Apache Commons Compress (Studio archive import) refers to optional codecs and tooling it never loads here:
# zstd, Brotli, ASM (Pack200) and OSGi. They are not on the classpath, by choice.
-dontwarn com.github.luben.zstd.**
-dontwarn org.brotli.dec.**
-dontwarn org.objectweb.asm.**
-dontwarn org.osgi.**

# Failure types are logged by class name (TranslationPipeline "Image failed: ..."); keep the names readable.
-keepnames class com.smnexstudio.panelglass.core.model.EngineFailure
-keepnames class com.smnexstudio.panelglass.core.model.EngineFailure$*
