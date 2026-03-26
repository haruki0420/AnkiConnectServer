# 「すごい波線」解消のための実装計画

## 背景
現在、プロジェクトで `compileSdk 36` および `AGP 9.1.0` という非常に新しい（あるいはプレビュー版の）バージョンが指定されています。
これにより、ローカル環境に該当する SDK が存在しない、あるいは IDE が対応していないために、多くのコードが赤い波線で表示されている可能性が高いです。

これを安定したバージョン（Android 15 / API 35）に戻すことで、正常にプロジェクトを認識できるようにします。

## 変更内容

### [gradle]
#### [MODIFY] [libs.versions.toml](file:///c:/antigravity/AnkiConnectServer/AnkiConnectServer/gradle/libs.versions.toml)
- `agp` バージョンを `9.1.0` から `8.7.3` に変更します。

### [app]
#### [MODIFY] [build.gradle.kts](file:///c:/antigravity/AnkiConnectServer/AnkiConnectServer/app/build.gradle.kts)
- `compileSdk` を `35` に変更します。
- `targetSdk` を `35` に変更します。

## 検証計画

### 自動テスト
- `./gradlew clean` を実行し、ビルドスクリプトの構文エラーがないことを確認します。
- `./gradlew assembleDebug` を実行し、コンパイルが通ることを確認します。

### 手動確認
- ユーザーに Android Studio (または使用している IDE) で **"Sync Project with Gradle Files"** を実行してもらい、赤い波線が消えるか確認してもらいます。
