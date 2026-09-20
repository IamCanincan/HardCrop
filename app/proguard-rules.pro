# The entry class is listed by name in META-INF/xposed/java_init.list, it must survive R8.
# Do not add allowobfuscation here: -adaptresourcefilecontents would rewrite the list as well.
-keep class com.iamcanincan.hardcrop.XposedMain
-keep class com.iamcanincan.hardcrop.XposedMain {
    public <init>();
}

-adaptresourcefilecontents META-INF/xposed/java_init.list
-keep,allowoptimization public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}

-dontwarn io.github.libxposed.annotation.**
