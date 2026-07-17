# Add project specific ProGuard rules here.
-keepattributes Signature
-keepattributes *Annotation*

# Gson
-keepclassmembers class com.goshelf.app.data.api.** { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
