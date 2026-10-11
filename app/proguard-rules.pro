# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

-optimizationpasses 5
-dontwarn javax.lang.model.**
-dontwarn sun.misc.**
-dontwarn org.xmlpull.v1.**
-dontwarn org.kxml2.io.**
-dontwarn android.content.res.**
-dontwarn org.slf4j.impl.StaticLoggerBinder
# dnsjava retains this compile-time annotation on generated logger fields.
-dontwarn lombok.Generated
-dontwarn org.jetbrains.kotlin.**
-dontwarn com.google.gson.**
# Rhino 1.8.1's optional BEAN JSON converter uses desktop JavaBeans introspection.
# Android has none of these types; the host leaves Context's STRING default in use.
-dontwarn java.beans.BeanDescriptor
-dontwarn java.beans.BeanInfo
-dontwarn java.beans.IntrospectionException
-dontwarn java.beans.Introspector
-dontwarn java.beans.PropertyDescriptor
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation

-keepattributes Signature, *Annotation*, InnerClasses
-keep public class * implements java.lang.reflect.Type
-keepclassmembers,allowobfuscation,allowoptimization class <1> {
  <init>();
}
-keep class org.xmlpull.** { *; }
-keepclassmembers class org.xmlpull.** { *; }
-dontnote kotlinx.serialization.AnnotationsKt
-dontnote kotlinx.serialization.SerializationKt
-keep,includedescriptorclasses class indi.renakoni.nextvol.**$$serializer { *; }
-keepclassmembers class indi.renakoni.nextvol.** {
    *** Companion;
}
-keepclasseswithmembers class indi.renakoni.nextvol.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep class indi.renakoni.nextvol.defaultplugin.** { *; }

-dontwarn org.dom4j.**
-keep class org.dom4j.**{*;}
-keep interface org.dom4j.** { *; }

# Jaxen loads its default XPath parser by class name and public no-arg constructor.
-keep class org.jaxen.saxpath.base.XPathReader {
    public <init>();
}

-dontwarn com.fasterxml.jackson.annotation.JsonInclude$Include
-dontwarn com.fasterxml.jackson.core.json.JsonWriteFeature
-dontwarn com.fasterxml.jackson.core.type.TypeReference
-dontwarn com.fasterxml.jackson.databind.DeserializationFeature
-dontwarn com.fasterxml.jackson.databind.Module
-dontwarn com.fasterxml.jackson.databind.ObjectMapper
-dontwarn com.fasterxml.jackson.databind.SerializationFeature
-dontwarn com.fasterxml.jackson.databind.cfg.MapperBuilder
-dontwarn com.fasterxml.jackson.databind.json.JsonMapper$Builder
-dontwarn com.fasterxml.jackson.databind.json.JsonMapper
-dontwarn com.fasterxml.jackson.module.kotlin.KotlinModule$Builder
-dontwarn com.fasterxml.jackson.module.kotlin.KotlinModule
