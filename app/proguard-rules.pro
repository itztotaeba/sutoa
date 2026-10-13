# Add project specific ProGuard rules here.
# Moshi codegen
-keepclassmembers class * {
    @com.squareup.moshi.FromJson <methods>();
    @com.squareup.moshi.ToJson <methods>();
}
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn retrofit2.**
