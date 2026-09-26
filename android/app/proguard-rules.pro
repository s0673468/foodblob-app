-keep class * extends android.content.ContentProvider { public <init>(); }
-keep class * extends androidx.glance.appwidget.GlanceAppWidgetReceiver { public <init>(); }

# Glance resolves these runtime widget instances through its receiver/session boundary.
# Keep the four fixed-skin subclasses distinct so R8 cannot vertically merge them and
# dispatch every receiver through one variant in optimized release builds.
-keep class org.example.foodblob.widget.** extends androidx.glance.appwidget.GlanceAppWidget { *; }

# WorkManager 2.7's consumer rule keeps InputMerger subclasses but permits R8 to
# remove the constructor later instantiated by name. Glance 1.1 sessions enqueue
# through that reflective path, so retain only the required public constructor.
-keep class * extends androidx.work.InputMerger { public <init>(); }
