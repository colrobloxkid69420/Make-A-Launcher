# R8 rules for release and benchmark builds.
# The app uses no reflection, so the defaults in proguard-android-optimize.txt are enough.
# Keep source file names and line numbers so crash stack traces stay readable.
-keepattributes SourceFile,LineNumberTable
