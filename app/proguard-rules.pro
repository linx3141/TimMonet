# LSPosed loads the module entry by name from META-INF/xposed/java_init.list
-keep class com.timmonet.MainModule { *; }

# Keep the whole module (hooks/settings/UI) intact; shrinking still strips
# unused library code (Compose/material-icons/materialkolor), which is the
# bulk of the APK's dex size.
-keep class com.timmonet.** { *; }

# LibXposed API is compileOnly; keep it out of R8's way.
-keep class io.github.libxposed.** { *; }
-dontwarn io.github.libxposed.**
