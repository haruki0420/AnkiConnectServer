# タスクリスト

- [x] Android SDKパスの確認と修正
  - [x] `local.properties` の `sdk.dir` を確認
  - [x] 指定されたパスに Android SDK が存在するか確認
  - [x] 環境変数（ANDROID_HOME等）との整合性確認
- [x] プロジェクトのクリーンアップ
  - [x] `.gradle` フォルダ의削除
  - [x] `.idea` フォルダの削除
  - [x] Gradle キャッシュのクリーン (`./gradlew clean`)
- [x] プロジェクトの再インポートと同期
  - [x] Gradle Sync の実行
  - [x] 標準クラスが認識されるか確認
