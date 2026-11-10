# Vosk («Oye Claude») usa JNA: las clases nativas se buscan por nombre.
-keep class org.vosk.** { *; }
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { public *; }
-dontwarn java.awt.**

# Interfaces JavaScript de los WebView (widgets de diseño libre): R8 quitaría sus métodos.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes JavascriptInterface

# OCR, memoria de pantalla y etiquetas de las fotos: ONNX Runtime crea sus objetos desde el código nativo (JNI), por nombre.
-keep class ai.onnxruntime.** { *; }
