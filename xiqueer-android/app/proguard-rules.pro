# ============================================================================
# R8 / ProGuard 规则
#
# ⚠️ 背景(实测踩过):release 包曾经**一启动就崩**:
#
#     Caused by: java.lang.NoSuchMethodException:
#         androidx.work.impl.WorkDatabase_Impl.<init> []
#       at androidx.work.WorkManagerInitializer.…
#       at androidx.startup.InitializationProvider.onCreate
#
# debug 包完全正常 —— 因为 debug 不跑 R8,这类问题**只在压缩包里出现**。
# 所以下面的规则不是"保险起见",每一条都对应一个真实的反射调用点。
# ============================================================================

# ---------------------------------------------------------------- Room / WorkManager
#
# Room 生成的实现类是反射实例化的:
#   androidx.room.Room.getGeneratedImplementation()
#     → Class.forName("…_Impl").getDeclaredConstructor().newInstance()
#
# 只写 `-keep class * extends androidx.room.RoomDatabase` 不够 ——
# 那条规则(由 WorkManager 的 consumer rules 提供)保住了类,但没保住**构造函数**:
# R8 认为没人直接调用它,就把它删了,于是真机上掷 NoSuchMethodException。
-keep class * extends androidx.room.RoomDatabase {
    public <init>();
}
-keep class androidx.work.impl.WorkDatabase_Impl {
    public <init>();
}

# WorkManager 的默认 WorkerFactory 反射构造 Worker:
#   Class.forName(workerClassName).getConstructor(Context, WorkerParameters)
# 我们自己的 DailyRefreshWorker 就走这条路。
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# 周期任务的类名是**字符串写进数据库**的,混淆后按名字找不到类:
#   Class.forName("com.xiqueer.android.sync.DailyRefreshWorker")
-keep class com.xiqueer.android.sync.DailyRefreshWorker { *; }

# ---------------------------------------------------------------- 启动 / 组件
#
# androidx.startup 的 Provider 由系统按 manifest 里的全限定名加载,
# 名字被混淆就找不到(上面那次崩溃正是从 InitializationProvider 冒出来的)。
-keep class androidx.startup.** { *; }

# 保留行号,崩溃栈才有意义 —— 调试收益远大于那点包体
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
