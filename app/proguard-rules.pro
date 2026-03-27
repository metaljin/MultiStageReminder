# --- Room 数据库保护 ---
# 保护实体类 (Entity)，防止表名和列名被混淆
-keep @androidx.room.Entity class * { *; }

# 保护数据访问对象 (DAO)，防止查询方法被删除
-keep interface * extends androidx.room.Dao { *; }

# 保护 Room 自动生成的实现类
-keep class * extends androidx.room.RoomDatabase { *; }

# --- 协程 (Coroutines) 保护 ---
# 防止协程内部逻辑被错误优化导致挂起函数异常
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.coroutines.android.HandlerContext$ScheduledPatch {
    volatile <fields>;
}

# --- Compose 保护 (通常 R8 会自动处理，但加上更稳) ---
-keepclassmembers class * extends androidx.lifecycle.ViewModel {
    <init>(...);
}

# --- 保持 Kotlin 序列化/反射相关 ---
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes EnclosingMethod
-keepattributes InnerClasses
