# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Keep all the classes needed for Compose
-keepattributes *Annotation*
-keepclassmembers class * {
    @androidx.compose.material3.* <methods>;
}

# Keep names used in Compose UI
-keepnames class ** extends android.app.Activity{
   public *** onCreate(android.os.Bundle);
   public void onBackPressed();
}

# Add any project specific keep rules here.
