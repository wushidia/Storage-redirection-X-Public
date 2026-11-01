# Gson 反序列化需要保留字段名
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.storage.redirect.x.data.model.** { *; }
-keep class org.srx.packages.PackageQueryMain { public static void main(java.lang.String[]); }

-repackageclasses
-allowaccessmodification
