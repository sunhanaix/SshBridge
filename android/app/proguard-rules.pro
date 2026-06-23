# sshlib
-keep class org.connectbot.** { *; }
# Termux terminal
-keep class com.termux.** { *; }

# Missing annotation classes from Tink / Conscrypt dependencies
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
