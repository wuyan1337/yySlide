# JSch references optional classes not available on Android.
# These are compile-time optional dependencies that JSch checks for at runtime.
-dontwarn org.apache.logging.log4j.**
-dontwarn org.slf4j.**
-dontwarn org.ietf.jgss.**
-dontwarn org.newsclub.net.unix.**
-dontwarn javax.naming.**
-dontwarn com.sun.jna.**
-dontwarn com.sun.jna.platform.win32.**
-dontwarn com.jcraft.jsch.PageantConnector
-dontwarn com.jcraft.jsch.Log4j2Logger
-dontwarn com.jcraft.jsch.Slf4jLogger
-dontwarn com.jcraft.jsch.jgss.**
-dontwarn com.jcraft.jsch.JUnixSocketFactory
