# Keep native entry points whose implementations live in packaged shared libraries.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# Retain metadata used by serialization and service/provider discovery while still allowing
# R8 to optimize the implementations themselves.
-keepattributes Signature,*Annotation*,InnerClasses,EnclosingMethod

# Ktor's cross-platform debugger detector probes these JVM-only APIs. Its Android path does
# not execute them, and java.management is intentionally absent from the Android boot classpath.
-dontwarn java.lang.management.ManagementFactory
-dontwarn java.lang.management.RuntimeMXBean

# yt-dlp reaches its own code by reflection twice over, and neither reference is visible to R8.
#
# Unpacking the bundled Python runtime goes through commons-compress, whose ExtraFieldUtils
# registers every ZipExtraField implementation from a static initializer with Class.newInstance().
# The no-arg constructors therefore have no call site, R8 strips them, register() rethrows the
# InstantiationException as "is not a concrete class", and ExtraFieldUtils stays erroneous for the
# life of the process. What reaches the viewer is a NoClassDefFoundError carrying nothing but the
# minified class name, so the failure reads as gibberish rather than as a missing constructor.
-keep class * implements org.apache.commons.compress.archivers.zip.ZipExtraField {
    <init>();
}

# yt-dlp's own output is then bound onto these models by Jackson, which needs the no-arg
# constructor and the fields the @JsonProperty annotations sit on. Nothing in the app calls
# either, so without this R8 empties the classes out and every extraction returns a blank
# VideoInfo — url included, which is the one field playback cannot do without.
-keep class com.yausername.youtubedl_android.mapper.** {
    <init>();
    <fields>;
    <methods>;
}
