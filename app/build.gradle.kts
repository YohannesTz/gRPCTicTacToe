import com.google.protobuf.gradle.id
import java.util.Properties

val localProperties = rootProject.file("local.properties")
    .takeIf { it.exists() }
    ?.inputStream()?.use { Properties().apply { load(it) } }

val grpcHost = localProperties?.getProperty("GRPC_HOST") ?: "no-host"
val grpcPort = localProperties?.getProperty("GRPC_PORT") ?: "100"

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.protobuf)
}

android {
    namespace = "com.github.yohannestz.grpctictactoe"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.github.yohannestz.grpctictactoe"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "GRPC_HOST", "\"$grpcHost\"")
        buildConfigField("int", "GRPC_PORT", grpcPort)
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }


    sourceSets {
        val main by getting
        main.java.srcDirs(
            "build/generated/sources"
        )
    }
}

dependencies {
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.constraintlayout)
    implementation(libs.navigation.fragment)
    implementation(libs.navigation.ui)

    implementation(libs.protobuf.java)

    implementation(libs.grpc.okhttp)
    implementation(libs.grpc.protobuf)
    implementation(libs.grpc.stub)

    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:3.21.5"
    }

    plugins {
        id("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:1.75.0"
        }
    }

    generateProtoTasks {
        all().forEach { task ->
            task.plugins {
                create("grpc")
            }
            task.builtins {
                create("java")
            }
        }
    }
}