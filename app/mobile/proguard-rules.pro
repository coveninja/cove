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
