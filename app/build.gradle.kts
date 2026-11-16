import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// APK 签名：复用 OplusCameraPro 所使用的 keystore。
// 参数从根目录 local.properties 读取（该文件已被 .gitignore 忽略），也支持同名环境变量覆盖。
val signingProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.isFile) file.inputStream().use { load(it) }
}
fun signingValue(key: String): String? = signingProperties.getProperty(key) ?: System.getenv(key)

val releaseKeystore = signingValue("KEYSTORE_PATH")?.let { rootProject.file(it) }?.takeIf { it.isFile }
if (releaseKeystore == null) {
    logger.warn("未找到可用的签名配置（KEYSTORE_PATH），本次构建将回退到 Gradle 默认签名。")
}

android {
    namespace = "com.tlsu.pods"
    compileSdk = 36
    ndkVersion = "29.0.14206865"
    defaultConfig {
        applicationId = "com.tlsu.pods"
        minSdk = 33
        targetSdk = 36
        versionCode = 43
        versionName = "0.6.23"
        testInstrumentationRunner = "com.tlsu.pods.DeviceSmoke"
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild { cmake { cppFlags += "-std=c++20" } }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // debug 与 release 都使用同一签名，避免覆盖安装时因签名不一致需要先卸载。
    val releaseSigning = releaseKeystore?.let { keystore ->
        signingConfigs.create("release") {
            storeFile = keystore
            storePassword = signingValue("KEYSTORE_PASS")
            keyAlias = signingValue("KEY_ALIAS")
            keyPassword = signingValue("KEY_PASSWORD")
            enableV3Signing = true
        }
    }

    buildTypes {
        debug {
            if (releaseSigning != null) signingConfig = releaseSigning
        }
        release {
            isMinifyEnabled = false
            if (releaseSigning != null) signingConfig = releaseSigning
        }
    }
    packaging {
        jniLibs { useLegacyPackaging = true }
        resources { excludes += setOf("/META-INF/*.kotlin_module", "/META-INF/{AL2.0,LGPL2.1}") }
    }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
}
dependencies {
    compileOnly("io.github.libxposed:api:102.0.0")
    // Runtime dex deobfuscation: resolves host hooks by structural/string
    // fingerprints instead of hard-coded (often obfuscated) symbol names.
    implementation("org.luckypray:dexkit:2.3.0")
    testImplementation("junit:junit:4.13.2")
}
