# R8 保护规则（11：开启代码压缩 + 混淆 + 资源收缩）
#
# 原则：只保护「按名字被系统 / 框架调用」的东西，应用自己的类一律参与压缩与混淆。
# 本项目的情况：
#   - 没有反射（没有 Class.forName / getDeclaredMethod）；
#   - 没有 Gson/Moshi/Jackson 之类按字段名反序列化的库，JSON 全部用 org.json 手工读写；
#   - 没有 kotlin-reflect 依赖；
#   - Activity 只有 MainActivity 一个，清单里声明，AGP 会自动 keep。
# 所以下面的规则很少，主要是「让崩溃堆栈还能看」和「枚举别被 R8 full mode 裁掉」。

# 1) 保留行号与源文件名：release 崩溃时堆栈能定位到具体行。
#    混淆后的类名/方法名用 app/build/outputs/mapping/release/mapping.txt 还原。
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# 2) 应用入口。manifest 里声明的 Activity AGP 已经会自动 keep，
#    这里显式写一遍，避免以后换构建工具或加 activity-alias 时被裁掉。
-keep class com.shuaati.app.MainActivity { *; }

# 3) 枚举：R8 full mode 下有可能裁掉 values()/valueOf()，而 Kotlin 1.9+ 的 entries
#    依赖编译器生成的 $ENTRIES/$VALUES 字段。App 里 Tab、Overlay、TagTone 等枚举都这么用。
-keepclassmembers enum com.shuaati.app.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# 4) 说明：Compose、Activity Compose、Kotlin 协程都自带 consumer proguard 规则，
#    不需要在这里重复；重复写容易出现「规则互相覆盖」的隐性问题。
