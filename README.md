# Bafang Companion — Public Android source

An independent Kotlin Android companion for the Bafang B02NC-BF / B02NC_SMK_BF_N06 Bluetooth e-bike display.

## Features
- BLE Nordic UART transport and authenticated B02NC connection.
- Bafang assist level, lights and brightness controls (physical observations on N06).
- OpenStreetMap cycling route preview, touch gestures and turn-by-turn B02NC navigation.
- GPS-led safe routing: an inaccurate fix delays BLE turn instructions without blocking session activation.
- **Tokenless in-app updates** using this repository's [public GitHub Releases](https://github.com/AlixerVolkov/Bafang-Companion-Public/releases).
- Validates SHA-256, package identity, increasing Android version code and signing certificate before invoking Android's installer.

## Update channel
This is the **public distribution and source repository**. The original private development repository holds the historical Android signing material. That material, and the private Git history, are **not included here**.

An update can install over an existing version only when the public Release APK has the **same Android signing identity**. No APK is provided until a signed artifact is published.

## Build
- Android SDK 35, Java 17, Gradle 8.11.1.
- `gradle :core:test :app:assembleDebug` produces a development APK; without the original signing identity it **cannot update an existing signed installation**.
- Signed public Release APKs must be built with the original signing key in the private CI and published to this repository, never by exposing the key.

## Safety and limitations
B02NC telemetry offsets differ from other EKD01 variants. PAS, lights, brightness and navigation were field-tested on the user's N06 variant; battery remains provisional, wheel-speed BLE field has not been identified. Motor power, walk-assist BLE commands and motor firmware modification are intentionally not implemented.

Source code is provided for review and personal testing. No third-party trademark affiliation or license grant is implied.
